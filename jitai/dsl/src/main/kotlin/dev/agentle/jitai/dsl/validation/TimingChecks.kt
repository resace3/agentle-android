package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.analysis.MinuteMask
import dev.agentle.jitai.dsl.analysis.Polarity
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral

/**
 * Time-of-day consistency checks added by the integrator (jitai-correctness-17), on top of R10 §11.2:
 *
 * - E029: `since*` is the latest occurrence of `since` at or before the decision point, up to 24 h back (R10 §10.4).
 *   A `since` inside the active window therefore reads from the previous day before it is reached, so `since` must equal
 *   or precede the window start; for `daily_at`, each time must fall 1 minute to 12 hours after `since`; without a
 *   window or daily times the decision points are unbounded and `since` is rejected.
 * - W09: `local_time gt/gte` an evening time is FALSE after midnight; without a midnight-crossing window the user most
 *   likely meant "late evening" (`local_time_in` covers it).
 */
internal class TimingChecks(private val sink: IssueSink, private val view: RuleView) {
    fun check() {
        val trees = listOfNotNull(
            view.conditions?.let { RuleChecks.CONDITIONS to it },
            view.contextRequirements?.let { RuleChecks.CONTEXT_REQUIREMENTS to it },
        )
        for ((name, tree) in trees) {
            val root = view.path(name)
            for (info in RuleAnalysis.leaves(tree)) {
                val leaf = info.node as? Condition.FeatureLeaf ?: continue
                leaf.args[SINCE]?.let { since -> since(since, "$root${info.path}/args/$SINCE") }
                if (info.polarity == Polarity.POSITIVE) evening(leaf, "$root${info.path}/value")
            }
        }
    }

    private fun since(since: String, path: String) {
        val sinceMinute = ClockTime.minuteOfDay(since) ?: return
        when (val trigger = view.trigger) {
            is Trigger.DailyAt -> {
                val late = trigger.times.firstOrNull { time ->
                    val minute = ClockTime.minuteOfDay(time) ?: return@firstOrNull false
                    Math.floorMod(minute - sinceMinute, ClockTime.MINUTES_PER_DAY) !in 1..MAX_SINCE_LEAD_MINUTES
                }
                if (late != null) sink.add(IssueCode.E029, Stage.S6, path, mapOf("since" to since, "time" to late), variant = 1)
            }

            is Trigger.Event, is Trigger.Interval, null -> window(since, sinceMinute, path)
        }
    }

    private fun window(since: String, sinceMinute: Int, path: String) {
        // A rule without a trigger is only checked here when it is a SUPPRESSION (an INTERVENTION has E050).
        if (view.trigger == null && view.kind != dev.agentle.jitai.dsl.model.JitaiKind.SUPPRESSION) return
        val window = view.activeWindow
        if (window == null) {
            sink.add(IssueCode.E029, Stage.S6, path, mapOf("since" to since), variant = 2)
            return
        }
        val start = ClockTime.minuteOfDay(window.start) ?: return
        val mask = MinuteMask.window(window.start, window.end) ?: return
        if (window.start == window.end) return
        if (sinceMinute != start && sinceMinute in mask) {
            sink.add(IssueCode.E029, Stage.S6, path, mapOf("since" to since, "start" to window.start), variant = 0)
        }
    }

    private fun evening(leaf: Condition.FeatureLeaf, path: String) {
        if (leaf.feature != LOCAL_TIME || (leaf.operator != Operator.GT && leaf.operator != Operator.GTE)) return
        val text = (leaf.literals.first() as? RuleLiteral.Text)?.value ?: return
        val minute = ClockTime.minuteOfDay(text) ?: return
        if (minute < EVENING_START_MINUTE) return
        val window = view.activeWindow
        val crossesMidnight =
            window != null && ClockTime.isValid(window.start) && ClockTime.isValid(window.end) && window.end < window.start
        if (!crossesMidnight) {
            sink.add(IssueCode.W09, Stage.S6, path, mapOf("op" to leaf.operator.wire, "value" to text))
        }
    }

    private companion object {
        const val SINCE = "since"
        const val LOCAL_TIME = "local_time"
        const val MAX_SINCE_LEAD_MINUTES = 12 * 60

        /** 18:00: literals from here on are "evening" times. */
        const val EVENING_START_MINUTE = 18 * 60
    }
}
