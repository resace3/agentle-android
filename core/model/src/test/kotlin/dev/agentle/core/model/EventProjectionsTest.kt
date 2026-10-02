package dev.agentle.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EventProjectionsTest {
    @Test
    fun `app usage projects the package and the session duration`() {
        val payload = AppUsagePayload("com.example.social", durationMs = 90_000)
        assertThat(EventProjections.subjectOf(payload)).isEqualTo("com.example.social")
        assertThat(EventProjections.valueOf(payload)).isEqualTo(90_000.0)
    }

    @Test
    fun `numeric health payloads project their measurement without a subject`() {
        assertThat(EventProjections.valueOf(StepsPayload(1234))).isEqualTo(1234.0)
        assertThat(EventProjections.valueOf(HeartRatePayload(61.5))).isEqualTo(61.5)
        assertThat(EventProjections.subjectOf(StepsPayload(1))).isNull()
    }

    @Test
    fun `sleep without a reported total sums the asleep stages in minutes`() {
        val stages = listOf(
            SleepStage(SleepStageKind.LIGHT, 0, 30 * 60_000L),
            SleepStage(SleepStageKind.AWAKE, 30 * 60_000L, 40 * 60_000L),
            SleepStage(SleepStageKind.DEEP, 40 * 60_000L, 100 * 60_000L),
        )
        assertThat(EventProjections.valueOf(SleepSessionPayload(stages))).isEqualTo(90.0)
        assertThat(EventProjections.valueOf(SleepSessionPayload(stages, minutesAsleep = 400))).isEqualTo(400.0)
        assertThat(EventProjections.valueOf(SleepSessionPayload())).isNull()
    }

    @Test
    fun `coordinates and personal text are never projected`() {
        val location = LocationSamplePayload(52.52, 13.405, placeClass = PlaceClass.HOME)
        assertThat(EventProjections.subjectOf(location)).isEqualTo("HOME")
        assertThat(EventProjections.valueOf(location)).isNull()
        val note = UserLogPayload(UserLogKind.NOTE, note = "personal text")
        assertThat(EventProjections.subjectOf(note)).isEqualTo("NOTE")
        assertThat(EventProjections.valueOf(note)).isNull()
        val notification = NotificationPayload("com.chat", title = "secret title", text = "secret text")
        assertThat(EventProjections.subjectOf(notification)).isEqualTo("com.chat")
    }

    @Test
    fun `activity transitions project enter as one and exit as zero`() {
        assertThat(EventProjections.valueOf(ActivityTransitionPayload(ActivityKind.WALKING, TransitionKind.ENTER))).isEqualTo(1.0)
        assertThat(EventProjections.valueOf(ActivityTransitionPayload(ActivityKind.WALKING, TransitionKind.EXIT))).isEqualTo(0.0)
        assertThat(EventProjections.subjectOf(ActivityTransitionPayload(ActivityKind.WALKING, TransitionKind.EXIT))).isEqualTo("WALKING")
    }

    @Test
    fun `unknown payloads project nothing`() {
        val unknown = UnknownPayload("future", "{}")
        assertThat(EventProjections.subjectOf(unknown)).isNull()
        assertThat(EventProjections.valueOf(unknown)).isNull()
    }

    @Test
    fun `day-keyed records project their metric, value and authoritative date`() {
        val date = kotlinx.datetime.LocalDate(2026, 10, 1)
        val total = DailyTotalPayload(date, DailyTotalMetric.STEPS, 8421.0)
        assertThat(EventProjections.subjectOf(total)).isEqualTo("STEPS")
        assertThat(EventProjections.valueOf(total)).isEqualTo(8421.0)
        assertThat(EventProjections.localDateOf(total)).isEqualTo(date)
        val resting = RestingHeartRatePayload(54.0, date)
        assertThat(EventProjections.valueOf(resting)).isEqualTo(54.0)
        assertThat(EventProjections.localDateOf(resting)).isEqualTo(date)
        assertThat(EventProjections.localDateOf(StepsPayload(3))).isNull()
    }
}
