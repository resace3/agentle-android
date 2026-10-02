package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Duration.Companion.minutes

/**
 * Group C, screen and app usage (R10 §5.4 C, §5.5). Every feature needs the usage events of its window, a collector
 * coverage of the whole window, and, where a stream has no event in the window, the live state at `t`.
 */
internal object UsageFeatures {
    private val LAST_60 = 60.minutes
    private const val UNDEFINED = "UNDEFINED"

    suspend fun compute(pass: FeaturePass, featureId: String, args: ParsedArgs, collectorId: String): FeatureValue {
        val since = args.since
        val window = if (since != null) {
            ClosedOpenRange(LocalTimeRules.sinceStart(pass.at, since, pass.zone), pass.at)
        } else {
            ClosedOpenRange(pass.at - LAST_60, pass.at)
        }
        return pass.usageEvents(window).orMissing { events ->
            if (featureId == "foreground_app") {
                // An "at t" feature needs coverage at t only: the window is clipped to the covered stretch (R1-5).
                return pass.coveredSince(collectorId, window).orMissing { from ->
                    val clipped = ClosedOpenRange(from, pass.at)
                    val inside = pass.memo("usage-inside" to clipped) { UsageAlgebra.inWindow(events, clipped) }
                    WindowUsage(pass, inside, clipped).foregroundApp()
                }
            }
            val gap = pass.collectorGap(collectorId, window)
            if (gap != null) return FeatureValue.Missing(gap)
            val inside = pass.memo("usage-inside" to window) { UsageAlgebra.inWindow(events, window) }
            val usage = WindowUsage(pass, inside, window)
            when (featureId) {
                "screen_minutes_last_60m", "screen_minutes_since" -> usage.screen().orMissing { minutes(pass, it, window) }
                "app_minutes_last_60m", "app_minutes_since" -> usage.appMinutes(args.packageName.orEmpty())
                "app_category_minutes_last_60m", "app_category_minutes_since" -> usage.categoryMinutes(args.appCategory.orEmpty())
                "app_opens_last_60m" -> usage.opens(args.packageName.orEmpty())
                "foreground_app" -> usage.foregroundApp()
                else -> FeatureValue.Missing(MissingReason.INVALID_VALUE)
            }
        }
    }

    private fun minutes(pass: FeaturePass, spans: List<Span>, window: ClosedOpenRange): FeatureValue =
        pass.known(FeatureScalar.IntValue(UsageAlgebra.wholeMinutes(spans, window)))

    /** The intervals of one window, memoized in the pass so several refs over the same window share them. */
    private class WindowUsage(private val pass: FeaturePass, private val inside: List<UsageEvent>, private val window: ClosedOpenRange) {
        /** The three-state screen; a live read of `isInteractive()` only when the window has no screen event or reboot. */
        suspend fun screenState(): Read<ScreenState> = pass.memo("usage-screen" to window) {
            if (UsageAlgebra.hasScreenEvent(inside) || UsageAlgebra.hasBoundary(inside)) {
                Read.Ok(UsageAlgebra.screenState(inside, window, liveInteractive = null))
            } else {
                when (val live = pass.interactive()) {
                    is Read.Fail -> live
                    is Read.Ok -> Read.Ok(UsageAlgebra.screenState(inside, window, live.value))
                }
            }
        }

        /**
         * Interactive intervals for minute features; `COVERAGE_GAP` when the screen state is unknown somewhere in the
         * window (after a reboot, before the first screen event): never inferred as "not interactive".
         */
        suspend fun screen(): Read<List<Span>> = when (val state = screenState()) {
            is Read.Fail -> state
            is Read.Ok -> if (state.value.unknown.isEmpty()) Read.Ok(state.value.interactive) else Read.Fail(MissingReason.COVERAGE_GAP)
        }

        /** Interactive at `t`: from the events when they know it, else the live read. */
        suspend fun interactiveAtEnd(): Read<Boolean> = when (val state = screenState()) {
            is Read.Fail -> state
            is Read.Ok -> state.value.atEnd?.let { Read.Ok(it) } ?: pass.interactive()
        }

        /** Merged foreground intervals of the packages with an activity event in the window. */
        suspend fun foreground(): Map<String, List<Span>> =
            pass.memo("usage-foreground" to window) { UsageAlgebra.foreground(inside, window) }

        /** Foreground intervals of [packageName], using the live foreground app when it has no event in the window. */
        suspend fun spansOf(packageName: String): Read<List<Span>> {
            foreground()[packageName]?.let { return Read.Ok(it) }
            return when (val live = pass.foregroundApp()) {
                is Read.Fail -> live
                is Read.Ok -> Read.Ok(if (live.value == packageName) UsageAlgebra.foregroundWithoutEvents(inside, window) else emptyList())
            }
        }

        /** Merged foreground intervals of the package that start inside the window (R10 §5.4 C). */
        suspend fun opens(packageName: String): FeatureValue =
            pass.known(FeatureScalar.IntValue(foreground()[packageName].orEmpty().count { it.opened }.toLong()))

        suspend fun appMinutes(packageName: String): FeatureValue = screen().orMissing { screen ->
            spansOf(packageName).orMissing { spans -> minutes(pass, UsageAlgebra.intersect(spans, screen), window) }
        }

        /** Union over the packages of [category] (overlaps counted once, R10 §5.5 step 5). */
        suspend fun categoryMinutes(category: String): FeatureValue = screen().orMissing { screen ->
            pass.foregroundApp().orMissing { live ->
                val byPackage = foreground().toMutableMap()
                if (live != null && live !in byPackage) byPackage[live] = UsageAlgebra.foregroundWithoutEvents(inside, window)
                if (byPackage.isEmpty()) {
                    minutes(pass, emptyList(), window)
                } else {
                    pass.appCategories(byPackage.keys).orMissing { categories ->
                        val used = byPackage.filterKeys { effectiveCategory(categories[it]) == category }
                            .values.flatMap { UsageAlgebra.intersect(it, screen) }
                        minutes(pass, UsageAlgebra.union(used), window)
                    }
                }
            }
        }

        /**
         * The package whose merged foreground interval contains `t` while the screen is interactive; `NoPackage` if none.
         * An app resumed inside the window wins over one known only from the live read; ties go to the latest resume,
         * then the package name (design).
         */
        suspend fun foregroundApp(): FeatureValue = interactiveAtEnd().orMissing { interactive ->
            if (!interactive) return@orMissing pass.known(FeatureScalar.NoPackage)
            val open = foreground().filterValues { UsageAlgebra.openAtEnd(it, window) }
            val best = open.entries.sortedWith(
                compareByDescending<Map.Entry<String, List<Span>>> { e ->
                    e.value.last().start
                }.thenBy { it.key },
            )
                .firstOrNull()?.key
            if (best != null) return@orMissing pass.known(FeatureScalar.PackageValue(best))
            pass.foregroundApp().orMissing { live ->
                val livePackage = live?.takeIf { it !in foreground() }
                val stillOpen = livePackage != null && UsageAlgebra.openAtEnd(UsageAlgebra.foregroundWithoutEvents(inside, window), window)
                pass.known(if (stillOpen) FeatureScalar.PackageValue(livePackage) else FeatureScalar.NoPackage)
            }
        }

        private fun effectiveCategory(raw: String?): String = raw?.takeIf { it in RealtimeFeatureCatalog.APP_CATEGORIES } ?: UNDEFINED
    }
}

/**
 * Group D, notifications (R10 §5.4 D, lifecycle-battery-02): distinct notification keys whose first POSTED in
 * `[t-60min, t)` is from another package, excluding ongoing notifications and group summaries. Updates never count
 * as new posts: the collector folds them into the key's POSTED row.
 */
internal object NotificationFeatures {
    private val LAST_60 = 60.minutes

    suspend fun compute(pass: FeaturePass, collectorId: String): FeatureValue {
        val window = ClosedOpenRange(pass.at - LAST_60, pass.at)
        return pass.notificationPosts(window).orMissing { posts ->
            val gap = pass.collectorGap(collectorId, window)
            if (gap != null) return FeatureValue.Missing(gap)
            val firstPerKey = posts.filter { it.at in window }.sortedBy { it.at }.groupBy { it.keyHash }.values.map { it.first() }
            val counted = firstPerKey.count { it.packageName !in pass.config.ownPackages && !it.ongoing && !it.groupSummary }
            pass.known(FeatureScalar.IntValue(counted.toLong()))
        }
    }
}
