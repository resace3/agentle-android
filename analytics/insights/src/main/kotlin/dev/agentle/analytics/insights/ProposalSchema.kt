package dev.agentle.analytics.insights

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One structural problem: a JSON pointer and a short reason. */
public data class SchemaIssue(val path: String, val reason: String)

/**
 * A structural check of JitaiProposalSchema v1 (docs/research/10 §13.3): closed objects, required keys, types, enums,
 * ranges and patterns, plus the validator's own reading of absent `args` keys as null (sparse args are accepted, §13.3
 * difference 1) and its rejection of fractional numbers where an integer is required (difference 3).
 *
 * This is the fallback the brief asks for while `:jitai:dsl` (RuleValidator, team JITAI-DSL) is not on main: it does
 * not run the semantic checks of §11 (feature types per operator, placeholders, static analysis). A discovered draft
 * must still pass RuleValidator with origin AI before review.
 */
public object ProposalSchema {
    public const val SCHEMA_VERSION: Int = 1

    /** The §13.3 envelope around a draft produced by the app. */
    public fun envelope(draft: JsonObject): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("status", "OK")
        put("unsupported", JsonNull)
        put("questions", JsonArray(emptyList()))
        put("assumptions", JsonArray(emptyList()))
        put("jitai", draft)
    }

    /** Issues of a whole proposal envelope; empty when it conforms. */
    public fun checkEnvelope(json: JsonElement): List<SchemaIssue> = Checker().apply { envelope(json, "") }.issues

    /** Issues of a draft (the `jitai` object). */
    public fun checkDraft(json: JsonElement, path: String = "/jitai"): List<SchemaIssue> = Checker().apply { draft(json, path) }.issues

    internal val FEATURE_IDS: Set<String> = setOf(
        "local_time", "day_of_week", "day_type", "engine_day_of_week", "charging", "battery_pct", "device_interactive", "dnd_active",
        "in_call", "headphones_connected", "screen_minutes_last_60m", "screen_minutes_since", "app_minutes_last_60m", "app_minutes_since",
        "app_category_minutes_last_60m", "app_category_minutes_since", "app_opens_last_60m", "foreground_app", "notifications_last_60m",
        "location_class", "activity_state", "activity_level_last_30m", "steps_today", "steps_last_60m", "steps_last_30m",
        "sleep_minutes_last_night", "bedtime_last_night", "wake_time_today", "resting_hr_today", "resting_hr_delta_vs_28d",
        "minutes_since_last_delivery", "deliveries_today", "deliveries_last_7d", "last_response", "consecutive_ignored",
    )
    internal val CATEGORIES = setOf("PHYSICAL_ACTIVITY", "SLEEP_WIND_DOWN", "DIGITAL_WELLBEING", "STRESS_BREAK", "GENERAL")
    internal val EVENTS = setOf(
        "LOCATION_CLASS_CHANGED", "ACTIVITY_STATE_CHANGED", "HEALTH_SYNC_COMPLETED", "SLEEP_SESSION_AVAILABLE", "NOTIFICATION_POSTED",
        "POWER_CONNECTED", "POWER_DISCONNECTED", "SCREEN_INTERACTIVE", "USER_PRESENT",
    )
    internal val METRICS = setOf(
        "STEPS_AFTER", "SCREEN_MINUTES_AFTER", "APP_MINUTES_AFTER", "APP_CATEGORY_MINUTES_AFTER", "NOTIFICATION_OPENED",
        "SELF_REPORT_HELPFUL", "BEDTIME_NEXT", "SLEEP_MINUTES_NEXT", "STEPS_DAY_TOTAL",
    )
    internal val APP_CATEGORIES =
        setOf("SOCIAL", "VIDEO", "GAME", "AUDIO", "NEWS", "IMAGE", "MAPS", "PRODUCTIVITY", "ACCESSIBILITY", "UNDEFINED")
    internal val HHMM = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    private val DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    private val JITAI_ARG = Regex("self|any|category:(PHYSICAL_ACTIVITY|SLEEP_WIND_DOWN|DIGITAL_WELLBEING|STRESS_BREAK|GENERAL)")
    private val ASSUMPTION_PATH = Regex("/jitai(/[A-Za-z0-9_]+)*")
    private val ASSET_ID = Regex("[a-z0-9_]{1,40}")
    private val DRAFT_KEYS = setOf(
        "name", "description", "kind", "category", "trigger", "activeWindow", "conditions", "contextRequirements", "delivery", "content",
        "cooldownMinutes", "maxPerDay", "maxPerWeek", "priority", "snooze", "expiresInDays", "outcome", "suppression",
    )
    private val ARG_KEYS = setOf("package", "appLabel", "category", "since", "jitai")
    private const val MAX_DEPTH = 16

    private class Checker {
        val issues = mutableListOf<SchemaIssue>()

        fun fail(path: String, reason: String) {
            issues += SchemaIssue(path.ifEmpty { "/" }, reason)
        }

        fun envelope(json: JsonElement, path: String) {
            val o = obj(json, path, setOf("schemaVersion", "status", "unsupported", "questions", "assumptions", "jitai")) ?: return
            if ((o["schemaVersion"] as? JsonPrimitive)?.let { it.isString || it.content != "1" } !=
                false
            ) {
                fail("$path/schemaVersion", "must be 1")
            }
            enum(o["status"], "$path/status", setOf("OK", "NEEDS_CLARIFICATION", "UNSUPPORTED"))
            nullOr(o["unsupported"], "$path/unsupported") { el, p ->
                val u = obj(el, p, setOf("reason", "detail")) ?: return@nullOr
                enum(
                    u["reason"],
                    "$p/reason",
                    setOf(
                        "NEEDS_UNAVAILABLE_DATA",
                        "NEEDS_FINER_TIMING",
                        "NEEDS_UNAVAILABLE_ACTION",
                        "NOT_A_REMINDER",
                        "HEALTH_OR_SAFETY",
                        "OTHER",
                    ),
                )
                nullOr(u["detail"], "$p/detail") { d, dp -> string(d, dp, 0..LONG_TEXT) }
            }
            array(o["questions"], "$path/questions", 0..MAX_QUESTIONS) { el, p ->
                val q = obj(el, p, setOf("id", "text", "options")) ?: return@array
                enum(q["id"], "$p/id", setOf("q1", "q2", "q3"))
                string(q["text"], "$p/text", 1..LONG_TEXT)
                array(q["options"], "$p/options", 2..MAX_OPTIONS) { opt, op -> string(opt, op, 1..SHORT_TEXT) }
            }
            array(o["assumptions"], "$path/assumptions", 0..MAX_ASSUMPTIONS) { el, p ->
                val a = obj(el, p, setOf("path", "text")) ?: return@array
                string(a["path"], "$p/path", 1..LONG_TEXT, ASSUMPTION_PATH)
                string(a["text"], "$p/text", 1..LONG_TEXT)
            }
            nullOr(o["jitai"], "$path/jitai") { el, p -> draft(el, p) }
        }

        fun draft(json: JsonElement, path: String) {
            val o = obj(json, path, DRAFT_KEYS) ?: return
            string(o["name"], "$path/name", 1..SHORT_TEXT)
            string(o["description"], "$path/description", 1..DESCRIPTION)
            enum(o["kind"], "$path/kind", setOf("INTERVENTION", "SUPPRESSION"))
            enum(o["category"], "$path/category", CATEGORIES)
            nullOr(o["trigger"], "$path/trigger") { el, p -> trigger(el, p) }
            nullOr(o["activeWindow"], "$path/activeWindow") { el, p -> window(el, p) }
            nullOr(o["conditions"], "$path/conditions") { el, p -> condition(el, p, 0) }
            nullOr(o["contextRequirements"], "$path/contextRequirements") { el, p -> condition(el, p, 0) }
            obj(o["delivery"], "$path/delivery", setOf("channel", "quietHoursPolicy", "notificationTimeoutMinutes"))?.let { d ->
                enum(d["channel"], "$path/delivery/channel", setOf("NOTIFICATION", "IMAGE", "VOICE", "VIDEO", "NONE"))
                enum(d["quietHoursPolicy"], "$path/delivery/quietHoursPolicy", setOf("RESPECT", "ALLOW_WHEN_INTERACTIVE"))
                nullOr(d["notificationTimeoutMinutes"], "$path/delivery/notificationTimeoutMinutes") { el, p ->
                    int(
                        el,
                        p,
                        5..MINUTES_PER_DAY,
                    )
                }
            }
            nullOr(o["content"], "$path/content") { el, p -> content(el, p) }
            nullOr(o["cooldownMinutes"], "$path/cooldownMinutes") { el, p -> int(el, p, 60..MINUTES_PER_WEEK) }
            nullOr(o["maxPerDay"], "$path/maxPerDay") { el, p -> int(el, p, 1..3) }
            nullOr(o["maxPerWeek"], "$path/maxPerWeek") { el, p -> int(el, p, 1..14) }
            int(o["priority"], "$path/priority", 0..60)
            nullOr(o["snooze"], "$path/snooze") { el, p ->
                val s = obj(el, p, setOf("mode", "options")) ?: return@nullOr
                enum(s["mode"], "$p/mode", setOf("SUPPRESS_ONLY", "RE_EVALUATE_AFTER"))
                val options = setOf("MINUTES_30", "MINUTES_60", "MINUTES_120", "UNTIL_WINDOW_END", "UNTIL_TOMORROW")
                array(s["options"], "$p/options", 1..3, unique = true) { opt, op -> enum(opt, op, options) }
            }
            nullOr(o["expiresInDays"], "$path/expiresInDays") { el, p -> int(el, p, 1..90) }
            nullOr(o["outcome"], "$path/outcome") { el, p ->
                val out = obj(el, p, setOf("proximal", "distal")) ?: return@nullOr
                metricRef(out["proximal"], "$p/proximal")
                nullOr(out["distal"], "$p/distal") { d, dp -> metricRef(d, dp) }
            }
            nullOr(o["suppression"], "$path/suppression") { el, p ->
                val s = obj(el, p, setOf("categories")) ?: return@nullOr
                array(s["categories"], "$p/categories", 1..5, unique = true) { c, cp -> enum(c, cp, CATEGORIES) }
            }
        }

        fun trigger(json: JsonElement, path: String) {
            when (typeOf(json)) {
                "event" -> obj(json, path, setOf("type", "events", "debounceSeconds"))?.let { o ->
                    array(o["events"], "$path/events", 1..8, unique = true) { el, p -> enum(el, p, EVENTS) }
                    int(o["debounceSeconds"], "$path/debounceSeconds", 0..600)
                }

                "interval" -> obj(json, path, setOf("type", "everyMinutes"))?.let { o ->
                    val every = int(o["everyMinutes"], "$path/everyMinutes", 30..MINUTES_PER_DAY)
                    if (every != null && every % 15 != 0L) fail("$path/everyMinutes", "must be a multiple of 15")
                }

                "daily_at" -> obj(json, path, setOf("type", "times", "maxLatenessMinutes"))?.let { o ->
                    array(o["times"], "$path/times", 1..6, unique = true) { el, p -> string(el, p, 5..5, HHMM) }
                    int(o["maxLatenessMinutes"], "$path/maxLatenessMinutes", 5..120)
                }

                else -> fail("$path/type", "unknown trigger type")
            }
        }

        fun window(json: JsonElement, path: String) {
            val o = obj(json, path, setOf("start", "end", "days")) ?: return
            string(o["start"], "$path/start", 5..5, HHMM)
            string(o["end"], "$path/end", 5..5, HHMM)
            nullOr(o["days"], "$path/days") { el, p -> array(el, p, 1..7, unique = true) { d, dp -> enum(d, dp, DAYS) } }
        }

        fun condition(json: JsonElement, path: String, depth: Int) {
            if (depth > MAX_DEPTH) return fail(path, "nested too deeply")
            when (val type = typeOf(json)) {
                "all", "any" -> obj(json, path, setOf("type", "of"))?.let { o ->
                    array(o["of"], "$path/of", 1..8) { el, p -> condition(el, p, depth + 1) }
                }

                "not" -> obj(json, path, setOf("type", "of"))?.let { o -> condition(o["of"] ?: JsonNull, "$path/of", depth + 1) }

                "gt", "gte", "lt", "lte", "eq", "neq" -> obj(json, path, setOf("type", "feature", "args", "value", "onUnknown"))?.let { o ->
                    leaf(o, path)
                    literal(o["value"], "$path/value")
                }

                "between" -> obj(json, path, setOf("type", "feature", "args", "min", "max", "onUnknown"))?.let { o ->
                    leaf(o, path)
                    literal(o["min"], "$path/min")
                    literal(o["max"], "$path/max")
                }

                "in" -> obj(json, path, setOf("type", "feature", "args", "values", "onUnknown"))?.let { o ->
                    leaf(o, path)
                    array(o["values"], "$path/values", 1..10, unique = true) { el, p -> literal(el, p) }
                }

                "local_time_in" -> obj(json, path, setOf("type", "start", "end"))?.let { o ->
                    string(o["start"], "$path/start", 5..5, HHMM)
                    string(o["end"], "$path/end", 5..5, HHMM)
                }

                else -> fail("$path/type", "unknown node type ${type ?: "(none)"}".take(SHORT_TEXT))
            }
        }

        private fun leaf(o: JsonObject, path: String) {
            enum(o["feature"], "$path/feature", FEATURE_IDS)
            args(o["args"], "$path/args")
            nullOr(o["onUnknown"], "$path/onUnknown") { el, p -> enum(el, p, setOf("ASSUME_TRUE", "ASSUME_FALSE")) }
        }

        fun args(json: JsonElement?, path: String) {
            val o = obj(json, path, ARG_KEYS, sparse = true) ?: return
            o["package"]?.let { nullOr(it, "$path/package") { el, p -> string(el, p, 0..PACKAGE) } }
            o["appLabel"]?.let { nullOr(it, "$path/appLabel") { el, p -> string(el, p, 1..SHORT_TEXT) } }
            o["category"]?.let { nullOr(it, "$path/category") { el, p -> enum(el, p, APP_CATEGORIES) } }
            o["since"]?.let { nullOr(it, "$path/since") { el, p -> string(el, p, 5..5, HHMM) } }
            o["jitai"]?.let { nullOr(it, "$path/jitai") { el, p -> string(el, p, 1..SHORT_TEXT, JITAI_ARG) } }
        }

        fun content(json: JsonElement, path: String) {
            when (typeOf(json)) {
                "static", "template" -> textPair(json, path, withType = true)

                "variants" -> obj(json, path, setOf("type", "items", "selection"))?.let { o ->
                    array(o["items"], "$path/items", 2..8) { el, p -> textPair(el, p, withType = false) }
                    enum(o["selection"], "$path/selection", setOf("ROTATE"))
                }

                "ai_text" -> obj(json, path, setOf("type", "goal", "tone", "fallback"))?.let { o ->
                    string(o["goal"], "$path/goal", 1..LONG_TEXT)
                    enum(o["tone"], "$path/tone", setOf("WARM", "NEUTRAL", "BRIEF"))
                    template(o["fallback"], "$path/fallback")
                }

                "local_media" -> obj(json, path, setOf("type", "assetId", "caption"))?.let { o ->
                    string(o["assetId"], "$path/assetId", 1..40, ASSET_ID)
                    template(o["caption"], "$path/caption")
                }

                else -> fail("$path/type", "unknown content type")
            }
        }

        private fun template(json: JsonElement?, path: String) {
            if (typeOf(json) != "template") return fail("$path/type", "must be template")
            textPair(json!!, path, withType = true)
        }

        private fun textPair(json: JsonElement, path: String, withType: Boolean) {
            val o = obj(json, path, if (withType) setOf("type", "title", "body") else setOf("title", "body")) ?: return
            string(o["title"], "$path/title", 1..SHORT_TEXT)
            string(o["body"], "$path/body", 1..BODY)
        }

        fun metricRef(json: JsonElement?, path: String) {
            val o = obj(json, path, setOf("metric", "args", "windowMinutes")) ?: return
            enum(o["metric"], "$path/metric", METRICS)
            args(o["args"], "$path/args")
            nullOr(o["windowMinutes"], "$path/windowMinutes") { el, p -> int(el, p, 5..240) }
        }

        fun literal(json: JsonElement?, path: String) {
            val p = json as? JsonPrimitive
            when {
                p == null || p is JsonNull -> fail(path, "must be an integer, string or boolean")
                p.isString || p.booleanOrNull != null -> Unit
                p.content.toLongOrNull() == null -> fail(path, "must be an integer (no fraction or exponent)")
            }
        }

        fun obj(json: JsonElement?, path: String, keys: Set<String>, sparse: Boolean = false): JsonObject? {
            val o = json as? JsonObject ?: return null.also { fail(path, "must be an object") }
            (o.keys - keys).forEach { fail("$path/$it", "unknown key") }
            if (!sparse) (keys - o.keys).forEach { fail("$path/$it", "missing key") }
            return o
        }

        fun nullOr(json: JsonElement?, path: String, check: (JsonElement, String) -> Unit) {
            when (json) {
                null -> fail(path, "missing value")
                is JsonNull -> Unit
                else -> check(json, path)
            }
        }

        fun string(json: JsonElement?, path: String, length: IntRange, pattern: Regex? = null) {
            val p = json as? JsonPrimitive
            if (p == null || !p.isString) return fail(path, "must be a string")
            val count = p.content.codePointCount(0, p.content.length)
            if (count !in length) fail(path, "length must be ${length.first}-${length.last}")
            if (pattern != null && !pattern.matches(p.content)) fail(path, "does not match the pattern")
        }

        fun enum(json: JsonElement?, path: String, values: Set<String>) {
            val p = json as? JsonPrimitive
            if (p == null || !p.isString || p.content !in values) fail(path, "must be one of the allowed values")
        }

        fun int(json: JsonElement?, path: String, range: IntRange): Long? {
            val p = json as? JsonPrimitive
            val value = if (p == null || p.isString || p is JsonNull) null else p.content.toLongOrNull()
            if (value == null) {
                fail(path, "must be an integer (no fraction or exponent)")
                return null
            }
            if (value !in range.first.toLong()..range.last.toLong()) fail(path, "must be ${range.first}-${range.last}")
            return value
        }

        fun array(json: JsonElement?, path: String, size: IntRange, unique: Boolean = false, item: (JsonElement, String) -> Unit) {
            val a = json as? JsonArray ?: return fail(path, "must be an array")
            if (a.size !in size) fail(path, "must have ${size.first}-${size.last} items")
            if (unique && a.toSet().size != a.size) fail(path, "items must be unique")
            a.forEachIndexed { i, el -> item(el, "$path/$i") }
        }

        private fun typeOf(json: JsonElement?): String? = ((json as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf {
            it.isString
        }?.content
    }

    private const val SHORT_TEXT = 60
    private const val LONG_TEXT = 200
    private const val BODY = 240
    private const val DESCRIPTION = 280
    private const val PACKAGE = 255
    private const val MAX_QUESTIONS = 3
    private const val MAX_OPTIONS = 4
    private const val MAX_ASSUMPTIONS = 5
    private const val MINUTES_PER_DAY = 1_440
    private const val MINUTES_PER_WEEK = 10_080
}
