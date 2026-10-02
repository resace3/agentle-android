package dev.agentle.analytics.features.daily

import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.ClosedOpenRange

/**
 * Screen, app and unlock features from the usage collector (docs/research/10 §5.5): screen-on intervals clipped to the
 * window and floored to whole minutes; an app's foreground intervals merged over gaps of at most 2 s and intersected
 * with screen-on time; category minutes as the union (not the sum) over the category's packages.
 */
internal class UsageFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> {
        val events = day.events(
            DailyGroupQueries.USAGE,
            day.hullOf(DailyWindow.ENGINE_DAY, DailyWindow.LATE_NIGHT, DailyWindow.EVENING_22_24),
        )
        val screen = mergeRanges(events.filter { it.type == EventType.SCREEN_SESSION }.map { it.interval() })
        val apps = events.filter { it.type == EventType.APP_SESSION && it.payload is AppUsagePayload }
        val byPackage = apps.groupBy { (it.payload as AppUsagePayload).packageName }.mapValues { (_, list) ->
            MinuteFusion.intersect(IntervalMath.mergeGaps(list.map { it.interval() }, day.config.appGapMergeMillis), screen)
        }
        val categoryOf = apps.associate { e -> (e.payload as AppUsagePayload).let { it.packageName to category(it) } }
        val byCategory = byPackage.entries.groupBy({
            categoryOf.getValue(it.key)
        }, { it.value }).mapValues { (_, lists) -> mergeRanges(lists.flatten()) }
        val rows = ArrayList<DailySummaryRow>()
        rows += minutesRow("screen_minutes", screen, screen)
        rows += minutesRow("screen_minutes_late_night", screen, screen)
        rows += minutesRow("screen_minutes_22_24", screen, screen)
        rows += minutesRow("social_minutes_22_24", byCategory[SOCIAL].orEmpty(), apps.map { it.interval() })
        rows += subjectRows("app_minutes", byPackage)
        rows += subjectRows("app_category_minutes", byCategory)
        rows += unlockRow()
        return rows
    }

    private suspend fun minutesRow(id: String, intervals: List<ClosedOpenRange>, evidence: List<ClosedOpenRange>): DailySummaryRow {
        val def = DailyFeatureCalculator.feature(id)
        val window = day.window(def.window)
        val minutes = IntervalMath.unionMinutes(intervals, window)
        val seen = evidence.any { interval -> window.any { it.overlaps(interval) } }
        return day.rows.collector(def, def.id, minutes.toDouble(), day.coverage(collectorOf(def), def.window), seen)
    }

    /** One row per subject with time in the window; a subject without a row had no time in an observed window. */
    private suspend fun subjectRows(id: String, bySubject: Map<String, List<ClosedOpenRange>>): List<DailySummaryRow> {
        val def = DailyFeatureCalculator.feature(id)
        val window = day.window(def.window)
        val coverage = day.coverage(collectorOf(def), def.window)
        return bySubject.toSortedMap().mapNotNull { (subject, intervals) ->
            val millis = IntervalMath.unionMillis(intervals, window)
            if (millis == 0L) {
                null
            } else {
                val key = DailySummaryRow.metricKey(def.id, def.subject, subject)
                day.rows.collector(def, key, (millis / IntervalMath.MS_PER_MINUTE).toDouble(), coverage, hasEvents = true)
            }
        }
    }

    private suspend fun unlockRow(): DailySummaryRow {
        val def = DailyFeatureCalculator.feature("unlocks")
        val window = day.window(def.window)
        val unlocks = day.events(DailyGroupQueries.UNLOCKS, day.hullOf(def.window)).count { IntervalMath.contains(window, it.startTime) }
        return day.rows.collector(def, def.id, unlocks.toDouble(), day.coverage(collectorOf(def), def.window), unlocks > 0)
    }

    /** The user's override, else the declared category (`social` -> `SOCIAL`), else `UNDEFINED` (docs/research/10 §5.4 C). */
    private fun category(payload: AppUsagePayload): String {
        val raw = day.config.appCategoryOverrides[payload.packageName] ?: payload.appCategory
        val normalized = raw?.trim()?.uppercase()?.replace(NON_WORD, "_")?.trim('_')
        return if (normalized.isNullOrEmpty()) UNDEFINED else normalized
    }

    private companion object {
        const val SOCIAL = "SOCIAL"
        const val UNDEFINED = "UNDEFINED"
        val NON_WORD = Regex("[^A-Z0-9]+")
    }
}

/**
 * Notification counts (metadata only): distinct notification keys first posted in the window by another package,
 * excluding ongoing notifications, foreground-service notifications and group summaries (docs/research/10 §5.4 D).
 * Updates of a posted notification are folded into its row by the collector, so they never count as new posts.
 */
internal class NotificationFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> {
        val posted = day.events(
            DailyGroupQueries.NOTIFICATIONS,
            day.hullOf(DailyWindow.ENGINE_DAY, DailyWindow.LATE_NIGHT, DailyWindow.EVENING_21_24),
        ).filter { counts(it) }
        val rows = ArrayList<DailySummaryRow>()
        for (id in listOf("notifications", "notifications_late_night", "notifications_21_24")) {
            val def = DailyFeatureCalculator.feature(id)
            val inWindow = posted.filter { IntervalMath.contains(day.window(def.window), it.startTime) }
            val count = inWindow.map { keyOf(it) }.toSet().size
            rows += day.rows.collector(def, def.id, count.toDouble(), day.coverage(collectorOf(def), def.window), inWindow.isNotEmpty())
        }
        val def = DailyFeatureCalculator.feature("app_notifications")
        val coverage = day.coverage(collectorOf(def), def.window)
        val inDay = posted.filter { IntervalMath.contains(day.window(def.window), it.startTime) }
        inDay.groupBy { (it.payload as NotificationPayload).packageName }.toSortedMap().forEach { (pkg, list) ->
            val key = DailySummaryRow.metricKey(def.id, def.subject, pkg)
            rows += day.rows.collector(def, key, list.map { keyOf(it) }.toSet().size.toDouble(), coverage, hasEvents = true)
        }
        return rows
    }

    private fun counts(event: PersonalEvent): Boolean {
        val p = event.payload as? NotificationPayload ?: return false
        return event.type == EventType.NOTIFICATION_POSTED && p.packageName !in day.config.ownPackages &&
            !p.ongoing && !p.foregroundService && !p.groupSummary
    }

    /** The notification key (hash of `StatusBarNotification.key`); rows without one count by their own dedup key. */
    private fun keyOf(event: PersonalEvent): String = (event.payload as NotificationPayload).keyHash ?: event.dedupKey
}

/** The coverage collector of a collector feature. */
internal fun collectorOf(def: DailyFeatureDefinition): String = requireNotNull(def.coverageCollector) { "${def.id} has no collector" }
