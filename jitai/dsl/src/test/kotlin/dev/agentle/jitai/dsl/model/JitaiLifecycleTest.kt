package dev.agentle.jitai.dsl.model

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.StoredRuleVerdict
import dev.agentle.jitai.dsl.validation.ValidationIssue
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** R10 §3.4 lifecycle, §11.4 step 2 (expiry) and §11.5 (approval), as pure functions. */
class JitaiLifecycleTest {
    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @MethodSource("table")
    fun `R10 3_4 transition table`(from: JitaiStatus, event: LifecycleEvent, expected: JitaiStatus?) {
        assertThat(JitaiLifecycle.next(from, event)).isEqualTo(expected)
    }

    @Test
    fun `12_M M4 an approved NL proposal is the only way to an active rule`() {
        val proposed = golden("definition-13-6-1.json")
        val clock = Fixtures.clock(Instant.parse("2026-10-01T16:00:00.750Z"))

        val active = JitaiLifecycle.apply(
            proposed,
            LifecycleEvent.APPROVE,
            clock,
            verdict = RuleValidator.revalidate(proposed),
            approvedRendering = "Every 30 minutes ...",
        ).getOrThrow()

        assertThat(proposed.kind).isEqualTo(JitaiKind.INTERVENTION)
        assertThat(proposed.status to proposed.enabled).isEqualTo(JitaiStatus.PROPOSED to false)
        assertThat(active.status to active.enabled).isEqualTo(JitaiStatus.ACTIVE to true)
        assertThat(active.modifiedAt).isEqualTo(Instant.parse("2026-10-01T16:00:00Z"))
        assertThat(active.version).isEqualTo(1)
        assertThat(active.expiresAt).isNull()
        assertThat(active.provenance?.approvedRendering).isEqualTo("Every 30 minutes ...")
        assertThat(active.provenance?.nlRequest).isEqualTo(proposed.provenance?.nlRequest)
        assertThat(code(JitaiLifecycle.apply(proposed, LifecycleEvent.SAVE, clock))).isEqualTo(JitaiLifecycle.ILLEGAL_TRANSITION)
    }

    @Test
    fun `approving a discovered rule sets expiresAt from its trial length (R10 11_4 step 2)`() {
        val proposed = golden("definition-14-7.json")
        val clock = Fixtures.clock(Instant.parse("2026-10-01T16:00:00Z"), Fixtures.BERLIN)

        val active = JitaiLifecycle.apply(
            proposed,
            LifecycleEvent.APPROVE,
            clock,
            verdict = RuleValidator.revalidate(proposed),
        ).getOrThrow()

        assertThat(active.expiresAt).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"))
        assertThat(active.provenance).isEqualTo(proposed.provenance)
    }

    @Test
    fun `approval without provenance keeps the rendering in a new provenance`() {
        val proposed = golden("definition-13-6-2.json").copy(provenance = null)
        val clock = Fixtures.clock()

        assertThat(
            JitaiLifecycle.apply(
                proposed,
                LifecycleEvent.APPROVE,
                clock,
                verdict = RuleValidator.revalidate(proposed),
            ).getOrThrow().provenance,
        ).isNull()
        assertThat(
            JitaiLifecycle.apply(
                proposed,
                LifecycleEvent.APPROVE,
                clock,
                verdict = RuleValidator.revalidate(proposed),
                approvedRendering = "At 5:00 PM ...",
            ).getOrThrow().provenance,
        )
            .isEqualTo(Provenance(approvedRendering = "At 5:00 PM ..."))
    }

    @Test
    fun `enabled is true exactly in ACTIVE`() {
        val clock = Fixtures.clock()
        val active = golden("definition-3-1.json")

        val paused = JitaiLifecycle.apply(active, LifecycleEvent.PAUSE, clock).getOrThrow()
        val resumed = JitaiLifecycle.apply(paused, LifecycleEvent.RESUME, clock).getOrThrow()
        val expired = JitaiLifecycle.apply(paused, LifecycleEvent.EXPIRE, clock).getOrThrow()
        val archived = JitaiLifecycle.apply(expired, LifecycleEvent.ARCHIVE, clock).getOrThrow()
        val declined = JitaiLifecycle.apply(golden("definition-13-6-2.json"), LifecycleEvent.DECLINE, clock).getOrThrow()
        val saved = JitaiLifecycle.apply(active.copy(status = JitaiStatus.DRAFT, enabled = false), LifecycleEvent.SAVE, clock).getOrThrow()

        assertThat(listOf(paused, resumed, expired, archived, declined, saved).map { it.status to it.enabled }).containsExactly(
            JitaiStatus.PAUSED to false,
            JitaiStatus.ACTIVE to true,
            JitaiStatus.EXPIRED to false,
            JitaiStatus.ARCHIVED to false,
            JitaiStatus.DECLINED to false,
            JitaiStatus.ACTIVE to true,
        ).inOrder()
        assertThat(listOf(paused, resumed, expired, archived, saved).map { it.version }).containsExactly(3, 3, 3, 3, 3)
        assertThat(declined.version).isEqualTo(1)
    }

    @Test
    fun `edit creates a new draft version and archived rules stay archived`() {
        val clock = Fixtures.clock()
        val active = golden("definition-3-1.json")

        val edited = JitaiLifecycle.apply(active, LifecycleEvent.EDIT, clock).getOrThrow()
        val archived = JitaiLifecycle.apply(active, LifecycleEvent.ARCHIVE, clock).getOrThrow()
        val failure = JitaiLifecycle.apply(archived, LifecycleEvent.EDIT, clock).errorOrNull() as AppError.ValidationError

        assertThat(edited.status to edited.enabled).isEqualTo(JitaiStatus.DRAFT to false)
        assertThat(edited.version).isEqualTo(active.version + 1)
        assertThat(failure.codes).containsExactly(JitaiLifecycle.ILLEGAL_TRANSITION)
        assertThat(failure.detail).isEqualTo("ARCHIVED on EDIT")
    }

    @Test
    fun `renewal needs 1-90 days for discovered rules and creates a new version`() {
        val clock = Fixtures.clock(Instant.parse("2026-10-01T16:00:00Z"), Fixtures.BERLIN)
        val discovered = golden("definition-14-7.json").copy(status = JitaiStatus.EXPIRED)
        val withoutTrial = discovered.copy(provenance = discovered.provenance?.copy(expiresInDays = null))
        val user = golden("definition-3-1.json").copy(status = JitaiStatus.EXPIRED, expiresAt = Instant.parse("2026-09-01T00:00:00Z"))

        val stored = JitaiLifecycle.apply(discovered, LifecycleEvent.RENEW, clock).getOrThrow()
        val given = JitaiLifecycle.apply(withoutTrial, LifecycleEvent.RENEW, clock, renewDays = 14).getOrThrow()
        val userRenewal = JitaiLifecycle.apply(user, LifecycleEvent.RENEW, clock).getOrThrow()

        assertThat(stored.expiresAt).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"))
        assertThat(stored.version).isEqualTo(discovered.version + 1)
        assertThat(stored.status to stored.enabled).isEqualTo(JitaiStatus.ACTIVE to true)
        assertThat(given.expiresAt).isEqualTo(Instant.parse("2026-10-14T22:00:00Z"))
        assertThat(userRenewal.expiresAt).isNull()
        listOf(null, 0, 91).forEach { days ->
            assertThat(code(JitaiLifecycle.apply(withoutTrial, LifecycleEvent.RENEW, clock, renewDays = days)))
                .isEqualTo(JitaiLifecycle.INVALID_RENEWAL)
        }
    }

    @Test
    fun `expiry is the start of a local day in the zone at approval, never the JVM zone`() {
        val approved = Instant.parse("2026-10-01T16:00:00Z")

        assertThat(JitaiLifecycle.expiresAt(approved, Fixtures.BERLIN, 28)).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"))
        assertThat(JitaiLifecycle.expiresAt(approved, TimeZone.UTC, 28)).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"))
        assertThat(JitaiLifecycle.expiresAt(approved, TimeZone.of("America/St_Johns"), 28)).isEqualTo(Instant.parse("2026-10-29T02:30:00Z"))
        assertThat(JitaiLifecycle.expiresAt(Instant.parse("2027-03-01T12:00:00Z"), Fixtures.BERLIN, 27))
            .isEqualTo(Instant.parse("2027-03-27T23:00:00Z"))
        assertThat(JitaiLifecycle.expiresAt(Instant.parse("2026-10-01T22:30:00Z"), Fixtures.BERLIN, 1))
            .isEqualTo(Instant.parse("2026-10-02T22:00:00Z"))
    }

    @Test
    fun `a stored rule that fails re-validation is paused with a notice (jitai-correctness-01)`() {
        val clock = Fixtures.clock()
        val active = golden("definition-3-1.json")
        val issue = ValidationIssue(IssueCode.E010, "/conditions/feature", "Unknown feature \"x\" at /conditions/feature.")
        val failing = StoredRuleVerdict(active.id, 1, listOf(issue))
        val passing = StoredRuleVerdict(active.id, 1, emptyList())

        val paused = JitaiLifecycle.pauseIfInvalid(active, failing, clock)

        assertThat(paused.status to paused.enabled).isEqualTo(JitaiStatus.PAUSED to false)
        assertThat(paused.modifiedAt).isEqualTo(Fixtures.F0_NOW)
        assertThat(JitaiLifecycle.pauseIfInvalid(active, passing, clock)).isSameInstanceAs(active)
        assertThat(JitaiLifecycle.pauseIfInvalid(active, failing.copy(jitaiId = "other"), clock)).isSameInstanceAs(active)
        assertThat(JitaiLifecycle.pauseIfInvalid(paused, failing, clock)).isSameInstanceAs(paused)
        assertThat(JitaiLifecycle.pauseNotice(active))
            .isEqualTo(
                "\"Afternoon walk nudge\" was paused because it no longer passes the checks of this app version. Open it to review it.",
            )
    }

    private fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

    private fun code(outcome: Outcome<*>): String? = (outcome.errorOrNull() as? AppError.ValidationError)?.codes?.single()

    companion object {
        private val LEGAL: Map<Pair<JitaiStatus, LifecycleEvent>, JitaiStatus> = buildMap {
            put(JitaiStatus.DRAFT to LifecycleEvent.SAVE, JitaiStatus.ACTIVE)
            put(JitaiStatus.PROPOSED to LifecycleEvent.APPROVE, JitaiStatus.ACTIVE)
            put(JitaiStatus.PROPOSED to LifecycleEvent.DECLINE, JitaiStatus.DECLINED)
            put(JitaiStatus.ACTIVE to LifecycleEvent.PAUSE, JitaiStatus.PAUSED)
            put(JitaiStatus.PAUSED to LifecycleEvent.RESUME, JitaiStatus.ACTIVE)
            put(JitaiStatus.ACTIVE to LifecycleEvent.EXPIRE, JitaiStatus.EXPIRED)
            put(JitaiStatus.PAUSED to LifecycleEvent.EXPIRE, JitaiStatus.EXPIRED)
            put(JitaiStatus.EXPIRED to LifecycleEvent.RENEW, JitaiStatus.ACTIVE)
            JitaiStatus.entries.filter { it != JitaiStatus.ARCHIVED }.forEach {
                put(it to LifecycleEvent.EDIT, JitaiStatus.DRAFT)
                put(it to LifecycleEvent.ARCHIVE, JitaiStatus.ARCHIVED)
            }
        }

        @JvmStatic
        fun table(): List<Arguments> = JitaiStatus.entries.flatMap { from ->
            LifecycleEvent.entries.map { event -> Arguments.of(from, event, LEGAL[from to event]) }
        }
    }
}
