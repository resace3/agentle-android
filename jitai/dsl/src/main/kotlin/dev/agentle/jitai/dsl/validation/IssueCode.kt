package dev.agentle.jitai.dsl.validation

/** ERROR rejects the rule; CONFIRM blocks saving until the user acts; WARNING is informational (R10 §11.2-11.3). */
public enum class IssueSeverity { ERROR, CONFIRM, WARNING }

/** Pipeline stages of R10 §11.1, in order. Issues are sorted by stage, then path, then code. */
public enum class Stage { S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10 }

/**
 * Every validator code with its fixed English message template(s) (R10 §11.2, §11.3). `{x}` marks a parameter; the
 * messages never contain user data beyond what the rule itself contains, and never an exception's text.
 *
 * Codes not in R10, added by integrator corrections (listed in the module README):
 * - [E028] CAPABILITY_UNAVAILABLE: a feature or trigger event the catalog marks unavailable in this build
 *   (`location_class`, `LOCATION_CLASS_CHANGED`; red team lifecycle-battery-06).
 * - [E029] SINCE_MISALIGNED: a `since` arg that would read from the previous day at some decision points
 *   (jitai-correctness-17).
 * - [W08] BEST_EFFORT_TRIGGER: event triggers that only a runtime receiver sees (lifecycle-battery-07).
 * - [W09] EVENING_TIME_STOPS_AT_MIDNIGHT: `local_time gt/gte` an evening time without a midnight-crossing window
 *   (jitai-correctness-17).
 * - [W10] GENERIC_NOTIFICATION_TEXT: placeholders or app names stay inside the app unless detailed notifications are
 *   on (jitai-correctness-18).
 * Extra templates (variant index > 0) cover stored-definition fields that proposals cannot set ([E048] for
 * `deliveryDeadlineMinutes` and `experiment.deliverProbability`), an assumption path outside `/jitai` ([E092]), the
 * video text of [C02] and quiet hours switched off ([C03]).
 */
public enum class IssueCode(public val title: String, public val severity: IssueSeverity, private vararg val templates: String) {
    // Structure and envelope
    E001("MALFORMED_JSON", IssueSeverity.ERROR, "Response is not one valid JSON object: {detail}."),
    E002("TOO_LARGE", IssueSeverity.ERROR, "Response is {bytes} bytes; the limit is 16384."),
    E003("TOO_DEEP_JSON", IssueSeverity.ERROR, "JSON nesting is deeper than 20 at offset {offset}."),
    E004("UNSUPPORTED_SCHEMA_VERSION", IssueSeverity.ERROR, "schemaVersion must be 1; got {value}."),
    E005("FORBIDDEN_FIELD", IssueSeverity.ERROR, "{path} is set by the app and must not appear in a proposal."),
    E006("UNKNOWN_FIELD", IssueSeverity.ERROR, "Unknown field {path}."),
    E007("MISSING_FIELD", IssueSeverity.ERROR, "Missing required field {path}."),
    E008("WRONG_JSON_TYPE", IssueSeverity.ERROR, "{path} must be {expected}; got {actual}."),
    E009("INVALID_ENUM_VALUE", IssueSeverity.ERROR, "{path} must be one of {allowed}; got \"{value}\"."),
    E090("ENVELOPE_INCONSISTENT", IssueSeverity.ERROR, "status {status} requires {requirement}."),
    E091("QUESTIONS_INVALID", IssueSeverity.ERROR, "questions at {path}: {reason}."),
    E092(
        "ASSUMPTIONS_INVALID",
        IssueSeverity.ERROR,
        "At most 5 assumptions are allowed; got {n}.",
        "Assumption path at {path} must be a JSON pointer into /jitai of at most 200 characters.",
    ),
    E099("INTERNAL", IssueSeverity.ERROR, "Internal validation error in stage {stage}; the proposal was rejected."),

    // Conditions
    E010("UNKNOWN_FEATURE", IssueSeverity.ERROR, "Unknown feature \"{feature}\" at {path}."),
    E011("MISSING_FEATURE_ARG", IssueSeverity.ERROR, "{feature} at {path} requires arg \"{arg}\"."),
    E012("UNEXPECTED_FEATURE_ARG", IssueSeverity.ERROR, "{feature} at {path} does not take arg \"{arg}\"."),
    E013("INVALID_FEATURE_ARG", IssueSeverity.ERROR, "Arg \"{arg}\" of {feature} at {path} is invalid: {reason}."),
    E014(
        "OPERATOR_NOT_ALLOWED",
        IssueSeverity.ERROR,
        "Operator \"{op}\" cannot be used with {feature} ({valueType}) at {path}; allowed: {allowed}.",
    ),
    E015("VALUE_TYPE_MISMATCH", IssueSeverity.ERROR, "Value at {path} must be {expected} for {feature}; got {json}."),
    E016("VALUE_OUT_OF_RANGE", IssueSeverity.ERROR, "Value {value} at {path} is outside {min}..{max} for {feature}."),
    E017("BETWEEN_BOUNDS_INVERTED", IssueSeverity.ERROR, "between at {path}: min {min} is greater than max {max}."),
    E018("IN_LIST_SIZE", IssueSeverity.ERROR, "in at {path} needs 1-{max} values; got {n}."),
    E019("IN_LIST_DUPLICATES", IssueSeverity.ERROR, "in at {path} lists {value} more than once."),
    E020("MAX_DEPTH", IssueSeverity.ERROR, "{tree} is {depth} levels deep; the limit is {max}."),
    E021("MAX_NODES", IssueSeverity.ERROR, "{tree} has {n} nodes; the limit is {max}."),
    E022("EMPTY_GROUP", IssueSeverity.ERROR, "\"{op}\" at {path} has no conditions."),
    E023("GROUP_TOO_WIDE", IssueSeverity.ERROR, "\"{op}\" at {path} has {n} conditions; the limit is {max}."),
    E024("INVALID_TIME_LITERAL", IssueSeverity.ERROR, "\"{value}\" at {path} is not a 24-hour HH:mm time."),
    E025("EMPTY_TIME_WINDOW", IssueSeverity.ERROR, "Time window at {path} starts and ends at {start}."),
    E026(
        "UNSAFE_UNKNOWN_OVERRIDE",
        IssueSeverity.ERROR,
        "onUnknown {value} at {path} would let this rule {effect} when {feature} is unknown.",
    ),
    E027("UNSATISFIABLE", IssueSeverity.ERROR, "Conditions at {path} can never all be true: {explanation}."),
    E028(
        "CAPABILITY_UNAVAILABLE",
        IssueSeverity.ERROR,
        "{kind} \"{name}\" at {path} cannot be used: capability unavailable: {capability}.",
    ),
    E029(
        "SINCE_MISALIGNED",
        IssueSeverity.ERROR,
        "since {since} at {path} must be at or before the active window start {start}; " +
            "inside the window it would read from the previous day.",
        "since {since} at {path} must be 1 minute to 12 hours before the daily time {time}.",
        "since {since} at {path} needs an active window or daily times; " +
            "at other times it would read from the previous day.",
    ),

    // Trigger and active window
    E030("UNKNOWN_EVENT_TYPE", IssueSeverity.ERROR, "Event \"{value}\" at {path} is not supported; supported: {allowed}."),
    E031("EVENT_LIST_INVALID", IssueSeverity.ERROR, "events at {path} must list 1-8 different event types."),
    E032("DEBOUNCE_OUT_OF_RANGE", IssueSeverity.ERROR, "debounceSeconds must be 0-600; got {n}."),
    E033("INTERVAL_OUT_OF_RANGE", IssueSeverity.ERROR, "everyMinutes must be {min}-1440 for {origin} rules; got {n}."),
    E034("INTERVAL_NOT_MULTIPLE_OF_15", IssueSeverity.ERROR, "everyMinutes must be a multiple of 15; got {n}."),
    E035("DAILY_TIMES_INVALID", IssueSeverity.ERROR, "times at {path} must list 1-6 different times."),
    E036("LATENESS_OUT_OF_RANGE", IssueSeverity.ERROR, "maxLatenessMinutes must be 5-120; got {n}."),
    E037("ACTIVE_WINDOW_REQUIRED", IssueSeverity.ERROR, "A {triggerType} trigger needs an activeWindow in AI-made rules."),
    E038("WINDOW_DAYS_INVALID", IssueSeverity.ERROR, "activeWindow.days must be null or 1-7 different days."),
    E039(
        "DAILY_TIME_OUTSIDE_WINDOW",
        IssueSeverity.ERROR,
        "Time {time} is outside the active window {start}-{end}, so it would never run.",
    ),

    // Frequency and lifetime
    E040("LIMIT_REQUIRED", IssueSeverity.ERROR, "{field} is required for a reminder rule."),
    E041("COOLDOWN_OUT_OF_RANGE", IssueSeverity.ERROR, "cooldownMinutes must be {min}-10080 for {origin} rules; got {n}."),
    E042("DAILY_CAP_OUT_OF_RANGE", IssueSeverity.ERROR, "maxPerDay must be 1-{max} for {origin} rules; got {n}."),
    E043("WEEKLY_CAP_OUT_OF_RANGE", IssueSeverity.ERROR, "maxPerWeek must be 1-{max} for {origin} rules; got {n}."),
    E044("WEEKLY_CAP_BELOW_DAILY", IssueSeverity.ERROR, "maxPerWeek ({w}) must be at least maxPerDay ({d})."),
    E045("PRIORITY_OUT_OF_RANGE", IssueSeverity.ERROR, "priority must be 0-{max} for {origin} rules; got {n}."),
    E046("EXPIRY_REQUIRED", IssueSeverity.ERROR, "Discovered rules must end: set expiresInDays (1-90)."),
    E047("EXPIRY_OUT_OF_RANGE", IssueSeverity.ERROR, "expiresInDays must be 1-90; got {n}."),
    E048(
        "DELIVERY_SETTING_OUT_OF_RANGE",
        IssueSeverity.ERROR,
        "notificationTimeoutMinutes must be null or 5-1440; got {n}.",
        "deliveryDeadlineMinutes must be 1-60; got {n}.",
        "experiment.deliverProbability must be {range}; got {n}.",
    ),
    E049("SNOOZE_INVALID", IssueSeverity.ERROR, "snooze.options must list 1-3 different options."),

    // Kind and structure
    E050("TRIGGER_REQUIRED", IssueSeverity.ERROR, "A reminder rule needs a trigger."),
    E051("SUPPRESSION_HAS_TRIGGER", IssueSeverity.ERROR, "A blocking rule must have trigger null."),
    E052("SUPPRESSION_TARGET_REQUIRED", IssueSeverity.ERROR, "A blocking rule must name at least one category to block."),
    E053("SUPPRESSION_HAS_DELIVERY_FIELDS", IssueSeverity.ERROR, "A blocking rule cannot have {field}; set it to {expected}."),
    E054("INTERVENTION_NEEDS_DELIVERY", IssueSeverity.ERROR, "A reminder rule needs {field}."),
    E055("CONDITIONS_REQUIRED", IssueSeverity.ERROR, "A {triggerType} trigger needs conditions in AI-made rules."),
    E056("SUPPRESSION_TARGET_INVALID", IssueSeverity.ERROR, "Block target {target} at {path} {reason}."),
    E057(
        "SUPPRESSION_UNBOUNDED",
        IssueSeverity.ERROR,
        "A blocking rule needs conditions or an active window; to turn a category off, use its switch.",
    ),
    E058("VARIANTS_COUNT", IssueSeverity.ERROR, "variants at {path} needs 2-8 items; got {n}."),

    // Content
    E060("TEXT_LENGTH", IssueSeverity.ERROR, "{path} must be {min}-{max} characters; got {n}."),
    E061("TEXT_CONTAINS_CONTACT", IssueSeverity.ERROR, "{path} contains a link, email address or phone number (\"{match}\")."),
    E062("TEXT_FORBIDDEN_CONTENT", IssueSeverity.ERROR, "{path} contains {category}: \"{match}\"."),
    E063("UNKNOWN_PLACEHOLDER", IssueSeverity.ERROR, "Placeholder \"{name}\" at {path} is not a known feature."),
    E064("PLACEHOLDER_NOT_IN_RULE", IssueSeverity.ERROR, "Placeholder \"{name}\" at {path} must refer to a condition of this rule."),
    E065(
        "PLACEHOLDER_AMBIGUOUS",
        IssueSeverity.ERROR,
        "Placeholder \"{name}\" at {path} must match exactly one condition that has to be true for the rule to fire.",
    ),
    E066("CONTROL_OR_INVISIBLE_CHARS", IssueSeverity.ERROR, "{path} contains a control or invisible character (U+{hex})."),
    E067("PLACEHOLDER_SYNTAX", IssueSeverity.ERROR, "Malformed placeholder at {path} near \"{snippet}\"."),
    E068("MEDIA_ASSET_UNKNOWN", IssueSeverity.ERROR, "Media \"{assetId}\" at {path} is not in the app's media library."),
    E069("CONTENT_CHANNEL_MISMATCH", IssueSeverity.ERROR, "Content type \"{type}\" cannot be used with channel {channel}."),

    // Outcome and packages
    E070("OUTCOME_REQUIRED", IssueSeverity.ERROR, "A reminder rule needs outcome.proximal."),
    E071("OUTCOME_ROLE_MISMATCH", IssueSeverity.ERROR, "Metric {metric} cannot be used as a {role} outcome."),
    E072("OUTCOME_ARG_INVALID", IssueSeverity.ERROR, "Outcome {metric} at {path}: {reason}."),
    E073("OUTCOME_WINDOW_OUT_OF_RANGE", IssueSeverity.ERROR, "windowMinutes for {metric} must be {range}; got {n}."),
    E081("PACKAGE_NAME_INVALID", IssueSeverity.ERROR, "\"{value}\" at {path} is not a valid Android package name."),

    // Confirm items
    C01("APP_SELECTION", IssueSeverity.CONFIRM, "Which app did you mean by \"{appLabel}\"?"),
    C02(
        "VOICE_OR_VIDEO",
        IssueSeverity.CONFIRM,
        "This reminder will speak out loud. Allow?",
        "This reminder will open a short video. Allow?",
    ),
    C03(
        "QUIET_HOURS_OVERRIDE",
        IssueSeverity.CONFIRM,
        "This reminder may appear during your quiet hours ({start}-{end}) while you are using your phone. Allow?",
        "This reminder may appear during quiet hours while you are using your phone. Allow?",
    ),
    C04("ASSUMPTION", IssueSeverity.CONFIRM, "Assumed: \"{text}\""),
    C05("UNKNOWN_OVERRIDE", IssueSeverity.CONFIRM, "This reminder can fire even when {feature} is missing or out of date."),

    // Warnings
    W01("DUPLICATE", IssueSeverity.WARNING, "You already have a rule that does this: \"{name}\"."),
    W02("FEATURE_UNAVAILABLE", IssueSeverity.WARNING, "{feature} is not available on this phone, so this rule will not fire."),
    W03("PERMISSION_NEEDED", IssueSeverity.WARNING, "Needs {access}. Until you allow it, this rule will not fire."),
    W04(
        "WINDOW_IN_QUIET_HOURS",
        IssueSeverity.WARNING,
        "This rule only runs during your quiet hours ({start}-{end}), so it will not notify you.",
    ),
    W05("CAP_ABOVE_GLOBAL", IssueSeverity.WARNING, "Your overall limit of {n} reminders per {period} applies first."),
    W06("BLOCKED_BY_EXISTING", IssueSeverity.WARNING, "\"{name}\" may block this reminder."),
    W07(
        "REMOTE_DATA_DELAY",
        IssueSeverity.WARNING,
        "{feature} reaches the phone when your tracker syncs, so this rule may skip a check when the data is late.",
    ),
    W08(
        "BEST_EFFORT_TRIGGER",
        IssueSeverity.WARNING,
        "Reacting when {event} is best effort: it is reliable only while notification access keeps Agentle running.",
    ),
    W09(
        "EVENING_TIME_STOPS_AT_MIDNIGHT",
        IssueSeverity.WARNING,
        "\"local_time {op} {value}\" at {path} is false from midnight on; " +
            "to include the hours after midnight use local_time_in or a window that crosses midnight.",
    ),
    W10(
        "GENERIC_NOTIFICATION_TEXT",
        IssueSeverity.WARNING,
        "Notifications show a general text unless detailed notifications are on; " +
            "the values and app names in this text appear only inside the app.",
    ),
    ;

    /** Number of message templates (variants) of this code. */
    public val variantCount: Int get() = templates.size

    /** The template of [variant] (0 = the R10 template). */
    public fun template(variant: Int = 0): String = templates[variant]

    /** Fills `{name}` tokens of the template in one pass; inserted values are never re-scanned. */
    public fun format(params: Map<String, String>, variant: Int = 0): String {
        val template = templates[variant]
        val out = StringBuilder(template.length + 32)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            val close = if (open < 0) -1 else template.indexOf('}', open + 1)
            if (open < 0 || close < 0) {
                out.append(template, i, template.length)
                break
            }
            val key = template.substring(open + 1, close)
            out.append(template, i, open)
            out.append(params[key] ?: "{$key}")
            i = close + 1
        }
        return out.toString()
    }
}
