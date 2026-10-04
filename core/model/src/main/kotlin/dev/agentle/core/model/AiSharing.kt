package dev.agentle.core.model

import kotlinx.serialization.Serializable

/**
 * Categories for sharing data with an AI provider (architecture red team privacy-ai-04). They are finer than
 * [DataCategory] (which groups stored events for retention and deletion): consent is granted per AI category and
 * purpose, and every value sent to a model carries the AI categories of everything it was derived from (its
 * [DataLineage]).
 *
 * [storageCategory] reconciles the two taxonomies: deleting a [DataCategory] revokes the AI grants of every AI category
 * whose storage category it is (database-sync-08). [SETTINGS] is not stored as events, so it has none.
 *
 * [thirdPartyText] categories hold text written by someone other than the user or Agentle (notification and calendar
 * text). They are never sent to a model in v1, whatever the grants say.
 */
@Serializable
public enum class AiDataCategory(public val storageCategory: DataCategory?, public val thirdPartyText: Boolean = false) {
    /** Screen-on minutes, unlocks and app-category minutes, without saying which app. */
    SCREEN_TIME_TOTALS(DataCategory.SCREEN),

    /** Which apps were used (labels; package names are never sent). */
    APP_IDENTITY(DataCategory.APP_USAGE),

    /** Notification counts, without text or sender. */
    NOTIFICATION_COUNTS(DataCategory.NOTIFICATIONS),

    /** Notification titles and text. Never sent in v1. */
    NOTIFICATION_TEXT(DataCategory.NOTIFICATION_CONTENT, thirdPartyText = true),

    /** Busy and free time from the calendar, without titles. */
    CALENDAR_BUSY(DataCategory.CALENDAR),

    /** Calendar titles. Never sent in v1. */
    CALENDAR_TEXT(DataCategory.CALENDAR, thirdPartyText = true),

    /** Home, work or other; coordinates are never sent. */
    LOCATION_CLASS(DataCategory.LOCATION),

    /** Detected activity and exercise sessions. */
    ACTIVITY(DataCategory.ACTIVITY),

    /** Step counts, distance, floors and active energy. */
    STEPS(DataCategory.ACTIVITY),
    SLEEP(DataCategory.SLEEP),
    HEART(DataCategory.HEART),
    BODY(DataCategory.BODY),

    /** The user's own free text: log notes and labels. */
    USER_TEXT(DataCategory.USER_LOGS),

    /** The user's goals (text and targets). */
    GOALS(DataCategory.GOALS),

    /** Numeric self-reports: mood, energy, stress. */
    SELF_REPORTS(DataCategory.USER_LOGS),

    /** Battery, charging, Do Not Disturb, connectivity kind, headphones, call state, and which sensors the phone has. */
    DEVICE_STATE(DataCategory.DEVICE_STATE),

    /** Agentle's own reminder history: deliveries, responses, rule categories. */
    INTERVENTION_HISTORY(DataCategory.INTERVENTIONS),

    /** App settings that shape reminders: quiet hours, weekend days, clock format, locale. */
    SETTINGS(null),
    ;

    public companion object {
        /** The AI categories derived from [category]; deleting [category] revokes their grants. */
        public fun forStorageCategory(category: DataCategory): Set<AiDataCategory> =
            entries.filterTo(LinkedHashSet()) { it.storageCategory == category }
    }
}

/**
 * The [SourceFamily] of a connector id ([ConnectorIds]) for AI gating; null for a connector this version does not know,
 * so a value from it gets UNKNOWN lineage (fail closed). Unlike [DataSourceId.family], which files every connector that
 * is not a wearable under ON_DEVICE for deletion, AI gating never assumes that an unknown connector is the phone.
 */
public fun sourceFamilyOfConnector(connectorId: String): SourceFamily? = when (connectorId) {
    ConnectorIds.GOOGLE_HEALTH -> SourceFamily.GH_API
    ConnectorIds.HEALTH_CONNECT -> SourceFamily.HEALTH_CONNECT
    ConnectorIds.ANDROID, ConnectorIds.USER, ConnectorIds.AGENTLE -> SourceFamily.ON_DEVICE
    else -> null
}

/**
 * The AI categories and source families a value was derived from. The lineage of a derived value is the union of the
 * lineages of its inputs; a value whose lineage is not known has [UNKNOWN] lineage (every category and every source
 * family), so it can only leave the device if everything is allowed, which never happens in v1.
 *
 * A lineage with categories but no source family is treated as [UNKNOWN] by [normalized]: its origin is not known.
 *
 * It is the AI lineage the consent gate checks (docs/ARCHITECTURE.md section 9.2). [Lineage] is the storage lineage
 * (storage [DataCategory] plus [SourceFamily]) that derived rows and insights carry for retention and deletion. A value
 * known only by its stored [Lineage] is converted with [fromStorage], which is never narrower.
 */
@Serializable
public data class DataLineage(val categories: Set<AiDataCategory>, val sources: Set<SourceFamily>) {
    public operator fun plus(other: DataLineage): DataLineage = DataLineage(categories + other.categories, sources + other.sources)

    /** [UNKNOWN] if categories are present without any source family, otherwise this lineage. */
    public fun normalized(): DataLineage = if (categories.isNotEmpty() && sources.isEmpty()) UNKNOWN else this

    public companion object {
        /** Every category and every source family. */
        public val UNKNOWN: DataLineage = DataLineage(AiDataCategory.entries.toSet(), SourceFamily.entries.toSet())

        /** No personal data at all (clock values, app constants). */
        public val NONE: DataLineage = DataLineage(emptySet(), emptySet())

        public fun of(category: AiDataCategory, source: SourceFamily): DataLineage = DataLineage(setOf(category), setOf(source))

        public fun union(lineages: Iterable<DataLineage>): DataLineage = lineages.fold(NONE) { acc, lineage -> acc + lineage }

        /**
         * The AI lineage of a value whose stored lineage is [lineage]: every AI category of each storage category
         * ([AiDataCategory.forStorageCategory]) and the same source families. It is never narrower. A storage category
         * without any AI category (communication, media, insights, generated media), or categories without a source
         * family, give [UNKNOWN].
         */
        public fun fromStorage(lineage: Lineage): DataLineage {
            val mapped = lineage.categories.map(AiDataCategory::forStorageCategory)
            if (mapped.any { it.isEmpty() }) return UNKNOWN
            return DataLineage(mapped.flatMapTo(LinkedHashSet()) { it }, lineage.sourceFamilies).normalized()
        }
    }
}

/** Who wrote an [UntrustedText]. */
@Serializable
public enum class TextOrigin(public val thirdParty: Boolean) {
    /** A request or question the user typed for an AI feature. */
    USER_REQUEST(false),

    /** A note or label the user wrote in a log. */
    USER_NOTE(false),

    /** A goal the user wrote. */
    USER_GOAL(false),

    /** An app's launcher label (written by that app's developer). */
    APP_LABEL(true),

    /** An Android package name. */
    PACKAGE_NAME(true),

    /** Any field of a notification posted by another app. */
    NOTIFICATION(true),

    /** Any field of a calendar event (titles, locations, attendees). */
    CALENDAR(true),

    /** Wi-Fi network names, Bluetooth device names and other device-provided names. */
    DEVICE_NAME(true),

    /** Text produced by an AI model. Always [UntrustedText.aiGenerated]. */
    AI_OUTPUT(false),

    /** Any other text Agentle did not write. */
    OTHER(true),
}

/**
 * A string Agentle did not write (privacy-ai-05): app labels, package names, every notification and calendar field,
 * user goals and notes, natural-language requests and every AI output.
 *
 * [toString] never shows the text, so string templates, concatenation and logging cannot leak it by accident; code
 * that needs the characters reads [raw] explicitly. The AI request serializer emits it only as a JSON string value
 * inside a data item, never inside instructions. [aiGenerated] is a taint carried into storage: AI output is left out of
 * later AI contexts unless a purpose needs it.
 */
@Serializable
public class UntrustedText(
    public val raw: String,
    public val origin: TextOrigin,
    public val aiGenerated: Boolean = origin ==
        TextOrigin.AI_OUTPUT,
) {
    init {
        require(origin != TextOrigin.AI_OUTPUT || aiGenerated) { "AI output is always aiGenerated" }
    }

    public val length: Int get() = raw.length

    public fun isBlank(): Boolean = raw.isBlank()

    override fun equals(other: Any?): Boolean =
        other is UntrustedText && other.raw == raw && other.origin == origin && other.aiGenerated == aiGenerated

    override fun hashCode(): Int = (raw.hashCode() * 31 + origin.hashCode()) * 31 + aiGenerated.hashCode()

    override fun toString(): String = "UntrustedText(origin=$origin, aiGenerated=$aiGenerated, length=${raw.length})"
}
