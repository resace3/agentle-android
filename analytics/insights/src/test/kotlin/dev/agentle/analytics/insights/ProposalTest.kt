package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

/** Proposals of docs/research/10 §14.7 and the structural JitaiProposalSchema v1 check of §13.3. */
class ProposalTest {
    @Test
    fun `the P1 proposal is the example of R10 14_7 plus the lineage of its evidence`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)
        val result = run.result(hypothesis("H04"))

        val proposal = DiscoveredProposals.build(result, run, "b1f7c3a0-5d2e-4f61-9c8a-2e4d6f8a1b3c", T0)

        assertThat(normalized(proposal)).isEqualTo(normalized(Json.parseToJsonElement(P1_PROPOSAL)))
        assertThat(proposal.getValue("whyProposed").jsonObject.getValue("text").jsonPrimitive.content).isEqualTo(P1_TEXT)
    }

    @Test
    fun `the trial length is the expiry of the draft`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)

        val proposal =
            DiscoveredProposals.build(run.result(hypothesis("H04")), run, "p", T0, InsightConfig(trialDays = 14), consecutiveRuns = 3)

        assertThat(proposal.getValue("jitai").jsonObject.getValue("expiresInDays").jsonPrimitive.int).isEqualTo(14)
        assertThat(proposal.getValue("trial").jsonObject.getValue("days").jsonPrimitive.int).isEqualTo(14)
        val evidence = proposal.getValue("whyProposed").jsonObject.getValue("evidence").jsonObject
        assertThat(evidence.getValue("consecutiveRuns").jsonPrimitive.int).isEqualTo(3)
    }

    @Test
    fun `only positive findings are proposed`() {
        val run = runOf(date("2026-10-04"))

        assertThrows<IllegalArgumentException> { DiscoveredProposals.build(claim(hypothesis("H04"), rdMh = -0.46), run, "p", T0) }
        assertThrows<IllegalArgumentException> {
            DiscoveredProposals.build(claim(hypothesis("H04"), tier = PatternTier.WEAK), run, "p", T0)
        }
        assertThrows<IllegalArgumentException> { DiscoveredProposals.build(untested(hypothesis("H04")), run, "p", T0) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("family")
    fun `every template of the family conforms to the schema and the lint`(id: String) {
        val h = hypothesis(id)
        val draft = RuleTemplates.draft(h)

        assertWithMessage(id).that(ProposalSchema.checkEnvelope(ProposalSchema.envelope(draft))).isEmpty()
        assertWithMessage(id).that(ProposalSchema.checkDraft(draft)).isEmpty()
        val content = draft.getValue("content").jsonObject
        val texts = listOf(
            draft.string("name"),
            draft.string("description"),
            content.string("title"),
            RuleTemplates.expectedOutcome(h),
        ) + RuleTemplates.dataRequired(h) + listOf(
            PatternText.exposurePhrase(h.exposure),
            PatternText.outcomePhrase(h.outcome),
            PatternText.exposureLabel(h.exposure),
            PatternText.outcomeLabel(h.outcome),
        )
        texts.forEach { assertWithMessage("$id: $it").that(TextLint.check(it)).isEmpty() }
        // The body is a {{feature}} template for the engine to fill; apart from the placeholder it is clean.
        assertWithMessage(id).that(TextLint.check(content.string("body"))).containsExactly(LintCheck.PLACEHOLDER)
        assertWithMessage(id).that(draft.getValue("expiresInDays").jsonPrimitive.int).isEqualTo(RuleTemplates.DEFAULT_EXPIRES_IN_DAYS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("family")
    fun `every hypothesis can be proposed with a non-causal text`(id: String) {
        val h = hypothesis(id)
        val result = claim(h)
        val run = runOf(date("2026-10-04"), result)

        val proposal = DiscoveredProposals.build(result, run, "p-$id", T0)

        assertThat(proposal.string("hypothesisId")).isEqualTo(id)
        assertThat(proposal.string("patternId")).isEqualTo("$id:+")
        assertThat(proposal.string("exposure")).isEqualTo(h.exposure.id)
        assertThat(proposal.string("outcome")).isEqualTo(h.outcome.id)
        val text = proposal.getValue("whyProposed").jsonObject.string("text")
        assertThat(TextLint.check(text)).isEmpty()
        assertThat(text).contains(PatternText.exposurePhrase(h.exposure))
        assertThat(text).contains(PatternText.outcomePhrase(h.outcome))
    }

    @Test
    fun `templates follow the exposure and the outcome`() {
        val social = RuleTemplates.draft(hypothesis("H11"))
        val steps = RuleTemplates.draft(hypothesis("H18"))
        val notifications = RuleTemplates.draft(hypothesis("H14"))

        val socialOutcome = social.getValue("outcome").jsonObject
        assertThat(social.getValue("conditions").jsonObject.getValue("args"))
            .isEqualTo(Json.parseToJsonElement("""{"category":"SOCIAL","since":"22:00"}"""))
        assertThat(socialOutcome.getValue("proximal").jsonObject.string("metric")).isEqualTo("APP_CATEGORY_MINUTES_AFTER")
        assertThat(socialOutcome.getValue("distal").jsonObject.string("metric")).isEqualTo("SLEEP_MINUTES_NEXT")
        assertThat(steps.getValue("trigger"))
            .isEqualTo(Json.parseToJsonElement("""{"type":"daily_at","times":["18:00"],"maxLatenessMinutes":60}"""))
        assertThat(steps.getValue("activeWindow")).isEqualTo(JsonNull)
        assertThat(steps.getValue("contextRequirements")).isEqualTo(JsonNull)
        assertThat(steps.getValue("delivery").jsonObject.string("quietHoursPolicy")).isEqualTo("RESPECT")
        assertThat(steps.getValue("outcome").jsonObject.getValue("distal")).isEqualTo(JsonNull)
        assertThat(notifications.string("category")).isEqualTo("DIGITAL_WELLBEING")
        assertThat(notifications.getValue("activeWindow").jsonObject.string("start")).isEqualTo("21:00")
        assertThat(RuleTemplates.expectedOutcome(hypothesis("H18")))
            .isEqualTo("More steps in the hour after a reminder. The trial measures it; nothing is promised.")
        assertThat(RuleTemplates.dataRequired(hypothesis("H14")))
            .containsExactly("Notification access (counts only)", "Usage access (screen time)", "Sleep from your connected tracker")
            .inOrder()
        assertThat(RuleTemplates.dataRequired(hypothesis("H18"))).containsExactly("Steps from your phone or connected tracker")
        assertThrows<IllegalArgumentException> { RuleTemplates.draft(hypothesis("H04"), 0) }
        assertThrows<IllegalArgumentException> { RuleTemplates.draft(hypothesis("H04"), 91) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    fun `single mutations of a conforming proposal are rejected`(row: String, pointer: String, value: String?, path: String) {
        val mutated = edit(validEnvelope(), pointer, value?.let { Json.parseToJsonElement(it) })

        assertWithMessage(row).that(ProposalSchema.checkEnvelope(mutated).map { it.path }).contains(path)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    fun `conforming variants are accepted`(row: String, pointer: String, value: String) {
        val mutated = edit(validEnvelope(), pointer, Json.parseToJsonElement(value))

        assertWithMessage(row).that(ProposalSchema.checkEnvelope(mutated)).isEmpty()
    }

    @Test
    fun `conditions nested deeper than the limit are rejected`() {
        var node: JsonElement = Json.parseToJsonElement("""{"type":"eq","feature":"charging","args":{},"value":true,"onUnknown":null}""")
        repeat(18) { node = JsonObject(mapOf("type" to JsonPrimitive("not"), "of" to node)) }

        val issues = ProposalSchema.checkEnvelope(edit(validEnvelope(), "/jitai/conditions", node))

        assertThat(issues.map { it.reason }).contains("nested too deeply")
    }

    @Test
    fun `non-object documents are rejected`() {
        assertThat(ProposalSchema.checkEnvelope(JsonArray(emptyList())).map { it.path }).containsExactly("/")
        assertThat(ProposalSchema.checkDraft(JsonPrimitive("x")).map { it.path }).containsExactly("/jitai")
    }

    private fun validEnvelope(): JsonObject = ProposalSchema.envelope(RuleTemplates.draft(hypothesis("H04")))

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** Sets (or with null removes) the value at JSON pointer [pointer]. */
    private fun edit(root: JsonElement, pointer: String, value: JsonElement?): JsonElement = edit(root, pointer.split('/').drop(1), value)

    private fun edit(node: JsonElement, path: List<String>, value: JsonElement?): JsonElement {
        if (path.isEmpty()) return requireNotNull(value)
        val key = path.first()
        val rest = path.drop(1)
        return when (node) {
            is JsonObject -> {
                val map = LinkedHashMap(node)
                if (rest.isEmpty() && value == null) map.remove(key) else map[key] = edit(node[key] ?: JsonNull, rest, value)
                JsonObject(map)
            }

            is JsonArray -> JsonArray(node.mapIndexed { i, e -> if (i == key.toInt()) edit(e, rest, value) else e })

            else -> error("no container at $key")
        }
    }

    /** Numbers compared by value (`0.250` equals `0.25`). */
    private fun normalized(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalized(it.value) })

        is JsonArray -> JsonArray(e.map { normalized(it) })

        is JsonNull -> e

        is JsonPrimitive ->
            if (e.isString || e.booleanOrNull != null) {
                e
            } else {
                JsonPrimitive("#" + BigDecimal(e.content).stripTrailingZeros().toPlainString())
            }
    }

    companion object {
        @JvmStatic
        fun family(): List<String> = HypothesisFamily.all.map { it.id }

        private const val UUID = "\"b1f7c3a0-5d2e-4f61-9c8a-2e4d6f8a1b3c\""

        // The 14 single mutations listed in docs/research/10 §13.3 (M1-M14, except `args: {}`, which the validator reads
        // as sparse args and accepts: see A1) and further structural rows.
        @JvmStatic
        fun mutations(): List<Arguments> = listOf(
            Arguments.of("M1 extra key", "/jitai/foo", "1", "/jitai/foo"),
            Arguments.of("M2 id present", "/jitai/id", UUID, "/jitai/id"),
            Arguments.of("M3 missing key", "/jitai/priority", null, "/jitai/priority"),
            Arguments.of("M4 maxPerDay 4", "/jitai/maxPerDay", "4", "/jitai/maxPerDay"),
            Arguments.of("M5 interval 15", "/jitai/trigger/everyMinutes", "15", "/jitai/trigger/everyMinutes"),
            Arguments.of("M6 interval 50", "/jitai/trigger/everyMinutes", "50", "/jitai/trigger/everyMinutes"),
            Arguments.of("M7 9:00", "/jitai/activeWindow/start", "\"9:00\"", "/jitai/activeWindow/start"),
            Arguments.of("M8 unknown feature", "/jitai/conditions/feature", "\"screen_minutes_tonight\"", "/jitai/conditions/feature"),
            Arguments.of("M9 unknown node type", "/jitai/conditions/type", "\"matches\"", "/jitai/conditions/type"),
            Arguments.of("M10 fractional value", "/jitai/conditions/value", "45.0", "/jitai/conditions/value"),
            Arguments.of("M11 empty all", "/jitai/conditions", """{"type":"all","of":[]}""", "/jitai/conditions/of"),
            Arguments.of("M12 channel SMS", "/jitai/delivery/channel", "\"SMS\"", "/jitai/delivery/channel"),
            Arguments.of("M13 UUID in jitai", "/jitai/conditions/args/jitai", UUID, "/jitai/conditions/args/jitai"),
            Arguments.of("M14 schemaVersion 2", "/schemaVersion", "2", "/schemaVersion"),
            Arguments.of("M15 schemaVersion as text", "/schemaVersion", "\"1\"", "/schemaVersion"),
            Arguments.of("M16 unknown args key", "/jitai/conditions/args/foo", "\"x\"", "/jitai/conditions/args/foo"),
            Arguments.of(
                "M17 time 18:0",
                "/jitai/trigger",
                """{"type":"daily_at","times":["18:0"],"maxLatenessMinutes":60}""",
                "/jitai/trigger/times/0",
            ),
            Arguments.of(
                "M18 unknown event",
                "/jitai/trigger",
                """{"type":"event","events":["SMS_RECEIVED"],"debounceSeconds":0}""",
                "/jitai/trigger/events/0",
            ),
            Arguments.of("M19 unknown trigger", "/jitai/trigger", """{"type":"cron"}""", "/jitai/trigger/type"),
            Arguments.of(
                "M20 one variant",
                "/jitai/content",
                """{"type":"variants","items":[{"title":"a","body":"b"}],"selection":"ROTATE"}""",
                "/jitai/content/items",
            ),
            Arguments.of(
                "M21 static ai_text fallback",
                "/jitai/content",
                """{"type":"ai_text","goal":"g","tone":"WARM","fallback":{"type":"static","title":"a","body":"b"}}""",
                "/jitai/content/fallback/type",
            ),
            Arguments.of(
                "M22 asset id",
                "/jitai/content",
                """{"type":"local_media","assetId":"Calm","caption":{"type":"template","title":"a","body":"b"}}""",
                "/jitai/content/assetId",
            ),
            Arguments.of("M23 repeated snooze option", "/jitai/snooze/options", """["MINUTES_30","MINUTES_30"]""", "/jitai/snooze/options"),
            Arguments.of("M24 empty suppression", "/jitai/suppression", """{"categories":[]}""", "/jitai/suppression/categories"),
            Arguments.of("M25 unknown metric", "/jitai/outcome/proximal/metric", "\"MOOD_AFTER\"", "/jitai/outcome/proximal/metric"),
            Arguments.of("M26 window 241", "/jitai/outcome/proximal/windowMinutes", "241", "/jitai/outcome/proximal/windowMinutes"),
            Arguments.of(
                "M27 four questions",
                "/questions",
                """[{"id":"q1","text":"a","options":["x","y"]},{"id":"q2","text":"a","options":["x","y"]},""" +
                    """{"id":"q3","text":"a","options":["x","y"]},{"id":"q1","text":"a","options":["x","y"]}]""",
                "/questions",
            ),
            Arguments.of("M28 assumption path", "/assumptions", """[{"path":"/questions","text":"x"}]""", "/assumptions/0/path"),
            Arguments.of("M29 unsupported reason", "/unsupported", """{"reason":"BORED","detail":null}""", "/unsupported/reason"),
            Arguments.of("M30 status", "/status", "\"DONE\"", "/status"),
            Arguments.of("M31 jitai missing", "/jitai", null, "/jitai"),
            Arguments.of(
                "M32 fractional max",
                "/jitai/conditions",
                """{"type":"between","feature":"battery_pct","args":{},"min":10,"max":20.5,"onUnknown":null}""",
                "/jitai/conditions/max",
            ),
            Arguments.of(
                "M33 repeated in values",
                "/jitai/conditions",
                """{"type":"in","feature":"day_type","args":{},"values":["WORKDAY","WORKDAY"],"onUnknown":null}""",
                "/jitai/conditions/values",
            ),
            Arguments.of(
                "M34 time 24:00",
                "/jitai/conditions",
                """{"type":"local_time_in","start":"24:00","end":"01:00"}""",
                "/jitai/conditions/start",
            ),
            Arguments.of(
                "M35 unknown node under not",
                "/jitai/conditions",
                """{"type":"not","of":{"type":"xor"}}""",
                "/jitai/conditions/of/type",
            ),
            Arguments.of("M36 onUnknown", "/jitai/conditions/onUnknown", "\"ASSUME_MAYBE\"", "/jitai/conditions/onUnknown"),
            Arguments.of("M37 name of 61 characters", "/jitai/name", "\"" + "x".repeat(61) + "\"", "/jitai/name"),
            Arguments.of("M38 category", "/jitai/category", "\"SLEEP\"", "/jitai/category"),
            Arguments.of("M39 day name", "/jitai/activeWindow/days", """["MONDAY"]""", "/jitai/activeWindow/days/0"),
            Arguments.of("M40 timeout 4", "/jitai/delivery/notificationTimeoutMinutes", "4", "/jitai/delivery/notificationTimeoutMinutes"),
            Arguments.of("M41 cooldown 59", "/jitai/cooldownMinutes", "59", "/jitai/cooldownMinutes"),
            Arguments.of("M42 expiry 91", "/jitai/expiresInDays", "91", "/jitai/expiresInDays"),
            Arguments.of("M43 priority as text", "/jitai/priority", "\"40\"", "/jitai/priority"),
            Arguments.of("M44 kind", "/jitai/kind", "\"NUDGE\"", "/jitai/kind"),
            Arguments.of("M45 content without type", "/jitai/content", """{"title":"a","body":"b"}""", "/jitai/content/type"),
            Arguments.of("M46 jitai not an object", "/jitai", "[]", "/jitai"),
            Arguments.of("M47 since 22:60", "/jitai/conditions/args/since", "\"22:60\"", "/jitai/conditions/args/since"),
            Arguments.of("M48 snooze mode", "/jitai/snooze/mode", "\"LATER\"", "/jitai/snooze/mode"),
            Arguments.of("M49 one option", "/questions", """[{"id":"q1","text":"Which?","options":["a"]}]""", "/questions/0/options"),
            Arguments.of("M50 null value", "/jitai/conditions/value", "null", "/jitai/conditions/value"),
            Arguments.of("M51 app category", "/jitai/conditions/args/category", "\"social\"", "/jitai/conditions/args/category"),
            Arguments.of("M52 outcome without proximal", "/jitai/outcome/proximal", null, "/jitai/outcome/proximal"),
        )

        @JvmStatic
        fun accepted(): List<Arguments> = listOf(
            Arguments.of("A1 sparse args (13.3 difference 1)", "/jitai/conditions/args", "{}"),
            Arguments.of("A2 no draft", "/jitai", "null"),
            Arguments.of(
                "A3 event trigger",
                "/jitai/trigger",
                """{"type":"event","events":["USER_PRESENT","POWER_CONNECTED"],"debounceSeconds":30}""",
            ),
            Arguments.of(
                "A4 two variants",
                "/jitai/content",
                """{"type":"variants","items":[{"title":"a","body":"b"},{"title":"c","body":"d"}],"selection":"ROTATE"}""",
            ),
            Arguments.of(
                "A5 nested nodes",
                "/jitai/conditions",
                """{"type":"all","of":[{"type":"any","of":[{"type":"local_time_in","start":"22:00","end":"01:00"}]},""" +
                    """{"type":"not","of":{"type":"in","feature":"day_type","args":{},"values":["WORKDAY"],""" +
                    """"onUnknown":"ASSUME_FALSE"}},""" +
                    """{"type":"between","feature":"battery_pct","args":{},"min":10,"max":90,"onUnknown":null}]}""",
            ),
            Arguments.of("A6 suppression", "/jitai/suppression", """{"categories":["GENERAL","STRESS_BREAK"]}"""),
            Arguments.of("A7 days", "/jitai/activeWindow/days", """["MON","TUE"]"""),
            Arguments.of(
                "A8 ai_text",
                "/jitai/content",
                """{"type":"ai_text","goal":"Remind me gently","tone":"BRIEF","fallback":{"type":"template","title":"a","body":"b"}}""",
            ),
            Arguments.of(
                "A9 local media",
                "/jitai/content",
                """{"type":"local_media","assetId":"calm_1","caption":{"type":"template","title":"a","body":"b"}}""",
            ),
            Arguments.of("A10 no distal outcome", "/jitai/outcome/distal", "null"),
            Arguments.of(
                "A11 boolean value",
                "/jitai/conditions",
                """{"type":"eq","feature":"charging","args":{"jitai":"self"},"value":false,"onUnknown":null}""",
            ),
            Arguments.of("A12 clarification", "/questions", """[{"id":"q1","text":"Which app?","options":["Instagram","TikTok"]}]"""),
        )

        /** docs/research/10 §14.7, with the `lineage` of the evidence (integrator correction 3). */
        private val P1_PROPOSAL = """
            {
              "proposalId": "b1f7c3a0-5d2e-4f61-9c8a-2e4d6f8a1b3c",
              "patternId": "H04:+",
              "hypothesisId": "H04",
              "exposure": "E_screen45",
              "outcome": "O_late",
              "createdAt": "2026-10-05T18:00:00Z",
              "tier": "MODERATE",
              "approvalRequired": true,
              "whyProposed": {
                "text": "$P1_TEXT",
                "evidence": {
                  "analysisWindow": { "firstNight": "2026-08-06", "lastNight": "2026-10-04" },
                  "nightsComplete": 60,
                  "exposed": { "nights": 24, "withOutcome": 17 },
                  "unexposed": { "nights": 36, "withOutcome": 9 },
                  "rateExposed": 0.708,
                  "rateUnexposed": 0.250,
                  "riskDifference": 0.458,
                  "riskDifferenceCi95": [0.202, 0.640],
                  "lift": 2.83,
                  "riskDifferenceMh": 0.460,
                  "pExactStratified": 0.00103,
                  "qBenjaminiHochberg": 0.0185,
                  "familySize": 18,
                  "strata": [
                    { "nightType": "WORK_NIGHT", "exposed": { "nights": 14, "withOutcome": 10 }, "unexposed": { "nights": 28, "withOutcome": 7 } },
                    { "nightType": "WEEKEND_NIGHT", "exposed": { "nights": 10, "withOutcome": 7 }, "unexposed": { "nights": 8, "withOutcome": 2 } }
                  ],
                  "checks": { "strataSign": true, "splitHalf": true },
                  "consecutiveRuns": 2,
                  "method": "exact-stratified-permutation-v1",
                  "lineage": { "categories": ["SCREEN", "SLEEP"], "sourceFamilies": ["GH_API", "ON_DEVICE"] }
                }
              },
              "jitai": {
                "name": "Wind down after late screen time",
                "description": "On nights when I use my phone for 45 minutes or more after 10 PM, remind me to start winding down.",
                "kind": "INTERVENTION",
                "category": "SLEEP_WIND_DOWN",
                "trigger": { "type": "interval", "everyMinutes": 30 },
                "activeWindow": { "start": "22:00", "end": "01:00", "days": null },
                "conditions": { "type": "gte", "feature": "screen_minutes_since", "args": { "since": "22:00" }, "value": 45, "onUnknown": null },
                "contextRequirements": { "type": "eq", "feature": "device_interactive", "args": {}, "value": true, "onUnknown": null },
                "delivery": { "channel": "NOTIFICATION", "quietHoursPolicy": "ALLOW_WHEN_INTERACTIVE", "notificationTimeoutMinutes": 60 },
                "content": { "type": "template", "title": "Winding down?", "body": "{{screen_minutes_since}} minutes of screen time since 10 PM. Want to start winding down now?" },
                "cooldownMinutes": 120,
                "maxPerDay": 1,
                "maxPerWeek": 7,
                "priority": 40,
                "snooze": { "mode": "SUPPRESS_ONLY", "options": ["MINUTES_30", "UNTIL_TOMORROW"] },
                "expiresInDays": 28,
                "outcome": {
                  "proximal": { "metric": "SCREEN_MINUTES_AFTER", "args": {}, "windowMinutes": 30 },
                  "distal": { "metric": "BEDTIME_NEXT", "args": {}, "windowMinutes": null }
                },
                "suppression": null
              },
              "expectedOutcome": "Fewer screen minutes in the 30 minutes after a reminder, and an earlier bedtime on reminder nights. The trial measures both; nothing is promised.",
              "dataRequired": ["Usage access (screen time)", "Sleep from your connected tracker"],
              "trial": { "days": 28, "experimentOffer": { "mode": "MICRO_RANDOMIZED", "deliverProbability": 0.5, "requiresConsent": true } }
            }
        """.trimIndent()
    }
}
