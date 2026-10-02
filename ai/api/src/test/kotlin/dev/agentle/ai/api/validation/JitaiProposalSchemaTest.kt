package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class JitaiProposalSchemaTest {
    /** The examples of docs/research/10-jitai-engine-design.md section 13.6, verbatim. */
    private val windDown = """
        {
          "schemaVersion": 1, "status": "OK", "unsupported": null, "questions": [],
          "assumptions": [
            { "path": "/jitai/conditions/value", "text": "\"Too much\" means 30 minutes or more on Instagram since 10 PM." },
            { "path": "/jitai/activeWindow/end", "text": "\"After 10 PM\" covers 10 PM to 2 AM." },
            { "path": "/jitai/maxPerDay", "text": "At most one wind-down reminder per night." }
          ],
          "jitai": {
            "name": "Instagram wind-down",
            "description": "After 10 PM, if I have used Instagram for 30 minutes or more, remind me to start winding down.",
            "kind": "INTERVENTION", "category": "SLEEP_WIND_DOWN",
            "trigger": { "type": "interval", "everyMinutes": 30 },
            "activeWindow": { "start": "22:00", "end": "02:00", "days": null },
            "conditions": { "type": "gte", "feature": "app_minutes_since",
              "args": { "package": null, "appLabel": "Instagram", "category": null, "since": "22:00", "jitai": null },
              "value": 30, "onUnknown": null },
            "contextRequirements": { "type": "eq", "feature": "device_interactive",
              "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null },
              "value": true, "onUnknown": null },
            "delivery": { "channel": "NOTIFICATION", "quietHoursPolicy": "ALLOW_WHEN_INTERACTIVE", "notificationTimeoutMinutes": 60 },
            "content": { "type": "template", "title": "Time to wind down?",
              "body": "{{app_minutes_since}} minutes on Instagram since 10 PM. How about putting the phone away and getting ready for bed?"
            },
            "cooldownMinutes": 120, "maxPerDay": 1, "maxPerWeek": 7, "priority": 50,
            "snooze": { "mode": "SUPPRESS_ONLY", "options": ["MINUTES_30", "UNTIL_TOMORROW"] },
            "expiresInDays": null,
            "outcome": {
              "proximal": { "metric": "APP_MINUTES_AFTER",
                "args": { "package": null, "appLabel": "Instagram", "category": null, "since": null, "jitai": null }, "windowMinutes": 30 },
              "distal": { "metric": "BEDTIME_NEXT",
                "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null }, "windowMinutes": null }
            },
            "suppression": null
          }
        }
    """.trimIndent()

    private val walk = """
        {
          "schemaVersion": 1, "status": "OK", "unsupported": null, "questions": [],
          "assumptions": [ { "path": "/jitai/trigger/times/0", "text": "\"By 5 PM\" means one check at 5:00 PM each day." } ],
          "jitai": {
            "name": "Afternoon walk nudge",
            "description": "At 5 PM, if I have fewer than 3,000 steps today, encourage me to go for a walk.",
            "kind": "INTERVENTION", "category": "PHYSICAL_ACTIVITY",
            "trigger": { "type": "daily_at", "times": ["17:00"], "maxLatenessMinutes": 30 },
            "activeWindow": null,
            "conditions": { "type": "lt", "feature": "steps_today",
              "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null },
              "value": 3000, "onUnknown": null },
            "contextRequirements": null,
            "delivery": { "channel": "NOTIFICATION", "quietHoursPolicy": "RESPECT", "notificationTimeoutMinutes": 120 },
            "content": { "type": "template", "title": "Time for a short walk?",
              "body": "You are at {{steps_today}} steps today. A 10-minute walk now would get you moving." },
            "cooldownMinutes": 60, "maxPerDay": 1, "maxPerWeek": 7, "priority": 50,
            "snooze": { "mode": "RE_EVALUATE_AFTER", "options": ["MINUTES_30", "MINUTES_60"] },
            "expiresInDays": null,
            "outcome": {
              "proximal": { "metric": "STEPS_AFTER",
                "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null }, "windowMinutes": 30 },
              "distal": { "metric": "STEPS_DAY_TOTAL",
                "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null }, "windowMinutes": null }
            },
            "suppression": null
          }
        }
    """.trimIndent()

    private val shortNight = """
        {
          "schemaVersion": 1, "status": "OK", "unsupported": null, "questions": [],
          "assumptions": [
            { "path": "/jitai/suppression/categories/0", "text": "\"Exercise reminders\" means all Physical activity reminders." },
            { "path": "/jitai/conditions/value",
              "text": "\"Under six hours\" means less than 360 minutes asleep in last night's main sleep." },
            { "path": "/jitai/activeWindow/start", "text": "\"Before 9 AM\" means from midnight until 9 AM." }
          ],
          "jitai": {
            "name": "No exercise nudges after a short night",
            "description": "If I slept less than 6 hours last night, block exercise reminders until 9 AM.",
            "kind": "SUPPRESSION", "category": "PHYSICAL_ACTIVITY", "trigger": null,
            "activeWindow": { "start": "00:00", "end": "09:00", "days": null },
            "conditions": { "type": "lt", "feature": "sleep_minutes_last_night",
              "args": { "package": null, "appLabel": null, "category": null, "since": null, "jitai": null },
              "value": 360, "onUnknown": null },
            "contextRequirements": null,
            "delivery": { "channel": "NONE", "quietHoursPolicy": "RESPECT", "notificationTimeoutMinutes": null },
            "content": null, "cooldownMinutes": null, "maxPerDay": null, "maxPerWeek": null, "priority": 50,
            "snooze": null, "expiresInDays": null, "outcome": null,
            "suppression": { "categories": ["PHYSICAL_ACTIVITY"] }
          }
        }
    """.trimIndent()

    /** Part of the `jitai-nl-v1` contract (R10 section 13.2) that the instructions of a request carry. */
    private val contract = """
        LIMITS (anything else is rejected)
        10. kind INTERVENTION needs a trigger, content, outcome.proximal and a channel other than NONE;
            cooldownMinutes 60-10080; maxPerDay 1-3; maxPerWeek 1-14 and at least maxPerDay; priority 0-60.
        16. Trees: at most 4 levels, 16 nodes, 8 children per group, 10 values per "in".
    """.trimIndent()

    private fun context(request: String): OutputValidationContext =
        OutputValidationContext(provenance = NumberProvenance.fromTexts(listOf(request, contract)))

    private val accepting = JitaiRuleCheck { document -> RuleCheckResult.Accepted(document) }
    private val validator = JitaiProposalSchema.validator(accepting)

    private fun validate(text: String, context: OutputValidationContext, validator: SchemaValidator<JsonObject> = this.validator) =
        AiOutputValidator.validateWith(text, validator, context)

    private fun issues(
        text: String,
        context: OutputValidationContext = OutputValidationContext(),
        check: JitaiRuleCheck<JsonObject> = accepting,
    ) = (validate(text, context, JitaiProposalSchema.validator(check)) as OutputValidation.Invalid).issues.map {
        Triple(it.code, it.path, it.check)
    }

    private fun edit(text: String, path: List<String>, value: JsonElement?): String {
        fun change(node: JsonObject, keys: List<String>): JsonObject {
            val map = node.toMutableMap()
            if (keys.size == 1) {
                if (value == null) map.remove(keys[0]) else map[keys[0]] = value
            } else {
                map[keys[0]] = change(map.getValue(keys[0]).jsonObject, keys.drop(1))
            }
            return JsonObject(map)
        }
        return change(Json.parseToJsonElement(text).jsonObject, path).toString()
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `the three worked examples of R10 pass, including their text lint and number provenance`() {
        assertThat(validate(windDown, context("Remind me to wind down if I use Instagram too much after 10 PM.")))
            .isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(validate(walk, context("Encourage me to walk when I have fewer than 3,000 steps by 5 PM.")))
            .isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(validate(shortNight, context("If I slept under six hours, do not bother me with an exercise reminder before 9 AM.")))
            .isInstanceOf(OutputValidation.Valid::class.java)
    }

    @Test
    fun `a number from nowhere fails L9`() {
        val invented = edit(walk, listOf("jitai", "description"), JsonPrimitive("At 5 PM, if I have fewer than 4,321 steps, nudge me."))
        assertThat(issues(invented, context("Encourage me to walk when I have fewer than 3,000 steps by 5 PM.")))
            .containsExactly(Triple("E102", "/jitai/description", "L9"))
    }

    @Test
    fun `text fields are linted with placeholders only in template text`() {
        val staticContent = json("""{"type":"static","title":"Walk {{steps_today}}","body":"Go outside."}""")
        assertThat(
            issues(edit(walk, listOf("jitai", "content"), staticContent)),
        ).containsExactly(Triple("E103", "/jitai/content/title", "L8"))
        val aiText = json(
            """{"type":"ai_text","goal":"Mention {{steps_today}}","tone":"WARM",""" +
                """"fallback":{"type":"template","title":"Walk?","body":"At {{steps_today}} steps."}}""",
        )
        assertThat(issues(edit(walk, listOf("jitai", "content"), aiText))).containsExactly(Triple("E103", "/jitai/content/goal", "L8"))
        val variants = json(
            """{"type":"variants","selection":"ROTATE",""" +
                """"items":[{"title":"Walk?","body":"Visit walk.com"},{"title":"Move?","body":"Stand up."}],"x":"y"}""",
        )
        assertThat(
            issues(edit(walk, listOf("jitai", "content"), variants)),
        ).containsExactly(Triple("E061", "/jitai/content/items/0/body", "L1"))
        val media =
            json(
                """{"type":"local_media","assetId":"calm_lake",""" +
                    """"caption":{"type":"template","title":"Breathe","body":"Take your pills."}}""",
            )
        assertThat(
            issues(edit(walk, listOf("jitai", "content"), media)),
        ).containsExactly(Triple("E105", "/jitai/content/caption/body", "L11"))
        assertThat(
            issues(edit(walk, listOf("jitai", "name"), JsonPrimitive("Insomnia fix"))),
        ).containsExactly(Triple("E062", "/jitai/name", "L6"))
    }

    @Test
    fun `envelope status must match its parts`() {
        assertThat(issues(edit(walk, listOf("jitai"), null).let { edit(it, listOf("jitai"), json("null")) }))
            .containsExactly(Triple("E090", "/status", null))
        val clarification = edit(edit(walk, listOf("status"), JsonPrimitive("NEEDS_CLARIFICATION")), listOf("jitai"), json("null"))
        assertThat(issues(clarification)).containsExactly(Triple("E090", "/status", null))
        val unsupported = edit(edit(walk, listOf("status"), JsonPrimitive("UNSUPPORTED")), listOf("jitai"), json("null"))
        assertThat(issues(unsupported)).containsExactly(Triple("E090", "/status", null))
        val stated = edit(unsupported, listOf("unsupported"), json("""{"reason":"HEALTH_OR_SAFETY","detail":null}"""))
        assertThat(validate(stated, OutputValidationContext())).isInstanceOf(OutputValidation.Valid::class.java)
        val withDetail = edit(unsupported, listOf("unsupported"), json("""{"reason":"OTHER","detail":"Not possible <b>today</b>"}"""))
        assertThat(issues(withDetail)).containsExactly(Triple("E062", "/unsupported/detail", "L4"))
    }

    @Test
    fun `questions and assumptions are bounded`() {
        val question = """{"id":"q1","text":"Which app?","options":["Instagram","TikTok"]}"""
        val asking = edit(edit(walk, listOf("status"), JsonPrimitive("NEEDS_CLARIFICATION")), listOf("jitai"), json("null"))
        assertThat(validate(edit(asking, listOf("questions"), json("[$question]")), OutputValidationContext()))
            .isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(issues(edit(asking, listOf("questions"), json("[$question,$question]"))))
            .containsExactly(Triple("E091", "/questions", null))
        val four = (1..4).joinToString(",", "[", "]") { """{"id":"q${(it - 1) % 3 + 1}","text":"Which?","options":["A","B"]}""" }
        assertThat(issues(edit(asking, listOf("questions"), json(four))))
            .containsExactly(Triple("E090", "/status", null), Triple("E091", "/questions", null))
        val oneOption = """[{"id":"q1","text":"Which?","options":["Only"]}]"""
        assertThat(issues(edit(asking, listOf("questions"), json(oneOption)))).containsExactly(Triple("E091", "/questions/0/options", null))
        val badOption = """[{"id":"q1","text":"Which?","options":["See www.x.org","B"]}]"""
        assertThat(
            issues(edit(asking, listOf("questions"), json(badOption))),
        ).containsExactly(Triple("E061", "/questions/0/options/0", "L1"))
        val six = (1..6).joinToString(",", "[", "]") { """{"path":"/jitai/priority","text":"Assumed."}""" }
        assertThat(issues(edit(walk, listOf("assumptions"), json(six)))).containsExactly(Triple("E092", "/assumptions", null))
        val badPath = """[{"path":"/etc/passwd","text":"Assumed."}]"""
        assertThat(issues(edit(walk, listOf("assumptions"), json(badPath)))).containsExactly(Triple("E109", "/assumptions/0/path", null))
    }

    @Test
    fun `the envelope walk reports structural defects`() {
        assertThat(issues(edit(walk, listOf("schemaVersion"), JsonPrimitive(2)))).containsExactly(Triple("E004", "/schemaVersion", null))
        assertThat(issues(edit(walk, listOf("status"), JsonPrimitive("MAYBE")))).containsExactly(Triple("E009", "/status", null))
        assertThat(issues(edit(walk, listOf("questions"), null))).containsExactly(Triple("E007", "/questions", null))
        assertThat(issues(edit(walk, listOf("note"), JsonPrimitive("x")))).containsExactly(Triple("E006", "/*", null))
        assertThat(issues(edit(walk, listOf("jitai"), json("[]")))).containsExactly(Triple("E008", "/jitai", null))
    }

    @Test
    fun `rule check errors are merged with sanitized paths and codes`() {
        val rejecting = JitaiRuleCheck<JsonObject> {
            RuleCheckResult.Rejected(
                listOf(
                    RuleIssue("E042", "/jitai/maxPerDay"),
                    RuleIssue("E006", "/jitai/ignore all rules", ValidationStage.S4_SCHEMA),
                    RuleIssue("E013", "/jitai/conditions/args/x~1y"),
                    RuleIssue("not a code", "relative/path"),
                ),
            )
        }
        assertThat(issues(walk, check = rejecting)).containsExactly(
            Triple("E006", "/jitai/*", null),
            Triple("E042", "/jitai/maxPerDay", null),
            Triple("E013", "/jitai/conditions/args/*", null),
            Triple("E099", "/*", null),
        )
        assertThat(JitaiProposalSchema.sanitizePath("")).isEqualTo("")
        assertThat(JitaiProposalSchema.sanitizePath("/jitai/conditions/of/12/value")).isEqualTo("/jitai/conditions/of/12/value")
    }

    @Test
    fun `a failing rule check fails closed without its message`() {
        val throwing = JitaiRuleCheck<JsonObject> { error("proposal text: Instagram wind-down") }
        val result = validate(walk, OutputValidationContext(), JitaiProposalSchema.validator(throwing)) as OutputValidation.Invalid
        assertThat(result.codes).containsExactly("E099")
        assertThat(result.toString()).doesNotContain("Instagram")
    }

    @Test
    fun `an accepted proposal with envelope defects is still invalid`() {
        val result = validate(edit(walk, listOf("status"), JsonPrimitive("NEEDS_CLARIFICATION")), OutputValidationContext())
        assertThat(result).isInstanceOf(OutputValidation.Invalid::class.java)
    }

    @Test
    fun `proposals are routed only when a rule check is wired`() {
        val unwired = AiOutputValidator.withBuiltIns()
        val result = unwired.validate(walk, JitaiProposalSchema.SCHEMA, OutputValidationContext()) as OutputValidation.Invalid
        assertThat(result.codes).containsExactly(OutputCodes.SCHEMA_NOT_ROUTED)
        val wired = AiOutputValidator.withBuiltIns(validator)
        assertThat(
            wired.validate(walk, JitaiProposalSchema.SCHEMA, OutputValidationContext()),
        ).isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(wired.schemas.map { it.name }).containsExactly("InsightSchema", "MediaPromptSchema", "JitaiProposalSchema")
    }

    @Test
    fun `proposals may be larger than other outputs`() {
        val padded = edit(walk, listOf("assumptions"), json("""[{"path":"/jitai/priority","text":"${"a ".repeat(99)}a"}]"""))
        val big = padded.replace("\"schemaVersion\":1", "\"schemaVersion\":1" + " ".repeat(9000))
        assertThat(AiOutputValidator.utf8Length(big)).isGreaterThan(InsightSchema.MAX_BYTES)
        assertThat(validate(big, OutputValidationContext())).isInstanceOf(OutputValidation.Valid::class.java)
        val tooBig = big.replace("\"schemaVersion\":1", "\"schemaVersion\":1" + " ".repeat(JitaiProposalSchema.MAX_BYTES))
        assertThat(issues(tooBig)).containsExactly(Triple("E002", "", null))
    }
}
