package dev.agentle.connectors.android.core

import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Sensitivity
import dev.agentle.core.time.AgentleClock
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Instant

/**
 * Event ids: UUIDv7-shaped (48-bit start time in ms, version 7, variant 2) with the remaining bits taken from SHA-256 of
 * the dedup key, so the same upstream record always gets the same id and ids still sort by time (docs/ARCHITECTURE.md §5.1).
 */
public object EventIds {
    private const val MAX_MS = 0xFFFF_FFFF_FFFFL

    public fun forEvent(startEpochMs: Long, dedupKey: String): EventId {
        val hash = MessageDigest.getInstance("SHA-256").digest(dedupKey.toByteArray(Charsets.UTF_8))
        var tail = 0L
        for (i in 0 until 8) tail = (tail shl 8) or (hash[i].toLong() and 0xff)
        val randA = ((hash[8].toLong() and 0x0f) shl 8) or (hash[9].toLong() and 0xff)
        val ms = startEpochMs.coerceIn(0L, MAX_MS)
        val msb = (ms shl 16) or (0x7L shl 12) or randA
        val lsb = (tail and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return EventId(UUID(msb, lsb).toString())
    }
}

/** Builds [PersonalEvent]s with the capture-time zone, an ingestion time from [AgentleClock] and deterministic ids. */
public class EventFactory(private val clock: AgentleClock) {
    @Suppress("LongParameterList") // Mirrors the PersonalEvent fields a collector may set.
    public fun create(
        type: EventType,
        source: DataSourceId,
        start: Instant,
        payload: EventPayload,
        dedupKey: String,
        end: Instant? = null,
        confidence: Double? = null,
        sensitivity: Sensitivity = Sensitivity.NORMAL,
        origin: String? = null,
        upstreamId: String? = null,
        upstreamUpdatedAt: Instant? = null,
        zoneId: String? = null,
    ): PersonalEvent = PersonalEvent(
        id = EventIds.forEvent(start.toEpochMilliseconds(), dedupKey),
        type = type,
        source = source,
        startTime = start,
        endTime = end?.takeIf { it >= start },
        zoneId = zoneId ?: clock.zone().id,
        payload = payload,
        confidence = confidence,
        dedupKey = dedupKey,
        metadata = EventMetadata(
            ingestedAt = clock.now(),
            origin = origin,
            sensitivity = sensitivity,
            upstreamId = upstreamId,
            upstreamUpdatedAt = upstreamUpdatedAt,
        ),
    )
}

/**
 * Data source ids of the on-device collectors (`<connector>.<stream>`, docs/ARCHITECTURE.md §5.1). Usage events are the
 * truth for screen and unlock state ([SCREEN]); live receivers write confirmed rows to [SCREEN_LIVE] and unconfirmed
 * ones to [SCREEN_HINT] (red team lifecycle-battery-07).
 */
public object AndroidSources {
    public val USAGE: DataSourceId = DataSourceId("android.usage")
    public val SCREEN: DataSourceId = DataSourceId("android.screen")
    public val SCREEN_LIVE: DataSourceId = DataSourceId("android.screen_live")
    public val SCREEN_HINT: DataSourceId = DataSourceId("android.screen_hint")
    public val NOTIFICATIONS: DataSourceId = DataSourceId("android.notifications")
    public val BATTERY: DataSourceId = DataSourceId("android.battery")
    public val BATTERY_HINT: DataSourceId = DataSourceId("android.battery_hint")
    public val NETWORK: DataSourceId = DataSourceId("android.network")
    public val BLUETOOTH: DataSourceId = DataSourceId("android.bluetooth")
    public val AUDIO: DataSourceId = DataSourceId("android.audio")
    public val DEVICE: DataSourceId = DataSourceId("android.device")
    public val SYSTEM: DataSourceId = DataSourceId("android.system")
    public val ACTIVITY: DataSourceId = DataSourceId("android.activity")
    public val STEPS: DataSourceId = DataSourceId("android.steps")
    public val CALENDAR: DataSourceId = DataSourceId("android.calendar")
    public val CALL: DataSourceId = DataSourceId("android.call")

    /** Cursor connector id of every `android.*` stream: cursors are `("android", <stream>)`. */
    public const val CURSOR_CONNECTOR: String = "android"
}

/** Connector ids of the on-device collectors; they double as coverage collector ids. */
public object AndroidConnectorIds {
    public const val USAGE: String = "android.usage"
    public const val SCREEN: String = "android.screen"
    public const val NOTIFICATIONS: String = "android.notifications"
    public const val BATTERY: String = "android.battery"
    public const val NETWORK: String = "android.network"
    public const val BLUETOOTH: String = "android.bluetooth"
    public const val AUDIO: String = "android.audio"
    public const val DEVICE: String = "android.device"
    public const val SYSTEM: String = "android.system"
    public const val LOCATION: String = "android.location"
    public const val ACTIVITY: String = "android.activity"
    public const val STEPS: String = "android.steps"
    public const val CALENDAR: String = "android.calendar"
    public const val CALL: String = "android.call"
    public const val HEALTH_CONNECT: String = "healthconnect"

    /** Opt-in connectors: off until the user enables them (listed in `CollectionSettings.enabledOptInConnectors`). */
    public val OPT_IN: Set<String> = setOf(CALL)
}

/** Fixed-size time buckets for sample dedup keys (`battery|sample|<5-min bucket>`, red team database-sync-17). */
public object Buckets {
    public const val SAMPLE_MS: Long = 5 * 60 * 1000L

    public fun floor(epochMs: Long, sizeMs: Long = SAMPLE_MS): Long = Math.floorDiv(epochMs, sizeMs) * sizeMs
}
