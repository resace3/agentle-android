package dev.agentle.jitai.dsl.render

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Inputs of the renderer besides the definition (R10 §13.5): the clock format, the zone for `Ends after`, installed-app
 * labels (package -> label) and the names of other rules a `jitai` arg or suppression target refers to (id -> name).
 */
public data class RenderOptions(
    val use24HourClock: Boolean = false,
    val zone: TimeZone = TimeZone.UTC,
    val appLabels: Map<String, String> = emptyMap(),
    val jitaiNames: Map<String, String> = emptyMap(),
)

/** One text field of the content with its parts marked (integrator correction jitai-correctness-18). */
public data class RenderedText(val path: String, val parts: List<ContentPart>) {
    /** The text as written. */
    val text: String get() = parts.joinToString("") { it.text }

    /** True when the text contains a placeholder or an app name: the posted notification shows a general text. */
    val isPersonal: Boolean get() = parts.any { it.isPersonal }
}

/**
 * The deterministic renderer (R10 §13.5): one English sentence per definition, without AI. It is a pure function of
 * the definition and [RenderOptions], so the sentence the user approves can be stored (`provenance.approvedRendering`)
 * and compared later.
 *
 * Sentence = `{schedule}: {body}. {limits}.` plus `Ends after {date}.` when `expiresAt` is set.
 */
public object RuleRenderer {
    /** The plain-language sentence of [definition]. */
    public fun render(definition: JitaiDefinition, options: RenderOptions = RenderOptions()): String {
        val schedule = schedule(definition, options)
        val sentence = StringBuilder()
        sentence.append(schedule).append(": ").append(body(definition, options)).append('.')
        if (definition.kind == JitaiKind.INTERVENTION) {
            limits(definition)?.let { sentence.append(' ').append(it).append('.') }
        }
        definition.expiresAt?.let { expiresAt ->
            val date = (expiresAt - 1.seconds).toLocalDateTime(options.zone).date
            val month = date.month.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
            sentence.append(" Ends after ").append(month).append(' ').append(date.day).append(", ").append(date.year).append('.')
        }
        return sentence.toString()
    }

    /** A condition tree as a phrase (`screen time in the last 60 minutes is at least 45 min and ...`). */
    public fun condition(tree: Condition, options: RenderOptions = RenderOptions()): String = node(tree, options, nested = false)

    /**
     * Every text of the definition's content with placeholder and app-name parts marked: the review screen shows them,
     * and a posted notification shows a general text instead unless detailed notifications are on.
     */
    public fun content(definition: JitaiDefinition, options: RenderOptions = RenderOptions()): List<RenderedText> {
        val content = definition.content ?: return emptyList()
        val labels = appLabelsOf(definition, options)
        return contentFields(content).map { (path, text) -> RenderedText(path, ContentParts.parse(text, labels)) }
    }

    // ------------------------------------------------------------------ schedule

    private fun schedule(definition: JitaiDefinition, options: RenderOptions): String {
        val window = definition.activeWindow
        val days = window?.days?.takeIf { it.isNotEmpty() }?.let { " on " + Phrases.list(it.map(Phrases::day)) }.orEmpty()
        if (definition.kind == JitaiKind.SUPPRESSION) {
            return (window?.let { "From " + windowText(it, options) } ?: "At any time") + days
        }
        val windowSuffix = window?.let { " from " + windowText(it, options) }.orEmpty()
        return when (val trigger = definition.trigger) {
            is Trigger.Interval -> "Every ${trigger.everyMinutes} minutes$windowSuffix$days"

            is Trigger.DailyAt -> {
                val times = Phrases.list(trigger.times.map { time(it, options) })
                if (days.isEmpty()) "Every day at $times" else "At $times$days"
            }

            is Trigger.Event -> "When " + trigger.events.joinToString(" or ") { Phrases.event(it) } + windowSuffix + days

            null -> "At any time$days"
        }
    }

    private fun windowText(window: ActiveWindow, options: RenderOptions): String =
        "${time(window.start, options)} to ${time(window.end, options)}"

    // ------------------------------------------------------------------ body

    private fun body(definition: JitaiDefinition, options: RenderOptions): String =
        if (definition.kind == JitaiKind.SUPPRESSION) suppressionBody(definition, options) else interventionBody(definition, options)

    private fun interventionBody(definition: JitaiDefinition, options: RenderOptions): String {
        val conditions = definition.conditions?.let { "if " + condition(it, options) }
        val context = definition.contextRequirements?.let { "only while " + condition(it, options) }
        val delivery = delivery(definition)
        return when {
            conditions != null && context != null -> "$conditions, and $context, $delivery"
            conditions != null -> "$conditions, $delivery"
            context != null -> "$context, $delivery"
            else -> delivery
        }
    }

    private fun delivery(definition: JitaiDefinition): String {
        val action = when (definition.delivery.channel) {
            DeliveryChannel.NOTIFICATION, DeliveryChannel.NONE -> "send a notification"
            DeliveryChannel.IMAGE -> "send an image notification"
            DeliveryChannel.VOICE -> "say a reminder out loud"
            DeliveryChannel.VIDEO -> "offer a short video"
        }
        val quiet = if (definition.delivery.quietHoursPolicy == QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE) {
            " (allowed during quiet hours while you are using the phone)"
        } else {
            ""
        }
        return action + quiet
    }

    private fun suppressionBody(definition: JitaiDefinition, options: RenderOptions): String {
        val targets = definition.suppression
        val categories = targets?.categories.orEmpty().map(Phrases::category)
        val names = targets?.jitaiIds.orEmpty().map { "\"${options.jitaiNames[it] ?: "another reminder"}\"" }
        val blocked = buildList {
            if (categories.isNotEmpty()) add(Phrases.list(categories) + " reminders")
            addAll(names)
        }
        val text = StringBuilder("block ").append(Phrases.list(blocked.ifEmpty { listOf("no reminders") }))
        definition.conditions?.let { tree ->
            text.append(" if ").append(condition(tree, options))
            val labels = dataLabels(tree)
            if (labels.isNotEmpty()) text.append(" (also when ").append(Phrases.list(labels)).append(" is unknown)")
        }
        return text.toString()
    }

    private fun dataLabels(tree: Condition): List<String> {
        val labels = LinkedHashSet<String>()
        val stack = ArrayDeque<Condition>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is Condition.AllOf -> node.of.asReversed().forEach(stack::addLast)
                is Condition.AnyOf -> node.of.asReversed().forEach(stack::addLast)
                is Condition.Not -> stack.addLast(node.of)
                is Condition.FeatureLeaf -> FeatureLabels.dataLabel(node.feature)?.let(labels::add)
                is Condition.LocalTimeIn -> Unit
            }
        }
        return labels.toList()
    }

    // ------------------------------------------------------------------ limits

    private fun limits(definition: JitaiDefinition): String? {
        val day = definition.maxPerDay
        val week = definition.maxPerWeek
        val cooldown = definition.cooldownMinutes
        val caps = when {
            day != null && week != null -> "At most $day per day and $week per week"
            day != null -> "At most $day per day"
            week != null -> "At most $week per week"
            else -> null
        }
        val gap = cooldown?.let { "at least ${Phrases.duration(it.toLong())} apart" }
        return when {
            caps != null && gap != null -> "$caps, $gap"
            caps != null -> caps
            gap != null -> gap.replaceFirstChar { it.uppercaseChar() }
            else -> null
        }
    }

    // ------------------------------------------------------------------ conditions

    private fun node(node: Condition, options: RenderOptions, nested: Boolean): String = when (node) {
        is Condition.AllOf -> group(node.of, "and", options, nested)
        is Condition.AnyOf -> group(node.of, "or", options, nested)
        is Condition.Not -> "not (" + node(node.of, options, nested = false) + ")"
        is Condition.LocalTimeIn -> "the time is between ${time(node.start, options)} and ${time(node.end, options)}"
        is Condition.FeatureLeaf -> leaf(node, options) + unknownSuffix(node.onUnknown)
    }

    private fun group(children: List<Condition>, conjunction: String, options: RenderOptions, nested: Boolean): String {
        val text = Phrases.list(children.map { node(it, options, nested = true) }, conjunction)
        return if (nested && children.size >= 2) "($text)" else text
    }

    private fun unknownSuffix(onUnknown: OnUnknown?): String = when (onUnknown) {
        OnUnknown.ASSUME_TRUE -> " (counted as met if unknown)"
        OnUnknown.ASSUME_FALSE -> " (counted as not met if unknown)"
        null -> ""
    }

    private fun leaf(leaf: Condition.FeatureLeaf, options: RenderOptions): String {
        val definition = RealtimeFeatureCatalog[leaf.feature] ?: return "${leaf.feature} ${leaf.operator.wire} " +
            leaf.literals.joinToString(", ") { it.json }
        if (definition.type == FeatureType.BOOL) return boolPredicate(leaf)
        val subject = subject(leaf, options)
        val values = leaf.literals.map { value(definition, it, options) }
        val timeLike = definition.type == FeatureType.LOCAL_TIME || definition.type == FeatureType.NIGHT_TIME
        val predicate = when (leaf.operator) {
            Operator.GT -> if (timeLike) "is after ${values[0]}" else "is more than ${values[0]}"
            Operator.GTE -> if (timeLike) "is ${values[0]} or later" else "is at least ${values[0]}"
            Operator.LT -> if (timeLike) "is before ${values[0]}" else "is less than ${values[0]}"
            Operator.LTE -> if (timeLike) "is ${values[0]} or earlier" else "is at most ${values[0]}"
            Operator.EQ -> "is ${values[0]}"
            Operator.NEQ -> "is not ${values[0]}"
            Operator.BETWEEN -> "is between ${values[0]} and ${values.getOrElse(1) { values[0] }}"
            Operator.IN -> "is " + Phrases.list(values, "or")
        }
        return "$subject $predicate"
    }

    /** BOOL leaves are whole predicates: `eq true` and `neq false` positive, otherwise negative. */
    private fun boolPredicate(leaf: Condition.FeatureLeaf): String {
        val literal = (leaf.literals.firstOrNull() as? RuleLiteral.Bool)?.value ?: true
        val positive = (leaf.operator == Operator.EQ) == literal
        val (yes, no) = BOOL_PREDICATES[leaf.feature] ?: ("${leaf.feature} is true" to "${leaf.feature} is false")
        return if (positive) yes else no
    }

    private fun subject(leaf: Condition.FeatureLeaf, options: RenderOptions): String {
        val since = leaf.args["since"]?.let { time(it, options) }.orEmpty()
        val app = leaf.args["package"]?.let { appLabel(it, options) } ?: leaf.args["appLabel"] ?: "the app"
        val category = leaf.args["category"]?.let { APP_CATEGORY_LABELS[it] ?: it.lowercase(Locale.ROOT) }.orEmpty()
        val jitai = leaf.args["jitai"]?.let { jitaiText(it, options) } ?: "this reminder"
        return when (leaf.feature) {
            "local_time" -> "the time"
            "day_of_week", "day_type" -> "the day"
            "engine_day_of_week" -> "the night's day"
            "battery_pct" -> "the battery level"
            "screen_minutes_last_60m" -> "screen time in the last 60 minutes"
            "screen_minutes_since" -> "screen time since $since"
            "app_minutes_last_60m" -> "time in $app in the last 60 minutes"
            "app_minutes_since" -> "time in $app since $since"
            "app_category_minutes_last_60m" -> "time in $category apps in the last 60 minutes"
            "app_category_minutes_since" -> "time in $category apps since $since"
            "app_opens_last_60m" -> "times $app was opened in the last 60 minutes"
            "foreground_app" -> "the app on screen"
            "notifications_last_60m" -> "notifications in the last 60 minutes"
            "location_class" -> "your location"
            "activity_state" -> "your current activity"
            "activity_level_last_30m" -> "your activity in the last 30 minutes"
            "steps_today" -> "your step count today"
            "steps_last_60m" -> "your steps in the last 60 minutes"
            "steps_last_30m" -> "your steps in the last 30 minutes"
            "sleep_minutes_last_night" -> "your sleep last night"
            "bedtime_last_night" -> "your bedtime last night"
            "wake_time_today" -> "your wake-up time today"
            "resting_hr_today" -> "your resting heart rate today"
            "resting_hr_delta_vs_28d" -> "your resting heart rate today compared with your usual"
            "minutes_since_last_delivery" -> "the time since $jitai was last sent"
            "deliveries_today" -> "$jitai sent today"
            "deliveries_last_7d" -> "$jitai sent in the last 7 days"
            "last_response" -> "your last response to $jitai"
            "consecutive_ignored" -> "$jitai ignored in a row"
            else -> leaf.feature
        }
    }

    private fun value(definition: FeatureDefinition, literal: RuleLiteral, options: RenderOptions): String {
        val text = (literal as? RuleLiteral.Text)?.value
        val number = (literal as? RuleLiteral.NumberToken)?.token?.toLongOrNull()
        return when (definition.type) {
            FeatureType.LOCAL_TIME, FeatureType.NIGHT_TIME -> text?.let { time(it, options) } ?: literal.json
            FeatureType.DAY_OF_WEEK -> text?.let { DAY_LABELS[it] } ?: literal.json
            FeatureType.ENUM -> text?.let { ENUM_LABELS[definition.id]?.get(it) ?: it.lowercase(Locale.ROOT) } ?: literal.json
            FeatureType.PACKAGE -> text?.let { if (it == NO_APP) "no app" else appLabel(it, options) } ?: literal.json
            FeatureType.BOOL -> literal.json
            FeatureType.INT -> number?.let { intValue(definition, it) } ?: literal.json
        }
    }

    private fun intValue(definition: FeatureDefinition, n: Long): String = when (definition.id) {
        "battery_pct" -> "$n%"
        "steps_today", "steps_last_60m", "steps_last_30m" -> "${Phrases.grouped(n)} steps"
        "sleep_minutes_last_night" -> Phrases.duration(n)
        "resting_hr_today" -> "$n bpm"
        "resting_hr_delta_vs_28d" -> if (n > 0) "+$n bpm" else "$n bpm"
        else -> if (definition.unit == "min") "$n min" else n.toString()
    }

    private fun jitaiText(ref: String, options: RenderOptions): String = when {
        ref == "self" -> "this reminder"

        ref == "any" -> "any reminder"

        ref.startsWith(CATEGORY_PREFIX) -> {
            val category = JitaiCategory.entries.firstOrNull { it.name == ref.removePrefix(CATEGORY_PREFIX) }
            category?.let { Phrases.category(it) + " reminders" } ?: ref
        }

        else -> "\"${options.jitaiNames[ref] ?: "another reminder"}\""
    }

    private fun appLabel(pkg: String, options: RenderOptions): String = options.appLabels[pkg] ?: pkg

    private fun time(text: String, options: RenderOptions): String =
        ClockTime.minuteOfDay(text)?.let { Phrases.time(it, options.use24HourClock) } ?: text

    /** App names a content text may contain: labels of the rule's packages and the labels kept in provenance. */
    private fun appLabelsOf(definition: JitaiDefinition, options: RenderOptions): Set<String> {
        val labels = LinkedHashSet<String>()
        definition.provenance?.appLabels?.values?.let(labels::addAll)
        val packages = buildList {
            listOfNotNull(definition.conditions, definition.contextRequirements).forEach { tree -> collectPackages(tree, this) }
            definition.outcome?.proximal?.args?.get("package")?.let(::add)
            definition.outcome?.distal?.args?.get("package")?.let(::add)
        }
        packages.forEach { pkg -> options.appLabels[pkg]?.let(labels::add) }
        return labels
    }

    private fun collectPackages(tree: Condition, out: MutableList<String>) {
        val stack = ArrayDeque<Condition>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is Condition.AllOf -> node.of.forEach(stack::addLast)

                is Condition.AnyOf -> node.of.forEach(stack::addLast)

                is Condition.Not -> stack.addLast(node.of)

                is Condition.FeatureLeaf -> {
                    node.args["package"]?.let(out::add)
                    if (node.feature == "foreground_app") {
                        node.literals.mapNotNull { (it as? RuleLiteral.Text)?.value }.forEach(out::add)
                    }
                }

                is Condition.LocalTimeIn -> Unit
            }
        }
    }

    private fun contentFields(content: ContentStrategy): List<Pair<String, String>> = when (content) {
        is ContentStrategy.Static -> listOf("/content/title" to content.title, "/content/body" to content.body)

        is ContentStrategy.Template -> listOf("/content/title" to content.title, "/content/body" to content.body)

        is ContentStrategy.Variants -> content.items.flatMapIndexed { index, item ->
            listOf("/content/items/$index/title" to item.title, "/content/items/$index/body" to item.body)
        }

        is ContentStrategy.AiText -> listOf(
            "/content/fallback/title" to content.fallback.title,
            "/content/fallback/body" to content.fallback.body,
        )

        is ContentStrategy.LocalMedia -> listOf(
            "/content/caption/title" to content.caption.title,
            "/content/caption/body" to content.caption.body,
        )
    }

    private const val CATEGORY_PREFIX = "category:"
    private const val NO_APP = "NONE"

    private val BOOL_PREDICATES: Map<String, Pair<String, String>> = mapOf(
        "charging" to ("the phone is charging" to "the phone is not charging"),
        "device_interactive" to ("you are using the phone" to "you are not using the phone"),
        "dnd_active" to ("Do Not Disturb is on" to "Do Not Disturb is off"),
        "in_call" to ("you are in a call" to "you are not in a call"),
        "headphones_connected" to ("headphones are connected" to "no headphones are connected"),
    )

    private val DAY_LABELS: Map<String, String> = mapOf(
        "MON" to "Monday",
        "TUE" to "Tuesday",
        "WED" to "Wednesday",
        "THU" to "Thursday",
        "FRI" to "Friday",
        "SAT" to "Saturday",
        "SUN" to "Sunday",
    )

    private val ENUM_LABELS: Map<String, Map<String, String>> = mapOf(
        "day_type" to mapOf("WEEKDAY" to "a weekday", "WEEKEND" to "a weekend day"),
        "location_class" to mapOf("HOME" to "Home", "WORK" to "Work", "OTHER" to "somewhere else"),
        "activity_state" to mapOf(
            "STILL" to "still",
            "WALKING" to "walking",
            "RUNNING" to "running",
            "ON_BICYCLE" to "cycling",
            "IN_VEHICLE" to "in a vehicle",
        ),
        "activity_level_last_30m" to mapOf(
            "SEDENTARY" to "sedentary",
            "LIGHT" to "light",
            "MODERATE_OR_VIGOROUS" to "moderate or vigorous",
        ),
        "last_response" to mapOf(
            "NONE" to "none",
            "OPENED" to "opened",
            "DISMISSED" to "dismissed",
            "SNOOZED" to "snoozed",
            "HELPFUL" to "helpful",
            "NOT_HELPFUL" to "not helpful",
            "IGNORED" to "ignored",
        ),
    )

    private val APP_CATEGORY_LABELS: Map<String, String> = mapOf(
        "SOCIAL" to "social",
        "VIDEO" to "video",
        "GAME" to "game",
        "AUDIO" to "audio",
        "NEWS" to "news",
        "IMAGE" to "photo",
        "MAPS" to "maps",
        "PRODUCTIVITY" to "productivity",
        "ACCESSIBILITY" to "accessibility",
        "UNDEFINED" to "uncategorized",
    )
}
