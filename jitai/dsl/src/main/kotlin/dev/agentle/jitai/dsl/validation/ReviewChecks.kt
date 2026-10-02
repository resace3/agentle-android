package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.time.LocalTimeWindow
import dev.agentle.jitai.dsl.analysis.LeafInfo
import dev.agentle.jitai.dsl.analysis.MinuteMask
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.render.ContentParts
import dev.agentle.jitai.dsl.render.FeatureLabels
import dev.agentle.jitai.dsl.render.Phrases
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition

/** Warnings of R10 §11.3 (W02-W08 from the rule and the context; W01 from the normalized definition). */
internal class ReviewChecks(private val sink: IssueSink, private val view: RuleView, private val context: ValidationContext) {
    private val leaves: List<Pair<String, LeafInfo>> = buildList {
        view.conditions?.let { tree -> RuleAnalysis.leaves(tree).forEach { add(view.path(RuleChecks.CONDITIONS) + it.path to it) } }
        view.contextRequirements?.let { tree ->
            RuleAnalysis.leaves(tree).forEach { add(view.path(RuleChecks.CONTEXT_REQUIREMENTS) + it.path to it) }
        }
    }

    fun check() {
        featureAccess()
        remoteData()
        bestEffortEvents()
        if (view.kind == JitaiKind.INTERVENTION) {
            quietHours()
            globalCaps()
            blockedByExisting()
            genericNotificationText()
        }
    }

    /**
     * W10 (integrator correction jitai-correctness-18): placeholders and app names make the text personal, and the
     * posted notification shows a general text unless the user turned on detailed notifications (off by default).
     */
    private fun genericNotificationText() {
        val content = view.content ?: return
        val texts = ContentParts.texts(content)
        val usesPlaceholders = texts.any { it.contains("{{") }
        val labels = appLabels()
        val usesAppLabel = labels.any { label -> label.isNotBlank() && texts.any { it.contains(label, ignoreCase = true) } }
        if (usesPlaceholders || usesAppLabel) add(IssueCode.W10, view.path("content"))
    }

    /** App names this rule refers to: `appLabel` args, labels of installed packages, labels kept in provenance. */
    private fun appLabels(): Set<String> {
        val args = leaves.mapNotNull { (_, info) -> (info.node as? Condition.FeatureLeaf)?.args } +
            listOfNotNull(view.outcome?.proximal?.args, view.outcome?.distal?.args)
        val labels = LinkedHashSet<String>(view.provenanceAppLabels)
        for (map in args) {
            map[ArgRules.APP_LABEL]?.let { labels += it }
            map[ArgRules.PACKAGE]?.let { pkg -> context.apps?.labelOf(pkg)?.let { labels += it } }
        }
        return labels
    }

    /** W02 and W03 per dependency (first leaf of each feature; each access named once). */
    private fun featureAccess() {
        val reportedAccess = HashSet<String>()
        val firstLeafByFeature = LinkedHashMap<String, String>()
        leaves.forEach { (path, info) -> firstLeafByFeature.putIfAbsent(info.featureId, path) }
        for ((featureId, path) in firstLeafByFeature) {
            val definition = RealtimeFeatureCatalog[featureId] ?: continue
            if (!definition.isAvailable) continue
            when (val status = context.featureAccess.statusOf(definition)) {
                is FeatureStatus.Unsupported -> {
                    add(IssueCode.W02, path, "feature" to FeatureLabels.capitalized(FeatureLabels.labelOrId(featureId)))
                }

                is FeatureStatus.NeedsAccess -> if (reportedAccess.add(status.access)) {
                    add(IssueCode.W03, path, "access" to status.access)
                }

                FeatureStatus.Ready -> Unit
            }
        }
    }

    /** W07 once per remote data label (step, sleep, heart rate data). */
    private fun remoteData() {
        val reported = HashSet<String>()
        for ((path, info) in leaves) {
            if (!FeatureLabels.isRemoteHealth(info.featureId)) continue
            val label = FeatureLabels.labelOrId(info.featureId)
            if (reported.add(label)) add(IssueCode.W07, path, "feature" to FeatureLabels.capitalized(label))
        }
    }

    /** W08 for events only a runtime receiver sees (integrator correction, red team lifecycle-battery-07). */
    private fun bestEffortEvents() {
        val trigger = view.trigger as? Trigger.Event ?: return
        trigger.events.forEachIndexed { index, event ->
            if (event.bestEffort) add(IssueCode.W08, view.path("trigger", "events", index), "event" to Phrases.event(event))
        }
    }

    /** W04: RESPECT and every minute of the window (or every `daily_at` time) inside quiet hours. */
    private fun quietHours() {
        if (view.quietHoursPolicy != QuietHoursPolicy.RESPECT) return
        val quiet = context.settings.quietHours ?: return
        val quietMask = quietMask(quiet)
        val inside = when (val trigger = view.trigger) {
            is Trigger.DailyAt -> {
                val minutes = trigger.times.mapNotNull { ClockTime.minuteOfDay(it) }
                minutes.isNotEmpty() && minutes.all { it in quietMask }
            }

            is Trigger.Event, is Trigger.Interval -> windowMask()?.let { !it.isEmpty && it.isSubsetOf(quietMask) } ?: quietMask.isFull

            null -> false
        }
        if (inside) {
            add(IssueCode.W04, view.path("delivery", "quietHoursPolicy"), "start" to quiet.start.toString(), "end" to quiet.end.toString())
        }
    }

    /** W05: a per-rule cap above the global cap. */
    private fun globalCaps() {
        val settings = context.settings
        val day = view.maxPerDay
        if (day != null && day > settings.globalMaxPerDay) {
            add(IssueCode.W05, view.path("maxPerDay"), "n" to settings.globalMaxPerDay.toString(), "period" to "day")
        }
        val week = view.maxPerWeek
        if (week != null && week > settings.globalMaxPerWeek) {
            add(IssueCode.W05, view.path("maxPerWeek"), "n" to settings.globalMaxPerWeek.toString(), "period" to "week")
        }
    }

    /** W06: an effective SUPPRESSION targets this rule's category (or id) in an overlapping window. */
    private fun blockedByExisting() {
        val now = context.clock.now()
        val mine = activeMinutes()
        for (other in context.existingJitais) {
            if (other.kind != JitaiKind.SUPPRESSION || other.id == view.id) continue
            val effective = other.enabled && other.status == JitaiStatus.ACTIVE && (other.expiresAt?.let { it > now } ?: true)
            val targets = other.suppression?.let { view.category in it.categories || (view.id != null && view.id in it.jitaiIds) } ?: false
            if (!effective || !targets) continue
            val theirs = other.activeWindow?.takeIf { it.start != it.end }?.let { MinuteMask.window(it.start, it.end) } ?: MinuteMask.ALL
            if (!(mine and theirs).isEmpty) add(IssueCode.W06, view.path("category"), "name" to other.name)
        }
    }

    private fun activeMinutes(): MinuteMask {
        windowMask()?.let { return it }
        val trigger = view.trigger
        if (trigger is Trigger.DailyAt) {
            var mask = MinuteMask.NONE
            trigger.times.mapNotNull { ClockTime.minuteOfDay(it) }.forEach { mask = mask or MinuteMask.of(it) }
            return mask
        }
        return MinuteMask.ALL
    }

    private fun windowMask(): MinuteMask? {
        val window = view.activeWindow ?: return null
        if (window.start == window.end) return null
        return MinuteMask.window(window.start, window.end)
    }

    private fun add(code: IssueCode, path: String, vararg params: Pair<String, String>) {
        sink.add(code, Stage.S6, path, params.toMap())
    }

    companion object {
        /** Quiet hours as a minute mask; `start == end` means the whole day in [LocalTimeWindow]. */
        fun quietMask(quiet: LocalTimeWindow): MinuteMask = if (quiet.isAllDay) {
            MinuteMask.ALL
        } else {
            val start = quiet.start.hour * MINUTES_PER_HOUR + quiet.start.minute
            MinuteMask.window(start, quiet.end.hour * MINUTES_PER_HOUR + quiet.end.minute)
        }

        /** W01: the same content hash as an existing, non-archived rule other than itself. */
        fun duplicates(sink: IssueSink, definition: JitaiDefinition, hash: String, context: ValidationContext, path: String) {
            val duplicate = context.existingJitais.firstOrNull {
                it.id != definition.id && it.status != JitaiStatus.ARCHIVED && RuleCodec.contentHash(it) == hash
            } ?: return
            sink.add(IssueCode.W01, Stage.S7, path, mapOf("name" to duplicate.name))
        }

        private const val MINUTES_PER_HOUR = 60
    }
}
