package dev.agentle.feature.insights.builder

/**
 * Field ids of the builder form. Validator issues are mapped to them by their JSON-pointer path ([fieldOf]) so each
 * message appears on the field it concerns; issues without a field land on [GENERAL].
 */
internal object Fields {
    const val GENERAL = "general"
    const val NAME = "name"
    const val DESCRIPTION = "description"
    const val CATEGORY = "category"
    const val TRIGGER = "trigger"
    const val TIMES = "times"
    const val LATENESS = "lateness"
    const val INTERVAL = "interval"
    const val EVENTS = "events"
    const val DEBOUNCE = "debounce"
    const val WINDOW = "window"
    const val WINDOW_START = "window.start"
    const val WINDOW_END = "window.end"
    const val WINDOW_DAYS = "window.days"
    const val CONDITIONS = "conditions"
    const val REQUIREMENTS = "requirements"
    const val COOLDOWN = "cooldown"
    const val MAX_PER_DAY = "maxPerDay"
    const val MAX_PER_WEEK = "maxPerWeek"
    const val PRIORITY = "priority"
    const val CHANNEL = "channel"
    const val QUIET_HOURS = "quietHours"
    const val TIMEOUT = "timeout"
    const val CONTENT = "content"
    const val CONTENT_TITLE = "content.title"
    const val CONTENT_BODY = "content.body"
    const val CONTENT_GOAL = "content.goal"
    const val CONTENT_ASSET = "content.asset"
    const val CONTENT_VARIANTS = "content.items"
    const val SNOOZE = "snooze"
    const val EXPIRY = "expiry"
    const val OUTCOME = "outcome"
    const val OUTCOME_WINDOW = "outcome.window"
    const val OUTCOME_ARG = "outcome.arg"
    const val OUTCOME_DISTAL = "outcome.distal"
    const val SUPPRESSION = "suppression"

    /** `time.N`: the N-th daily time. */
    fun time(index: Int): String = "$TIMES.$index"

    /** `content.items.N.title` / `.body`. */
    fun variant(index: Int, part: String): String = "$CONTENT_VARIANTS.$index.$part"

    /** A part of a condition row: `cond.<key>.<part>` or `req.<key>.<part>`. */
    fun row(tree: String, key: Int, part: RowPart): String = "$tree.$key.${part.id}"

    const val TREE_CONDITIONS = "cond"
    const val TREE_REQUIREMENTS = "req"
}

/** The parts of a condition row an issue can point at. */
internal enum class RowPart(val id: String) {
    ROW("row"),
    FEATURE("feature"),
    OPERATOR("operator"),
    VALUE("value"),
    MIN("min"),
    MAX("max"),
    VALUES("values"),
    PACKAGE("package"),
    CATEGORY("category"),
    SINCE("since"),
    JITAI("jitai"),
    ON_UNKNOWN("onUnknown"),
    START("start"),
    END("end"),
}

/** The form field of the validator issue at [path] (a JSON pointer into the definition, `""` for the root). */
internal fun fieldOf(path: String, layout: TreeLayout): String {
    val segments = path.split('/').drop(1)
    val head = segments.firstOrNull() ?: return Fields.GENERAL
    return when (head) {
        "name" -> Fields.NAME
        "description" -> Fields.DESCRIPTION
        "category", "kind" -> Fields.CATEGORY
        "trigger" -> triggerField(segments.drop(1))
        "activeWindow" -> windowField(segments.getOrNull(1))
        "conditions" -> rowField(path.removePrefix("/conditions"), layout.conditions, Fields.TREE_CONDITIONS, Fields.CONDITIONS)
        "contextRequirements" ->
            rowField(path.removePrefix("/contextRequirements"), layout.requirements, Fields.TREE_REQUIREMENTS, Fields.REQUIREMENTS)
        "cooldownMinutes" -> Fields.COOLDOWN
        "maxPerDay" -> Fields.MAX_PER_DAY
        "maxPerWeek" -> Fields.MAX_PER_WEEK
        "priority" -> Fields.PRIORITY
        "delivery" -> deliveryField(segments.getOrNull(1))
        "content" -> contentField(segments.drop(1))
        "snooze" -> Fields.SNOOZE
        "expiresAt" -> Fields.EXPIRY
        "outcome" -> outcomeField(segments.drop(1))
        "suppression" -> Fields.SUPPRESSION
        else -> Fields.GENERAL
    }
}

private fun triggerField(rest: List<String>): String = when (rest.firstOrNull()) {
    "times" -> rest.getOrNull(1)?.toIntOrNull()?.let { Fields.time(it) } ?: Fields.TIMES
    "maxLatenessMinutes" -> Fields.LATENESS
    "everyMinutes" -> Fields.INTERVAL
    "events" -> Fields.EVENTS
    "debounceSeconds" -> Fields.DEBOUNCE
    else -> Fields.TRIGGER
}

private fun windowField(part: String?): String = when (part) {
    "start" -> Fields.WINDOW_START
    "end" -> Fields.WINDOW_END
    "days" -> Fields.WINDOW_DAYS
    else -> Fields.WINDOW
}

private fun deliveryField(part: String?): String = when (part) {
    "quietHoursPolicy" -> Fields.QUIET_HOURS
    "notificationTimeoutMinutes" -> Fields.TIMEOUT
    else -> Fields.CHANNEL
}

private fun contentField(rest: List<String>): String = when (rest.firstOrNull()) {
    "title" -> Fields.CONTENT_TITLE
    "body" -> Fields.CONTENT_BODY
    "goal" -> Fields.CONTENT_GOAL
    "assetId" -> Fields.CONTENT_ASSET
    // E069: the content type does not fit the channel; the channel is what the user changes.
    "type" -> Fields.CHANNEL
    "fallback", "caption" -> when (rest.getOrNull(1)) {
        "title" -> Fields.CONTENT_TITLE
        "body" -> Fields.CONTENT_BODY
        else -> Fields.CONTENT
    }
    "items" -> {
        val index = rest.getOrNull(1)?.toIntOrNull()
        val part = rest.getOrNull(2)
        if (index != null && part != null) Fields.variant(index, part) else Fields.CONTENT_VARIANTS
    }
    else -> Fields.CONTENT
}

private fun outcomeField(rest: List<String>): String = when {
    rest.firstOrNull() == "distal" -> Fields.OUTCOME_DISTAL
    rest.getOrNull(1) == "windowMinutes" -> Fields.OUTCOME_WINDOW
    rest.getOrNull(1) == "args" -> Fields.OUTCOME_ARG
    else -> Fields.OUTCOME
}

/** Maps a path inside one condition tree ([rest] starts after the tree name) to a row part or the tree itself. */
private fun rowField(rest: String, tree: TreeRows, treeId: String, treeField: String): String {
    if (tree.preserved) return treeField
    val placement = tree.rows
        .filter { rest == it.path || rest.startsWith(it.path + "/") || (it.path.isEmpty() && rest.isEmpty()) }
        .maxByOrNull { it.path.length }
        ?: return treeField
    // With several rows the root itself (depth, size, an impossible combination) belongs to the whole tree.
    if (rest.isEmpty() && placement.path.isNotEmpty()) return treeField
    var inner = rest.removePrefix(placement.path)
    if (placement.negated) inner = inner.removePrefix("/of")
    return Fields.row(treeId, placement.key, partOf(inner))
}

private fun partOf(inner: String): RowPart {
    val segments = inner.split('/').drop(1)
    return when (segments.firstOrNull()) {
        null -> RowPart.ROW
        "feature" -> RowPart.FEATURE
        "type" -> RowPart.OPERATOR
        "value" -> RowPart.VALUE
        "min" -> RowPart.MIN
        "max" -> RowPart.MAX
        "values" -> RowPart.VALUES
        "onUnknown" -> RowPart.ON_UNKNOWN
        "start" -> RowPart.START
        "end" -> RowPart.END
        "args" -> when (segments.getOrNull(1)) {
            "package", "appLabel" -> RowPart.PACKAGE
            "category" -> RowPart.CATEGORY
            "since" -> RowPart.SINCE
            "jitai" -> RowPart.JITAI
            else -> RowPart.ROW
        }
        else -> RowPart.ROW
    }
}
