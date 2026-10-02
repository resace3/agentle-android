package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.analysis.Unsatisfiability
import dev.agentle.jitai.dsl.codec.FeatureAliases
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.nl.AppLabelResolver
import dev.agentle.jitai.dsl.nl.AppResolution
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.nl.NlContract
import dev.agentle.jitai.dsl.nl.NlDecision
import dev.agentle.jitai.dsl.nl.NlRole
import dev.agentle.jitai.dsl.nl.NlRoundState
import dev.agentle.jitai.dsl.nl.NlSettings
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.LiteralConversion
import dev.agentle.jitai.dsl.rule.LiteralRejection
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.TypedLiteral
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationContext
import dev.agentle.jitai.dsl.validation.ValidationSettings
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** The examples of `jitai/dsl/README.md`, one per contract type, so the README cannot drift from the code. */
internal class ReadmeExamplesTest {
    private val clock = TestAgentleClock(Instant.parse("2026-10-01T10:00:00Z"), TimeZone.of("Europe/Berlin"))

    private val walk: JitaiDefinition = JitaiDefinition(
        id = "6f1c2b9e-4a7d-4c1e-9b3a-2d5e8f0a1c47",
        name = "Afternoon walk",
        category = JitaiCategory.PHYSICAL_ACTIVITY,
        trigger = Trigger.DailyAt(times = listOf("17:00")),
        conditions = Condition.Lt(feature = "steps_today", value = RuleLiteral.of(3000)),
        content = ContentStrategy.Static(title = "Time for a short walk?", body = "A 10-minute walk now would get you moving."),
        cooldownMinutes = 60,
        maxPerDay = 1,
        maxPerWeek = 7,
        outcome = OutcomeSpec(proximal = OutcomeMetricRef(OutcomeMetric.STEPS_AFTER, windowMinutes = 30)),
        snooze = SnoozePolicy.DEFAULT_DAILY_AT,
        createdBy = CreatedBy.USER_MANUAL,
        createdAt = clock.now(),
        modifiedAt = clock.now(),
    )

    private val evening = Condition.AllOf(
        listOf(
            Condition.LocalTimeIn(start = "22:00", end = "02:00"),
            Condition.Gte(
                feature = "app_minutes_since",
                args = mapOf("package" to "com.instagram.android", "since" to "22:00"),
                value = RuleLiteral.of(30),
            ),
        ),
    )

    @Test
    fun `JitaiDefinition - a user rule is valid and SAVE makes it ACTIVE and enabled`() {
        val saved = JitaiLifecycle.apply(walk, LifecycleEvent.SAVE, clock).getOrThrow()

        assertThat(RuleValidator.validateDefinition(walk, ValidationContext(clock = clock)).errors).isEmpty()
        assertThat(saved.status).isEqualTo(JitaiStatus.ACTIVE)
        assertThat(saved.enabled).isTrue()
        assertThat(JitaiLifecycle.next(JitaiStatus.DRAFT, LifecycleEvent.APPROVE)).isNull()
    }

    @Test
    fun `Condition - the tree encodes to the wire form and renders as a phrase`() {
        assertThat(RuleCodec.encodeCondition(evening)).isEqualTo(
            """{"type":"all","of":[{"type":"local_time_in","start":"22:00","end":"02:00"},""" +
                """{"type":"gte","feature":"app_minutes_since","args":{"package":"com.instagram.android","since":"22:00"},""" +
                """"value":30,"onUnknown":null}]}""",
        )
        assertThat(RuleRenderer.condition(evening, RenderOptions(appLabels = mapOf("com.instagram.android" to "Instagram"))))
            .isEqualTo("the time is between 10:00 PM and 2:00 AM and time in Instagram since 10:00 PM is at least 30 min")
    }

    @Test
    fun `TypedLiteral - 45, 45_0, 4_5e1 and quoted 45 are different literals`() {
        val steps = checkNotNull(RealtimeFeatureCatalog["steps_today"])

        assertThat(TypedLiterals.convert(steps, RuleLiteral.NumberToken("45")))
            .isEqualTo(LiteralConversion.Converted(TypedLiteral(FeatureScalar.IntValue(45))))
        for (literal in listOf(RuleLiteral.NumberToken("45.0"), RuleLiteral.NumberToken("4.5e1"), RuleLiteral.Text("45"))) {
            assertThat(TypedLiterals.convert(steps, literal)).isEqualTo(LiteralConversion.Rejected(LiteralRejection.TYPE_MISMATCH))
        }
        assertThat(TypedLiterals.isAllowed(FeatureType.BOOL, Operator.GT)).isFalse()
    }

    @Test
    fun `RuleCodec - canonical fixed point, verbatim number tokens and unknown keys rejected`() {
        val stored = RuleCodec.decodeDefinition(Fixtures.resource("r10/definition-13-6-2.json")).getOrThrow()
        val canonical = RuleCodec.encodeDefinition(stored)
        val decimalText = """{"type":"lt","feature":"steps_today","args":{},"value":45.0,"onUnknown":null}"""
        val decimal = RuleCodec.decodeCondition(decimalText).getOrThrow()
        val unknown = RuleCodec.decodeCondition("""{"type":"lt","feature":"steps_today","args":{},"value":3,"onUnknown":null,"colour":1}""")

        assertThat(RuleCodec.encodeDefinition(RuleCodec.decodeDefinition(canonical).getOrThrow())).isEqualTo(canonical)
        assertThat(decimal).isEqualTo(Condition.Lt(feature = "steps_today", value = RuleLiteral.NumberToken("45.0")))
        assertThat(RuleCodec.encodeCondition(decimal)).isEqualTo(decimalText)
        assertThat(unknown.errorOrNull()).isEqualTo(AppError.ValidationError(listOf("E006"), "E006 /colour"))
        assertThat(RuleCodec.contentHash(stored)).isEqualTo(RuleCodec.contentHash(stored.copy(id = "other", version = 2)))
    }

    @Test
    fun `RuleValidator - a model reply validates, and a wrong one gives the repair line`() {
        val context = ValidationContext(clock = clock, apps = Fixtures.apps, settings = ValidationSettings())
        val reply = Fixtures.example2

        val report = RuleValidator.validateProposalText(reply, context, nlRequest = Fixtures.REQUEST_2)
        val wrong = RuleValidator.validateProposalText(json(reply).with("/jitai/maxPerDay", 4).toString(), context)

        assertThat(report.isValid).isTrue()
        assertThat(report.definition?.status).isEqualTo(JitaiStatus.PROPOSED)
        assertThat(report.codes).containsExactly(IssueCode.C04, IssueCode.W07, IssueCode.W10).inOrder()
        assertThat(report.rendering).isEqualTo(
            "Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification. " +
                "At most 1 per day and 7 per week, at least 1 h apart.",
        )
        assertThat(wrong.errors.single().line).isEqualTo("E042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.")
        assertThat(wrong.repairLines).containsExactly("E042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.")
    }

    @Test
    fun `RuleValidator - revalidate is a pure function of the definition and the catalog`() {
        val verdict = RuleValidator.revalidate(walk)
        val broken = RuleValidator.revalidate(walk.copy(conditions = Condition.Lt(feature = "steps_todya", value = RuleLiteral.of(3000))))

        assertThat(verdict.isValid).isTrue()
        assertThat(verdict.catalogVersion).isEqualTo(RealtimeFeatureCatalog.VERSION)
        assertThat(broken.errors.map { it.line })
            .containsExactly("E010 /conditions/feature: Unknown feature \"steps_todya\" at /conditions/feature.")
    }

    @Test
    fun `RuleAnalysis - dependencies and an unsatisfiable tree`() {
        val never = Condition.AllOf(
            listOf(
                Condition.Lt(feature = "steps_today", value = RuleLiteral.of(3000)),
                Condition.Gte(feature = "steps_today", value = RuleLiteral.of(3000)),
            ),
        )

        assertThat(RuleAnalysis.dependencies(evening).map { it.toString() })
            .containsExactly("local_time", "app_minutes_since{package=com.instagram.android,since=22:00}")
        assertThat(RuleAnalysis.unsatisfiable(never)).isEqualTo(Unsatisfiability("", "steps_today < 3000 and steps_today >= 3000"))
        assertThat(RuleAnalysis.unsatisfiable(evening)).isNull()
    }

    @Test
    fun `RuleRenderer - the sentence of a definition uses the clock format it is given`() {
        val limits = "At most 1 per day and 7 per week, at least 1 h apart."

        assertThat(RuleRenderer.render(walk))
            .isEqualTo("Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification. $limits")
        assertThat(RuleRenderer.render(walk, RenderOptions(use24HourClock = true)))
            .isEqualTo("Every day at 17:00: if your step count today is less than 3,000 steps, send a notification. $limits")
    }

    @Test
    fun `NlContract - the prompt version, the first round and the repair decision`() {
        val context = ValidationContext(clock = clock, apps = Fixtures.apps)
        val wrong = RuleValidator.validateProposalText(json(Fixtures.example2).with("/jitai/maxPerDay", 4).toString(), context)

        val first = NlContract.firstRound(NlSettings(quietHours = null), Fixtures.REQUEST_2)

        assertThat(NlContract.PROMPT_VERSION).isEqualTo("jitai-nl-v1")
        assertThat(first.map { it.role }).containsExactly(NlRole.DEVELOPER, NlRole.USER).inOrder()
        assertThat(first[1].text).isEqualTo("USER_REQUEST\n" + Fixtures.REQUEST_2)
        assertThat(NlContract.decide(wrong, NlRoundState())).isEqualTo(
            NlDecision.Repair(
                "VALIDATION_ERRORS\nE042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.\nReturn the corrected proposal only.",
            ),
        )
    }

    @Test
    fun `AppLabelResolver - a fixed list resolves labels on the phone`() {
        val apps = AppLabelResolver.of(
            listOf(
                InstalledApp("com.instagram.android", "Instagram", usageMinutesLast7Days = 300),
                InstalledApp("com.instagram.barcelona", "Threads, an Instagram app", usageMinutesLast7Days = 20),
            ),
        )

        assertThat(apps.resolve("instagram app")).isEqualTo(AppResolution.Resolved(apps.launcherApps()[0]))
        assertThat(apps.resolve("Threads")).isEqualTo(AppResolution.Ambiguous(listOf(apps.launcherApps()[1])))
        assertThat(AppLabelResolver.NONE.resolve("Instagram")).isEqualTo(AppResolution.NotFound)
    }

    @Test
    fun `FeatureAliases - a renamed catalog id is read through the alias table`() {
        assertThat(FeatureAliases.canonical("steps_so_far", mapOf("steps_so_far" to "steps_today"))).isEqualTo("steps_today")
        assertThat(FeatureAliases.problems()).isEmpty()
    }
}
