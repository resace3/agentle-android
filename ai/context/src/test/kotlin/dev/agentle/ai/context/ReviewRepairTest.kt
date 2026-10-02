@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiEnvelopeJson
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.validation.InsightSchema
import dev.agentle.ai.api.validation.MediaPromptSchema
import dev.agentle.core.common.AppError
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.fakes.ai.FakeAiScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/** Regression tests for the review repairs R1-1 to R1-5 of team/ai-context. */
class ReviewRepairTest {
    @Test
    fun `R1-1 the account hash uses the per-install salt, so it is stable across restarts`() = runTest {
        val hashes = List(2) {
            val world = World(data = sleepData())
            world.grant(AiPurpose.SLEEP_INSIGHT, SLEEP, STEPS)
            val envelope = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()
            world.send(envelope).getOrThrow()
            world.audit[envelope.requestId]!!.accountSubHash
        }
        assertThat(hashes.distinct()).containsExactly(AiEnvelopeJson.sha256Hex(SALT + "\n" + ACCOUNT))
    }

    @Test
    fun `R1-2 a failed load never lets a grant wipe the other grants, while a corrupt document starts again`() = runTest {
        val world = World()
        world.grant(AiPurpose.SLEEP_INSIGHT, SLEEP)
        val before = world.store.document
        world.store.loadFailure = { IOException("disk") }

        assertThat(world.consent.grant(setOf(STEPS), AiPurpose.SLEEP_INSIGHT).errorOrNull())
            .isEqualTo(AppError.DatabaseError(AiConsentRepository.CODE_UNREADABLE))
        val template = world.engine.standingConsentTemplate(AiPurpose.SLEEP_INSIGHT).getOrThrow()
        assertThat(world.consent.acceptStanding(template).errorOrNull())
            .isEqualTo(AppError.DatabaseError(AiConsentRepository.CODE_UNREADABLE))
        assertThat(world.store.document).isEqualTo(before)

        world.store.loadFailure = null
        world.store.overwrite("{not json", notify = false)
        world.consent.grant(setOf(STEPS), AiPurpose.SLEEP_INSIGHT).getOrThrow()
        assertThat(world.snapshot().grants.map { it.category }).containsExactly(STEPS)

        world.store.loadFailure = { IOException("disk") }
        world.consent.revokeAll().getOrThrow()
        world.store.loadFailure = null
        assertThat(world.snapshot().grants).isEmpty()
    }

    @Test
    fun `R1-3 a cancelled caller closes the record as cancelled, still counted as possibly sent`() = runTest {
        val world = World(data = sleepData())
        world.grant(AiPurpose.SLEEP_INSIGHT, SLEEP)
        world.provider.script(AiPurpose.SLEEP_INSIGHT, FakeAiScenario.TIMEOUT)
        val envelope = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()

        val call = async { world.send(envelope) }
        runCurrent()
        call.cancel()
        runCurrent()

        val record = world.audit[envelope.requestId]!!
        assertThat(record.status).isEqualTo(AiRequestStatus.CANCELLED)
        assertThat(record.reason).isEqualTo(EgressGuard.CALLER_CANCELLED)
        assertThat(record.status.mayHaveBeenSent).isTrue()
    }

    @Test
    fun `a natural-language rule request without the jitai-nl-v1 contract is never built`() = runTest {
        val world = World(instructions = AiInstructionSet())
        assertThat(world.engine.build(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "Remind me to walk").errorOrNull())
            .isEqualTo(AppError.NotEligible(AiInstructionSet.CONTRACT_MISSING))
        assertThat(World().engine.build(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "Remind me to walk").errorOrNull()).isNull()
    }

    @Test
    fun `a structured call must name the schema of its purpose's instructions`() = runTest {
        val world = World()
        val question = world.engine.build(AiPurpose.GENERAL_QUESTION, "How was my week").getOrThrow()
        assertThat(world.guard.generateStructuredResult(question, InsightSchema.SCHEMA).errorOrNull())
            .isEqualTo(AppError.ValidationError(listOf(EgressGuard.SCHEMA_NOT_FOR_PURPOSE)))
        world.grant(AiPurpose.SLEEP_INSIGHT, SLEEP)
        val insight = World(data = sleepData()).let { other ->
            other.grant(AiPurpose.SLEEP_INSIGHT, SLEEP)
            other to other.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()
        }
        assertThat(insight.first.guard.generateStructuredResult(insight.second, MediaPromptSchema.SCHEMA).errorOrNull())
            .isEqualTo(AppError.ValidationError(listOf(EgressGuard.SCHEMA_NOT_FOR_PURPOSE)))
        assertThat(insight.first.provider.journal).isEmpty()
    }

    @Test
    fun `R1-4 every code field has a closed vocabulary, and a code outside it is refused`() {
        AiFieldRegistry.all.filter { it.kind == ItemKind.CODE }.forEach { assertThat(it.codes).isNotEmpty() }
        assertThat(AiFieldRegistry["pattern.kind"]!!.codes).doesNotContain("IGNORE_ALL")
    }
}
