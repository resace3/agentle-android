package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class EventId(public val value: String) {
    override fun toString(): String = value
}

/** How sensitive a single event is; decides logging, export and AI handling on top of its category. */
@Serializable
public enum class Sensitivity { NORMAL, PERSONAL, SENSITIVE }

/** Provenance and bookkeeping that is not part of the measurement itself. */
@Serializable
public data class EventMetadata(
    val ingestedAt: Instant,
    val schemaVersion: Int = PersonalEvent.SCHEMA_VERSION,
    /** Package or device that produced the data upstream (e.g. Health Connect data origin), if known. */
    val origin: String? = null,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
)

/**
 * The normalized event (docs/ARCHITECTURE.md §5.1). Times are instants; [zoneId] is the zone at capture time so
 * local-day features stay correct after travel. [dedupKey] is a deterministic, source-specific natural key: the
 * same upstream record always produces the same key, so re-syncs and overlapping windows converge.
 */
@Serializable
public data class PersonalEvent(
    val id: EventId,
    val type: EventType,
    val source: DataSourceId,
    val startTime: Instant,
    val endTime: Instant? = null,
    val zoneId: String,
    val payload: EventPayload,
    val confidence: Double? = null,
    val dedupKey: String,
    val metadata: EventMetadata,
) {
    init {
        require(endTime == null || endTime >= startTime) { "endTime before startTime for $type ($dedupKey)" }
        require(confidence == null || confidence in 0.0..1.0) { "confidence out of range: $confidence" }
        require(dedupKey.isNotBlank()) { "dedupKey must not be blank" }
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}
