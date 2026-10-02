package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.SourceFamily
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import kotlin.time.Duration.Companion.hours

class AiConsentRepositoryTest {
    /** One way the consent read can fail (round 4 correction 2). */
    class Fault(private val name: String, val apply: (InMemoryConsentStore) -> Unit) {
        override fun toString(): String = name
    }

    @Test
    fun `a store that was never written grants nothing`() = runTest {
        val world = World(data = sleepData())
        assertThat(world.consent.read()).isEqualTo(ConsentState.Readable(ConsentSnapshot.EMPTY))
        val error = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).errorOrNull() as AppError.ConsentViolation
        assertThat(error.detail).isEqualTo(GateCodes.CONSENT_REQUIRED)
        assertThat(error.categories).containsExactly("SLEEP")
        assertThat(world.scripted.queries).isEmpty()
    }

    @Test
    fun `grants are stored with the consent version, the time and the account`() = runTest {
        val world = World()
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP, AiDataCategory.STEPS)
        val snapshot = (world.consent.read() as ConsentState.Readable).snapshot
        assertThat(snapshot.grants).containsExactly(
            ConsentGrant(AiDataCategory.SLEEP, AiPurpose.SLEEP_INSIGHT, 1, START, ACCOUNT),
            ConsentGrant(AiDataCategory.STEPS, AiPurpose.SLEEP_INSIGHT, 1, START, ACCOUNT),
        )
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        assertThat((world.consent.read() as ConsentState.Readable).snapshot.grants).hasSize(2)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    fun `every unreadable consent state denies, at build and at send`(fault: Fault) = runTest {
        val world = World(data = sleepData())
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP, AiDataCategory.STEPS, AiDataCategory.SCREEN_TIME_TOTALS)
        val builtBefore = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()

        fault.apply(world.store)

        assertThat(world.consent.read()).isInstanceOf(ConsentState.Unreadable::class.java)
        val rebuilt = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).errorOrNull() as AppError.ConsentViolation
        assertThat(rebuilt.detail).isAnyOf(AiConsentRepository.CODE_UNREADABLE, AiConsentRepository.CODE_CORRUPT)
        val sent = world.send(builtBefore).errorOrNull()
        assertThat(sent).isInstanceOf(AppError.ConsentViolation::class.java)
        assertThat(world.provider.journal.none { it.sent }).isTrue()
        assertThat(world.audit[builtBefore.requestId]?.status).isEqualTo(AiRequestStatus.DENIED)
        assertThat(world.sink.text()).doesNotContain("disk")
    }

    @Test
    fun `third-party text cannot be granted and nobody signed in cannot grant`() = runTest {
        val world = World()
        val thirdParty = world.consent.grant(setOf(AiDataCategory.NOTIFICATION_TEXT, AiDataCategory.SLEEP), AiPurpose.GENERAL_QUESTION)
        assertThat(thirdParty.errorOrNull()).isEqualTo(AppError.ConsentViolation(setOf("NOTIFICATION_TEXT"), GateCodes.THIRD_PARTY))
        world.account.sub = null
        val anonymous = world.consent.grant(setOf(AiDataCategory.SLEEP), AiPurpose.GENERAL_QUESTION)
        assertThat(anonymous.errorOrNull()).isEqualTo(AppError.AuthenticationRequired(AiConsentRepository.ACCOUNT_PROVIDER))
        assertThat(world.store.document).isNull()
    }

    @Test
    fun `deleting a data category revokes the grants and standing consents derived from it`() = runTest {
        val world = World()
        world.grant(AiPurpose.ACTIVITY_INSIGHT, AiDataCategory.ACTIVITY, AiDataCategory.STEPS, AiDataCategory.HEART)
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.STEPS, AiDataCategory.SLEEP)
        val template = world.engine.standingConsentTemplate(AiPurpose.ACTIVITY_INSIGHT).getOrThrow()
        world.consent.acceptStanding(template).getOrThrow()

        world.consent.revokeForDeletedData(DataCategory.ACTIVITY).getOrThrow()

        val snapshot = (world.consent.read() as ConsentState.Readable).snapshot
        assertThat(snapshot.grants.map { it.category to it.purpose }).containsExactly(
            AiDataCategory.HEART to AiPurpose.ACTIVITY_INSIGHT,
            AiDataCategory.SLEEP to AiPurpose.SLEEP_INSIGHT,
        )
        assertThat(snapshot.standing).isEmpty()
    }

    @Test
    fun `a consent version bump needs fresh grants`() = runTest {
        val old = World(data = sleepData())
        old.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        val bumped = World(data = sleepData(), currentVersion = 2)
        bumped.store.overwrite(old.store.document)
        val denied = bumped.engine.build(AiPurpose.SLEEP_INSIGHT, null).errorOrNull() as AppError.ConsentViolation
        assertThat(denied.detail).isEqualTo(GateCodes.CONSENT_REQUIRED)

        bumped.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        val envelope = bumped.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()
        assertThat(envelope.consentVersion).isEqualTo(2)
        assertThat(envelope.categories).containsExactly(AiDataCategory.SLEEP)
    }

    @Test
    fun `an envelope approved under an older consent version is refused at send time`() = runTest {
        val world = World(data = sleepData())
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        val envelope = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()
        val newer = AiConsentRepository(world.store, world.account, world.clock, currentVersion = 2)
        val check = SendTimeConsentCheck(newer, world.account, healthConnectToAi = true)
        assertThat((check.check(envelope).errorOrNull() as AppError.ConsentViolation).detail).isEqualTo(GateCodes.VERSION_CHANGED)
    }

    @Test
    fun `grants of another ChatGPT account do not count`() = runTest {
        val world = World(data = sleepData())
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        val envelope = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).getOrThrow()
        world.account.sub = "acct-someone-else"
        val rebuilt = world.engine.build(AiPurpose.SLEEP_INSIGHT, null).errorOrNull() as AppError.ConsentViolation
        assertThat(rebuilt.detail).isEqualTo(GateCodes.CONSENT_REQUIRED)
        assertThat(world.send(envelope).errorOrNull()).isInstanceOf(AppError.ConsentViolation::class.java)
        world.account.sub = null
        assertThat(world.send(envelope).errorOrNull()).isInstanceOf(AppError.ConsentViolation::class.java)
        assertThat(world.provider.journal.none { it.sent }).isTrue()
    }

    @Test
    fun `a write after corruption starts from nothing and a failed write is reported`() = runTest {
        val world = World()
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        world.store.overwrite("{\"format\":1,\"grants\":[{\"category\":\"MOOD\"}],\"standing\":[]}")
        world.grant(AiPurpose.ACTIVITY_INSIGHT, AiDataCategory.STEPS)
        val snapshot = (world.consent.read() as ConsentState.Readable).snapshot
        assertThat(snapshot.grants.map { it.category }).containsExactly(AiDataCategory.STEPS)

        world.store.saveFailure = { IOException("disk full at /data/secret") }
        val failed = world.consent.revoke(setOf(AiDataCategory.STEPS))
        assertThat(failed.errorOrNull()).isEqualTo(AppError.DatabaseError(AiConsentRepository.CODE_WRITE_FAILED))
        assertThat(world.sink.text()).doesNotContain("secret")
        assertThat(world.sink.text()).contains("IOException")
    }

    @Test
    fun `revoking a purpose or everything removes grants and standing consents`() = runTest {
        val world = World()
        world.grant(AiPurpose.SLEEP_INSIGHT, AiDataCategory.SLEEP)
        world.grant(AiPurpose.ACTIVITY_INSIGHT, AiDataCategory.STEPS)
        world.consent.acceptStanding(world.engine.standingConsentTemplate(AiPurpose.SLEEP_INSIGHT).getOrThrow()).getOrThrow()
        world.consent.revokeStanding(AiPurpose.SLEEP_INSIGHT).getOrThrow()
        assertThat((world.consent.read() as ConsentState.Readable).snapshot.standing).isEmpty()
        world.consent.revokePurpose(AiPurpose.SLEEP_INSIGHT).getOrThrow()
        assertThat(
            (world.consent.read() as ConsentState.Readable).snapshot.grants.map {
                it.purpose
            },
        ).containsExactly(AiPurpose.ACTIVITY_INSIGHT)
        world.consent.revokeAll().getOrThrow()
        assertThat(world.consent.read()).isEqualTo(ConsentState.Readable(ConsentSnapshot.EMPTY))
    }

    @Test
    fun `standing consent templates are validated before they are stored`() = runTest {
        val world = World()
        val template = world.engine.standingConsentTemplate(AiPurpose.SCREEN_TIME_INSIGHT).getOrThrow()
        val invalid = listOf(
            template.copy(purpose = AiPurpose.GENERAL_QUESTION),
            template.copy(fields = emptySet()),
            template.copy(fields = setOf("user.note")),
            template.copy(categories = setOf(AiDataCategory.HEART)),
            template.copy(sourceFamilies = emptySet()),
            template.copy(lookbackDays = 15),
            template.copy(cadence = 1.hours / 2),
            template.copy(dailyBudget = 0),
        )
        invalid.forEach { candidate ->
            val result = world.consent.acceptStanding(candidate)
            assertThat(result.errorOrNull()).isEqualTo(AppError.ValidationError(listOf(AiConsentRepository.CODE_TEMPLATE_INVALID)))
        }
        world.account.sub = " "
        assertThat(world.consent.acceptStanding(template).errorOrNull()).isInstanceOf(AppError.AuthenticationRequired::class.java)
        world.account.sub = ACCOUNT
        val standing = world.consent.acceptStanding(template).getOrThrow()
        assertThat(standing.accountSub).isEqualTo(ACCOUNT)
        assertThat(standing.sourceFamilies).containsExactly(SourceFamily.ON_DEVICE)
        val stored = (world.consent.read() as ConsentState.Readable).snapshot
        assertThat(stored.standing).containsExactly(standing)
        assertThat(stored.grants.map { it.category }.toSet()).isEqualTo(template.categories)
        world.store.saveFailure = { IOException("full") }
        assertThat(world.consent.acceptStanding(template).errorOrNull()).isInstanceOf(AppError.DatabaseError::class.java)
    }

    @Test
    fun `the disclosure names OpenAI and the ChatGPT account and cannot change without a version bump`() {
        assertThat(AiConsentDisclosure.TEXT).contains("OpenAI")
        assertThat(AiConsentDisclosure.TEXT).contains("linked to your ChatGPT account")
        assertThat(AiConsentDisclosure.PROVIDER).isEqualTo("OpenAI")
        // Changing the text? Bump AiConsentDisclosure.VERSION, then update both pins.
        assertThat(AiConsentDisclosure.VERSION).isEqualTo(1)
        assertThat(AiConsentDisclosure.fingerprint()).isEqualTo(DISCLOSURE_V1_SHA256)
    }

    @Test
    fun `the stored document is exact and round-trips`() {
        val snapshot = ConsentSnapshot(
            grants = listOf(ConsentGrant(AiDataCategory.SLEEP, AiPurpose.SLEEP_INSIGHT, 1, START, ACCOUNT)),
            standing = listOf(
                StandingConsent(
                    purpose = AiPurpose.SLEEP_INSIGHT,
                    fields = setOf("sleep.minutes_avg"),
                    categories = setOf(AiDataCategory.SLEEP),
                    sourceFamilies = setOf(SourceFamily.HEALTH_CONNECT),
                    lookbackDays = 14,
                    cadence = 12.hours,
                    dailyBudget = 1,
                    consentVersion = 1,
                    grantedAt = START,
                    accountSub = ACCOUNT,
                ),
            ),
        )
        val text = ConsentDocuments.encode(snapshot)
        assertThat(ConsentDocuments.decode(text)).isEqualTo(snapshot)
        val standing = Json.parseToJsonElement(text).jsonObject.getValue("standing").jsonArray[0].jsonObject
        listOf(
            "lookbackDays" to "0",
            "cadenceMinutes" to "0",
            "dailyBudget" to "0",
            "consentVersion" to "0",
            "accountSub" to "\"\"",
            "fields" to "[\"Sleep Minutes\"]",
            "categories" to "[]",
            "sourceFamilies" to "[]",
        ).forEach { (key, value) ->
            val changed = JsonObject(standing + (key to Json.parseToJsonElement(value)))
            val document = JsonObject(
                Json.parseToJsonElement(text).jsonObject + ("standing" to kotlinx.serialization.json.JsonArray(listOf(changed))),
            )
            assertThat(ConsentDocuments.decode(document.toString())).isNull()
        }
    }

    companion object {
        const val DISCLOSURE_V1_SHA256: String = "a41756c610c47be80a61074fbafe8bea743378d88697e45c1891ce238aa9666d"

        private fun mutateFirstGrant(store: InMemoryConsentStore, change: (MutableMap<String, JsonElement>) -> Unit) {
            val root = Json.parseToJsonElement(checkNotNull(store.document)).jsonObject.toMutableMap()
            val grants = root.getValue("grants").jsonArray.toMutableList()
            val first = grants[0].jsonObject.toMutableMap()
            change(first)
            grants[0] = JsonObject(first)
            root["grants"] = kotlinx.serialization.json.JsonArray(grants)
            store.overwrite(JsonObject(root).toString())
        }

        @JvmStatic
        fun faults(): List<Fault> = listOf(
            Fault("IOException on read") { it.loadFailure = { IOException("disk error") } },
            Fault("CorruptionException on read") { it.loadFailure = { CorruptionException("disk proto corrupt") } },
            Fault("missing key") { store -> mutateFirstGrant(store) { it.remove("accountSub") } },
            Fault("unknown category") { store -> mutateFirstGrant(store) { it["category"] = JsonPrimitive("MOOD") } },
            Fault("unknown purpose") { store -> mutateFirstGrant(store) { it["purpose"] = JsonPrimitive("DREAMS") } },
            Fault("unknown key") { store -> mutateFirstGrant(store) { it["scope"] = JsonPrimitive("all") } },
            Fault("null value") { store -> mutateFirstGrant(store) { it["consentVersion"] = kotlinx.serialization.json.JsonNull } },
            Fault("implausible version") { store -> mutateFirstGrant(store) { it["consentVersion"] = JsonPrimitive(0) } },
            Fault("blank account") { store -> mutateFirstGrant(store) { it["accountSub"] = JsonPrimitive(" ") } },
            Fault("bad timestamp") { store -> mutateFirstGrant(store) { it["grantedAt"] = JsonPrimitive("yesterday") } },
            Fault("unknown format") { store -> store.overwrite(checkNotNull(store.document).replace("\"format\":1", "\"format\":2")) },
            Fault("not json") { it.overwrite("{{{") },
            Fault("truncated document") { store -> store.overwrite(checkNotNull(store.document).dropLast(4)) },
            Fault("empty document") { it.overwrite("") },
        )
    }
}
