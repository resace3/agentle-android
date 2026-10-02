package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.googlehealth.GhJson.double
import dev.agentle.connectors.googlehealth.GhJson.hasValue
import dev.agentle.connectors.googlehealth.GhJson.instant
import dev.agentle.connectors.googlehealth.GhJson.long
import dev.agentle.connectors.googlehealth.GhJson.obj
import dev.agentle.connectors.googlehealth.GhJson.string
import dev.agentle.core.model.BodyFatPayload
import dev.agentle.core.model.CaloriesPayload
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DailyTotalPayload
import dev.agentle.core.model.DistancePayload
import dev.agentle.core.model.EnergyBasis
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.FloorsPayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Provenance
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.Sensitivity
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.WearableDevicePayload
import dev.agentle.core.model.WeightPayload
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Instant

/** One mapped point: an event, or the reason it was skipped and counted (docs/research/05 §8.6 R4, R5). */
internal sealed interface Mapped {
    data class Event(val event: PersonalEvent) : Mapped

    data class Skip(val reason: String) : Mapped
}

/** A paired device as the sync needs it: its type and last upload. */
internal data class GhDevice(val deviceType: String?, val lastSync: Instant?)

/**
 * Maps Google Health points to [PersonalEvent]s (docs/research/05 §4, §5.4, §5.9, §5.10):
 * - every key and id is scoped by [accountId] (a hash of `healthUserId`, round-2 correction 3), and resource names are
 *   stored as `users/me/...`, so no raw user id is persisted;
 * - dedup keys: `gh|<account>|<stream>|<point id>` for identifiable points, else the interval or sample time plus a
 *   source key from the client-provided `dataSource` fields (`recordingMethod`, `device`); the output-only `platform`
 *   and `application` never enter a key or a hash (round-2 correction 4);
 * - a true zero (an interval without its value field) is 0; absence is no event;
 * - instants are kept as instants, each record's own offset gives its zone; civil-date values keep their `LocalDate`.
 */
internal class GhMapper(private val accountId: String, private val accountZone: TimeZone, private val ingestedAt: Instant) {
    private val sessions = GhSessionMapper(this)

    // ---------------------------------------------------------------- list and :reconcile points

    /** A `list` or `:reconcile` point of [stream]: exactly one known value-union key, the stream's own. */
    fun point(stream: GhStream, point: JsonObject): Mapped {
        val unionKeys = point.keys.filter { it in UNION_KEYS && point.hasValue(it) }
        if (unionKeys.size != 1 || unionKeys[0] != stream.unionKey) return Mapped.Skip("union")
        val value = point.obj(stream.unionKey) ?: return Mapped.Skip("union")
        val upstreamId = (point.string("name") ?: point.string("dataPointName"))?.let(::normalizedName)
        val provenance = provenance(point.obj("dataSource"))
        return when (stream.kind) {
            GhKind.INTERVAL -> interval(stream, value, provenance, upstreamId)
            GhKind.SAMPLE -> sample(stream, value, provenance, upstreamId)
            GhKind.DAILY -> restingHeartRate(stream, value, provenance)
            GhKind.SLEEP -> sessions.sleep(stream, value, provenance, upstreamId)
            GhKind.EXERCISE -> sessions.exercise(stream, value, provenance, upstreamId)
            GhKind.ROLL_UP, GhKind.DAILY_ROLL_UP, GhKind.DEVICES -> Mapped.Skip("kind")
        }
    }

    @Suppress("ReturnCount")
    private fun interval(stream: GhStream, value: JsonObject, provenance: Provenance?, upstreamId: String?): Mapped {
        val time = intervalOf(value.obj("interval")) ?: return Mapped.Skip("interval")
        val payload: EventPayload = when (stream.unionKey) {
            "steps" -> StepsPayload(count(value, "count", MAX_STEPS) ?: return Mapped.Skip("value"))
            "distance" -> DistancePayload((count(value, "millimeters", MAX_MILLIMETERS) ?: return Mapped.Skip("value")) / MM_PER_M)
            "floors" -> FloorsPayload((count(value, "count", MAX_FLOORS) ?: return Mapped.Skip("value")).toDouble())
            "activeEnergyBurned" -> CaloriesPayload(amount(value, "kcal", MAX_KCAL) ?: return Mapped.Skip("value"), EnergyBasis.ACTIVE)
            else -> return Mapped.Skip("type")
        }
        val key = upstreamId?.let { "${prefix(stream)}|${it.substringAfterLast('/')}" }
            ?: "${prefix(stream)}|${time.start.toEpochMilliseconds()}|${time.end.toEpochMilliseconds()}|${sourceKey(provenance)}"
        return Mapped.Event(event(stream, time.start, time.end, time.startOffset, payload, key, provenance, upstreamId, null))
    }

    /**
     * An int64 quantity: absent is a true zero (docs/research/05 §5.4; ProtoJSON omits zero scalars), present but
     * malformed or outside `0..max` is invalid (null, R5j).
     */
    private fun count(value: JsonObject, field: String, max: Long): Long? =
        if (!value.hasValue(field)) 0L else value.long(field)?.takeIf { it in 0..max }

    /** A double quantity with the same true-zero rule as [count]. */
    private fun amount(value: JsonObject, field: String, max: Double): Double? =
        if (!value.hasValue(field)) 0.0 else value.double(field)?.takeIf { it in 0.0..max }

    @Suppress("ReturnCount")
    private fun sample(stream: GhStream, value: JsonObject, provenance: Provenance?, upstreamId: String?): Mapped {
        val sampleTime = value.obj("sampleTime") ?: return Mapped.Skip("sample_time")
        val at = sampleTime.instant("physicalTime") ?: return Mapped.Skip("sample_time")
        val offset = GhJson.offsetSeconds(sampleTime.string("utcOffset"))
        val sensitivity = if (value.string("notes") != null) Sensitivity.PERSONAL else Sensitivity.NORMAL
        val payload: EventPayload = when (stream.unionKey) {
            "heartRate" -> {
                val bpm = value.long("beatsPerMinute")?.takeIf { it in MIN_BPM..MAX_BPM } ?: return Mapped.Skip("value")
                val metadata = value.obj("metadata")
                HeartRatePayload(bpm.toDouble(), metadata?.string("motionContext"), metadata?.string("sensorLocation"))
            }

            "weight" -> WeightPayload(
                (value.double("weightGrams")?.takeIf { it > 0.0 && it <= MAX_GRAMS } ?: return Mapped.Skip("value")) / GRAMS_PER_KG,
                value.string("notes"),
            )

            "bodyFat" -> BodyFatPayload(value.double("percentage")?.takeIf { it in 0.0..PERCENT } ?: return Mapped.Skip("value"))

            else -> return Mapped.Skip("type")
        }
        val key = upstreamId?.let { "${prefix(stream)}|${it.substringAfterLast('/')}" }
            ?: "${prefix(stream)}|${at.toEpochMilliseconds()}|${sourceKey(provenance)}"
        return Mapped.Event(event(stream, at, null, offset, payload, key, provenance, upstreamId, null, sensitivity))
    }

    @Suppress("ReturnCount")
    private fun restingHeartRate(stream: GhStream, value: JsonObject, provenance: Provenance?): Mapped {
        val date = GhJson.date(value.obj("date")) ?: return Mapped.Skip("date")
        val bpm = value.long("beatsPerMinute")?.takeIf { it in MIN_BPM..MAX_BPM } ?: return Mapped.Skip("value")
        val method = value.obj("dailyRestingHeartRateMetadata")?.string("calculationMethod")
        val payload = RestingHeartRatePayload(bpm.toDouble(), date, method)
        return Mapped.Event(daily(stream, date, payload, provenance))
    }

    // ---------------------------------------------------------------- :rollUp and :dailyRollUp

    /** One `:rollUp` window. Windows without data are absent upstream and stay absent here. */
    @Suppress("ReturnCount")
    fun rollUp(stream: GhStream, point: JsonObject): Mapped {
        val start = point.instant("startTime") ?: return Mapped.Skip("interval")
        val end = point.instant("endTime") ?: return Mapped.Skip("interval")
        if (end <= start) return Mapped.Skip("interval")
        val value = point.obj(stream.unionKey) ?: return Mapped.Skip("union")
        val payload = rollUpPayload(stream.unionKey, value) ?: return Mapped.Skip("value")
        val key = "${prefix(stream)}|${start.toEpochMilliseconds()}|${end.toEpochMilliseconds()}"
        return Mapped.Event(event(stream, start, end, null, payload, key, null, null, null))
    }

    @Suppress("ReturnCount")
    private fun rollUpPayload(unionKey: String, value: JsonObject): EventPayload? = when (unionKey) {
        "heartRate" -> {
            val avg = value.double("beatsPerMinuteAvg")?.takeIf { it >= MIN_BPM && it <= MAX_BPM }
            avg?.let { HeartRatePayload(it, minBpm = value.double("beatsPerMinuteMin"), maxBpm = value.double("beatsPerMinuteMax")) }
        }

        "steps" -> sum(value, "countSum")?.let { StepsPayload(it.toLong()) }

        "distance" -> sum(value, "millimetersSum")?.let { DistancePayload(it / MM_PER_M) }

        "floors" -> sum(value, "countSum")?.let { FloorsPayload(it) }

        "activeEnergyBurned" -> sum(value, "kcalSum")?.let { CaloriesPayload(it, EnergyBasis.ACTIVE) }

        "totalCalories" -> sum(value, "kcalSum")?.let { CaloriesPayload(it, EnergyBasis.TOTAL) }

        else -> null
    }

    /** A rollup sum: absent is a true zero, negative or malformed is invalid. */
    private fun sum(value: JsonObject, field: String): Double? =
        if (!value.hasValue(field)) 0.0 else value.double(field)?.takeIf { it >= 0.0 }

    /** One `:dailyRollUp` day, keyed by its own `civilStartTime.date` (docs/research/05 §4.5), never by position. */
    @Suppress("ReturnCount")
    fun dailyRollUp(stream: GhStream, point: JsonObject): Mapped {
        val date = GhJson.civilDate(point.obj("civilStartTime")) ?: return Mapped.Skip("date")
        val metric = stream.dailyMetric ?: return Mapped.Skip("type")
        val value = point.obj(stream.unionKey) ?: return Mapped.Skip("union")
        val amount = when (metric) {
            DailyTotalMetric.STEPS, DailyTotalMetric.FLOORS -> sum(value, "countSum")
            DailyTotalMetric.DISTANCE_METERS -> sum(value, "millimetersSum")?.div(MM_PER_M)
            DailyTotalMetric.TOTAL_CALORIES_KCAL, DailyTotalMetric.ACTIVE_CALORIES_KCAL -> sum(value, "kcalSum")
        } ?: return Mapped.Skip("value")
        return Mapped.Event(daily(stream, date, DailyTotalPayload(date, metric, amount), null))
    }

    // ---------------------------------------------------------------- paired devices

    /** A paired device; `macAddress` and `features` are never kept (S19). */
    fun device(stream: GhStream, point: JsonObject): Pair<Mapped, GhDevice?> {
        val name = point.string("name") ?: return Mapped.Skip("name") to null
        val deviceHash = hash("$accountId|${name.substringAfterLast('/')}", DEVICE_HASH_CHARS)
        val lastSync = point.instant("lastSyncTime")
        val payload = WearableDevicePayload(
            deviceIdHash = deviceHash,
            model = point.string("deviceVersion"),
            batteryPercent = point.long("batteryLevel")?.takeIf { it in 0..PERCENT.toLong() }?.toInt(),
            lastSyncEpochMs = lastSync?.toEpochMilliseconds(),
            deviceType = point.string("deviceType"),
            batteryStatus = point.string("batteryStatus"),
        )
        val key = "${prefix(stream)}|$deviceHash"
        val event = event(stream, lastSync ?: ingestedAt, null, null, payload, key, null, null, null)
        return Mapped.Event(event) to GhDevice(payload.deviceType, lastSync)
    }

    // ---------------------------------------------------------------- shared helpers

    internal data class Interval(val start: Instant, val end: Instant, val startOffset: Int?, val endOffset: Int?)

    /** An interval with valid instants and end after start; a missing end offset falls back to the start offset (R5g). */
    @Suppress("ReturnCount")
    internal fun intervalOf(interval: JsonObject?): Interval? {
        if (interval == null) return null
        val start = interval.instant("startTime") ?: return null
        val end = interval.instant("endTime") ?: return null
        if (end <= start) return null
        val startOffset = GhJson.offsetSeconds(interval.string("startUtcOffset"))
        val endOffset = GhJson.offsetSeconds(interval.string("endUtcOffset")) ?: startOffset
        return Interval(start, end, startOffset, endOffset)
    }

    internal fun prefix(stream: GhStream): String = "gh|$accountId|${stream.id}"

    /** A civil-date event: starts at the date's midnight in the account zone; [payload] holds the authoritative date. */
    private fun daily(stream: GhStream, date: LocalDate, payload: EventPayload, provenance: Provenance?): PersonalEvent {
        val start = date.atStartOfDayIn(accountZone)
        val end = date.plus(DatePeriod(days = 1)).atStartOfDayIn(accountZone)
        return event(stream, start, end, null, payload, "${prefix(stream)}|$date", provenance, null, null)
    }

    @Suppress("LongParameterList")
    internal fun event(
        stream: GhStream,
        start: Instant,
        end: Instant?,
        offsetSeconds: Int?,
        payload: EventPayload,
        key: String,
        provenance: Provenance?,
        upstreamId: String?,
        updatedAt: Instant?,
        sensitivity: Sensitivity = Sensitivity.NORMAL,
    ): PersonalEvent = PersonalEvent(
        id = EventId(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()),
        type = stream.eventType,
        source = stream.source,
        startTime = start,
        endTime = end,
        zoneId = zoneIdFor(start, offsetSeconds),
        payload = payload,
        dedupKey = key,
        metadata = EventMetadata(
            ingestedAt = ingestedAt,
            origin = provenance?.appPackage ?: provenance?.deviceName,
            sensitivity = sensitivity,
            provenance = provenance,
            upstreamId = upstreamId,
            upstreamUpdatedAt = updatedAt,
            payloadHash = hash(EventCodec.encode(payload), PAYLOAD_HASH_CHARS),
        ),
    )

    /** The account zone when the record's offset matches it there, else the record's own fixed offset (R9b). */
    internal fun zoneIdFor(at: Instant, offsetSeconds: Int?): String = when {
        offsetSeconds == null -> accountZone.id
        accountZone.offsetAt(at).totalSeconds == offsetSeconds -> accountZone.id
        offsetSeconds == 0 -> "UTC"
        else -> UtcOffset(seconds = offsetSeconds).toString()
    }

    companion object {
        private const val MAX_STEPS = 1_000_000L
        private const val MAX_MILLIMETERS = 1_000_000_000L
        private const val MAX_FLOORS = 100_000L
        private const val MAX_KCAL = 100_000.0
        private const val MIN_BPM = 1L
        private const val MAX_BPM = 300L
        private const val MAX_GRAMS = 1_000_000.0
        private const val GRAMS_PER_KG = 1000.0
        private const val MM_PER_M = 1000.0
        private const val PERCENT = 100.0
        private const val PAYLOAD_HASH_CHARS = 32
        private const val DEVICE_HASH_CHARS = 16
        private const val SOURCE_HASH_CHARS = 12

        /** Every value-union key of `DataPoint` known from discovery (docs/research/05 §3.2, §4.4). */
        val UNION_KEYS: Set<String> = setOf(
            "steps", "distance", "floors", "activeEnergyBurned", "totalCalories", "basalEnergyBurned", "heartRate",
            "dailyRestingHeartRate", "heartRateVariability", "dailyHeartRateVariability", "oxygenSaturation",
            "dailyOxygenSaturation", "sleep", "exercise", "weight", "bodyFat", "height", "altitude", "activeMinutes",
            "activeZoneMinutes", "activityLevel", "sedentaryPeriod", "swimLengthsData", "timeInHeartRateZone",
            "caloriesInHeartRateZone", "vo2Max", "runVo2Max", "dailyVo2Max", "bloodGlucose", "coreBodyTemperature",
            "respiratoryRateSleepSummary", "dailyRespiratoryRate", "dailyHeartRateZones", "dailySleepTemperatureDerivations",
            "electrocardiogram", "irregularRhythmNotification", "hydrationLog", "nutritionLog", "menstrualPeriod", "moods",
            "ovulationTest", "symptoms",
        )

        /** `users/<id>/dataTypes/...` as `users/me/dataTypes/...`: the same resource, without the raw user id. */
        fun normalizedName(name: String): String {
            val parts = name.split('/')
            return if (parts.size > 2 && parts[0] == "users") (listOf("users", "me") + parts.drop(2)).joinToString("/") else name
        }

        /** The normalized provenance of `dataSource` (raw upstream strings; unknown enum values are kept as sent). */
        fun provenance(dataSource: JsonObject?): Provenance? {
            if (dataSource == null) return null
            val device = dataSource.obj("device")
            return Provenance(
                platform = dataSource.string("platform"),
                deviceName = device?.string("displayName"),
                deviceManufacturer = device?.string("manufacturer"),
                formFactor = device?.string("formFactor"),
                recordingMethod = dataSource.string("recordingMethod"),
                appPackage = dataSource.obj("application")?.string("packageName"),
            )
        }

        /** Source key of a non-identifiable point: client-provided fields only, never `platform` or `application`. */
        fun sourceKey(provenance: Provenance?): String {
            if (provenance == null) return "-"
            val parts = listOf(provenance.recordingMethod, provenance.deviceName, provenance.deviceManufacturer, provenance.formFactor)
            if (parts.all { it == null }) return "-"
            return hash(parts.joinToString("\u0001") { it.orEmpty() }, SOURCE_HASH_CHARS)
        }

        /** Hex SHA-256 of [text], cut to [chars] characters. */
        fun hash(text: String, chars: Int): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return buildString { digest.forEach { append(HEX[(it.toInt() shr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) } }.take(chars)
        }

        private const val HEX = "0123456789abcdef"
    }
}
