package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * An interval `[start, end)` inside a window. [opened] is true when an `ACTIVITY_RESUMED` inside the window started it,
 * false when the stream was already on at the window start (R10 §5.5 step 2).
 */
internal data class Span(val start: Instant, val end: Instant, val opened: Boolean = false)

/** The screen of a window: interactive spans, spans whose state is unknown (around a reboot), the state at `t`. */
internal data class ScreenState(val interactive: List<Span>, val unknown: List<Span>, val atEnd: Boolean?)

/**
 * The interval algebra of R10 §5.5 as pure functions:
 * 1. events in `[W0, t)`;
 * 2. the state of a two-state stream at `W0` is the opposite of the target of its first event in the window, or, with
 *    no event, the live state at `t`; consecutive events asserting the same state are ignored;
 * 3. `ACTIVITY_RESUMED` pairs with the next `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` of the same (package, class);
 *    `DEVICE_SHUTDOWN` closes every open interval; per package, intervals are unioned with gaps of 2,000 ms or less
 *    merged, then intersected with the interactive intervals;
 * 4. clip to the window, sum and floor to whole minutes;
 * 5. category minutes are a union across packages.
 *
 * `DEVICE_SHUTDOWN` only closes intervals; it does not take part in the step-2 inference, because the screen and the
 * foreground activity are already off when the system records it (design).
 */
internal object UsageAlgebra {
    val MERGE_GAP: Duration = 2000.milliseconds

    private data class Assertion(val at: Instant, val on: Boolean, val infers: Boolean)

    private val SCREEN_KINDS = setOf(UsageEventKind.SCREEN_INTERACTIVE, UsageEventKind.SCREEN_NON_INTERACTIVE)
    private val BOUNDARY_KINDS = setOf(UsageEventKind.DEVICE_SHUTDOWN, UsageEventKind.DEVICE_STARTUP)
    private val ACTIVITY_KINDS =
        setOf(UsageEventKind.ACTIVITY_RESUMED, UsageEventKind.ACTIVITY_PAUSED, UsageEventKind.ACTIVITY_STOPPED)

    /** Events of [events] inside [window], in time order (stable for ties). */
    fun inWindow(events: List<UsageEvent>, window: ClosedOpenRange): List<UsageEvent> = events.filter { it.at in window }.sortedBy { it.at }

    /** True if the screen stream has an event in [inside], so its state at `W0` needs no live read. */
    fun hasScreenEvent(inside: List<UsageEvent>): Boolean = inside.any { it.kind in SCREEN_KINDS }

    /** True if the window has a `DEVICE_SHUTDOWN` or `DEVICE_STARTUP`. */
    fun hasBoundary(inside: List<UsageEvent>): Boolean = inside.any { it.kind in BOUNDARY_KINDS }

    /**
     * The screen of the window as three states (reviewer question 2). A reboot breaks the inference: the state at
     * `W0` comes from the first screen event before any boundary, else (no screen event, no boundary) from
     * [liveInteractive], else it is unknown. `[shutdown, startup)` is off (the device is down); after a startup the
     * state is unknown until the next screen event, and so is the time after a shutdown with no startup logged. A
     * startup without a logged shutdown makes the open interactive span before it unknown (the device died at an
     * unknown instant). [ScreenState.atEnd] is the state at `t` when the events know it.
     */
    fun screenState(inside: List<UsageEvent>, window: ClosedOpenRange, liveInteractive: Boolean?): ScreenState {
        val firstScreen = inside.indexOfFirst { it.kind in SCREEN_KINDS }
        val firstBoundary = inside.indexOfFirst { it.kind in BOUNDARY_KINDS }
        var state = when {
            firstScreen >= 0 && (firstBoundary < 0 || firstScreen < firstBoundary) ->
                if (inside[firstScreen].kind == UsageEventKind.SCREEN_INTERACTIVE) Tri.OFF else Tri.ON

            firstBoundary < 0 -> if (liveInteractive == true) Tri.ON else Tri.OFF

            else -> Tri.UNKNOWN
        }
        var since = window.start
        val on = mutableListOf<Span>()
        val unknown = mutableListOf<Span>()
        fun close(at: Instant, spanState: Tri) {
            if (at > since) {
                if (spanState == Tri.ON) on += Span(since, at)
                if (spanState == Tri.UNKNOWN || spanState == Tri.DOWN) unknown += Span(since, at)
            }
            since = at
        }
        for (e in inside) {
            val next = when (e.kind) {
                UsageEventKind.SCREEN_INTERACTIVE -> Tri.ON
                UsageEventKind.SCREEN_NON_INTERACTIVE -> Tri.OFF
                UsageEventKind.DEVICE_SHUTDOWN -> Tri.DOWN
                UsageEventKind.DEVICE_STARTUP -> Tri.UNKNOWN
                else -> continue
            }
            if (next == state && next != Tri.UNKNOWN) continue
            val closedAs = when {
                e.kind == UsageEventKind.DEVICE_STARTUP && state == Tri.ON -> Tri.UNKNOWN
                e.kind == UsageEventKind.DEVICE_STARTUP && state == Tri.DOWN -> Tri.OFF
                e.kind == UsageEventKind.DEVICE_SHUTDOWN && state == Tri.DOWN -> Tri.UNKNOWN
                else -> state
            }
            close(e.at, closedAs)
            state = next
        }
        close(window.end, state)
        val atEnd = when (state) {
            Tri.ON -> true
            Tri.OFF -> false
            else -> null
        }
        return ScreenState(on, unknown, atEnd)
    }

    private enum class Tri { ON, OFF, UNKNOWN, DOWN }

    /** Merged foreground intervals of every package that has an activity event in the window. */
    fun foreground(inside: List<UsageEvent>, window: ClosedOpenRange): Map<String, List<Span>> {
        val shutdowns = shutdowns(inside)
        val pieces = mutableMapOf<String, MutableList<Span>>()
        inside.filter { it.kind in ACTIVITY_KINDS && it.packageName != null }
            .groupBy { it.packageName.orEmpty() to it.className }
            .forEach { (stream, events) ->
                val assertions = events.map { Assertion(it.at, on = it.kind == UsageEventKind.ACTIVITY_RESUMED, infers = true) }
                pieces.getOrPut(stream.first) { mutableListOf() } +=
                    walk((assertions + shutdowns).sortedBy { it.at }, window, liveState = false)
            }
        return pieces.mapValues { (_, spans) -> mergeForeground(spans) }
    }

    /**
     * Intervals of a package without any activity event in the window that is in the foreground at `t` (live read):
     * on since `W0`, closed by a `DEVICE_SHUTDOWN`.
     */
    fun foregroundWithoutEvents(inside: List<UsageEvent>, window: ClosedOpenRange): List<Span> =
        walk(shutdowns(inside), window, liveState = true)

    /** Union of per-package pieces with gaps of [MERGE_GAP] or less closed (activity switches inside one app). */
    fun mergeForeground(spans: List<Span>): List<Span> = mergeSorted(spans, MERGE_GAP)

    /** Plain union of possibly overlapping spans: split-screen minutes count once. */
    fun union(spans: List<Span>): List<Span> = mergeSorted(spans, Duration.ZERO)

    /** Intersection of two sorted, internally disjoint span lists; [a]'s `opened` flag is kept. */
    fun intersect(a: List<Span>, b: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].start, b[j].start)
            val end = minOf(a[i].end, b[j].end)
            if (start < end) out += Span(start, end, a[i].opened)
            if (a[i].end <= b[j].end) i++ else j++
        }
        return out
    }

    /** Whole minutes of [spans] inside [window]: clipped, summed exactly, floored once (44 min 59.999 s is 44). */
    fun wholeMinutes(spans: List<Span>, window: ClosedOpenRange): Long = spans.fold(Duration.ZERO) { acc, span ->
        val start = maxOf(span.start, window.start)
        val end = minOf(span.end, window.end)
        if (end > start) acc + (end - start) else acc
    }.inWholeMinutes

    /** True if one of [spans] is still open at the window end (the evaluation instant). */
    fun openAtEnd(spans: List<Span>, window: ClosedOpenRange): Boolean = spans.any { it.end == window.end && it.start < it.end }

    /** Shutdowns and startups: a reboot ends every activity (a startup implies an unlogged shutdown before it). */
    private fun shutdowns(inside: List<UsageEvent>): List<Assertion> =
        inside.filter { it.kind in BOUNDARY_KINDS }.map { Assertion(it.at, on = false, infers = false) }

    /** Sorts by start (an already-on piece before an opened one at the same instant) and merges gaps up to [gap]. */
    private fun mergeSorted(spans: List<Span>, gap: Duration): List<Span> {
        val sorted = spans.filter { it.end > it.start }.sortedWith(compareBy<Span> { it.start }.thenBy { it.opened })
        val out = mutableListOf<Span>()
        for (span in sorted) {
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end + gap) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, span.end))
            } else {
                out += span
            }
        }
        return out
    }

    /**
     * Walks one two-state stream over [window]. The state at the window start is the opposite of the first inferring
     * assertion, or [liveState] when there is none.
     */
    private fun walk(assertions: List<Assertion>, window: ClosedOpenRange, liveState: Boolean): List<Span> {
        // Inference never crosses a reboot: only events before the first boundary tell the state at W0.
        val first = assertions.takeWhile { it.infers }.firstOrNull()
        var on = if (first != null) !first.on else liveState
        var openedAt = window.start
        var opened = false
        val spans = mutableListOf<Span>()
        for (assertion in assertions) {
            if (assertion.on && !on) {
                on = true
                openedAt = assertion.at
                opened = true
            } else if (!assertion.on && on) {
                on = false
                if (assertion.at > openedAt) spans += Span(openedAt, assertion.at, opened)
            }
        }
        if (on && window.end > openedAt) spans += Span(openedAt, window.end, opened)
        return spans
    }
}
