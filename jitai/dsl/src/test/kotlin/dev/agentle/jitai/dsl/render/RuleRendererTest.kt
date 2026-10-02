package dev.agentle.jitai.dsl.render

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.model.TextPair
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** R10 §13.5: the deterministic renderer, its golden sentences and every phrase table. */
class RuleRendererTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    fun `R10 13_5 golden sentences`(file: String, expected: String) {
        assertThat(RuleRenderer.render(golden(file), INSTAGRAM_LABELS)).isEqualTo(expected)
    }

    @Test
    fun `the 24-hour setting changes every time`() {
        val options = INSTAGRAM_LABELS.copy(use24HourClock = true)

        assertThat(RuleRenderer.render(golden("definition-13-6-1.json"), options)).isEqualTo(
            "Every 30 minutes from 22:00 to 02:00: if time in Instagram since 22:00 is at least 30 min, and only while you are " +
                "using the phone, send a notification (allowed during quiet hours while you are using the phone). " +
                "At most 1 per day and 7 per week, at least 2 h apart.",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schedules")
    fun `schedules`(row: String, definition: JitaiDefinition, expected: String) {
        assertWithMessage(row).that(RuleRenderer.render(definition).substringBefore(": ")).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodies")
    fun `bodies and limits`(row: String, definition: JitaiDefinition, expected: String) {
        assertWithMessage(row).that(RuleRenderer.render(definition).substringAfter(": ")).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaves")
    fun `leaf phrases`(row: String, condition: Condition, expected: String) {
        val options = RenderOptions(appLabels = mapOf(INSTAGRAM to "Instagram"), jitaiNames = mapOf(WALK_ID to "Walk"))

        assertWithMessage(row).that(RuleRenderer.condition(condition, options)).isEqualTo(expected)
    }

    @Test
    fun `groups, not and parentheses follow R10 13_5`() {
        val a = Condition.Gte("battery_pct", value = RuleLiteral.of(20))
        val b = Condition.Eq("charging", value = RuleLiteral.of(true))
        val c = Condition.LocalTimeIn("22:00", "02:00")

        assertThat(RuleRenderer.condition(Condition.AllOf(listOf(Condition.AnyOf(listOf(a, b)), c))))
            .isEqualTo("(the battery level is at least 20% or the phone is charging) and the time is between 10:00 PM and 2:00 AM")
        assertThat(RuleRenderer.condition(Condition.AllOf(listOf(a, b, c))))
            .isEqualTo("the battery level is at least 20%, the phone is charging and the time is between 10:00 PM and 2:00 AM")
        assertThat(RuleRenderer.condition(Condition.Not(Condition.AllOf(listOf(a, b)))))
            .isEqualTo("not (the battery level is at least 20% and the phone is charging)")
        assertThat(RuleRenderer.condition(Condition.AllOf(listOf(Condition.AnyOf(listOf(a)), b))))
            .isEqualTo("the battery level is at least 20% and the phone is charging")
        assertThat(RuleRenderer.condition(Condition.LocalTimeIn("9:00", "17:00"))).isEqualTo("the time is between 9:00 and 5:00 PM")
    }

    @Test
    fun `Ends after uses the local date of expiresAt minus one second in the zone it is given`() {
        val expiring = base().copy(expiresAt = Instant.parse("2026-10-29T02:30:00Z"))

        assertThat(RuleRenderer.render(expiring, RenderOptions(zone = TimeZone.of("America/St_Johns"))))
            .endsWith("at least 1 h apart. Ends after October 28, 2026.")
        assertThat(RuleRenderer.render(expiring, RenderOptions(zone = Fixtures.BERLIN))).endsWith(" Ends after October 29, 2026.")
        assertThat(RuleRenderer.render(expiring)).endsWith(" Ends after October 29, 2026.")
    }

    @Test
    fun `content parts mark placeholders and app names (jitai-correctness-18)`() {
        val parts = RuleRenderer.content(golden("definition-13-6-1.json"), INSTAGRAM_LABELS)

        assertThat(parts.map { it.path }).containsExactly("/content/title", "/content/body").inOrder()
        assertThat(parts[0].isPersonal).isFalse()
        assertThat(parts[1].isPersonal).isTrue()
        assertThat(parts[1].parts).containsExactly(
            ContentPart.Placeholder("app_minutes_since"),
            ContentPart.Literal(" minutes on "),
            ContentPart.AppName("Instagram"),
            ContentPart.Literal(" since 10 PM. How about putting the phone away and getting ready for bed?"),
        ).inOrder()
        assertThat(parts[1].text).isEqualTo(golden("definition-13-6-1.json").content.let { (it as ContentStrategy.Template).body })
        assertThat(RuleRenderer.content(golden("definition-13-6-3.json"))).isEmpty()
    }

    @Test
    fun `content parts cover every content strategy`() {
        val template = ContentStrategy.Template("Walk?", "{{steps_today}} steps")
        val strategies = listOf(
            ContentStrategy.Static("Walk?", "Go") to listOf("/content/title", "/content/body"),
            ContentStrategy.Variants(listOf(TextPair("A", "a"), TextPair("B", "b"))) to
                listOf("/content/items/0/title", "/content/items/0/body", "/content/items/1/title", "/content/items/1/body"),
            ContentStrategy.AiText("Encourage a walk", Tone.WARM, template) to listOf("/content/fallback/title", "/content/fallback/body"),
            ContentStrategy.LocalMedia("sunset_walk_01", template) to listOf("/content/caption/title", "/content/caption/body"),
        )

        strategies.forEach { (content, paths) ->
            assertThat(RuleRenderer.content(base().copy(content = content)).map { it.path }).containsExactlyElementsIn(paths).inOrder()
            assertThat(ContentParts.texts(content)).hasSize(paths.size)
        }
        assertThat(ContentParts.hasPersonalParts(template)).isTrue()
        assertThat(ContentParts.hasPersonalParts(ContentStrategy.Static("Walk?", "Go"))).isFalse()
        assertThat(ContentParts.hasPersonalParts(ContentStrategy.Static("Walk?", "Close YouTube"), listOf("YouTube"))).isTrue()
    }

    @Test
    fun `app names match case-insensitively, longest first, and malformed placeholders stay literal`() {
        val parts = ContentParts.parse("instagram or Instagram Lite? {{steps_today} {{x}}", listOf("Instagram", "Instagram Lite", " "))

        assertThat(parts).containsExactly(
            ContentPart.AppName("instagram"),
            ContentPart.Literal(" or "),
            ContentPart.AppName("Instagram Lite"),
            ContentPart.Literal("? {{steps_today} "),
            ContentPart.Placeholder("x"),
        ).inOrder()
        assertThat(ContentParts.parse("")).isEmpty()
        assertThat(ContentParts.parse("{{a}}{{b}}")).containsExactly(ContentPart.Placeholder("a"), ContentPart.Placeholder("b")).inOrder()
        assertThat(ContentPart.Placeholder("a").text).isEqualTo("{{a}}")
        assertThat(ContentPart.Literal("a").isPersonal).isFalse()
    }

    @Test
    fun `app labels in content come from provenance and from the rule's packages`() {
        val definition = base().copy(
            conditions = Condition.Eq("foreground_app", value = RuleLiteral.of(YOUTUBE)),
            contextRequirements = Condition.Gte("app_opens_last_60m", mapOf("package" to INSTAGRAM), RuleLiteral.of(3)),
            content = ContentStrategy.Static("Break?", "Close YouTube, Instagram and Maps."),
            outcome = OutcomeSpec(
                OutcomeMetricRef(OutcomeMetric.APP_MINUTES_AFTER, mapOf("package" to MAPS), 30),
                OutcomeMetricRef(OutcomeMetric.APP_MINUTES_AFTER, mapOf("package" to "com.other.app"), 30),
            ),
            provenance = Provenance(appLabels = mapOf("com.example.unused" to "Unused")),
        )
        val options = RenderOptions(appLabels = mapOf(YOUTUBE to "YouTube", INSTAGRAM to "Instagram", MAPS to "Maps"))

        val body = RuleRenderer.content(definition, options)[1]

        assertThat(body.parts.filterIsInstance<ContentPart.AppName>().map { it.text }).containsExactly("YouTube", "Instagram", "Maps")
        assertThat(RuleRenderer.content(definition)[1].isPersonal).isFalse()
    }

    @Test
    fun `phrase tables`() {
        assertThat(JitaiEventType.entries.map(Phrases::event)).containsNoDuplicates()
        assertThat(JitaiCategory.entries.map(Phrases::category))
            .containsExactly("Physical activity", "Sleep wind-down", "Digital wellbeing", "Stress break", "General")
        assertThat(WeekDay.entries.map(Phrases::day))
            .containsExactly("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").inOrder()
        assertThat(Phrases.list(emptyList())).isEmpty()
        assertThat(Phrases.list(listOf("a"))).isEqualTo("a")
        assertThat(Phrases.list(listOf("a", "b"), "or")).isEqualTo("a or b")
        assertThat(Phrases.list(listOf("a", "b", "c"))).isEqualTo("a, b and c")
        assertThat(
            listOf(0L, 999L, 1000L, 1234567L, -1234L).map(Phrases::grouped),
        ).containsExactly("0", "999", "1,000", "1,234,567", "-1,234")
        assertThat(listOf(0L, 59L, 60L, 90L, 120L).map(Phrases::duration)).containsExactly("0 min", "59 min", "1 h", "1 h 30 min", "2 h")
        assertThat(listOf(0, 1, 720, 779, 1439).map { Phrases.time(it, use24HourClock = false) })
            .containsExactly("12:00 AM", "12:01 AM", "12:00 PM", "12:59 PM", "11:59 PM").inOrder()
        assertThat(Phrases.time(5, use24HourClock = true)).isEqualTo("00:05")
    }

    @Test
    fun `data labels name each feature group`() {
        assertThat(FeatureLabels.dataLabel("steps_today")).isEqualTo("step data")
        assertThat(FeatureLabels.dataLabel("activity_state")).isEqualTo("activity data")
        assertThat(FeatureLabels.dataLabel("sleep_minutes_last_night")).isEqualTo("sleep data")
        assertThat(FeatureLabels.dataLabel("resting_hr_today")).isEqualTo("heart rate data")
        assertThat(FeatureLabels.dataLabel("location_class")).isEqualTo("location data")
        assertThat(FeatureLabels.dataLabel("app_minutes_last_60m")).isEqualTo("app usage data")
        assertThat(FeatureLabels.dataLabel("notifications_last_60m")).isEqualTo("notification data")
        assertThat(FeatureLabels.dataLabel("charging")).isEqualTo("battery data")
        assertThat(FeatureLabels.dataLabel("local_time")).isNull()
        assertThat(FeatureLabels.dataLabel("deliveries_today")).isNull()
        assertThat(FeatureLabels.dataLabel("heart_rate_variability")).isNull()
        assertThat(FeatureLabels.labelOrId("local_time")).isEqualTo("local_time")
        assertThat(FeatureLabels.isRemoteHealth("steps_today")).isTrue()
        assertThat(FeatureLabels.isRemoteHealth("activity_state")).isFalse()
        assertThat(FeatureLabels.isRemoteHealth("battery_pct")).isFalse()
        assertThat(FeatureLabels.isRemoteHealth("heart_rate_variability")).isFalse()
        assertThat(FeatureLabels.capitalized("step data")).isEqualTo("Step data")
    }

    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val MAPS = "com.google.android.apps.maps"
        private const val WALK_ID = "3f6c1d2e-8b7a-4c1e-9a55-0d7e2b9c4a10"
        private val INSTAGRAM_LABELS = RenderOptions(appLabels = mapOf(INSTAGRAM to "Instagram"))

        private fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

        /** R10 §3.1: daily_at 17:00, `steps_today lt 3000`, NOTIFICATION, cooldown 60, 1 per day and 7 per week. */
        private fun base(): JitaiDefinition = golden("definition-3-1.json")

        private val window = ActiveWindow("22:00", "02:00")
        private val weekend = listOf(WeekDay.SAT, WeekDay.SUN)
        private val steps = Condition.Lt("steps_today", value = RuleLiteral.of(3000))
        private val interactive = Condition.Eq("device_interactive", value = RuleLiteral.of(true))

        private fun suppression(window: ActiveWindow?, conditions: Condition?, targets: SuppressionTarget) = base().copy(
            kind = JitaiKind.SUPPRESSION,
            trigger = null,
            activeWindow = window,
            conditions = conditions,
            delivery = Delivery(channel = DeliveryChannel.NONE),
            content = null,
            cooldownMinutes = null,
            maxPerDay = null,
            maxPerWeek = null,
            snooze = null,
            outcome = null,
            suppression = targets,
        )

        @JvmStatic
        fun goldens(): List<Arguments> = listOf(
            Arguments.of(
                "definition-12-r1.json",
                "Every 15 minutes from 8:00 PM to 2:00 AM: if screen time in the last 60 minutes is at least 45 min and the time is " +
                    "10:00 PM or later, send a notification. At most 3 per day and 21 per week, at least 1 h apart.",
            ),
            Arguments.of(
                "definition-13-6-1.json",
                "Every 30 minutes from 10:00 PM to 2:00 AM: if time in Instagram since 10:00 PM is at least 30 min, and only while " +
                    "you are using the phone, send a notification (allowed during quiet hours while you are using the phone). " +
                    "At most 1 per day and 7 per week, at least 2 h apart.",
            ),
            Arguments.of(
                "definition-13-6-2.json",
                "Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification. " +
                    "At most 1 per day and 7 per week, at least 1 h apart.",
            ),
            Arguments.of(
                "definition-13-6-3.json",
                "From 12:00 AM to 9:00 AM: block Physical activity reminders if your sleep last night is less than 6 h " +
                    "(also when sleep data is unknown).",
            ),
            Arguments.of(
                "definition-14-7.json",
                "Every 30 minutes from 10:00 PM to 1:00 AM: if screen time since 10:00 PM is at least 45 min, and only while you " +
                    "are using the phone, send a notification (allowed during quiet hours while you are using the phone). " +
                    "At most 1 per day and 7 per week, at least 2 h apart.",
            ),
        )

        @JvmStatic
        fun schedules(): List<Arguments> = listOf(
            Arguments.of("daily_at one time", base(), "Every day at 5:00 PM"),
            Arguments.of(
                "daily_at three times",
                base().copy(trigger = Trigger.DailyAt(listOf("07:30", "12:00", "17:00"))),
                "Every day at 7:30 AM, 12:00 PM and 5:00 PM",
            ),
            Arguments.of(
                "daily_at on days",
                base().copy(
                    trigger = Trigger.DailyAt(listOf("07:30", "12:00", "17:00")),
                    activeWindow = ActiveWindow("07:00", "18:00", weekend),
                ),
                "At 7:30 AM, 12:00 PM and 5:00 PM on Saturday and Sunday",
            ),
            Arguments.of("interval without window", base().copy(trigger = Trigger.Interval(30)), "Every 30 minutes"),
            Arguments.of(
                "interval with days",
                base().copy(trigger = Trigger.Interval(30), activeWindow = window.copy(days = listOf(WeekDay.FRI))),
                "Every 30 minutes from 10:00 PM to 2:00 AM on Friday",
            ),
            Arguments.of(
                "suppression with a window and days",
                suppression(window.copy(days = weekend), null, SuppressionTarget(listOf(JitaiCategory.GENERAL))),
                "From 10:00 PM to 2:00 AM on Saturday and Sunday",
            ),
            Arguments.of(
                "event alternatives",
                base().copy(trigger = Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED, JitaiEventType.USER_PRESENT))),
                "When the phone starts charging or you unlock the phone",
            ),
            Arguments.of("no trigger", base().copy(trigger = null), "At any time"),
            Arguments.of(
                "suppression without window",
                suppression(null, steps, SuppressionTarget(listOf(JitaiCategory.DIGITAL_WELLBEING))),
                "At any time",
            ),
        )

        @JvmStatic
        fun bodies(): List<Arguments> = listOf(
            Arguments.of(
                "no conditions",
                base().copy(conditions = null),
                "send a notification. At most 1 per day and 7 per week, at least 1 h apart.",
            ),
            Arguments.of(
                "only context",
                base().copy(conditions = null, contextRequirements = interactive),
                "only while you are using the phone, send a notification. At most 1 per day and 7 per week, at least 1 h apart.",
            ),
            Arguments.of(
                "image without caps",
                base().copy(delivery = Delivery(DeliveryChannel.IMAGE), maxPerDay = null, maxPerWeek = null, cooldownMinutes = null),
                "if your step count today is less than 3,000 steps, send an image notification.",
            ),
            Arguments.of(
                "voice with a daily cap only",
                base().copy(delivery = Delivery(DeliveryChannel.VOICE), maxPerWeek = null, cooldownMinutes = null),
                "if your step count today is less than 3,000 steps, say a reminder out loud. At most 1 per day.",
            ),
            Arguments.of(
                "video with a weekly cap only",
                base().copy(delivery = Delivery(DeliveryChannel.VIDEO), maxPerDay = null, cooldownMinutes = 90),
                "if your step count today is less than 3,000 steps, offer a short video. At most 7 per week, at least 1 h 30 min apart.",
            ),
            Arguments.of(
                "cooldown only",
                base().copy(maxPerDay = null, maxPerWeek = null, cooldownMinutes = 45),
                "if your step count today is less than 3,000 steps, send a notification. At least 45 min apart.",
            ),
            Arguments.of(
                "suppression of categories and named rules",
                suppression(
                    window.copy(days = weekend),
                    Condition.AnyOf(listOf(steps, Condition.Lt("sleep_minutes_last_night", value = RuleLiteral.of(390)))),
                    SuppressionTarget(listOf(JitaiCategory.STRESS_BREAK, JitaiCategory.GENERAL), listOf(WALK_ID, "unknown-id")),
                ),
                "block Stress break and General reminders, \"another reminder\" and \"another reminder\" if your step count today " +
                    "is less than 3,000 steps or your sleep last night is less than 6 h 30 min (also when step data and sleep data " +
                    "is unknown).",
            ),
            Arguments.of(
                "suppression without targets or data",
                suppression(window, Condition.LocalTimeIn("23:00", "01:00"), SuppressionTarget()),
                "block no reminders if the time is between 11:00 PM and 1:00 AM.",
            ),
            Arguments.of(
                "suppression without conditions",
                suppression(window, null, SuppressionTarget(listOf(JitaiCategory.PHYSICAL_ACTIVITY))),
                "block Physical activity reminders.",
            ),
        )

        private fun leaf(id: String, condition: Condition, expected: String) = Arguments.of(id, condition, expected)

        private fun lit(value: Int) = RuleLiteral.of(value)

        private fun lit(value: String) = RuleLiteral.of(value)

        @JvmStatic
        fun leaves(): List<Arguments> = valueLeaves() + argLeaves()

        private fun valueLeaves(): List<Arguments> = listOf(
            leaf(
                "int gt",
                Condition.Gt("screen_minutes_last_60m", value = lit(45)),
                "screen time in the last 60 minutes is more than 45 min",
            ),
            leaf("int lte", Condition.Lte("battery_pct", value = lit(15)), "the battery level is at most 15%"),
            leaf("int neq", Condition.Neq("notifications_last_60m", value = lit(0)), "notifications in the last 60 minutes is not 0"),
            leaf(
                "int between",
                Condition.Between("steps_last_60m", min = lit(1000), max = lit(2500)),
                "your steps in the last 60 minutes is between 1,000 steps and 2,500 steps",
            ),
            leaf(
                "int in",
                Condition.In("screen_minutes_last_60m", values = listOf(lit(45), lit(50), lit(55))),
                "screen time in the last 60 minutes is 45 min, 50 min or 55 min",
            ),
            leaf(
                "int not a whole token",
                Condition.Lt("steps_last_30m", value = RuleLiteral.NumberToken("45.0")),
                "your steps in the last 30 minutes is less than 45.0",
            ),
            leaf("time gt", Condition.Gt("bedtime_last_night", value = lit("23:30")), "your bedtime last night is after 11:30 PM"),
            leaf("time lt", Condition.Lt("wake_time_today", value = lit("07:00")), "your wake-up time today is before 7:00 AM"),
            leaf("time lte", Condition.Lte("local_time", value = lit("09:00")), "the time is 9:00 AM or earlier"),
            leaf("time eq", Condition.Eq("local_time", value = lit("12:00")), "the time is 12:00 PM"),
            leaf("time number", Condition.Eq("local_time", value = lit(5)), "the time is 5"),
            leaf("bool eq true", interactive, "you are using the phone"),
            leaf("bool eq false", Condition.Eq("dnd_active", value = RuleLiteral.of(false)), "Do Not Disturb is off"),
            leaf("bool neq false", Condition.Neq("in_call", value = RuleLiteral.of(false)), "you are in a call"),
            leaf("bool neq true", Condition.Neq("headphones_connected", value = RuleLiteral.of(true)), "no headphones are connected"),
            leaf("bool charging", Condition.Eq("charging", value = RuleLiteral.of(true)), "the phone is charging"),
            leaf("bool not a boolean", Condition.Eq("charging", value = lit(1)), "the phone is charging"),
            leaf("days", Condition.In("day_of_week", values = listOf(lit("SAT"), lit("SUN"))), "the day is Saturday or Sunday"),
            leaf("engine day", Condition.Eq("engine_day_of_week", value = lit("FRI")), "the night's day is Friday"),
            leaf("day spelled out", Condition.Eq("day_of_week", value = lit("Monday")), "the day is \"Monday\""),
            leaf("day type", Condition.Eq("day_type", value = lit("WEEKEND")), "the day is a weekend day"),
            leaf("location", Condition.Neq("location_class", value = lit("OTHER")), "your location is not somewhere else"),
            leaf("activity", Condition.Eq("activity_state", value = lit("ON_BICYCLE")), "your current activity is cycling"),
            leaf(
                "activity level",
                Condition.Eq("activity_level_last_30m", value = lit("MODERATE_OR_VIGOROUS")),
                "your activity in the last 30 minutes is moderate or vigorous",
            ),
            leaf("enum unknown member", Condition.Eq("activity_state", value = lit("FLYING")), "your current activity is flying"),
            leaf("enum number", Condition.Eq("activity_state", value = lit(1)), "your current activity is 1"),
            leaf("app on screen", Condition.Eq("foreground_app", value = lit(INSTAGRAM)), "the app on screen is Instagram"),
            leaf("no app on screen", Condition.Eq("foreground_app", value = lit("NONE")), "the app on screen is no app"),
            leaf("unlabelled app", Condition.Neq("foreground_app", value = lit(YOUTUBE)), "the app on screen is not $YOUTUBE"),
            leaf("app number", Condition.Eq("foreground_app", value = lit(5)), "the app on screen is 5"),
        )

        private fun argLeaves(): List<Arguments> = listOf(
            leaf(
                "app minutes",
                Condition.Gte("app_minutes_last_60m", mapOf("package" to INSTAGRAM), lit(20)),
                "time in Instagram in the last 60 minutes is at least 20 min",
            ),
            leaf(
                "app label of a proposal",
                Condition.Gte("app_opens_last_60m", mapOf("appLabel" to "Insta"), lit(5)),
                "times Insta was opened in the last 60 minutes is at least 5",
            ),
            leaf(
                "app missing",
                Condition.Gte("app_opens_last_60m", value = lit(5)),
                "times the app was opened in the last 60 minutes is at least 5",
            ),
            leaf(
                "app category",
                Condition.Gte("app_category_minutes_since", mapOf("category" to "IMAGE", "since" to "20:00"), lit(30)),
                "time in photo apps since 8:00 PM is at least 30 min",
            ),
            leaf(
                "unknown app category",
                Condition.Gte("app_category_minutes_last_60m", mapOf("category" to "TOYS"), lit(30)),
                "time in toys apps in the last 60 minutes is at least 30 min",
            ),
            leaf(
                "screen since",
                Condition.Gte("screen_minutes_since", mapOf("since" to "22:00"), lit(90)),
                "screen time since 10:00 PM is at least 90 min",
            ),
            leaf("sleep", Condition.Lt("sleep_minutes_last_night", value = lit(360)), "your sleep last night is less than 6 h"),
            leaf(
                "resting heart rate",
                Condition.Gt("resting_hr_today", value = lit(80)),
                "your resting heart rate today is more than 80 bpm",
            ),
            leaf(
                "heart rate up",
                Condition.Gte("resting_hr_delta_vs_28d", value = lit(5)),
                "your resting heart rate today compared with your usual is at least +5 bpm",
            ),
            leaf(
                "heart rate down",
                Condition.Lte("resting_hr_delta_vs_28d", value = lit(-3)),
                "your resting heart rate today compared with your usual is at most -3 bpm",
            ),
            leaf(
                "self",
                Condition.Lt("minutes_since_last_delivery", mapOf("jitai" to "self"), lit(120)),
                "the time since this reminder was last sent is less than 120 min",
            ),
            leaf("any", Condition.Gte("deliveries_today", mapOf("jitai" to "any"), lit(2)), "any reminder sent today is at least 2"),
            leaf(
                "category",
                Condition.Gte("deliveries_last_7d", mapOf("jitai" to "category:DIGITAL_WELLBEING"), lit(3)),
                "Digital wellbeing reminders sent in the last 7 days is at least 3",
            ),
            leaf(
                "unknown category",
                Condition.Gte("deliveries_today", mapOf("jitai" to "category:FUN"), lit(1)),
                "category:FUN sent today is at least 1",
            ),
            leaf(
                "named rule",
                Condition.Gte("consecutive_ignored", mapOf("jitai" to WALK_ID), lit(3)),
                "\"Walk\" ignored in a row is at least 3",
            ),
            leaf(
                "unknown rule",
                Condition.Eq("last_response", mapOf("jitai" to "8a5d0c3e-1b2f-4a6d-9e7c-5f4b3a2d1c0e"), lit("NOT_HELPFUL")),
                "your last response to \"another reminder\" is not helpful",
            ),
            leaf("default jitai", Condition.Gte("deliveries_today", value = lit(1)), "this reminder sent today is at least 1"),
            leaf("unknown feature", Condition.Gte("heart_rate_variability", value = lit(30)), "heart_rate_variability gte 30"),
            leaf(
                "onUnknown true",
                Condition.Lt("steps_today", value = lit(3000), onUnknown = OnUnknown.ASSUME_TRUE),
                "your step count today is less than 3,000 steps (counted as met if unknown)",
            ),
            leaf(
                "onUnknown false",
                Condition.Lt("steps_today", value = lit(3000), onUnknown = OnUnknown.ASSUME_FALSE),
                "your step count today is less than 3,000 steps (counted as not met if unknown)",
            ),
        )
    }
}
