package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.ACTIVITY
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.BODY
import dev.agentle.core.model.AiDataCategory.CALENDAR_BUSY
import dev.agentle.core.model.AiDataCategory.DEVICE_STATE
import dev.agentle.core.model.AiDataCategory.GOALS
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.INTERVENTION_HISTORY
import dev.agentle.core.model.AiDataCategory.LOCATION_CLASS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_COUNTS
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SELF_REPORTS
import dev.agentle.core.model.AiDataCategory.SETTINGS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.SourceFamily

/** The kind of a sent value; each maps to one `DataItem` type. */
public enum class ItemKind { QUANTITY, TIME_OF_DAY, CODE, TEXT, APP_USAGE, EVENT }

/**
 * One field the engine may send. The field codes are the vocabulary of everything that can leave the device.
 *
 * - [categories] is the lineage floor: every value of the field carries at least these categories, whatever its
 *   producer claims.
 * - [sources] are the source families its values can come from. A producer that claims another family is wrong, and
 *   the value gets UNKNOWN lineage.
 * - [group] names the block the value is sent in. [primary] is that block's category.
 * - Fields with an empty floor (`pattern.*`, `evidence.*` and raw events) combine several inputs. Their lineage is what
 *   the producer, or [AiLineageTables] for events, reports, and it must not be empty.
 * - [purposes] limits a field to some purposes (null: any purpose whose allow-list covers its categories).
 * - [codes] is the closed vocabulary of a CODE field, so text cannot be smuggled in as a code. It is null only for
 *   nothing: every CODE field has a closed vocabulary, and a code outside it is refused (fail closed).
 */
public data class AiField(
    val code: String,
    val kind: ItemKind,
    val group: String,
    val categories: Set<AiDataCategory>,
    val sources: Set<SourceFamily>,
    val unit: String? = null,
    val primary: AiDataCategory? = categories.firstOrNull(),
    val purposes: Set<AiPurpose>? = null,
    val codes: Set<String>? = null,
)

/** The closed set of [AiField]s (privacy-ai-04). A fact naming a field outside it has UNKNOWN lineage. */
public object AiFieldRegistry {
    /** The field of every raw event item; its lineage comes from [AiLineageTables.forEvent]. */
    public const val EVENT_FIELD: String = "event"

    private val OD = setOf(SourceFamily.ON_DEVICE)
    private val WEARABLE = setOf(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT)
    private val HEART_SOURCES = setOf(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT, SourceFamily.ON_DEVICE)
    private val STEP_SOURCES = setOf(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT, SourceFamily.ON_DEVICE)
    private val ANY_SOURCE = SourceFamily.entries.toSet()
    private val TRENDS = setOf("UP", "DOWN", "STABLE")
    private val YES_NO = setOf("YES", "NO")

    /** Closed vocabulary of `pattern.kind` (UNVERIFIED: the analytics layer defines no enum yet; extend together). */
    private val PATTERN_KINDS = setOf("LATE_SCREEN_SHORT_SLEEP", "ACTIVITY_BETTER_SLEEP", "WEEKDAY_WEEKEND", "TREND", "OTHER")

    /** Closed vocabulary of `evidence.template` (UNVERIFIED, as above). */
    private val EVIDENCE_TEMPLATES = setOf("RATE_ON_DAYS", "AVERAGE_DIFFERENCE", "COUNT_OF_DAYS", "OTHER")

    /** JITAI categories of docs/research/10-jitai-engine-design.md section 2.1 (the `category` enum). */
    private val JITAI_CATEGORIES = setOf("PHYSICAL_ACTIVITY", "SLEEP_WIND_DOWN", "DIGITAL_WELLBEING", "STRESS_BREAK", "GENERAL")

    private fun enumOf(featureId: String): Set<String> = RealtimeFeatureCatalog[featureId]?.enumValues.orEmpty().toSet()

    private class Group(val name: String, val categories: Set<AiDataCategory>, val sources: Set<SourceFamily>) {
        val fields = mutableListOf<AiField>()

        fun q(code: String, unit: String?, purposes: Set<AiPurpose>? = null) {
            fields += AiField(code, ItemKind.QUANTITY, name, categories, sources, unit, purposes = purposes)
        }

        fun t(code: String) {
            fields += AiField(code, ItemKind.TIME_OF_DAY, name, categories, sources)
        }

        fun c(code: String, codes: Set<String>?, purposes: Set<AiPurpose>? = null) {
            fields += AiField(code, ItemKind.CODE, name, categories, sources, purposes = purposes, codes = codes)
        }

        fun text(code: String) {
            fields += AiField(code, ItemKind.TEXT, name, categories, sources)
        }
    }

    private fun group(name: String, categories: Set<AiDataCategory>, sources: Set<SourceFamily>, block: Group.() -> Unit): List<AiField> =
        Group(name, categories, sources).apply(block).fields

    public val all: List<AiField> = buildList {
        addAll(
            group("sleep", setOf(SLEEP), WEARABLE) {
                listOf("sleep.minutes_avg", "sleep.minutes_min", "sleep.minutes_max", "sleep.minutes_last_night", "sleep.bedtime_spread")
                    .forEach { q(it, "min") }
                q("sleep.nights", "nights")
                t("sleep.bedtime_median")
                t("sleep.wake_time_median")
                c("sleep.trend", TRENDS)
            },
        )
        addAll(
            group("screen", setOf(SCREEN_TIME_TOTALS), OD) {
                listOf("screen.minutes_daily_avg", "screen.minutes_today", "screen.minutes_late_evening_avg").forEach { q(it, "min") }
                q("screen.unlocks_daily_avg", "count")
                t("screen.first_use_median")
                t("screen.last_use_median")
                c("screen.trend", TRENDS)
            },
        )
        addAll(
            group("app_categories", linkedSetOf(SCREEN_TIME_TOTALS, APP_IDENTITY), OD) {
                RealtimeFeatureCatalog.APP_CATEGORIES.forEach { q("apps.category_minutes_daily_avg.${it.lowercase()}", "min") }
            },
        )
        add(AiField("apps.usage", ItemKind.APP_USAGE, "app_usage", linkedSetOf(APP_IDENTITY, SCREEN_TIME_TOTALS), OD))
        addAll(
            group("notifications", setOf(NOTIFICATION_COUNTS), OD) {
                listOf("notifications.daily_avg", "notifications.late_evening_avg", "notifications.today").forEach { q(it, "count") }
            },
        )
        addAll(
            group("steps", setOf(STEPS), STEP_SOURCES) {
                listOf("steps.daily_avg", "steps.daily_min", "steps.daily_max", "steps.today").forEach { q(it, "steps") }
                q("steps.days", "days")
                q("steps.distance_daily_avg", "km")
                c("steps.trend", TRENDS)
            },
        )
        addAll(
            group("activity", setOf(ACTIVITY), STEP_SOURCES) {
                q("activity.active_minutes_daily_avg", "min")
                q("activity.exercise_sessions", "count")
                q("activity.exercise_minutes", "min")
                c("activity.state_now", enumOf("activity_state"))
            },
        )
        addAll(
            group("heart", setOf(HEART), HEART_SOURCES) {
                listOf("heart.resting_bpm_avg", "heart.resting_bpm_today", "heart.resting_bpm_delta_vs_28d").forEach { q(it, "bpm") }
            },
        )
        addAll(group("body", setOf(BODY), WEARABLE) { listOf("body.weight_latest", "body.weight_change").forEach { q(it, "kg") } })
        addAll(
            group("self_reports", setOf(SELF_REPORTS), OD) {
                listOf("self_report.mood_avg", "self_report.energy_avg", "self_report.stress_avg").forEach { q(it, "score") }
                q("self_report.entries", "count")
            },
        )
        addAll(group("user_text", setOf(USER_TEXT), OD) { text("user.note") })
        addAll(
            group("goals", setOf(GOALS), OD) {
                text("goal.text")
                q("goal.target", null)
            },
        )
        addAll(
            group("device", setOf(DEVICE_STATE), OD) {
                q("device.battery_pct_now", "pct")
                listOf("device.charging_now", "device.dnd_now", "device.headphones_now").forEach { c(it, YES_NO) }
            },
        )
        addAll(
            group("place", setOf(LOCATION_CLASS), OD) {
                c("place.class_now", enumOf("location_class"))
                q("place.home_minutes_daily_avg", "min")
            },
        )
        addAll(
            group("calendar", setOf(CALENDAR_BUSY), OD) {
                q("calendar.busy_minutes_today", "min")
                q("calendar.busy_minutes_daily_avg", "min")
                c("calendar.busy_now", setOf("BUSY", "FREE"))
            },
        )
        addAll(
            group("history", setOf(INTERVENTION_HISTORY), OD) {
                listOf("history.deliveries_7d", "history.opened_7d", "history.dismissed_7d", "history.ignored_streak").forEach {
                    q(it, "count")
                }
                c("history.last_response", enumOf("last_response"))
                c("jitai.category", JITAI_CATEGORIES)
                c("jitai.time_bucket", setOf("MORNING", "MIDDAY", "AFTERNOON", "EVENING", "NIGHT"))
            },
        )
        addAll(
            group("settings", setOf(SETTINGS), OD) {
                t("settings.quiet_hours_start")
                t("settings.quiet_hours_end")
                c("settings.weekend_day", setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"))
                c("settings.clock_format", setOf("H12", "H24"))
                c("settings.tone", setOf("WARM", "NEUTRAL", "DIRECT"))
                c("settings.day_type_today", enumOf("day_type"))
            },
        )
        val pattern = setOf(AiPurpose.PATTERN_EXPLANATION)
        addAll(
            group("pattern", emptySet(), ANY_SOURCE) {
                c("pattern.kind", PATTERN_KINDS, pattern)
                q("pattern.nights", "nights", pattern)
                q("pattern.effect_minutes", "min", pattern)
                q("pattern.share", "pct", pattern)
            },
        )
        val wording = setOf(AiPurpose.JITAI_PROPOSAL_WORDING)
        addAll(
            group("evidence", emptySet(), ANY_SOURCE) {
                c("evidence.tier", EvidenceStrength.entries.mapTo(LinkedHashSet()) { it.name }, wording)
                c("evidence.template", EVIDENCE_TEMPLATES, wording)
                q("evidence.count", "count", wording)
                q("evidence.rate", "pct", wording)
            },
        )
        add(AiField(EVENT_FIELD, ItemKind.EVENT, "events", emptySet(), ANY_SOURCE))
    }

    private val byCode: Map<String, AiField> = all.associateBy { it.code }

    init {
        require(byCode.size == all.size) { "duplicate AI field codes" }
    }

    public operator fun get(code: String): AiField? = byCode[code]

    /** Fields usable for [purpose] whose floor lies within [categories] and whose kind is in [kinds]. */
    public fun fieldsFor(purpose: AiPurpose, categories: Set<AiDataCategory>, kinds: Set<ItemKind>): List<AiField> = all.filter { field ->
        field.kind in kinds &&
            categories.containsAll(field.categories) &&
            (field.purposes?.contains(purpose) ?: field.categories.isNotEmpty())
    }
}
