package dev.agentle.fakes.googlehealth

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant

/**
 * Provenance of a point as `list` returns it (`dataSource`, docs/research/05 §4.2).
 *
 * @property wearable data recorded by the tracker: hidden until the tracker has synced it (config `deviceSyncLag`)
 */
public data class FakeSource(
    val recordingMethod: String? = "PASSIVELY_MEASURED",
    val platform: String? = "FITBIT",
    val deviceName: String? = "Charge 6",
    val manufacturer: String? = null,
    val formFactor: String? = null,
    val appPackage: String? = null,
    val wearable: Boolean = true,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        recordingMethod?.let { put("recordingMethod", it) }
        if (deviceName != null || manufacturer != null || formFactor != null) {
            put(
                "device",
                buildJsonObject {
                    formFactor?.let { put("formFactor", it) }
                    manufacturer?.let { put("manufacturer", it) }
                    deviceName?.let { put("displayName", it) }
                },
            )
        }
        platform?.let { put("platform", it) }
        appPackage?.let { put("application", buildJsonObject { put("packageName", it) }) }
    }

    public companion object {
        public val TRACKER: FakeSource = FakeSource()
        public val TRACKER_DERIVED: FakeSource = FakeSource(recordingMethod = "DERIVED")
        public val TRACKER_WORKOUT: FakeSource = FakeSource(recordingMethod = "ACTIVELY_MEASURED", formFactor = "FITNESS_BAND")
        public val PHONE_VIA_HEALTH_CONNECT: FakeSource = FakeSource(
            recordingMethod = "PASSIVELY_MEASURED",
            platform = "HEALTH_CONNECT",
            deviceName = "Pixel 9",
            manufacturer = "Google",
            formFactor = "PHONE",
            appPackage = "com.example.phonesteps",
            wearable = false,
        )
        public val SCALE: FakeSource = FakeSource("ACTIVELY_MEASURED", "FITBIT", "Aria Air", "Fitbit", "SCALE", wearable = false)
        public val MANUAL: FakeSource = FakeSource("MANUAL", "FITBIT", deviceName = null, wearable = false)

        internal fun parse(json: JsonObject?): FakeSource? {
            json ?: return null
            val device = json["device"] as? JsonObject
            val method = json.string("recordingMethod")
            return FakeSource(
                recordingMethod = method,
                platform = json.string("platform"),
                deviceName = device?.string("displayName"),
                manufacturer = device?.string("manufacturer"),
                formFactor = device?.string("formFactor"),
                appPackage = (json["application"] as? JsonObject)?.string("packageName"),
                wearable = method != "MANUAL" && device?.string("formFactor") != "SCALE",
            )
        }
    }
}

/**
 * One data point of the model-mode dataset (docs/research/05 §8.1). [start] is the interval start, the sample time,
 * or (daily types) the start of [date] in UTC; [end] equals [start] for samples. A null [amount] omits the value
 * field, which is how the fake models a true zero (§5.4). [fields] holds the other value fields (sleep stages,
 * exercise metrics, heart-rate metadata, notes, ...). A point parsed from a documented fixture keeps its JSON in
 * [raw] and is served verbatim.
 */
public data class FakePoint(
    val type: GhDataType,
    val start: Instant,
    val end: Instant = start,
    val startOffsetSeconds: Int? = EDT_SECONDS,
    val endOffsetSeconds: Int? = startOffsetSeconds,
    val date: LocalDate? = null,
    val amount: Double? = null,
    val name: String? = null,
    val source: FakeSource? = FakeSource.TRACKER,
    val fields: JsonObject = NO_FIELDS,
    val raw: JsonObject? = null,
) {
    /** The point as `list` and `get` return it. */
    public fun toListJson(): JsonObject = raw ?: buildJsonObject {
        name?.let { put("name", it) }
        source?.let { put("dataSource", it.toJson()) }
        put(type.unionKey, value())
    }

    /** The point as `:reconcile` returns it: no `dataSource`, and `dataPointName` instead of `name` (§4.2). */
    public fun toReconcileJson(): JsonObject {
        val list = toListJson()
        return buildJsonObject {
            list["name"]?.let { if (type.identifiable) put("dataPointName", it) }
            list.forEach { (key, value) -> if (key != "name" && key != "dataSource") put(key, value) }
        }
    }

    /** The upstream id: the last segment of [name]. */
    val id: String? get() = name?.substringAfterLast('/')

    private fun value(): JsonObject = buildJsonObject {
        when (type.category) {
            GhCategory.INTERVAL -> put("interval", PointJson.interval(start, startOffsetSeconds, end, endOffsetSeconds, civil = true))
            GhCategory.SAMPLE -> put("sampleTime", PointJson.sampleTime(start, startOffsetSeconds))
            GhCategory.DAILY -> put("date", PointJson.date(requireNotNull(date) { "daily point without a date" }))
            GhCategory.SESSION -> put("interval", PointJson.interval(start, startOffsetSeconds, end, endOffsetSeconds, civil = false))
        }
        val field = type.valueField
        if (field != null &&
            amount != null
        ) {
            put(field, if (type.int64) JsonPrimitive(amount.toLong().toString()) else PointJson.number(amount))
        }
        fields.forEach { (key, value) -> put(key, value) }
    }

    public companion object {
        /** America/New_York in summer (EDT), the fixture account's offset (§8.1). */
        public const val EDT_SECONDS: Int = -14_400
        public val NO_FIELDS: JsonObject = JsonObject(emptyMap())

        /**
         * A point from documented JSON (a `list` or `:reconcile` element). Returns null when the point has no single
         * known value key or no time container, like the client would skip it.
         */
        @Suppress("ReturnCount")
        public fun parse(json: JsonObject): FakePoint? {
            val keys = json.keys.mapNotNull { GhDataTypes.byUnionKey(it) }
            if (keys.size != 1) return null
            val type = keys.single()
            val value = json.getValue(type.unionKey) as? JsonObject ?: return null
            val name = json.string("name") ?: json.string("dataPointName")
            val source = FakeSource.parse(json["dataSource"] as? JsonObject)
            val amount = type.valueField?.let { value[it] as? JsonPrimitive }?.let { it.contentOrNull?.toDoubleOrNull() ?: it.doubleOrNull }
            return when (type.category) {
                GhCategory.INTERVAL, GhCategory.SESSION -> {
                    val interval = value["interval"] as? JsonObject ?: return null
                    val start = interval.instant("startTime") ?: return null
                    val startOffset = interval.offset("startUtcOffset")
                    FakePoint(
                        type, start, interval.instant("endTime") ?: start, startOffset, interval.offset("endUtcOffset") ?: startOffset,
                        amount = amount, name = name, source = source, raw = json,
                    )
                }

                GhCategory.SAMPLE -> {
                    val sample = value["sampleTime"] as? JsonObject ?: return null
                    val time = sample.instant("physicalTime") ?: return null
                    FakePoint(type, time, time, sample.offset("utcOffset"), amount = amount, name = name, source = source, raw = json)
                }

                GhCategory.DAILY -> {
                    val date = (value["date"] as? JsonObject)?.let { PointJson.parseDate(it) } ?: return null
                    val start = date.atStartOfDayIn(TimeZone.UTC)
                    FakePoint(type, start, start, null, date = date, amount = amount, name = name, source = source, raw = json)
                }
            }
        }
    }
}

/** A paired device (docs/research/05 §4.6). A [tracker] reports `lastSyncTime` as now minus the configured lag. */
public data class FakeDevice(
    val id: String,
    val deviceVersion: String,
    val deviceType: String,
    val batteryStatus: String? = null,
    val batteryLevel: Int? = null,
    val lastSyncTime: Instant? = null,
    val macAddress: String? = null,
    val features: List<String>? = null,
    val tracker: Boolean = false,
) {
    internal fun toJson(userId: String, lastSync: Instant?): JsonObject = buildJsonObject {
        put("name", "users/$userId/pairedDevices/$id")
        put("deviceVersion", deviceVersion)
        put("deviceType", deviceType)
        batteryStatus?.let { put("batteryStatus", it) }
        batteryLevel?.let { put("batteryLevel", it) }
        lastSync?.let { put("lastSyncTime", PointJson.timestamp(it)) }
        macAddress?.let { put("macAddress", it) }
        features?.let { list -> put("features", kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) })) }
    }

    public companion object {
        /** The two devices of F-DEVICES-P1 and -P2 (§8.4). */
        public val CHARGE_6: FakeDevice = FakeDevice(
            id = "2718281828",
            deviceVersion = "Charge 6",
            deviceType = "TRACKER",
            batteryStatus = "High",
            batteryLevel = 82,
            lastSyncTime = Instant.parse("2026-10-01T11:58:03Z"),
            macAddress = "A0:B1:C2:D3:E4:F5",
            features = listOf("STEPS", "HEART_RATE", "SLEEP", "SMART_SLEEP", "SPO2", "ACTIVE_ZONE_MINUTES", "CONNECTED_GPS"),
        )
        public val ARIA_AIR: FakeDevice = FakeDevice(
            id = "3141592653",
            deviceVersion = "Aria Air",
            deviceType = "SCALE",
            batteryStatus = "Low",
            batteryLevel = 12,
            lastSyncTime = Instant.parse("2026-09-28T07:15:40Z"),
            macAddress = "A0:B1:C2:D3:E4:F6",
        )
    }
}

/**
 * An immutable, versioned model-mode dataset (docs/research/05 §8.1: "Datasets are versioned (V1, V2, ...) so a test
 * can change upstream data between sync runs"). Each change returns the next version.
 */
public class FakeDataset(
    public val points: List<FakePoint> = emptyList(),
    public val devices: List<FakeDevice> = emptyList(),
    public val version: Int = 1,
) {
    private val byType: Map<String, List<FakePoint>> = points.groupBy { it.type.id }

    public fun of(type: GhDataType): List<FakePoint> = byType[type.id].orEmpty()

    public operator fun plus(added: List<FakePoint>): FakeDataset = FakeDataset(points + added, devices, version + 1)

    /** Removes every point that matches [predicate] (an upstream deletion). */
    public fun minus(predicate: (FakePoint) -> Boolean): FakeDataset = FakeDataset(points.filterNot(predicate), devices, version + 1)

    /** Replaces the points that match [predicate] with [replacement] (an upstream edit or re-segmentation). */
    public fun replacing(predicate: (FakePoint) -> Boolean, replacement: List<FakePoint>): FakeDataset {
        val index = points.indexOfFirst(predicate)
        val kept = points.filterNot(predicate)
        val at = if (index < 0) kept.size else index.coerceAtMost(kept.size)
        return FakeDataset(kept.take(at) + replacement + kept.drop(at), devices, version + 1)
    }

    public fun withDevices(devices: List<FakeDevice>): FakeDataset = FakeDataset(points, devices, version + 1)

    public companion object {
        public val EMPTY: FakeDataset = FakeDataset()

        /** List fixtures whose points make up [documented]. */
        public val DOCUMENTED_LISTS: List<String> = listOf(
            "F-STEPS-P1", "F-STEPS-P2", "F-DISTANCE", "F-AEB", "F-FLOORS-RECONCILE", "F-HR", "F-RHR", "F-SLEEP", "F-EXERCISE",
            "F-WEIGHT", "F-BODYFAT",
        )

        /** The points of the documented list fixtures (§8.4) and both paired devices, for model-mode tests. */
        public fun documented(): FakeDataset = FakeDataset(
            points = DOCUMENTED_LISTS.flatMap { id ->
                GoogleHealthFixtures.points(id).mapNotNull { (it as? JsonObject)?.let(FakePoint::parse) }
            },
            devices = listOf(FakeDevice.CHARGE_6, FakeDevice.ARIA_AIR),
        )
    }
}

/** JSON shapes of the API (docs/research/05 §4.1, §4.3). */
public object PointJson {
    public fun timestamp(t: Instant): String = t.toString()

    public fun offset(seconds: Int): String = "${seconds}s"

    /** A number, written without a fraction when it is whole (as the fixtures do: `"weightGrams": 72450`). */
    public fun number(x: Double): JsonPrimitive = if (floor(x) == x && abs(x) < WHOLE_LIMIT) JsonPrimitive(x.toLong()) else JsonPrimitive(x)

    public fun date(d: LocalDate): JsonObject = buildJsonObject {
        put("year", d.year)
        put("month", d.month.number)
        put("day", d.day)
    }

    /** A `CivilDateTime`: zero-valued time members are omitted, so midnight is `"time": {}` (§4.1). */
    public fun civil(t: Instant, offsetSeconds: Int?): JsonObject = civil(localTime(t, offsetSeconds))

    public fun civil(local: LocalDateTime): JsonObject = buildJsonObject {
        put("date", date(local.date))
        put(
            "time",
            buildJsonObject {
                if (local.hour != 0) put("hours", local.hour)
                if (local.minute != 0) put("minutes", local.minute)
                if (local.second != 0) put("seconds", local.second)
                if (local.nanosecond != 0) put("nanos", local.nanosecond)
            },
        )
    }

    public fun interval(start: Instant, startOffset: Int?, end: Instant, endOffset: Int?, civil: Boolean): JsonObject = buildJsonObject {
        put("startTime", timestamp(start))
        startOffset?.let { put("startUtcOffset", offset(it)) }
        put("endTime", timestamp(end))
        endOffset?.let { put("endUtcOffset", offset(it)) }
        if (civil && startOffset != null) {
            put("civilStartTime", civil(start, startOffset))
            put("civilEndTime", civil(end, endOffset ?: startOffset))
        }
    }

    public fun sampleTime(t: Instant, offsetSeconds: Int?): JsonObject = buildJsonObject {
        put("physicalTime", timestamp(t))
        offsetSeconds?.let {
            put("utcOffset", offset(it))
            put("civilTime", civil(t, it))
        }
    }

    /** Local date-time of [t] in a fixed offset (UTC when the offset is unknown). */
    public fun localTime(t: Instant, offsetSeconds: Int?): LocalDateTime =
        t.toLocalDateTime(UtcOffset(seconds = offsetSeconds ?: 0).asTimeZone())

    internal fun parseDate(json: JsonObject): LocalDate? = runCatching {
        LocalDate(json.getValue("year").jsonPrimitive.int, json.getValue("month").jsonPrimitive.int, json.getValue("day").jsonPrimitive.int)
    }.getOrNull()

    internal fun parseOffset(text: String?): Int? = text?.removeSuffix("s")?.toDoubleOrNull()?.toInt()

    private const val WHOLE_LIMIT = 1e15
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.instant(key: String): Instant? = string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun JsonObject.offset(key: String): Int? = PointJson.parseOffset(string(key))

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement.doubleValue(): Double? = (this as? JsonPrimitive)?.let {
    it.contentOrNull?.toDoubleOrNull()
        ?: runCatching { it.double }.getOrNull()
}

internal fun JsonObject.copyWithout(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })

internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
