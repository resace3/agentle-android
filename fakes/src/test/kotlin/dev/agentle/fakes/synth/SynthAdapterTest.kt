package dev.agentle.fakes.synth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.StepsPayload
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test

class SynthAdapterTest {
    private val spec = SynthSpec(seed = 42)
    private val raw = SyntheticUserTest.events
    private val adapter = SynthAdapter(spec)
    private val all by lazy { adapter.all() }

    @Test
    fun `every raw event except wear-off becomes exactly one event, plus one resting heart rate per recorded day`() {
        val mapped = raw.count { it.type != SynthEventType.WEAR_OFF }
        assertThat(all).hasSize(mapped + 88)
        assertThat(all.map { it.dedupKey }.toSet()).hasSize(all.size)
        assertThat(all.map { it.id }.toSet()).hasSize(all.size)
        assertThat(all.map { it.startTime }).isInOrder()
    }

    @Test
    fun `the same spec always gives identical events`() {
        assertThat(SynthAdapter(spec).all()).isEqualTo(all)
    }

    @Test
    fun `step totals equal the raw minutes per source`() {
        val wearable = all.filter { it.source == DataSourceId("googlehealth.steps") }
        val phone = all.filter { it.source == DataSourceId("android.steps") }
        assertThat(wearable.sumOf { (it.payload as StepsPayload).count })
            .isEqualTo(raw.filter { it.type == SynthEventType.STEPS_MINUTE }.sumOf { it.value.toLong() })
        assertThat(phone.sumOf { (it.payload as StepsPayload).count })
            .isEqualTo(raw.filter { it.type == SynthEventType.PHONE_STEPS_MINUTE }.sumOf { it.value.toLong() })
        assertThat(wearable.all { it.type == EventType.STEP_SAMPLE && it.endTime != null }).isTrue()
        assertThat(phone.first().metadata.provenance?.formFactor).isEqualTo("PHONE")
        assertThat(wearable.first().metadata.provenance?.deviceName).isEqualTo(SynthAdapter.DEVICE_NAME)
    }

    @Test
    fun `steps on the 25-hour Berlin day equal the per-minute sums in the zone the user is in`() {
        val day = LocalDate(2026, 10, 25)
        val berlin = TimeZone.of("Europe/Berlin")
        val adapted = all.filter {
            it.type == EventType.STEP_SAMPLE && it.source.stream == "steps" &&
                it.source.connectorId == "googlehealth"
        }
            .filter { it.startTime.toLocalDateTime(TimeZone.of(it.zoneId)).date == day }
        assertThat(adapted.map { it.zoneId }.toSet()).containsExactly("Europe/Berlin")
        val expected = raw.filter {
            it.type == SynthEventType.STEPS_MINUTE && it.start.toLocalDateTime(berlin).date == day &&
                it.zone == berlin
        }
            .sumOf { it.value.toLong() }
        assertThat(adapted.sumOf { (it.payload as StepsPayload).count }).isEqualTo(expected)
        assertThat(expected).isGreaterThan(0)
    }

    @Test
    fun `sleep sessions carry stages, totals and both offsets`() {
        val sleeps = all.filter { it.type == EventType.SLEEP_SESSION }
        assertThat(sleeps).hasSize(raw.count { it.type == SynthEventType.SLEEP_SESSION })
        for (event in sleeps) {
            val payload = event.payload as SleepSessionPayload
            assertThat(payload.stages).isNotEmpty()
            assertThat(payload.stages.first().startEpochMs).isEqualTo(event.startTime.toEpochMilliseconds())
            assertThat(payload.stages.last().endEpochMs).isEqualTo(event.endTime!!.toEpochMilliseconds())
            assertThat(payload.minutesAsleep!! + payload.minutesAwake!!).isEqualTo(payload.minutesInSleepPeriod)
            assertThat(payload.sleepType).isEqualTo("STAGES")
        }
        // The night into 2026-11-01 crosses the end of US DST: the offsets differ.
        val dstNight = sleeps.first { it.endTime!!.toLocalDateTime(TimeZone.of(it.zoneId)).date == LocalDate(2026, 11, 1) }
        val payload = dstNight.payload as SleepSessionPayload
        assertThat(payload.startUtcOffsetSeconds).isEqualTo(-4 * 3600)
        assertThat(payload.endUtcOffsetSeconds).isEqualTo(-5 * 3600)
    }

    @Test
    fun `exercise, heart rate and resting heart rate are typed`() {
        val exercise = all.filter { it.type == EventType.EXERCISE_SESSION }
        assertThat(exercise).isNotEmpty()
        exercise.forEach {
            val p = it.payload as ExercisePayload
            assertThat(p.exerciseType).isAnyOf("RUNNING", "WALKING")
            assertThat(p.steps!!).isGreaterThan(0L)
            assertThat(p.durationMs).isEqualTo((it.endTime!! - it.startTime).inWholeMilliseconds)
        }
        val hr = all.filter { it.type == EventType.HEART_RATE }
        assertThat(hr).hasSize(raw.count { it.type == SynthEventType.HEART_RATE_SAMPLE })
        assertThat(hr.all { it.endTime == null && (it.payload as HeartRatePayload).bpm in 20.0..220.0 }).isTrue()
        val rhr = all.filter { it.type == EventType.RESTING_HEART_RATE }
        assertThat(rhr.map { (it.payload as RestingHeartRatePayload).date }).containsNoDuplicates()
        assertThat(rhr.first().zoneId).isEqualTo("America/New_York")
    }

    @Test
    fun `wear-off has no event, single exercise events without context have no totals, and payloads encode`() {
        val wearOff = raw.first { it.type == SynthEventType.WEAR_OFF }
        assertThat(adapter.toPersonalEvent(wearOff)).isNull()
        val session = raw.first { it.type == SynthEventType.EXERCISE_SESSION }
        val bare = adapter.toPersonalEvent(session)!!.payload as ExercisePayload
        assertThat(bare.steps).isNull()
        assertThat(bare.distanceMeters).isNull()
        all.take(500).forEach { assertThat(EventCodec.decode(EventCodec.encode(it.payload))).isEqualTo(it.payload) }
    }

    @Test
    fun `connector id and phone source are configurable`() {
        val custom = SynthAdapter(spec, wearableConnectorId = "healthconnect", phoneSource = DataSourceId("android.recording_steps"))
        val events = custom.toPersonalEvents(raw.take(200))
        assertThat(events.map { it.source.connectorId }.toSet()).containsAnyOf("healthconnect", "android")
        assertThat(events.filter { it.source.connectorId == "android" }.map { it.source.stream }.toSet())
            .containsExactly("recording_steps")
    }
}
