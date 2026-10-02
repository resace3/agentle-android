package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentMode
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.confirmCodes
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.leaf
import dev.agentle.jitai.dsl.testing.with
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/**
 * R10 §11.2 checks of fields that only stored definitions (rule editor, storage) can set, and of outcome metrics, leaf
 * literals and block targets. Each row lists every error line the rule gets.
 */
class StoredDefinitionChecksTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `R10 11_2 stored definition rows`(row: String, definition: JitaiDefinition, existing: List<JitaiDefinition>, lines: List<String>) {
        val report = Fixtures.validateDefinition(definition, Fixtures.context(existing = existing))

        assertWithMessage(row).that(report.errors.map { it.line }).containsExactlyElementsIn(lines)
    }

    @Test
    fun `a block target only has to be a UUID when other rules are unknown`() {
        val target = S1.copy(suppression = SuppressionTarget(jitaiIds = listOf(OTHER_ID)))

        assertThat(RuleValidator.revalidate(target).errors).isEmpty()
        assertThat(Fixtures.validateDefinition(target).errorCodes).containsExactly(IssueCode.E056)
        assertThat(RuleValidator.revalidate(target.copy(suppression = SuppressionTarget(jitaiIds = listOf("walk")))).errors.map { it.line })
            .containsExactly("E056 /suppression/jitaiIds/0: Block target walk at /suppression/jitaiIds/0 is not a rule id.")
    }

    @Test
    fun `an appLabel longer than 60 characters is E013`() {
        val long = leaf("gte", "app_minutes_last_60m", 5, "appLabel" to "x".repeat(61))

        val report = Fixtures.validateText(json(Fixtures.example2).with("/jitai/conditions", long).toString())

        assertThat(report.issues(IssueCode.E013).map { it.path }).containsExactly("/jitai/conditions/args/appLabel")
        assertThat(report.issues(IssueCode.E013).single().message).endsWith("is invalid: must be 1-60 characters.")
    }

    @Test
    fun `C02 asks before an AI rule speaks or opens a video`() {
        val voice = Fixtures.validateDefinition(ai.copy(delivery = ai.delivery.copy(channel = DeliveryChannel.VOICE)))
        val video = Fixtures.validateDefinition(
            ai.copy(
                delivery = ai.delivery.copy(channel = DeliveryChannel.VIDEO),
                content = ContentStrategy.LocalMedia("breathing_clip_02", ContentStrategy.Template("Walk?", "A short walk now?")),
            ),
        )
        val userVoice = Fixtures.validateDefinition(walk.copy(delivery = walk.delivery.copy(channel = DeliveryChannel.VOICE)))

        assertThat(voice.issues(IssueCode.C02).map { it.line })
            .containsExactly("C02 /delivery/channel: This reminder will speak out loud. Allow?")
        assertThat(video.issues(IssueCode.C02).map { it.line })
            .containsExactly("C02 /delivery/channel: This reminder will open a short video. Allow?")
        assertThat(video.errorCodes).isEmpty()
        assertThat(userVoice.confirmCodes).doesNotContain(IssueCode.C02)
    }

    @Test
    fun `W02 and W03 name what the phone lacks, each access once`() {
        val r1 = RuleCodec.decodeDefinition(Fixtures.resource("r10/definition-12-r1.json")).getOrThrow()
        val usage = FeatureAccess.of(apiLevel = 34, missingAccess = mapOf("app_usage_events" to "usage access"))
        val onScreen = walk.copy(
            conditions = Condition.AllOf(
                listOf(
                    Condition.Lt("steps_today", value = RuleLiteral.of(3000)),
                    Condition.Neq("foreground_app", value = RuleLiteral.of("com.instagram.android")),
                    Condition.Gte("app_opens_last_60m", mapOf("package" to "com.instagram.android"), RuleLiteral.of(3)),
                ),
            ),
        )

        val needsAccess = Fixtures.validateDefinition(r1, Fixtures.context(featureAccess = usage))
        val oldPhone = Fixtures.validateDefinition(onScreen, Fixtures.context(featureAccess = FeatureAccess.of(apiLevel = 28)))
        val both = Fixtures.validateDefinition(onScreen, Fixtures.context(featureAccess = usage))

        assertThat(needsAccess.issues(IssueCode.W03).map { it.message })
            .containsExactly("Needs usage access. Until you allow it, this rule will not fire.")
        assertThat(oldPhone.issues(IssueCode.W02).map { it.message })
            .containsExactly("App usage data is not available on this phone, so this rule will not fire.")
        assertThat(both.issues(IssueCode.W03)).hasSize(1)
        assertThat(FeatureStatus.Unsupported("API level 29 or higher")).isNotEqualTo(FeatureStatus.Ready)
    }

    companion object {
        private const val OTHER_ID = "8a5d0c3e-1b2f-4a6d-9e7c-5f4b3a2d1c0e"

        private val walk: JitaiDefinition = golden("definition-3-1.json")
        private val ai: JitaiDefinition = golden("definition-13-6-2.json")

        /** R10 §12 rule S1 as a USER rule. */
        private val S1: JitaiDefinition = golden("definition-13-6-3.json").copy(
            id = "5b1e7c2a-9d3f-4e8b-a6c0-2f4d8e1b7a93",
            name = "Evening block",
            category = JitaiCategory.DIGITAL_WELLBEING,
            status = JitaiStatus.ACTIVE,
            enabled = true,
            activeWindow = ActiveWindow("22:00", "23:00"),
            conditions = null,
            suppression = SuppressionTarget(listOf(JitaiCategory.DIGITAL_WELLBEING)),
            createdBy = CreatedBy.USER_MANUAL,
            createdAt = Instant.parse("2026-09-01T08:00:00Z"),
            modifiedAt = Instant.parse("2026-09-01T08:00:00Z"),
            provenance = null,
        )

        private fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

        private fun row(id: String, definition: JitaiDefinition, vararg lines: String, existing: List<JitaiDefinition> = emptyList()) =
            Arguments.of(id, definition, existing, lines.toList())

        private fun outcome(metric: OutcomeMetric, args: Map<String, String> = emptyMap(), window: Int? = 30): JitaiDefinition =
            walk.copy(outcome = OutcomeSpec(OutcomeMetricRef(metric, args, window)))

        private fun targets(vararg ids: String): JitaiDefinition = S1.copy(suppression = SuppressionTarget(jitaiIds = ids.toList()))

        private fun conditions(condition: Condition): JitaiDefinition =
            walk.copy(conditions = condition, content = ContentStrategy.Static("Walk?", "A short walk now?"))

        @JvmStatic
        fun rows(): List<Arguments> = structureRows() + outcomeRows() + leafRows() + targetRows()

        private fun structureRows(): List<Arguments> = listOf(
            row(
                "E054 a reminder with channel NONE",
                walk.copy(delivery = walk.delivery.copy(channel = DeliveryChannel.NONE)),
                "E054 /delivery/channel: A reminder rule needs a delivery channel other than NONE.",
            ),
            row(
                "E053 a blocking rule with reminder fields",
                S1.copy(cooldownMinutes = 60, snooze = SnoozePolicy.DEFAULT),
                "E053 /cooldownMinutes: A blocking rule cannot have cooldownMinutes; set it to null.",
                "E053 /snooze: A blocking rule cannot have snooze; set it to null.",
            ),
            row(
                "E048 deliveryDeadlineMinutes 0",
                walk.copy(delivery = walk.delivery.copy(deliveryDeadlineMinutes = 0)),
                "E048 /delivery/deliveryDeadlineMinutes: deliveryDeadlineMinutes must be 1-60; got 0.",
            ),
            row(
                "E048 deliverProbability 0.9",
                walk.copy(experiment = ExperimentSpec(ExperimentMode.MICRO_RANDOMIZED, 0.9)),
                "E048 /experiment/deliverProbability: experiment.deliverProbability must be null or 0.3-0.7; got 0.9.",
            ),
            row("deliverProbability 0.5", walk.copy(experiment = ExperimentSpec(ExperimentMode.MICRO_RANDOMIZED, 0.5))),
            row("S1 as written", S1),
        )

        private fun outcomeRows(): List<Arguments> = listOf(
            row(
                "E072 a metric without its package",
                outcome(OutcomeMetric.APP_MINUTES_AFTER),
                "E072 /outcome/proximal/args/package: Outcome APP_MINUTES_AFTER at /outcome/proximal/args/package: " +
                    "requires arg \"package\".",
            ),
            row(
                "E081 a metric with an invalid package",
                outcome(OutcomeMetric.APP_MINUTES_AFTER, mapOf("package" to "not a package")),
                "E081 /outcome/proximal/args/package: \"not a package\" at /outcome/proximal/args/package is not a valid " +
                    "Android package name.",
            ),
            row(
                "E072 an arg the metric does not take",
                outcome(OutcomeMetric.STEPS_AFTER, mapOf("since" to "22:00")),
                "E072 /outcome/proximal/args/since: Outcome STEPS_AFTER at /outcome/proximal/args/since: does not take arg \"since\".",
            ),
            row(
                "E072 an unknown app category",
                outcome(OutcomeMetric.APP_CATEGORY_MINUTES_AFTER, mapOf("category" to "TOYS")),
                "E072 /outcome/proximal/args/category: Outcome APP_CATEGORY_MINUTES_AFTER at /outcome/proximal/args/category: " +
                    "arg \"category\" must be one of ${RealtimeFeatureCatalog.APP_CATEGORIES.joinToString(", ")}.",
            ),
            row("an app category metric", outcome(OutcomeMetric.APP_CATEGORY_MINUTES_AFTER, mapOf("category" to "SOCIAL"))),
            row(
                "E071 a proximal metric as the distal one",
                walk.copy(
                    outcome = OutcomeSpec(
                        OutcomeMetricRef(OutcomeMetric.STEPS_AFTER, windowMinutes = 30),
                        OutcomeMetricRef(OutcomeMetric.NOTIFICATION_OPENED, windowMinutes = 30),
                    ),
                ),
                "E071 /outcome/distal/metric: Metric NOTIFICATION_OPENED cannot be used as a distal outcome.",
            ),
            row(
                "E073 a window outside the range",
                outcome(OutcomeMetric.STEPS_AFTER, window = 5),
                "E073 /outcome/proximal/windowMinutes: windowMinutes for STEPS_AFTER must be 10-120; got 5.",
            ),
            row(
                "E073 a window where none is allowed",
                outcome(OutcomeMetric.SELF_REPORT_HELPFUL, window = 30),
                "E073 /outcome/proximal/windowMinutes: windowMinutes for SELF_REPORT_HELPFUL must be null; got 30.",
            ),
        )

        private fun leafRows(): List<Arguments> = listOf(
            row(
                "E013 an unknown app category",
                conditions(Condition.Gte("app_category_minutes_last_60m", mapOf("category" to "TOYS"), RuleLiteral.of(30))),
                "E013 /conditions/args/category: Arg \"category\" of app_category_minutes_last_60m at /conditions/args/category " +
                    "is invalid: must be one of ${RealtimeFeatureCatalog.APP_CATEGORIES.joinToString(", ")}.",
            ),
            row(
                "E011 a missing app category",
                conditions(Condition.Gte("app_category_minutes_last_60m", value = RuleLiteral.of(30))),
                "E011 /conditions/args/category: app_category_minutes_last_60m at /conditions/args/category requires arg \"category\".",
            ),
            row(
                "E024 a time literal",
                conditions(Condition.Gte("local_time", value = RuleLiteral.of("7:00"))),
                "E024 /conditions/value: \"7:00\" at /conditions/value is not a 24-hour HH:mm time.",
            ),
            row(
                "E081 a package literal",
                conditions(Condition.Eq("foreground_app", value = RuleLiteral.of("not a package"))),
                "E081 /conditions/value: \"not a package\" at /conditions/value is not a valid Android package name.",
            ),
            row(
                "E017 local times",
                conditions(Condition.Between("wake_time_today", min = RuleLiteral.of("09:00"), max = RuleLiteral.of("07:00"))),
                "E017 /conditions: between at /conditions: min 09:00 is greater than max 07:00.",
            ),
            row(
                "E017 night times run from noon",
                conditions(Condition.Between("bedtime_last_night", min = RuleLiteral.of("01:00"), max = RuleLiteral.of("23:00"))),
                "E017 /conditions: between at /conditions: min 01:00 is greater than max 23:00.",
            ),
            row(
                "a night window across midnight",
                conditions(Condition.Between("bedtime_last_night", min = RuleLiteral.of("23:00"), max = RuleLiteral.of("01:00"))),
            ),
        )

        private fun targetRows(): List<Arguments> {
            val blocker = S1.copy(id = OTHER_ID, name = "Other block")
            return listOf(
                row(
                    "E056 a category twice",
                    S1.copy(suppression = SuppressionTarget(listOf(JitaiCategory.DIGITAL_WELLBEING, JitaiCategory.DIGITAL_WELLBEING))),
                    "E056 /suppression/categories/1: Block target DIGITAL_WELLBEING at /suppression/categories/1 is listed more than once.",
                ),
                row(
                    "E056 a rule that does not exist",
                    targets(OTHER_ID),
                    "E056 /suppression/jitaiIds/0: Block target $OTHER_ID at /suppression/jitaiIds/0 does not exist.",
                ),
                row(
                    "E056 a blocking rule as target",
                    targets(OTHER_ID),
                    "E056 /suppression/jitaiIds/0: Block target $OTHER_ID at /suppression/jitaiIds/0 is itself a blocking rule.",
                    existing = listOf(blocker),
                ),
                row(
                    "E056 the rule itself",
                    targets(S1.id),
                    "E056 /suppression/jitaiIds/0: Block target ${S1.id} at /suppression/jitaiIds/0 is this rule itself.",
                ),
                row(
                    "E056 a rule twice",
                    targets(walk.id, walk.id),
                    "E056 /suppression/jitaiIds/1: Block target ${walk.id} at /suppression/jitaiIds/1 is listed more than once.",
                    existing = listOf(walk),
                ),
                row("an existing reminder as target", targets(walk.id), existing = listOf(walk)),
                row(
                    "E056 more than 20 targets",
                    targets(*Array(21) { walk.id.dropLast(2) + it.toString().padStart(2, '0') }),
                    "E056 /suppression/jitaiIds/20: Block target ${walk.id.dropLast(2)}20 at /suppression/jitaiIds/20 " +
                        "is beyond the limit of 20 targets.",
                    existing = List(20) { walk.copy(id = walk.id.dropLast(2) + it.toString().padStart(2, '0')) },
                ),
            )
        }
    }
}
