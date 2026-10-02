package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.between
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.get
import dev.agentle.jitai.dsl.testing.group
import dev.agentle.jitai.dsl.testing.inList
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.leaf
import dev.agentle.jitai.dsl.testing.negate
import dev.agentle.jitai.dsl.testing.timeIn
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.testing.withNull
import dev.agentle.jitai.dsl.testing.without
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * R10 §12.Q: each row mutates the valid Example 2 proposal of §13.6 (origin AI unless stated) and expects its code.
 * Rows list the code R10 names; a mutation may raise further codes (for example E028 next to E014 on `location_class`,
 * integrator correction lifecycle-battery-06), and no row may raise E099.
 */
class ValidationMatrixTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `R10 12_Q validation rows`(row: Row) {
        val report = row.run()

        assertThat(report.errorCodes).doesNotContain(IssueCode.E099)
        if (row.expected.isEmpty()) {
            assertThat(report.errors).isEmpty()
        } else {
            assertThat(report.codes).containsAtLeastElementsIn(row.expected)
        }
        row.path?.let { path -> assertThat(report.issues(row.expected.first()).map { it.path }).contains(path) }
    }

    /** One §12.Q row: [expected] empty means "valid" (no errors). */
    class Row(val id: String, val expected: List<IssueCode>, val path: String? = null, val run: () -> ValidationReport) {
        override fun toString(): String = id
    }

    companion object {
        private const val OTHER_ID = "3f6c1d2e-8b7a-4c1e-9a55-0d7e2b9c4a10"
        private val base: JsonElement get() = json(Fixtures.example2)
        private val example3: JsonElement get() = json(Fixtures.example3)
        private val steps = leaf("lt", "steps_today", 3000)
        private val battery = leaf("gte", "battery_pct", 10)
        private val window = json("""{"start": "16:00", "end": "18:00", "days": null}""")

        private fun ai(element: JsonElement, createdBy: CreatedBy = CreatedBy.AI_NATURAL_LANGUAGE): () -> ValidationReport =
            { Fixtures.validateText(element.toString(), createdBy = createdBy) }

        private fun text(text: String): () -> ValidationReport = { Fixtures.validateText(text) }

        /** Example 2 with `conditions = all[steps_today lt 3000, extra...]`, so the `{{steps_today}}` placeholder stays valid. */
        private fun withConditions(vararg extra: JsonElement): JsonElement = base.with("/jitai/conditions", group("all", steps, *extra))

        private fun row(id: String, vararg expected: IssueCode, path: String? = null, run: () -> ValidationReport) =
            Row(id, expected.toList(), path, run)

        @JvmStatic
        fun rows(): List<Row> = structureRows() + conditionRows() + triggerRows() + limitRows() + contentRows() + envelopeRows()

        private fun structureRows(): List<Row> = listOf(
            row("Q1 text before the object", IssueCode.E001) { Fixtures.validateText("Sure! " + base) },
            row("Q2 one json code fence") { Fixtures.validateText("```json\n$base\n```") },
            row("Q3 reply of 20000 bytes", IssueCode.E002) { Fixtures.validateText("{" + " ".repeat(20_000) + base.toString().drop(1)) },
            row("Q4 21 nested arrays", IssueCode.E003, run = ai(base.with("/jitai/name", nestedArrays(21)))),
            row("Q5 same key twice", IssueCode.E001) {
                Fixtures.validateText(Fixtures.example2.replace("\"priority\": 50,", "\"priority\": 50, \"priority\": 50,"))
            },
            row("Q6 schemaVersion 2", IssueCode.E004, path = "/schemaVersion", run = ai(base.with("/schemaVersion", 2))),
            row("Q7 id in the draft", IssueCode.E005, path = "/jitai/id", run = ai(base.with("/jitai/id", "x"))),
            row("Q8 unknown key color", IssueCode.E006, path = "/jitai/color", run = ai(base.with("/jitai/color", "red"))),
            row("Q9 priority removed", IssueCode.E007, path = "/jitai/priority", run = ai(base.without("/jitai/priority"))),
            row("Q10 maxPerDay as a string", IssueCode.E008, path = "/jitai/maxPerDay", run = ai(base.with("/jitai/maxPerDay", "1"))),
            row("Q11 channel SMS", IssueCode.E009, path = "/jitai/delivery/channel", run = ai(base.with("/jitai/delivery/channel", "SMS"))),
            row(
                "Q11 condition type regex",
                IssueCode.E009,
                path = "/jitai/conditions/type",
                run = ai(base.with("/jitai/conditions/type", "regex")),
            ),
        )

        private fun conditionRows(): List<Row> = listOf(
            row("Q12 unknown feature", IssueCode.E010, run = ai(withConditions(leaf("gte", "heart_rate_variability", 30)))),
            row("Q13 app_minutes_since without package", IssueCode.E011, path = "/jitai/conditions/of/1/args/package") {
                ai(withConditions(leaf("gte", "app_minutes_since", 30, "since" to "12:00")))()
            },
            row("Q14 steps_today with package", IssueCode.E012, path = "/jitai/conditions/args/package") {
                ai(base.with("/jitai/conditions/args/package", "com.example.app"))()
            },
            row("Q15 jitai UUID in an AI rule", IssueCode.E013) {
                ai(withConditions(leaf("lt", "minutes_since_last_delivery", 120, "jitai" to OTHER_ID)))()
            },
            row("Q16 location_class gt", IssueCode.E014, run = ai(withConditions(leaf("gt", "location_class", "HOME")))),
            row("Q17 lt 3000.0", IssueCode.E015, run = ai(base.with("/jitai/conditions/value", JsonPrimitive(3000.0)))),
            row("Q17 lt string 3000", IssueCode.E015, run = ai(base.with("/jitai/conditions/value", "3000"))),
            row("Q18 screen minutes 61", IssueCode.E016, run = ai(withConditions(leaf("gte", "screen_minutes_last_60m", 61)))),
            row("Q19 between 50..45", IssueCode.E017, run = ai(withConditions(between("screen_minutes_last_60m", 50, 45)))),
            row("Q20 in with 11 values", IssueCode.E018) {
                ai(withConditions(inList("screen_minutes_last_60m", (0..10).map { JsonPrimitive(it) })))()
            },
            row("Q21 in HOME HOME", IssueCode.E019) {
                ai(withConditions(inList("location_class", listOf(JsonPrimitive("HOME"), JsonPrimitive("HOME")))))()
            },
            row("Q22 depth 5", IssueCode.E020, run = ai(withConditions(negate(negate(negate(battery)))))),
            row("Q23 17 nodes", IssueCode.E021) {
                ai(
                    base.with(
                        "/jitai/conditions",
                        group(
                            "all",
                            steps,
                            group(
                                "all",
                                *Array(8) {
                                    battery
                                },
                            ),
                            group("all", *Array(5) { battery }),
                        ),
                    ),
                )()
            },
            row("Q24 empty all", IssueCode.E022, path = "/jitai/conditions/of/1", run = ai(withConditions(group("all")))),
            row("Q25 all with 9 children", IssueCode.E023, run = ai(withConditions(*Array(8) { battery }))),
            row("Q26 since 9:00", IssueCode.E024, run = ai(withConditions(leaf("gte", "screen_minutes_since", 30, "since" to "9:00")))),
            row("Q26 since 24:00", IssueCode.E024, run = ai(withConditions(leaf("gte", "screen_minutes_since", 30, "since" to "24:00")))),
            row("Q27 local_time_in 22:00-22:00", IssueCode.E025, run = ai(withConditions(timeIn("22:00", "22:00")))),
            row("Q28 ASSUME_TRUE on steps", IssueCode.E026, run = ai(base.with("/jitai/conditions/onUnknown", "ASSUME_TRUE"))),
            row("Q29 steps lt 3000 and gte 5000", IssueCode.E027, run = ai(withConditions(leaf("gte", "steps_today", 5000)))),
        )

        private fun triggerRows(): List<Row> = listOf(
            row("Q30 event APP_OPENED", IssueCode.E030, run = ai(event("""["APP_OPENED"]""", 60))),
            row("Q31 event without events", IssueCode.E031, run = ai(event("[]", 60))),
            row("Q32 debounce 900", IssueCode.E032, run = ai(event("""["POWER_CONNECTED"]""", 900))),
            row("Q33 interval 15 AI", IssueCode.E033, run = ai(interval(15))),
            row("Q33 interval 15 USER") { Fixtures.validateText(interval(15).toString(), createdBy = CreatedBy.RULE_TEMPLATE) },
            row("Q34 interval 50", IssueCode.E034, run = ai(interval(50))),
            row("Q35 daily_at 17:00 twice", IssueCode.E035, run = ai(base.with("/jitai/trigger/times", json("""["17:00", "17:00"]""")))),
            row("Q36 lateness 0", IssueCode.E036, run = ai(base.with("/jitai/trigger/maxLatenessMinutes", 0))),
            row("Q37 interval without window", IssueCode.E037, run = ai(interval(30).withNull("/jitai/activeWindow"))),
            row("Q38 days empty", IssueCode.E038, run = ai(base.with("/jitai/activeWindow", window.with("/days", JsonArray(emptyList()))))),
            row("Q39 daily_at 17:00 outside 20:00-23:00", IssueCode.E039) {
                ai(base.with("/jitai/activeWindow", window.with("/start", "20:00").with("/end", "23:00")))()
            },
            row("Q52 interval with window but no conditions", IssueCode.E055, run = ai(interval(30).withNull("/jitai/conditions"))),
        )

        private fun limitRows(): List<Row> = listOf(
            row("Q40 cooldown null", IssueCode.E040, run = ai(base.withNull("/jitai/cooldownMinutes"))),
            row("Q41 cooldown 59", IssueCode.E041, run = ai(base.with("/jitai/cooldownMinutes", 59))),
            row("Q41 cooldown 60", run = ai(base.with("/jitai/cooldownMinutes", 60))),
            row("Q42 maxPerDay 4", IssueCode.E042, path = "/jitai/maxPerDay", run = ai(base.with("/jitai/maxPerDay", 4))),
            row("Q43 maxPerWeek 15", IssueCode.E043, run = ai(base.with("/jitai/maxPerWeek", 15))),
            row("Q44 day 3 week 2", IssueCode.E044, run = ai(base.with("/jitai/maxPerDay", 3).with("/jitai/maxPerWeek", 2))),
            row("Q45 priority 80", IssueCode.E045, run = ai(base.with("/jitai/priority", 80))),
            row("Q46 discovered without expiry", IssueCode.E046, run = ai(base, CreatedBy.AI_DISCOVERED)),
            row("Q46 discovered expiry 120", IssueCode.E047, run = ai(base.with("/jitai/expiresInDays", 120), CreatedBy.AI_DISCOVERED)),
            row("Q47 timeout 2", IssueCode.E048, run = ai(base.with("/jitai/delivery/notificationTimeoutMinutes", 2))),
            row("Q48 snooze options empty", IssueCode.E049, run = ai(base.with("/jitai/snooze/options", JsonArray(emptyList())))),
            row("Q49 trigger null", IssueCode.E050, run = ai(base.withNull("/jitai/trigger"))),
            row("Q50 suppression with a trigger", IssueCode.E051, run = ai(example3.with("/jitai/trigger", base["/jitai/trigger"]))),
            row("Q50 suppression without categories", IssueCode.E052) {
                ai(example3.with("/jitai/suppression/categories", JsonArray(emptyList())))()
            },
            row("Q50 suppression with channel NOTIFICATION", IssueCode.E053) {
                ai(example3.with("/jitai/delivery/channel", "NOTIFICATION"))()
            },
            row("Q51 content null", IssueCode.E054, run = ai(base.withNull("/jitai/content"))),
            row("Q53 suppression unbounded", IssueCode.E057) {
                ai(example3.withNull("/jitai/activeWindow").withNull("/jitai/conditions"))()
            },
            row("Q65 outcome null", IssueCode.E070, run = ai(base.withNull("/jitai/outcome"))),
            row("Q66 distal STEPS_AFTER", IssueCode.E071) {
                ai(base.with("/jitai/outcome/distal/metric", "STEPS_AFTER").with("/jitai/outcome/distal/windowMinutes", 30))()
            },
            row(
                "Q67 APP_MINUTES_AFTER without app",
                IssueCode.E072,
                run = ai(base.with("/jitai/outcome/proximal/metric", "APP_MINUTES_AFTER")),
            ),
            row("Q68 STEPS_AFTER window 300", IssueCode.E073, run = ai(base.with("/jitai/outcome/proximal/windowMinutes", 300))),
            row("Q69 package instagram", IssueCode.E081) {
                ai(withConditions(leaf("gte", "app_minutes_last_60m", 10, "package" to "instagram")))()
            },
        )

        private fun contentRows(): List<Row> = listOf(
            row("Q54 variants with one item", IssueCode.E058) {
                ai(
                    base.with(
                        "/jitai/content",
                        json("""{"type": "variants", "items": [{"title": "Walk?", "body": "A walk now?"}], "selection": "ROTATE"}"""),
                    ),
                )()
            },
            row("Q55 body of 241 characters", IssueCode.E060, run = ai(body("a".repeat(241)))),
            row("Q56 body with a domain", IssueCode.E061, run = ai(body("Read more at example.com"))),
            row("Q57 markup", IssueCode.E062, run = ai(body("Take a short walk **now**"))),
            row("Q57 medical wording", IssueCode.E062, run = ai(body("A walk may help your insomnia"))),
            row("Q57 causal claim", IssueCode.E062, run = ai(body("scrolling causes poor sleep"))),
            row("Q58 unknown placeholder", IssueCode.E063, run = ai(body("{{step_today}} steps so far."))),
            row("Q59 placeholder not in the rule", IssueCode.E064, run = ai(body("Resting heart rate {{resting_hr_today}}."))),
            row("Q60 ambiguous placeholder", IssueCode.E065) {
                ai(base.with("/jitai/conditions", group("any", steps, leaf("eq", "location_class", "HOME"))))()
            },
            row("Q61 U+202E", IssueCode.E066, run = ai(body("Walk \u202E now"))),
            row("Q62 malformed placeholder", IssueCode.E067, run = ai(body("You are at {{steps_today} steps."))),
            row("Q63 unknown asset", IssueCode.E068) {
                val caption = """{"type": "template", "title": "Walk?", "body": "Go!"}"""
                val media = json("""{"type": "local_media", "assetId": "unknown_asset", "caption": $caption}""")
                ai(base.with("/jitai/delivery/channel", "IMAGE").with("/jitai/content", media))()
            },
            row("Q64 IMAGE with template", IssueCode.E069, run = ai(base.with("/jitai/delivery/channel", "IMAGE"))),
        )

        private fun envelopeRows(): List<Row> = listOf(
            row("Q70 OK without jitai", IssueCode.E090, run = ai(base.withNull("/jitai"))),
            row("Q71 four questions", IssueCode.E091) {
                val question = json("""{"id": "q1", "text": "Which time?", "options": ["5 PM", "6 PM"]}""")
                val questions = JsonArray(List(4) { question })
                ai(base.with("/status", "NEEDS_CLARIFICATION").withNull("/jitai").with("/questions", questions))()
            },
            row("Q72 six assumptions", IssueCode.E092) {
                val assumption = base["/assumptions/0"]
                ai(base.with("/assumptions", JsonArray(List(6) { assumption })))()
            },
            row("Q73 fuzzy app label", IssueCode.C01) {
                ai(withConditions(leaf("gte", "app_minutes_last_60m", 10, "appLabel" to "Insta")))()
            },
            row("Q74 channel VOICE", IssueCode.C02, run = ai(base.with("/jitai/delivery/channel", "VOICE"))),
            row(
                "Q75 ALLOW_WHEN_INTERACTIVE",
                IssueCode.C03,
                run = ai(base.with("/jitai/delivery/quietHoursPolicy", "ALLOW_WHEN_INTERACTIVE")),
            ),
            row("Q76 same contentHash", IssueCode.W01) {
                val first = checkNotNull(Fixtures.validateText(Fixtures.example2).definition).copy(id = OTHER_ID)
                Fixtures.validateText(Fixtures.example2, Fixtures.context(existing = listOf(first)))
            },
            row("Q77 window inside quiet hours", IssueCode.W04) {
                val text = interval(30).with("/jitai/activeWindow", window.with("/start", "23:00").with("/end", "06:00")).toString()
                Fixtures.validateText(text, Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS), origin = RuleOrigin.AI)
            },
        )

        private fun event(events: String, debounce: Int): JsonElement = base
            .with("/jitai/trigger", json("""{"type": "event", "events": $events, "debounceSeconds": $debounce}"""))
            .with("/jitai/activeWindow", window)

        private fun interval(minutes: Int): JsonElement = base
            .with("/jitai/trigger", json("""{"type": "interval", "everyMinutes": $minutes}"""))
            .with("/jitai/activeWindow", window)

        private fun body(text: String): JsonElement = base.with("/jitai/content/body", text)

        private fun nestedArrays(depth: Int): JsonElement {
            var element: JsonElement = JsonArray(emptyList())
            repeat(depth - 1) { element = JsonArray(listOf(element)) }
            return element
        }
    }
}
