package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Seeded property tests of the usage features (R10 §5.5): random event streams, checked against an independent oracle
 * that replays them on a 250 ms grid (every event lies on the grid, so the grid is exact). Minute features must be the
 * floor of the exact interval sums, category minutes never exceed the window or the screen time, and each value
 * matches the oracle.
 */
class UsagePropertyTest {
    private val unknownApp = "com.example.unknown"
    private val packages = listOf(INSTAGRAM, CHAT, MAPS, unknownApp)
    private val categoryOf = mapOf(INSTAGRAM to "SOCIAL", CHAT to "SOCIAL", MAPS to "MAPS")
    private val categories = listOf("SOCIAL", "MAPS", "UNDEFINED", "VIDEO")
    private val sinceTimes = listOf("20:15", "21:00", "22:00", "22:30", "22:59")

    /** How often each kind of case occurred, so the property cannot pass on trivial streams only. */
    private val seen = mutableMapOf<String, Int>()

    private fun note(case: String, occurred: Boolean) {
        if (occurred) seen[case] = (seen[case] ?: 0) + 1
    }

    @Test
    fun `usage features over random event streams match a grid oracle`() = runTest {
        for (seed in 1..SEEDS) check(seed)

        val cases =
            listOf("partial screen", "app minutes", "several opens", "category union", "foreground app", "live-only app", "shutdown")
        for (case in cases) assertWithMessage(case).that(seen[case] ?: 0).isAtLeast(MIN_CASES)
    }

    private suspend fun check(seed: Int) {
        val rng = Random(seed)
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.clock.advanceBy((rng.nextInt(0, 240) * CELL_MS).milliseconds)
        val t = f.now
        val since = sinceTimes[rng.nextInt(sinceTimes.size)]
        val windows = listOf(t - 60.minutes, f.local("2026-10-01T$since:00"))
        val earliest = windows.min()
        val events = randomEvents(rng, earliest, t, avoid = windows.toSet())
        f.inputs.usage.rows += events
        categoryOf.forEach { (p, c) -> f.inputs.usage.categories[p] = c }
        val liveApp = (packages + null).random(rng)
        f.inputs.live.state = f.inputs.live.state.copy(interactive = rng.nextBoolean(), foregroundApp = liveApp)

        val last60 = Oracle(events, windows[0], t, f.inputs.live.state.interactive, liveApp)
        val sinceWindow = Oracle(events, windows[1], t, f.inputs.live.state.interactive, liveApp)
        val refs = refs(since)
        val snapshot = f.engine.resolve(refs.toSet(), t)
        val message = "seed $seed, since $since"
        val social = listOf(INSTAGRAM, CHAT).maxOf { last60.appMinutes(it) }
        note("partial screen", last60.screenMinutes() in 1 until last60.windowMinutes())
        note("app minutes", packages.any { last60.appMinutes(it) > 0 })
        note("several opens", packages.any { last60.opens(it) > 1 })
        note("category union", last60.categoryMinutes("SOCIAL") > social)
        note("foreground app", last60.foregroundApp() is FeatureScalar.PackageValue)
        note("live-only app", liveApp != null && liveApp !in last60.withEvents && last60.appMinutes(liveApp) > 0)
        note("shutdown", events.any { it.kind == UsageEventKind.DEVICE_SHUTDOWN && it.at >= windows[0] })

        assertMinutes(snapshot, FeatureRef("screen_minutes_last_60m"), last60.screenMinutes(), message)
        assertMinutes(snapshot, FeatureRef("screen_minutes_since", mapOf("since" to since)), sinceWindow.screenMinutes(), message)
        for (p in packages) {
            assertMinutes(snapshot, FeatureRef("app_minutes_last_60m", mapOf("package" to p)), last60.appMinutes(p), "$message $p")
            assertMinutes(snapshot, sinceRef("app_minutes_since", since, "package" to p), sinceWindow.appMinutes(p), "$message $p")
            assertMinutes(snapshot, FeatureRef("app_opens_last_60m", mapOf("package" to p)), last60.opens(p), "$message $p opens")
        }
        for ((oracle, ref) in listOf(last60 to null, sinceWindow to since)) {
            for (c in categories) {
                val categoryRef = if (ref == null) {
                    FeatureRef("app_category_minutes_last_60m", mapOf("category" to c))
                } else {
                    sinceRef("app_category_minutes_since", ref, "category" to c)
                }
                val minutes = oracle.categoryMinutes(c)
                assertMinutes(snapshot, categoryRef, minutes, "$message $c")
                assertWithMessage("$message $c within the window").that(minutes).isAtMost(oracle.windowMinutes())
                assertWithMessage("$message $c within the screen time").that(minutes).isAtMost(oracle.screenMinutes())
                val members = packages.filter { (categoryOf[it] ?: "UNDEFINED") == c }
                assertWithMessage("$message $c covers its apps").that(minutes).isAtLeast(members.maxOfOrNull { oracle.appMinutes(it) } ?: 0)
            }
        }
        val app = snapshot[FeatureRef("foreground_app")]
        assertWithMessage("$message foreground_app").that((app as? FeatureValue.Known)?.value).isEqualTo(last60.foregroundApp())
    }

    private fun refs(since: String): List<FeatureRef> = buildList {
        add(FeatureRef("screen_minutes_last_60m"))
        add(FeatureRef("screen_minutes_since", mapOf("since" to since)))
        add(FeatureRef("foreground_app"))
        for (p in packages) {
            add(FeatureRef("app_minutes_last_60m", mapOf("package" to p)))
            add(sinceRef("app_minutes_since", since, "package" to p))
            add(FeatureRef("app_opens_last_60m", mapOf("package" to p)))
        }
        for (c in categories) {
            add(FeatureRef("app_category_minutes_last_60m", mapOf("category" to c)))
            add(sinceRef("app_category_minutes_since", since, "category" to c))
        }
    }

    private fun sinceRef(id: String, since: String, arg: Pair<String, String>) = FeatureRef(id, mapOf("since" to since, arg))

    private fun assertMinutes(snapshot: FeatureSnapshot, ref: FeatureRef, expected: Long, message: String) {
        val value = snapshot[ref]
        assertWithMessage("$message ${ref.key}").that(value).isInstanceOf(FeatureValue.Known::class.java)
        assertWithMessage("$message ${ref.key}").that((value as FeatureValue.Known).value).isEqualTo(FeatureScalar.IntValue(expected))
    }

    /** Up to 60 events on distinct grid instants strictly inside `(from, t)`, never on a window start. */
    private fun randomEvents(rng: Random, from: Instant, t: Instant, avoid: Set<Instant>): List<UsageEvent> {
        val cells = (t - from).inWholeMilliseconds / CELL_MS
        val count = rng.nextInt(0, 61)
        val instants = (0 until count).map { from + (rng.nextLong(1, cells) * CELL_MS).milliseconds }
            .filter { it !in avoid }
            .distinct()
            .sorted()
        return instants.map { at ->
            val roll = rng.nextInt(100)
            when {
                roll < 15 -> UsageEvent(
                    at,
                    if (rng.nextBoolean()) UsageEventKind.SCREEN_INTERACTIVE else UsageEventKind.SCREEN_NON_INTERACTIVE,
                )

                roll < 18 -> UsageEvent(at, UsageEventKind.DEVICE_SHUTDOWN)

                roll < 24 -> UsageEvent(
                    at,
                    listOf(UsageEventKind.KEYGUARD_SHOWN, UsageEventKind.KEYGUARD_HIDDEN, UsageEventKind.DEVICE_STARTUP).random(rng),
                )

                else -> {
                    val kind = listOf(
                        UsageEventKind.ACTIVITY_RESUMED,
                        UsageEventKind.ACTIVITY_RESUMED,
                        UsageEventKind.ACTIVITY_PAUSED,
                        UsageEventKind.ACTIVITY_STOPPED,
                    )
                        .random(rng)
                    UsageEvent(at, kind, packages.random(rng), if (rng.nextBoolean()) "A" else "B")
                }
            }
        }
    }

    /**
     * Replays [all] over the window `[w0, t)` cell by cell: a stream's state at `w0` is the opposite of its first event
     * in the window (else the live state), events set states, a shutdown switches everything off, per package the
     * union of its activities with gaps of at most 2,000 ms (8 cells) closed.
     */
    private class Oracle(
        all: List<UsageEvent>,
        private val w0: Instant,
        t: Instant,
        liveInteractive: Boolean,
        private val liveApp: String?,
    ) {
        private val cells = ((t - w0).inWholeMilliseconds / CELL_MS).toInt()
        private val events = all.filter { it.at >= w0 && it.at < t }.sortedBy { it.at }
        private val screen = screenCells(liveInteractive)
        val withEvents = events.filter { it.packageName != null }.map { it.packageName.orEmpty() }.toSet()
        private val foreground: Map<String, BooleanArray> = buildMap {
            for (p in withEvents) put(p, closeGaps(packageCells(p)))
            if (liveApp != null && liveApp !in withEvents) put(liveApp, liveCells())
        }

        fun windowMinutes(): Long = minutes(cells)

        fun screenMinutes(): Long = minutes(screen.count { it })

        fun appMinutes(p: String): Long = minutes(foreground[p]?.let { fg -> (0 until cells).count { fg[it] && screen[it] } } ?: 0)

        fun opens(p: String): Long {
            if (p !in withEvents) return 0
            val fg = checkNotNull(foreground[p])
            return (1 until cells).count { fg[it] && !fg[it - 1] }.toLong()
        }

        fun categoryMinutes(c: String): Long {
            val members = foreground.filterKeys { (CATEGORY[it] ?: "UNDEFINED") == c }.values
            return minutes((0 until cells).count { i -> screen[i] && members.any { it[i] } })
        }

        fun foregroundApp(): FeatureScalar {
            val last = cells - 1
            if (!screen[last]) return FeatureScalar.NoPackage
            val open = withEvents.filter { checkNotNull(foreground[it])[last] }
            val best = open.sortedWith(
                compareByDescending<String> {
                    runStart(checkNotNull(foreground[it]), last)
                }.thenBy { it },
            ).firstOrNull()
            if (best != null) return FeatureScalar.PackageValue(best)
            val live = liveApp?.takeIf { it !in withEvents && checkNotNull(foreground[it])[last] }
            return if (live != null) FeatureScalar.PackageValue(live) else FeatureScalar.NoPackage
        }

        private fun runStart(cellsOn: BooleanArray, end: Int): Int {
            var i = end
            while (i > 0 && cellsOn[i - 1]) i--
            return i
        }

        private fun cellOf(at: Instant): Int = ((at - w0).inWholeMilliseconds / CELL_MS).toInt()

        private fun screenCells(liveInteractive: Boolean): BooleanArray {
            val first = events.firstOrNull {
                it.kind == UsageEventKind.SCREEN_INTERACTIVE ||
                    it.kind == UsageEventKind.SCREEN_NON_INTERACTIVE
            }
            val initial = if (first != null) first.kind != UsageEventKind.SCREEN_INTERACTIVE else liveInteractive
            return replay(initial) { e ->
                when (e.kind) {
                    UsageEventKind.SCREEN_INTERACTIVE -> true
                    UsageEventKind.SCREEN_NON_INTERACTIVE, UsageEventKind.DEVICE_SHUTDOWN -> false
                    else -> null
                }
            }
        }

        private fun packageCells(p: String): BooleanArray {
            val union = BooleanArray(cells)
            for (className in events.filter { it.packageName == p }.map { it.className }.distinct()) {
                val stream = events.filter { it.packageName == p && it.className == className }
                val initial = stream.first().kind != UsageEventKind.ACTIVITY_RESUMED
                val on = replay(initial) { e ->
                    when {
                        e.kind == UsageEventKind.DEVICE_SHUTDOWN -> false
                        e.packageName != p || e.className != className -> null
                        else -> e.kind == UsageEventKind.ACTIVITY_RESUMED
                    }
                }
                for (i in 0 until cells) union[i] = union[i] || on[i]
            }
            return union
        }

        /** The live foreground app without events: on from `w0` until a shutdown. */
        private fun liveCells(): BooleanArray = replay(true) { e -> if (e.kind == UsageEventKind.DEVICE_SHUTDOWN) false else null }

        private fun replay(initial: Boolean, target: (UsageEvent) -> Boolean?): BooleanArray {
            val out = BooleanArray(cells)
            var state = initial
            var next = 0
            for (i in 0 until cells) {
                while (next < events.size && cellOf(events[next].at) <= i) {
                    target(events[next])?.let { state = it }
                    next++
                }
                out[i] = state
            }
            return out
        }

        private fun closeGaps(on: BooleanArray): BooleanArray {
            val out = on.copyOf()
            var lastOn = -1
            for (i in 0 until cells) {
                if (!on[i]) continue
                if (lastOn >= 0 && i - lastOn - 1 in 1..MERGE_CELLS) for (j in lastOn + 1 until i) out[j] = true
                lastOn = i
            }
            return out
        }

        private fun minutes(count: Int): Long = count * CELL_MS / MS_PER_MINUTE
    }

    private companion object {
        const val SEEDS = 300
        const val MIN_CASES = 10
        const val CELL_MS = 250L
        const val MERGE_CELLS = 8
        const val MS_PER_MINUTE = 60_000L
        val CATEGORY = mapOf(INSTAGRAM to "SOCIAL", CHAT to "SOCIAL", MAPS to "MAPS")
    }
}
