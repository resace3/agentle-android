package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.BodyFatPayload
import dev.agentle.core.model.CaloriesPayload
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DailyTotalPayload
import dev.agentle.core.model.DistancePayload
import dev.agentle.core.model.EnergyBasis
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.FloorsPayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.Sensitivity
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.WearableDevicePayload
import dev.agentle.core.model.WeightPayload
import dev.agentle.fakes.googlehealth.GoogleHealthFixtures
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Instant

class GhMapperTest {
    private val zone = TimeZone.of("America/New_York")
    private val mapper = GhMapper(GhHarness.ACCOUNT, zone, GhHarness.START)
    private val streams = GhStreams.catalog(GoogleHealthConfig()).associateBy { it.id }
    private val rawStreams = GhStreams.catalog(GoogleHealthConfig(rawHeartRate = true)).associateBy { it.id }

    private fun stream(id: String): GhStream = streams.getValue(id)

    /** Steps read through `list` (not the default): points keep their provenance and a source key. */
    private val listSteps = stream(GoogleHealthStreams.STEPS).copy(method = GhMethod.LIST)

    private fun points(fixture: String, key: String = "dataPoints"): List<JsonObject> =
        Json.parseToJsonElement(GoogleHealthFixtures.text(fixture)).jsonObject.getValue(key).jsonArray.map { it.jsonObject }

    private fun point(fixture: String): JsonObject = Json.parseToJsonElement(GoogleHealthFixtures.text(fixture)).jsonObject

    private fun mapped(stream: GhStream, fixture: String): List<Mapped> = points(fixture).map { mapper.point(stream, it) }

    private fun events(stream: GhStream, fixture: String): List<PersonalEvent> = mapped(stream, fixture).map { (it as Mapped.Event).event }

    private inline fun <reified T> PersonalEvent.payloadAs(): T = payload as T

    @Test
    fun `steps, distance, active energy and floors are read through reconcile`() {
        val reconciled = listOf(
            GoogleHealthStreams.STEPS,
            GoogleHealthStreams.DISTANCE,
            GoogleHealthStreams.ACTIVE_ENERGY,
            GoogleHealthStreams.FLOORS,
        )
        assertThat(streams.values.filter { it.method == GhMethod.RECONCILE }.map { it.id }).containsExactlyElementsIn(reconciled)
        assertThat(streams.values.filter { it.kind == GhKind.INTERVAL }.map { it.id }).containsExactlyElementsIn(reconciled)
    }

    @Test
    fun `R05 8 7 S07 R6a list-read steps keep both devices' overlapping rows, the Health Connect row and a true zero`() {
        val events = events(listSteps, "F-STEPS-P1") + events(listSteps, "F-STEPS-P2")
        assertThat(events.map { it.payloadAs<StepsPayload>().count }).containsExactly(112L, 98L, 240L, 87L, 0L).inOrder()
        assertThat(events.map { it.dedupKey }.toSet()).hasSize(5)
        assertThat(events.map { it.id }.toSet()).hasSize(5)
        val healthConnect = events[2]
        assertThat(healthConnect.metadata.provenance?.platform).isEqualTo("HEALTH_CONNECT")
        assertThat(healthConnect.endTime).isEqualTo(Instant.parse("2026-09-30T12:05:00Z"))
        assertThat(events.all { it.dedupKey.startsWith("gh|${GhHarness.ACCOUNT}|steps|") }).isTrue()
        assertThat(events.all { it.zoneId == "America/New_York" }).isTrue()
        assertThat(events.all { it.metadata.payloadHash?.length == 32 }).isTrue()
        assertThat(events[0].metadata.provenance?.deviceName).isEqualTo("Charge 6")
        assertThat(events[0].metadata.origin).isEqualTo("Charge 6")
    }

    @Test
    fun `R05 8 7 S09 reconcile points have no provenance and are keyed by their interval alone`() {
        val events = events(stream(GoogleHealthStreams.STEPS), "F-STEPS-RECONCILE")
        assertThat(events).hasSize(4)
        assertThat(events.all { it.metadata.provenance == null && it.metadata.origin == null }).isTrue()
        assertThat(events.map { it.payloadAs<StepsPayload>().count }).containsExactly(112L, 98L, 87L, 0L).inOrder()
        val start = Instant.parse("2026-09-30T12:02:00Z").toEpochMilliseconds()
        assertThat(events[0].dedupKey).isEqualTo("gh|${GhHarness.ACCOUNT}|steps|$start|${start + 60_000}")
        // A dataSource on a reconciled point (never documented) is not trusted: the stream is merged across devices.
        val withSource = (mapper.point(stream(GoogleHealthStreams.STEPS), point("R5G")) as Mapped.Event).event
        assertThat(withSource.metadata.provenance).isNull()
        assertThat(withSource.dedupKey.split('|')).hasSize(5)
    }

    @Test
    fun `R05 8 7 S10 steps rollUp windows keep the leading zero window`() {
        val rollUp = stream(GoogleHealthStreams.STEPS).copy(method = GhMethod.ROLL_UP, kind = GhKind.ROLL_UP)
        val events = points("F-STEPS-ROLLUP", "rollupDataPoints").map { (mapper.rollUp(rollUp, it) as Mapped.Event).event }
        assertThat(events.map { it.payloadAs<StepsPayload>().count }).containsExactly(0L, 87L, 98L, 112L).inOrder()
        assertThat(events.first().startTime).isEqualTo(Instant.parse("2026-09-30T11:59:00Z"))
    }

    @Test
    fun `R05 8 7 S11 S12 R9c daily rollups are keyed by their civil date`() {
        val steps = points("F-DAILY-STEPS", "rollupDataPoints").map {
            (mapper.dailyRollUp(stream(GoogleHealthStreams.DAILY_STEPS), it) as Mapped.Event).event.payloadAs<DailyTotalPayload>()
        }
        assertThat(steps).containsExactly(
            DailyTotalPayload(LocalDate(2026, 9, 29), DailyTotalMetric.STEPS, 10412.0),
            DailyTotalPayload(LocalDate(2026, 9, 30), DailyTotalMetric.STEPS, 8530.0),
        ).inOrder()
        val calories = points("F-DAILY-TOTALCAL", "rollupDataPoints").map {
            (mapper.dailyRollUp(stream(GoogleHealthStreams.DAILY_TOTAL_CALORIES), it) as Mapped.Event).event
        }
        assertThat(calories.map { it.payloadAs<DailyTotalPayload>().value }).containsExactly(2315.5, 2198.25).inOrder()
        // The civil day starts at midnight in the account zone; the payload keeps the date itself.
        assertThat(calories[0].startTime).isEqualTo(Instant.parse("2026-09-29T04:00:00Z"))
        assertThat(calories[0].dedupKey).endsWith("|2026-09-29")
    }

    @Test
    fun `R05 8 6 R5h a missing day stays absent`() {
        val days = points("R5H", "rollupDataPoints").map {
            (mapper.dailyRollUp(stream(GoogleHealthStreams.DAILY_STEPS), it) as Mapped.Event).event.payloadAs<DailyTotalPayload>().date
        }
        assertThat(days).containsExactly(LocalDate(2026, 9, 28), LocalDate(2026, 9, 30)).inOrder()
    }

    @Test
    fun `R05 8 7 S13 raw heart-rate samples keep motion context and sensor location only where sent`() {
        val events = events(rawStreams.getValue(GoogleHealthStreams.HEART_RATE), "F-HR")
        val payloads = events.map { it.payloadAs<HeartRatePayload>() }
        assertThat(payloads.map { it.bpm }).containsExactly(74.0, 73.0, 72.0).inOrder()
        assertThat(payloads.filter { it.motionContext != null }.map { it.bpm }).containsExactly(73.0)
        assertThat(payloads[1].sensorLocation).isEqualTo("WRIST")
        assertThat(payloads[1].motionContext).isEqualTo("SEDENTARY")
        assertThat(events.all { it.endTime == null }).isTrue()
    }

    @Test
    fun `R05 8 7 S14 heart-rate rollup windows carry min, average and max`() {
        val events = points("F-HR-ROLLUP", "rollupDataPoints").map {
            (mapper.rollUp(stream(GoogleHealthStreams.HEART_RATE), it) as Mapped.Event).event.payloadAs<HeartRatePayload>()
        }
        assertThat(events[1]).isEqualTo(HeartRatePayload(73.0, minBpm = 72.0, maxBpm = 74.0))
        assertThat(events[0].bpm).isEqualTo(71.4)
    }

    @Test
    fun `R05 8 7 S15 R5f resting heart rate is a civil-date value with its calculation method`() {
        val events = events(stream(GoogleHealthStreams.RESTING_HEART_RATE), "F-RHR")
        assertThat(events.map { it.payloadAs<RestingHeartRatePayload>() }).containsExactly(
            RestingHeartRatePayload(54.0, LocalDate(2026, 9, 30), "WITH_SLEEP"),
            RestingHeartRatePayload(55.0, LocalDate(2026, 9, 29), "ONLY_WITH_AWAKE_DATA"),
        ).inOrder()
        val noMetadata = (mapper.point(stream(GoogleHealthStreams.RESTING_HEART_RATE), point("R5F")) as Mapped.Event).event
        assertThat(noMetadata.payloadAs<RestingHeartRatePayload>().calculationMethod).isNull()
    }

    @Test
    fun `R05 8 7 S16 sleep sessions keep stages, summary and short awakenings as given`() {
        val events = events(stream(GoogleHealthStreams.SLEEP), "F-SLEEP")
        assertThat(events).hasSize(2)
        val main = events.map { it.payloadAs<SleepSessionPayload>() }.single { it.isMainSleep }
        assertThat(main.stages).hasSize(9)
        assertThat(main.minutesAsleep).isEqualTo(475)
        assertThat(main.minutesAwake).isEqualTo(17)
        assertThat(main.minutesInSleepPeriod).isEqualTo(492)
        assertThat(main.shortAwakenings).hasSize(1)
        assertThat(main.processed).isTrue()
        val nap = events.map { it.payloadAs<SleepSessionPayload>() }.single { it.isNap }
        assertThat(nap.isMainSleep).isFalse()
        assertThat(nap.sleepType).isEqualTo("CLASSIC")
        assertThat(nap.stagesStatus).isEqualTo("REJECTED_NAP")
        assertThat(events.all { it.metadata.upstreamId?.startsWith("users/me/dataTypes/sleep/dataPoints/") == true }).isTrue()
        assertThat(events.all { it.metadata.upstreamUpdatedAt != null }).isTrue()
    }

    @Test
    fun `R05 8 7 S17 exercise sessions keep metrics, events and notes`() {
        val events = events(stream(GoogleHealthStreams.EXERCISE), "F-EXERCISE")
        val run = events.single { it.payloadAs<ExercisePayload>().exerciseType == "RUNNING" }.payloadAs<ExercisePayload>()
        assertThat(run.activeDurationMs).isEqualTo(2_050_000L)
        assertThat(run.distanceMeters).isEqualTo(5012.0)
        assertThat(run.events).hasSize(4)
        assertThat(run.hasGps).isTrue()
        val walk = events.single { it.payloadAs<ExercisePayload>().exerciseType == "WALKING" }
        assertThat(walk.metadata.provenance?.recordingMethod).isEqualTo("MANUAL")
        assertThat(walk.metadata.provenance?.deviceName).isNull()
        assertThat(walk.payloadAs<ExercisePayload>().notes).isNotNull()
        assertThat(walk.metadata.sensitivity).isEqualTo(Sensitivity.PERSONAL)
    }

    @Test
    fun `R05 8 7 S18 R6d weight and body fat keep fractions and are keyed by name`() {
        val weights = events(stream(GoogleHealthStreams.WEIGHT), "F-WEIGHT")
        assertThat(weights.map { it.payloadAs<WeightPayload>().kilograms }).containsExactly(72.45, 72.8).inOrder()
        assertThat(weights[0].startTime).isEqualTo(Instant.parse("2026-09-30T11:15:22.5Z"))
        assertThat(weights[0].metadata.provenance?.formFactor).isEqualTo("SCALE")
        assertThat(weights[1].payloadAs<WeightPayload>().notes).isEqualTo("after dinner")
        assertThat(weights[1].metadata.sensitivity).isEqualTo(Sensitivity.PERSONAL)
        val bodyFat = events(stream(GoogleHealthStreams.BODY_FAT), "F-BODYFAT").single()
        assertThat(bodyFat.payloadAs<BodyFatPayload>().percent).isEqualTo(18.4)
        // R6d: the reconcile copy names the same data point, so it has the same key (no second row).
        val reconciled = events(stream(GoogleHealthStreams.WEIGHT), "R6D").single()
        assertThat(reconciled.dedupKey).isEqualTo(weights[0].dedupKey)
        assertThat(reconciled.metadata.provenance).isNull()
    }

    @Test
    fun `R05 8 7 S19 paired devices keep battery and last sync but never the MAC address`() {
        val devices = (points("F-DEVICES-P1", "pairedDevices") + points("F-DEVICES-P2", "pairedDevices")).map {
            mapper.device(stream(GoogleHealthStreams.DEVICES), it)
        }
        val payloads = devices.map { (it.first as Mapped.Event).event.payloadAs<WearableDevicePayload>() }
        assertThat(payloads.map { it.batteryPercent }).containsExactly(82, 12).inOrder()
        assertThat(payloads.map { it.batteryStatus }).containsExactly("High", "Low").inOrder()
        assertThat(devices.map { it.second?.deviceType }).containsExactly("TRACKER", "SCALE").inOrder()
        assertThat(devices[0].second?.lastSync).isEqualTo(Instant.parse("2026-10-01T11:58:03Z"))
        assertThat(payloads.toString()).doesNotContain("A0:B1")
        assertThat(payloads.map { it.deviceIdHash }.toSet()).hasSize(2)
    }

    @Test
    fun `R05 8 4 distance, active energy and floors convert units`() {
        val distance = events(stream(GoogleHealthStreams.DISTANCE), "F-DISTANCE").map { it.payloadAs<DistancePayload>().meters }
        assertThat(distance).containsExactly(84.0, 73.5).inOrder()
        val energy = events(stream(GoogleHealthStreams.ACTIVE_ENERGY), "F-AEB").map { it.payloadAs<CaloriesPayload>() }
        assertThat(energy.map { it.basis }.toSet()).containsExactly(EnergyBasis.ACTIVE)
        assertThat(energy[0].kilocalories).isEqualTo(4.25)
        val floors = events(stream(GoogleHealthStreams.FLOORS), "F-FLOORS-RECONCILE").map { it.payloadAs<FloorsPayload>().floors }
        assertThat(floors).containsExactly(2.0, 1.0).inOrder()
    }

    @Test
    fun `R05 8 6 R4 unknown fields are ignored, odd union keys skipped and int64 numbers accepted`() {
        val mapped = mapped(listSteps, "R4-STEPS")
        assertThat(mapped.filterIsInstance<Mapped.Skip>()).hasSize(2)
        val events = mapped.filterIsInstance<Mapped.Event>().map { it.event }
        assertThat(events.map { it.payloadAs<StepsPayload>().count }).containsExactly(61L, 42L).inOrder()
        // Unknown enum values stay as sent (the raw string is the stored value).
        assertThat(events[0].metadata.provenance?.recordingMethod).isEqualTo("MANUALLY_ENTERED")
        assertThat(events[0].metadata.provenance?.platform).isEqualTo("WEAR_OS")
        assertThat(events[0].metadata.provenance?.formFactor).isEqualTo("GLASSES")
    }

    @Test
    fun `R05 8 6 R4 an unknown exercise type is UNKNOWN with the raw value and the codelab spelling is accepted`() {
        val exercise = events(stream(GoogleHealthStreams.EXERCISE), "R4-EXERCISE").single().payloadAs<ExercisePayload>()
        assertThat(exercise.exerciseType).isEqualTo(GhSessionMapper.UNKNOWN)
        assertThat(exercise.rawExerciseType).isEqualTo("FUTURE_SPORT_X")
        assertThat(exercise.distanceMeters).isEqualTo(1284.3)
        assertThat(exercise.activeDurationMs).isEqualTo(1_025_000L)
        assertThat(exercise.steps).isEqualTo(1606L)
    }

    @Test
    fun `R05 8 6 R4 an unknown sleep stage keeps its raw name`() {
        val sleep = point("R5A").toMutableMap()
        val value = sleep.getValue("sleep").jsonObject.toMutableMap()
        value["stages"] = kotlinx.serialization.json.JsonArray(listOf(point("R4-SLEEP-STAGE")))
        sleep["sleep"] = JsonObject(value)
        val payload = (
            mapper.point(
                stream(GoogleHealthStreams.SLEEP),
                JsonObject(sleep),
            ) as Mapped.Event
            ).event.payloadAs<SleepSessionPayload>()
        assertThat(payload.stages.single().stage).isEqualTo(SleepStageKind.UNKNOWN)
        assertThat(payload.stages.single().rawStage).isEqualTo("MICRO_AWAKE")
    }

    @Test
    fun `R05 8 6 R5a a sleep session still processing is stored unprocessed without stages`() {
        val payload = (mapper.point(stream(GoogleHealthStreams.SLEEP), point("R5A")) as Mapped.Event).event.payloadAs<SleepSessionPayload>()
        assertThat(payload.processed).isFalse()
        assertThat(payload.stages).isEmpty()
        assertThat(payload.isMainSleep).isTrue()
        assertThat(payload.isNap).isFalse()
    }

    @Test
    fun `R05 8 6 R5c R5d heart-rate samples without civil time, offset or source are stored`() {
        val hr = rawStreams.getValue(GoogleHealthStreams.HEART_RATE)
        val withOffset = (mapper.point(hr, point("R5C")) as Mapped.Event).event
        assertThat(withOffset.zoneId).isEqualTo("America/New_York")
        val bare = (mapper.point(hr, point("R5D")) as Mapped.Event).event
        assertThat(bare.metadata.provenance).isNull()
        assertThat(bare.zoneId).isEqualTo("America/New_York")
        assertThat(bare.dedupKey).endsWith("|-")
    }

    @Test
    fun `R05 8 6 R5e exercise without metrics keeps nulls and takes its duration from the interval`() {
        val exercise = (mapper.point(stream(GoogleHealthStreams.EXERCISE), point("R5E")) as Mapped.Event).event.payloadAs<ExercisePayload>()
        assertThat(exercise.durationMs).isEqualTo(20 * 60_000L)
        assertThat(exercise.distanceMeters).isNull()
        assertThat(exercise.activeDurationMs).isNull()
        assertThat(exercise.displayName).isNull()
        assertThat(exercise.events).isEmpty()
    }

    @Test
    fun `R05 8 6 R5g a missing end offset falls back to the start offset`() {
        val event = (mapper.point(stream(GoogleHealthStreams.STEPS), point("R5G")) as Mapped.Event).event
        assertThat(event.payloadAs<StepsPayload>().count).isEqualTo(33L)
        assertThat(event.zoneId).isEqualTo("America/New_York")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "R5I, steps, interval",
        "R5J-1, steps, interval",
        "R5J-2, steps, value",
        "R5J-3, heart_rate, value",
        "R5J-4, steps, interval",
    )
    fun `R05 8 6 R5i R5j invalid points are skipped with a reason`(fixture: String, streamId: String, reason: String) {
        val target = if (streamId == GoogleHealthStreams.HEART_RATE) rawStreams.getValue(streamId) else stream(streamId)
        assertThat(mapper.point(target, point(fixture))).isEqualTo(Mapped.Skip(reason))
    }

    @Test
    fun `R05 8 6 R5k a point without dataSource has empty provenance and its own source key`() {
        val event = (mapper.point(listSteps, point("R5K")) as Mapped.Event).event
        assertThat(event.metadata.provenance).isNull()
        assertThat(event.dedupKey).endsWith("|-")
    }

    @Test
    fun `R05 8 6 R9a a sleep session across a DST change keeps both offsets`() {
        val event = (mapper.point(stream(GoogleHealthStreams.SLEEP), point("R9A")) as Mapped.Event).event
        val payload = event.payloadAs<SleepSessionPayload>()
        assertThat(requireNotNull(event.endTime) - event.startTime).isEqualTo(kotlin.time.Duration.parse("520m"))
        assertThat(payload.startUtcOffsetSeconds).isEqualTo(-14_400)
        assertThat(payload.endUtcOffsetSeconds).isEqualTo(-18_000)
        assertThat(payload.stages.map { it.endUtcOffsetSeconds }).containsExactly(-14_400, -18_000, -18_000).inOrder()
    }

    @Test
    fun `R05 8 6 R9b a record from another offset keeps its own zone`() {
        val event = (mapper.point(stream(GoogleHealthStreams.STEPS), point("R9B")) as Mapped.Event).event
        assertThat(event.zoneId).isEqualTo("+01:00")
        assertThat(event.payloadAs<StepsPayload>().count).isEqualTo(64L)
    }

    @Test
    fun `round-2 4 platform and application never enter the key or the payload hash`() {
        val original = point("R5G")
        val changed = JsonObject(
            original.toMutableMap().apply {
                val source = getValue("dataSource").jsonObject.toMutableMap()
                source["platform"] = kotlinx.serialization.json.JsonPrimitive("HEALTH_CONNECT")
                source["application"] = Json.parseToJsonElement("""{"packageName": "com.example"}""")
                put("dataSource", JsonObject(source))
            },
        )
        val a = (mapper.point(listSteps, original) as Mapped.Event).event
        val b = (mapper.point(listSteps, changed) as Mapped.Event).event
        assertThat(b.dedupKey).isEqualTo(a.dedupKey)
        assertThat(b.metadata.payloadHash).isEqualTo(a.metadata.payloadHash)
        assertThat(b.metadata.origin).isEqualTo("com.example")
    }

    @Test
    fun `a point of another type or with two union keys is skipped`() {
        val steps = point("R5G")
        assertThat(mapper.point(stream(GoogleHealthStreams.DISTANCE), steps)).isEqualTo(Mapped.Skip("union"))
        assertThat(mapper.point(stream(GoogleHealthStreams.DEVICES), steps)).isEqualTo(Mapped.Skip("union"))
        val rollUp = stream(GoogleHealthStreams.STEPS).copy(kind = GhKind.ROLL_UP)
        assertThat(mapper.point(rollUp, steps)).isEqualTo(Mapped.Skip("kind"))
    }

    @Test
    fun `rollups and devices with broken shapes are skipped`() {
        val hr = stream(GoogleHealthStreams.HEART_RATE)
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        assertThat(mapper.rollUp(hr, json("""{"endTime": "2026-09-30T12:01:00Z"}"""))).isEqualTo(Mapped.Skip("interval"))
        assertThat(mapper.rollUp(hr, json("""{"startTime": "2026-09-30T12:01:00Z", "endTime": "2026-09-30T12:01:00Z"}""")))
            .isEqualTo(Mapped.Skip("interval"))
        assertThat(mapper.rollUp(hr, json("""{"startTime": "2026-09-30T12:00:00Z", "endTime": "2026-09-30T12:01:00Z"}""")))
            .isEqualTo(Mapped.Skip("union"))
        assertThat(mapper.rollUp(hr, json("""{"startTime": "2026-09-30T12:00:00Z", "endTime": "2026-09-30T12:01:00Z", "heartRate": {}}""")))
            .isEqualTo(Mapped.Skip("value"))
        val daily = stream(GoogleHealthStreams.DAILY_STEPS)
        assertThat(mapper.dailyRollUp(daily, json("""{"steps": {"countSum": "1"}}"""))).isEqualTo(Mapped.Skip("date"))
        assertThat(mapper.dailyRollUp(daily, json("""{"civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}}}""")))
            .isEqualTo(Mapped.Skip("union"))
        assertThat(mapper.dailyRollUp(hr, json("""{"civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}}}""")))
            .isEqualTo(Mapped.Skip("type"))
        assertThat(mapper.device(stream(GoogleHealthStreams.DEVICES), json("""{"deviceType": "TRACKER"}""")).first)
            .isEqualTo(Mapped.Skip("name"))
    }

    @Test
    fun `resource names are stored without the raw user id`() {
        assertThat(
            GhMapper.normalizedName("users/1234567890/dataTypes/sleep/dataPoints/7"),
        ).isEqualTo("users/me/dataTypes/sleep/dataPoints/7")
        assertThat(GhMapper.normalizedName("dataPoints/7")).isEqualTo("dataPoints/7")
        assertThat(GhMapper.sourceKey(null)).isEqualTo("-")
    }
}
