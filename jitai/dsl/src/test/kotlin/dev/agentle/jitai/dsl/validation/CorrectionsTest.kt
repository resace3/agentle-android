package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.group
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.leaf
import dev.agentle.jitai.dsl.testing.warningCodes
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.testing.withNull
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The integrator corrections that change validation (sets 1 and 2; the README lists every correction). */
class CorrectionsTest {
    // ---------------------------------------------------------------- lifecycle-battery-06

    @Test
    fun `location_class is rejected as a capability that is unavailable (E028)`() {
        val report = ai(withConditions(leaf("eq", "location_class", "HOME")))

        assertThat(report.issues(IssueCode.E028).map { it.line }).containsExactly(
            "E028 /jitai/conditions/of/1/feature: Feature \"location_class\" at /jitai/conditions/of/1/feature cannot be used: " +
                "capability unavailable: location_background.",
        )
        assertThat(report.definition).isNull()
    }

    @Test
    fun `LOCATION_CLASS_CHANGED is rejected the same way, also in stored rules`() {
        val report = ai(event("LOCATION_CLASS_CHANGED"))
        val stored = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow().copy(
            trigger = Trigger.Event(listOf(JitaiEventType.LOCATION_CLASS_CHANGED)),
            activeWindow = ActiveWindow("16:00", "18:00"),
        )

        assertThat(report.issues(IssueCode.E028).single().message).isEqualTo(
            "Trigger event \"LOCATION_CLASS_CHANGED\" at /jitai/trigger/events/0 cannot be used: " +
                "capability unavailable: location_background.",
        )
        assertThat(RuleCodec.decodeDefinition(RuleCodec.encodeDefinition(stored)).getOrThrow()).isEqualTo(stored)
        assertThat(RuleValidator.revalidate(stored).codes).contains(IssueCode.E028)
    }

    // ---------------------------------------------------------------- lifecycle-battery-07

    @ParameterizedTest(name = "{0}")
    @EnumSource(JitaiEventType::class, names = ["LOCATION_CLASS_CHANGED"], mode = EnumSource.Mode.EXCLUDE)
    fun `W08 marks the events only a runtime receiver sees`(event: JitaiEventType) {
        val report = ai(event(event.name))
        val bestEffort = event in setOf(
            JitaiEventType.USER_PRESENT,
            JitaiEventType.SCREEN_INTERACTIVE,
            JitaiEventType.POWER_CONNECTED,
            JitaiEventType.POWER_DISCONNECTED,
        )

        assertThat(report.errorCodes).isEmpty()
        assertThat(report.warningCodes.contains(IssueCode.W08)).isEqualTo(bestEffort)
        if (event == JitaiEventType.USER_PRESENT) {
            assertThat(report.issues(IssueCode.W08).single().message).isEqualTo(
                "Reacting when you unlock the phone is best effort: it is reliable only while notification access keeps Agentle running.",
            )
        }
    }

    // ---------------------------------------------------------------- jitai-correctness-17

    @Test
    fun `E029 since inside the active window would read from the previous day`() {
        val inside = ai(example1.with("/jitai/conditions/args/since", "23:00"))
        val atStart = ai(example1)
        val before = ai(example1.with("/jitai/conditions/args/since", "21:00"))

        assertThat(inside.issues(IssueCode.E029).map { it.line }).containsExactly(
            "E029 /jitai/conditions/args/since: since 23:00 at /jitai/conditions/args/since must be at or before the active window " +
                "start 22:00; inside the window it would read from the previous day.",
        )
        assertThat(atStart.errorCodes).isEmpty()
        assertThat(before.errorCodes).isEmpty()
    }

    @Test
    fun `E029 daily times must fall 1 minute to 12 hours after since`() {
        fun since(since: String) = ai(withConditions(leaf("gte", "screen_minutes_since", 30, "since" to since)))

        assertThat(since("12:00").errorCodes).isEmpty()
        assertThat(since("05:00").errorCodes).isEmpty()
        assertThat(since("16:59").errorCodes).isEmpty()
        assertThat(since("04:59").errorCodes).containsExactly(IssueCode.E029)
        assertThat(since("17:00").errorCodes).containsExactly(IssueCode.E029)
        assertThat(since("18:00").issues(IssueCode.E029).single().message)
            .isEqualTo("since 18:00 at /jitai/conditions/of/1/args/since must be 1 minute to 12 hours before the daily time 17:00.")
    }

    @Test
    fun `E029 since without a window or daily times is unbounded`() {
        val text = example3.withNull("/jitai/activeWindow")
            .with("/jitai/conditions", leaf("gte", "screen_minutes_since", 120, "since" to "20:00"))

        assertThat(ai(text).issues(IssueCode.E029).single().message).isEqualTo(
            "since 20:00 at /jitai/conditions/args/since needs an active window or daily times; " +
                "at other times it would read from the previous day.",
        )
    }

    @Test
    fun `W09 local_time gte an evening time stops at midnight unless the window crosses it`() {
        val r1 = golden("definition-12-r1.json")
        val sameDay = r1.copy(activeWindow = ActiveWindow("20:00", "23:30"))
        val notEvening = sameDay.copy(conditions = replaceTime(sameDay, "09:00"))
        val negated = sameDay.copy(conditions = Condition.Not(Condition.Gte("local_time", value = RuleLiteral.of("22:00"))))

        assertThat(user(r1).warningCodes).doesNotContain(IssueCode.W09)
        assertThat(user(sameDay).issues(IssueCode.W09).map { it.line }).containsExactly(
            "W09 /conditions/of/1/value: \"local_time gte 22:00\" at /conditions/of/1/value is false from midnight on; " +
                "to include the hours after midnight use local_time_in or a window that crosses midnight.",
        )
        assertThat(user(notEvening).warningCodes).doesNotContain(IssueCode.W09)
        assertThat(user(negated).warningCodes).doesNotContain(IssueCode.W09)
    }

    // ---------------------------------------------------------------- jitai-correctness-18

    @Test
    fun `W10 when the text has placeholders or app names`() {
        val placeholder = ai(base)
        val plain = ai(base.with("/jitai/content", json("""{"type": "static", "title": "Walk?", "body": "A short walk now?"}""")))
        val appName = Fixtures.validateDefinition(
            golden("definition-13-6-1.json").copy(content = ContentStrategy.Static("Wind down?", "Maybe close Instagram for tonight.")),
        )

        assertThat(placeholder.issues(IssueCode.W10).map { it.line }).containsExactly(
            "W10 /jitai/content: Notifications show a general text unless detailed notifications are on; " +
                "the values and app names in this text appear only inside the app.",
        )
        assertThat(plain.warningCodes).doesNotContain(IssueCode.W10)
        assertThat(appName.warningCodes).contains(IssueCode.W10)
    }

    // ---------------------------------------------------------------- jitai-correctness-14

    @Test
    fun `a daily_at reminder without snooze re-evaluates after the snooze`() {
        val daily = checkNotNull(ai(base.withNull("/jitai/snooze")).definition)
        val interval = checkNotNull(ai(example1.withNull("/jitai/snooze")).definition)
        val suppression = checkNotNull(ai(example3).definition)

        assertThat(
            daily.snooze,
        ).isEqualTo(SnoozePolicy(SnoozeMode.RE_EVALUATE_AFTER, listOf(SnoozeOption.MINUTES_60, SnoozeOption.UNTIL_TOMORROW)))
        assertThat(interval.snooze).isEqualTo(SnoozePolicy.DEFAULT)
        assertThat(suppression.snooze).isNull()
        assertThat(SnoozePolicy.defaultFor(Trigger.DailyAt(listOf("17:00")))).isEqualTo(SnoozePolicy.DEFAULT_DAILY_AT)
        assertThat(SnoozePolicy.defaultFor(null)).isEqualTo(SnoozePolicy.DEFAULT)
    }

    // ---------------------------------------------------------------- jitai-correctness-01

    @Test
    fun `re-validation is a pure function of the definition and the catalog version`() {
        val definitions = GOLDEN_DEFINITIONS.map(::golden)

        val first = definitions.map { RuleValidator.revalidate(it, Fixtures.MEDIA) }
        val second = definitions.map { RuleValidator.revalidate(it, Fixtures.MEDIA) }

        assertThat(first).isEqualTo(second)
        assertThat(first.flatMap { it.errors }).isEmpty()
        assertThat(first.map { it.jitaiId }).containsExactlyElementsIn(definitions.map { it.id }).inOrder()
        assertThat(first.map { it.catalogVersion }.toSet()).containsExactly(RealtimeFeatureCatalog.VERSION)
    }

    @Test
    fun `re-validation without the other rules accepts UUID references and checks media only when given`() {
        val r1 = golden("definition-12-r1.json")
        val reference = r1.copy(
            conditions = Condition.AllOf(
                listOf(
                    Condition.Gte("screen_minutes_last_60m", value = RuleLiteral.of(45)),
                    Condition.Lt(
                        "minutes_since_last_delivery",
                        mapOf("jitai" to "3f6c1d2e-8b7a-4c1e-9a55-0d7e2b9c4a10"),
                        RuleLiteral.of(120),
                    ),
                ),
            ),
        )
        val badReference = reference.copy(
            conditions = Condition.Lt("minutes_since_last_delivery", mapOf("jitai" to "walk"), RuleLiteral.of(120)),
            content = ContentStrategy.Static("Break?", "Time for a short break?"),
        )
        val media = r1.copy(
            delivery = r1.delivery.copy(channel = DeliveryChannel.IMAGE),
            content = ContentStrategy.LocalMedia("unknown_asset", ContentStrategy.Template("Break?", "{{screen_minutes_last_60m}} min.")),
            conditions = Condition.Gte("screen_minutes_last_60m", value = RuleLiteral.of(45)),
        )

        assertThat(RuleValidator.revalidate(reference).errors).isEmpty()
        assertThat(RuleValidator.revalidate(badReference).codes).containsExactly(IssueCode.E013)
        assertThat(RuleValidator.revalidate(media).errors).isEmpty()
        assertThat(RuleValidator.revalidate(media, Fixtures.MEDIA).codes).containsExactly(IssueCode.E068)
        assertThat(RuleValidator.revalidate(media, MediaLibrary.of(setOf("unknown_asset"))).errors).isEmpty()
    }

    // ---------------------------------------------------------------- privacy-ai-12, privacy-ai-06

    @Test
    fun `the stored definition has the full R10 3_1 field set`() {
        val keys = (json(Fixtures.definition31) as kotlinx.serialization.json.JsonObject).keys.toList()

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val fields = (0 until JitaiDefinition.serializer().descriptor.elementsCount).map {
            JitaiDefinition.serializer().descriptor.getElementName(it)
        }

        assertThat(fields).containsExactlyElementsIn(keys).inOrder()
    }

    @Test
    fun `lint ids are exactly L1-L8`() {
        assertThat(LintCheck.entries.map { it.name }).containsExactly("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8").inOrder()
    }

    private fun ai(element: JsonElement, createdBy: CreatedBy = CreatedBy.AI_NATURAL_LANGUAGE): ValidationReport =
        Fixtures.validateText(element.toString(), createdBy = createdBy)

    private fun user(definition: JitaiDefinition): ValidationReport =
        Fixtures.validateDefinition(definition, Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS))

    private fun replaceTime(definition: JitaiDefinition, time: String): Condition {
        val group = definition.conditions as Condition.AllOf
        return group.copy(of = listOf(group.of[0], Condition.Gte("local_time", value = RuleLiteral.of(time))))
    }

    companion object {
        private val GOLDEN_DEFINITIONS = listOf(
            "definition-3-1.json",
            "definition-12-r1.json",
            "definition-13-6-1.json",
            "definition-13-6-2.json",
            "definition-13-6-3.json",
            "definition-14-7.json",
        )

        private val base: JsonElement get() = json(Fixtures.example2)
        private val example1: JsonElement get() = json(Fixtures.example1)
        private val example3: JsonElement get() = json(Fixtures.example3)

        private fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

        private fun withConditions(vararg extra: JsonElement): JsonElement =
            base.with("/jitai/conditions", group("all", leaf("lt", "steps_today", 3000), *extra))

        private fun event(name: String): JsonElement = base
            .with("/jitai/trigger", json("""{"type": "event", "events": ["$name"], "debounceSeconds": 60}"""))
            .with("/jitai/activeWindow", json("""{"start": "16:00", "end": "18:00", "days": null}"""))
    }
}
