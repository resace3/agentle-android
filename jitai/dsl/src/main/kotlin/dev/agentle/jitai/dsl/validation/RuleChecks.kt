package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureArg
import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.jitai.dsl.analysis.MinuteMask
import dev.agentle.jitai.dsl.codec.JsonPointer
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.model.wireType
import dev.agentle.jitai.dsl.rule.ClockTime

/**
 * The S6 semantic checks of one rule (R10 §11.2, plus C02, C03 and C05 of §11.3): kind and structure, trigger and
 * window, frequency and lifetime, delivery, outcome, suppression targets, and the condition trees and text through
 * [ConditionChecks] and [ContentChecks].
 */
internal class RuleChecks(
    private val sink: IssueSink,
    private val view: RuleView,
    private val origin: RuleOrigin,
    private val context: ValidationContext,
    private val scope: CheckScope = CheckScope.of(context),
) {
    private val limits = RuleLimits.of(origin)
    private val existing: Map<String, JitaiDefinition> = context.existingJitais.associateBy { it.id }
    private val knownIds: Set<String> = existing.keys + listOfNotNull(view.id)
    private val isKnownId: (String) -> Boolean = if (scope.existingRulesKnown) {
        { id -> id in knownIds }
    } else {
        { id -> id == view.id || CheckScope.UUID.matches(id) }
    }
    private val isIntervention = view.kind == JitaiKind.INTERVENTION

    fun check() {
        kind()
        trigger()
        window()
        frequency()
        delivery()
        outcome()
        suppressionTargets()
        experiment()
        expiry()
        val conditionChecks = ConditionChecks(sink, view, limits, isKnownId)
        view.conditions?.let { conditionChecks.check(it, CONDITIONS) }
        view.contextRequirements?.let { conditionChecks.check(it, CONTEXT_REQUIREMENTS) }
        ContentChecks(sink, view, limits, scope.mediaLibrary).check()
        TimingChecks(sink, view).check()
        confirmItems()
    }

    // ------------------------------------------------------------------ kind and structure (E050-E057, E070)

    private fun kind() {
        if (isIntervention) intervention() else suppression()
    }

    private fun intervention() {
        if (view.trigger == null) add(IssueCode.E050, view.path("trigger"))
        if (view.channel == DeliveryChannel.NONE) {
            add(IssueCode.E054, view.path("delivery", "channel"), "field" to "a delivery channel other than NONE")
        }
        if (view.content == null) add(IssueCode.E054, view.path("content"), "field" to "content")
        if (view.hasSuppression) add(IssueCode.E054, view.path("suppression"), "field" to "suppression set to null")
        if (view.outcome == null) add(IssueCode.E070, view.path("outcome"))
        if (view.cooldownMinutes == null) add(IssueCode.E040, view.path("cooldownMinutes"), "field" to "cooldownMinutes")
        if (view.maxPerDay == null) add(IssueCode.E040, view.path("maxPerDay"), "field" to "maxPerDay")
        if (view.maxPerWeek == null) add(IssueCode.E040, view.path("maxPerWeek"), "field" to "maxPerWeek")
    }

    private fun suppression() {
        if (view.trigger != null) add(IssueCode.E051, view.path("trigger"))
        if (view.suppressionCategories.isEmpty() && view.suppressionJitaiIds.isEmpty()) {
            val path = if (view.hasSuppression) view.path("suppression", "categories") else view.path("suppression")
            add(IssueCode.E052, path)
        }
        if (view.channel != DeliveryChannel.NONE) {
            add(IssueCode.E053, view.path("delivery", "channel"), "field" to "channel ${view.channel.name}", "expected" to "NONE")
        }
        val notNull = listOf(
            "content" to (view.content != null),
            "outcome" to (view.outcome != null),
            "snooze" to (view.snooze != null),
            "cooldownMinutes" to (view.cooldownMinutes != null),
            "maxPerDay" to (view.maxPerDay != null),
            "maxPerWeek" to (view.maxPerWeek != null),
            // Not in R10's E053 list: a SUPPRESSION never evaluates contextRequirements (R10 §6.5), so a non-null
            // tree would be shown to the user but never used.
            CONTEXT_REQUIREMENTS to (view.contextRequirements != null),
        )
        notNull.filter { it.second }.forEach { (field, _) ->
            add(IssueCode.E053, view.path(field), "field" to field, "expected" to "null")
        }
        if (view.conditions == null && view.activeWindow == null) add(IssueCode.E057, view.path(CONDITIONS))
    }

    // ------------------------------------------------------------------ trigger and window (E028, E030-E039, E055)

    private fun trigger() {
        when (val trigger = view.trigger) {
            is Trigger.Event -> eventTrigger(trigger)

            is Trigger.Interval -> {
                val n = trigger.everyMinutes
                val path = view.path("trigger", "everyMinutes")
                if (n !in limits.minIntervalMinutes..RuleLimits.MAX_INTERVAL_MINUTES) {
                    add(IssueCode.E033, path, "min" to limits.minIntervalMinutes.toString(), "origin" to limits.label, "n" to n.toString())
                }
                if (n % RuleLimits.INTERVAL_STEP_MINUTES != 0) add(IssueCode.E034, path, "n" to n.toString())
            }

            is Trigger.DailyAt -> dailyTrigger(trigger)

            null -> Unit
        }
        val trigger = view.trigger
        if (origin == RuleOrigin.AI && (trigger is Trigger.Event || trigger is Trigger.Interval)) {
            val type = trigger.wireType
            if (view.activeWindow == null) add(IssueCode.E037, view.path("activeWindow"), "triggerType" to type)
            if (view.conditions == null) add(IssueCode.E055, view.path(CONDITIONS), "triggerType" to type)
        }
    }

    private fun eventTrigger(trigger: Trigger.Event) {
        val events = trigger.events
        if (events.size !in 1..RuleLimits.MAX_EVENTS || events.toSet().size != events.size) {
            add(IssueCode.E031, view.path("trigger", "events"))
        }
        events.forEachIndexed { index, event ->
            val path = view.path("trigger", "events", index)
            val availability = event.availability
            if (availability is FeatureAvailability.Unavailable) {
                add(IssueCode.E028, path, "kind" to "Trigger event", "name" to event.name, "capability" to availability.capabilityId)
            }
        }
        if (trigger.debounceSeconds !in 0..RuleLimits.MAX_DEBOUNCE_SECONDS) {
            add(IssueCode.E032, view.path("trigger", "debounceSeconds"), "n" to trigger.debounceSeconds.toString())
        }
    }

    private fun dailyTrigger(trigger: Trigger.DailyAt) {
        val times = trigger.times
        if (times.size !in 1..RuleLimits.MAX_DAILY_TIMES || times.toSet().size != times.size) {
            add(IssueCode.E035, view.path("trigger", "times"))
        }
        val window = validWindow()
        times.forEachIndexed { index, time ->
            val path = view.path("trigger", "times", index)
            val minute = ClockTime.minuteOfDay(time)
            when {
                minute == null -> add(IssueCode.E024, path, "value" to time)

                window != null && minute !in window.second -> add(
                    IssueCode.E039,
                    path,
                    "time" to time,
                    "start" to window.first.start,
                    "end" to window.first.end,
                )
            }
        }
        if (trigger.maxLatenessMinutes !in RuleLimits.LATENESS_MINUTES) {
            add(IssueCode.E036, view.path("trigger", "maxLatenessMinutes"), "n" to trigger.maxLatenessMinutes.toString())
        }
    }

    private fun window() {
        val window = view.activeWindow ?: return
        val startValid = ClockTime.isValid(window.start)
        val endValid = ClockTime.isValid(window.end)
        if (!startValid) add(IssueCode.E024, view.path("activeWindow", "start"), "value" to window.start)
        if (!endValid) add(IssueCode.E024, view.path("activeWindow", "end"), "value" to window.end)
        if (startValid && endValid && window.start == window.end) add(IssueCode.E025, view.path("activeWindow"), "start" to window.start)
        val days = window.days
        if (days != null && (days.isEmpty() || days.toSet().size != days.size)) add(IssueCode.E038, view.path("activeWindow", "days"))
    }

    /** The active window with its minute mask, when both times are valid and differ. */
    private fun validWindow(): Pair<dev.agentle.jitai.dsl.model.ActiveWindow, MinuteMask>? {
        val window = view.activeWindow ?: return null
        if (window.start == window.end) return null
        val mask = MinuteMask.window(window.start, window.end) ?: return null
        return window to mask
    }

    // ------------------------------------------------------------------ frequency and lifetime (E040-E049)

    private fun frequency() {
        if (isIntervention) {
            view.cooldownMinutes?.let { n ->
                if (n !in limits.minCooldownMinutes..RuleLimits.MAX_COOLDOWN_MINUTES) {
                    val min = limits.minCooldownMinutes.toString()
                    add(IssueCode.E041, view.path("cooldownMinutes"), "min" to min, "origin" to limits.label, "n" to n.toString())
                }
            }
            view.maxPerDay?.let { n ->
                if (n !in 1..limits.maxPerDay) {
                    val max = limits.maxPerDay.toString()
                    add(IssueCode.E042, view.path("maxPerDay"), "max" to max, "origin" to limits.label, "n" to n.toString())
                }
            }
            view.maxPerWeek?.let { n ->
                if (n !in 1..limits.maxPerWeek) {
                    val max = limits.maxPerWeek.toString()
                    add(IssueCode.E043, view.path("maxPerWeek"), "max" to max, "origin" to limits.label, "n" to n.toString())
                }
            }
            val day = view.maxPerDay
            val week = view.maxPerWeek
            if (day != null && week != null && week < day) {
                add(IssueCode.E044, view.path("maxPerWeek"), "w" to week.toString(), "d" to day.toString())
            }
            view.snooze?.let { snooze ->
                val options = snooze.options
                if (options.size !in 1..RuleLimits.MAX_SNOOZE_OPTIONS || options.toSet().size != options.size) {
                    add(IssueCode.E049, view.path("snooze", "options"))
                }
            }
        }
        if (view.priority !in 0..limits.maxPriority) {
            val max = limits.maxPriority.toString()
            add(IssueCode.E045, view.path("priority"), "max" to max, "origin" to limits.label, "n" to view.priority.toString())
        }
    }

    private fun expiry() {
        val days = view.expiresInDays
        if (view.createdBy == CreatedBy.AI_DISCOVERED && days == null && !view.hasExpiresAt) {
            add(IssueCode.E046, if (view.isDraft) view.expiresInDaysPath else view.path("expiresAt"))
        }
        if (days != null && days !in RuleLimits.EXPIRES_IN_DAYS) add(IssueCode.E047, view.expiresInDaysPath, "n" to days.toString())
    }

    // ------------------------------------------------------------------ delivery (E048)

    private fun delivery() {
        view.notificationTimeoutMinutes?.let { n ->
            if (n !in RuleLimits.NOTIFICATION_TIMEOUT_MINUTES) {
                add(IssueCode.E048, view.path("delivery", "notificationTimeoutMinutes"), "n" to n.toString())
            }
        }
        view.deliveryDeadlineMinutes?.let { n ->
            if (n !in RuleLimits.DELIVERY_DEADLINE_MINUTES) {
                val path = view.path("delivery", "deliveryDeadlineMinutes")
                sink.add(IssueCode.E048, Stage.S6, path, mapOf("n" to n.toString()), variant = 1)
            }
        }
    }

    private fun experiment() {
        val p = view.experiment?.deliverProbability ?: return
        checkProbability(sink, p, view.path("experiment", "deliverProbability"))
    }

    // ------------------------------------------------------------------ outcome (E071-E073)

    private fun outcome() {
        val outcome = view.outcome ?: return
        if (!isIntervention) return
        metric(outcome.proximal, OutcomeRole.PROXIMAL, view.path("outcome", "proximal"))
        outcome.distal?.let { metric(it, OutcomeRole.DISTAL, view.path("outcome", "distal")) }
    }

    private fun metric(ref: OutcomeMetricRef, role: OutcomeRole, path: String) {
        val metric = ref.metric
        if (metric.role != role) add(IssueCode.E071, "$path/metric", "metric" to metric.name, "role" to role.name.lowercase())
        val takes = metricArgs(metric)
        for (problem in ArgRules.check(takes, ref.args, view.isDraft, origin, isKnownId)) {
            val argPath = JsonPointer.child("$path/args", problem.arg)
            val reason = when (problem.type) {
                ArgProblemType.MISSING -> "requires arg \"${problem.arg}\""
                ArgProblemType.UNEXPECTED -> "does not take arg \"${problem.arg}\""
                else -> "arg \"${problem.arg}\" ${problem.reason}"
            }
            when (problem.type) {
                ArgProblemType.PACKAGE -> add(IssueCode.E081, argPath, "value" to problem.value)
                ArgProblemType.TIME -> add(IssueCode.E024, argPath, "value" to problem.value)
                else -> add(IssueCode.E072, argPath, "metric" to metric.name, "reason" to reason)
            }
        }
        val range = metric.windowMinutes
        val window = ref.windowMinutes
        val ok = if (range == null) window == null else window != null && window in range
        if (!ok) {
            val rangeText = range?.let { "${it.first}-${it.last}" } ?: "null"
            add(IssueCode.E073, "$path/windowMinutes", "metric" to metric.name, "range" to rangeText, "n" to window.toString())
        }
    }

    private fun metricArgs(metric: OutcomeMetric): List<FeatureArg> = when (metric.argKind) {
        FeatureArgKind.PACKAGE -> listOf(FeatureArg(ArgRules.PACKAGE, FeatureArgKind.PACKAGE))
        FeatureArgKind.APP_CATEGORY -> listOf(FeatureArg("category", FeatureArgKind.APP_CATEGORY))
        FeatureArgKind.SINCE -> listOf(FeatureArg("since", FeatureArgKind.SINCE))
        FeatureArgKind.JITAI_REF -> listOf(FeatureArg("jitai", FeatureArgKind.JITAI_REF))
        null -> emptyList()
    }

    // ------------------------------------------------------------------ suppression targets (E056)

    private fun suppressionTargets() {
        val seenCategories = HashSet<String>()
        view.suppressionCategories.forEachIndexed { index, category ->
            if (!seenCategories.add(category.name)) {
                target(view.path("suppression", "categories", index), category.name, "is listed more than once")
            }
        }
        val seenIds = HashSet<String>()
        view.suppressionJitaiIds.forEachIndexed { index, id ->
            val path = view.path("suppression", "jitaiIds", index)
            val target = existing[id]
            val reason = when {
                index >= RuleLimits.MAX_SUPPRESSION_JITAI_IDS -> "is beyond the limit of ${RuleLimits.MAX_SUPPRESSION_JITAI_IDS} targets"
                !seenIds.add(id) -> "is listed more than once"
                id == view.id -> "is this rule itself"
                !scope.existingRulesKnown -> null
                target == null -> "does not exist"
                target.kind == JitaiKind.SUPPRESSION -> "is itself a blocking rule"
                else -> null
            }
            if (reason != null) target(path, id, reason)
        }
    }

    private fun target(path: String, target: String, reason: String) {
        add(IssueCode.E056, path, "target" to target, "reason" to reason)
    }

    // ------------------------------------------------------------------ confirm items (C02, C03)

    private fun confirmItems() {
        if (origin != RuleOrigin.AI || !isIntervention) return
        when (view.channel) {
            DeliveryChannel.VOICE -> sink.add(IssueCode.C02, Stage.S6, view.path("delivery", "channel"), variant = 0)
            DeliveryChannel.VIDEO -> sink.add(IssueCode.C02, Stage.S6, view.path("delivery", "channel"), variant = 1)
            else -> Unit
        }
        if (view.quietHoursPolicy == QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE) {
            val path = view.path("delivery", "quietHoursPolicy")
            val quiet = context.settings.quietHours
            if (quiet == null) {
                sink.add(IssueCode.C03, Stage.S6, path, variant = 1)
            } else {
                sink.add(IssueCode.C03, Stage.S6, path, mapOf("start" to quiet.start.toString(), "end" to quiet.end.toString()))
            }
        }
    }

    private fun add(code: IssueCode, path: String, vararg params: Pair<String, String>) {
        sink.add(code, Stage.S6, path, params.toMap())
    }

    companion object {
        const val CONDITIONS = "conditions"
        const val CONTEXT_REQUIREMENTS = "contextRequirements"

        /** `experiment.deliverProbability` and the discovered `experimentOffer`: null or 0.3-0.7 (R10 §15.3). */
        fun checkProbability(sink: IssueSink, p: Double, path: String) {
            if (!(p >= RuleLimits.MIN_DELIVER_PROBABILITY && p <= RuleLimits.MAX_DELIVER_PROBABILITY)) {
                sink.add(IssueCode.E048, Stage.S6, path, mapOf("range" to "null or 0.3-0.7", "n" to p.toString()), variant = 2)
            }
        }
    }
}
