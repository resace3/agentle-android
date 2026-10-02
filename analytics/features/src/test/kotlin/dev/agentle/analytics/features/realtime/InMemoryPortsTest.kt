package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.analytics.features.realtime.testing.InMemoryRealtimeInputs
import dev.agentle.analytics.features.realtime.testing.LiveRead
import dev.agentle.analytics.features.realtime.testing.PortRead
import dev.agentle.analytics.features.realtime.testing.StepSourceSpec
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.DndPayload
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.SystemEventPayload
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The in-memory ports other teams reuse: event mapping, canonical sources, overrides and the read log. */
class InMemoryPortsTest {
    private val t0 = Instant.parse("2026-10-01T20:00:00Z")
    private var keys = 0

    private fun event(
        type: EventType,
        source: String,
        payload: EventPayload,
        start: Instant = t0,
        end: Instant? = null,
        zone: String = "Europe/Berlin",
    ) = PersonalEvent(
        id = EventId("00000000-0000-4000-8000-${(keys++).toString().padStart(12, '0')}"),
        type = type,
        source = DataSourceId(source),
        startTime = start,
        endTime = end,
        zoneId = zone,
        payload = payload,
        dedupKey = "test|$source|$keys",
        metadata = EventMetadata(ingestedAt = t0),
    )

    private fun range(from: Instant, until: Instant) = ClosedOpenRange(from, until)

    private val everything = range(t0 - 24.hours, t0 + 24.hours)

    private fun <T> Outcome<InputAnswer<T>>.available(): T = ((this as Outcome.Success).value as InputAnswer.Available).value

    @Test
    fun `device events become usage, notification and activity rows`() = runTest {
        val app = "com.instagram.android"
        val inputs = InMemoryRealtimeInputs.fromEvents(
            listOf(
                event(EventType.APP_FOREGROUND, "android.app_usage", AppUsagePayload(app, appCategory = "social"), t0),
                event(EventType.APP_BACKGROUND, "android.app_usage", AppUsagePayload(app), t0 + 5.minutes),
                event(
                    EventType.APP_SESSION,
                    "android.app_usage",
                    AppUsagePayload(CHAT, appCategory = "not-a-category"),
                    t0,
                    t0 + 2.minutes,
                ),
                event(EventType.SCREEN_ON, "android.screen", ScreenPayload(interactive = true), t0 + 1.minutes),
                event(EventType.SCREEN_OFF, "android.screen", ScreenPayload(interactive = false), t0 + 2.minutes),
                event(EventType.SCREEN_SESSION, "android.screen", ScreenPayload(interactive = true), t0 + 3.minutes, t0 + 4.minutes),
                event(EventType.DEVICE_UNLOCK, "android.screen", ScreenPayload(interactive = true), t0 + 5.minutes),
                event(EventType.DEVICE_LOCK, "android.screen", ScreenPayload(interactive = false), t0 + 6.minutes),
                event(EventType.SHUTDOWN, "android.power", SystemEventPayload(), t0 + 7.minutes),
                event(EventType.BOOT_COMPLETED, "android.power", SystemEventPayload(), t0 + 8.minutes),
                event(EventType.NOTIFICATION_POSTED, "android.notifications", NotificationPayload(CHAT, keyHash = "k1"), t0),
                event(EventType.NOTIFICATION_POSTED, "android.notifications", NotificationPayload(MAPS, ongoing = true), t0),
                event(EventType.NOTIFICATION_REMOVED, "android.notifications", NotificationPayload(CHAT, keyHash = "k1"), t0),
                event(EventType.ACTIVITY, "android.activity", ActivityTransitionPayload(ActivityKind.WALKING, TransitionKind.ENTER), t0),
                event(EventType.DND_CHANGED, "android.dnd", DndPayload(interruptionFilter = 2), t0),
            ),
        )

        val usage = inputs.usage.events(everything).available()
        assertThat(usage.map { it.kind to it.packageName }).containsExactly(
            UsageEventKind.ACTIVITY_RESUMED to app,
            UsageEventKind.ACTIVITY_RESUMED to CHAT,
            UsageEventKind.SCREEN_INTERACTIVE to null,
            UsageEventKind.ACTIVITY_PAUSED to CHAT,
            UsageEventKind.SCREEN_NON_INTERACTIVE to null,
            UsageEventKind.SCREEN_INTERACTIVE to null,
            UsageEventKind.SCREEN_NON_INTERACTIVE to null,
            UsageEventKind.ACTIVITY_PAUSED to app,
            UsageEventKind.KEYGUARD_HIDDEN to null,
            UsageEventKind.KEYGUARD_SHOWN to null,
            UsageEventKind.DEVICE_SHUTDOWN to null,
            UsageEventKind.DEVICE_STARTUP to null,
        ).inOrder()
        // Only catalog categories are kept; others are UNDEFINED.
        assertThat(inputs.usage.appCategories(setOf(app, CHAT)).available()).containsExactly(app, "SOCIAL", CHAT, "UNDEFINED")
        val posts = inputs.notifications.posted(everything).available()
        assertThat(posts.map { it.packageName to it.ongoing }).containsExactly(CHAT to false, MAPS to true)
        assertThat(posts[0].keyHash).isEqualTo("k1")
        assertThat(posts[1].keyHash).startsWith("test|android.notifications|")
        assertThat(inputs.activity.transitions(everything).available())
            .containsExactly(ActivityTransition(t0, ActivityKind.WALKING, TransitionKind.ENTER))
        // No step event: no step source is connected.
        assertThat(inputs.steps.fusedMinuteSeries(everything)).isEqualTo(InputAnswer.unavailable(MissingReason.NO_DATA))
    }

    @Test
    fun `health events go to the canonical source of each metric, chosen by connector priority`() = runTest {
        val kolkata = 19_800
        val googleSleep = SleepSessionPayload(
            stages = listOf(
                SleepStage(SleepStageKind.LIGHT, (t0).toEpochMilliseconds(), (t0 + 2.hours).toEpochMilliseconds()),
                SleepStage(SleepStageKind.REM, (t0 + 2.hours).toEpochMilliseconds(), (t0 + 1.hours).toEpochMilliseconds()),
            ),
            outOfBedSegments = listOf(
                SleepStage(SleepStageKind.AWAKE, (t0 + 2.hours).toEpochMilliseconds(), (t0 + 3.hours).toEpochMilliseconds()),
            ),
            minutesAsleep = 400,
            isNap = false,
            processed = false,
            startUtcOffsetSeconds = kolkata,
            endUtcOffsetSeconds = kolkata,
        )
        val events = listOf(
            event(EventType.SLEEP_SESSION, "healthconnect.sleep", SleepSessionPayload(minutesAsleep = 300), t0, t0 + 5.hours),
            event(EventType.SLEEP_SESSION, "googlehealth.sleep", googleSleep, t0, t0 + 7.hours),
            event(
                EventType.RESTING_HEART_RATE,
                "healthconnect.resting_heart_rate",
                RestingHeartRatePayload(70.0, LocalDate(2026, 10, 1)),
                t0,
            ),
            event(
                EventType.RESTING_HEART_RATE,
                "googlehealth.resting_heart_rate",
                RestingHeartRatePayload(57.6, LocalDate(2026, 10, 1)),
                t0,
            ),
            event(EventType.HEART_RATE, "googlehealth.heart_rate", HeartRatePayload(71.4), t0 + 1.minutes),
            event(EventType.HEART_RATE, "healthconnect.heart_rate", HeartRatePayload(99.0), t0),
        )

        val inputs = InMemoryRealtimeInputs.fromEvents(events)

        assertThat(inputs.sleep.source).isEqualTo(HealthSources.GOOGLE_HEALTH_SLEEP)
        val session = inputs.sleep.sessionsEnding(everything).available().sessions.single()
        assertThat(session.startOffset).isEqualTo(UtcOffset(hours = 5, minutes = 30))
        assertThat(session.minutesAsleep).isEqualTo(400)
        assertThat(session.mainSleep).isTrue()
        assertThat(session.nap).isFalse()
        assertThat(session.processed).isFalse()
        // The REM stage ends before it starts and is dropped; the out-of-bed segment keeps its own kind.
        assertThat(session.stages.map { it.kind }).containsExactly(SleepStageKind.LIGHT, SleepStageKind.OUT_OF_BED).inOrder()
        assertThat(inputs.dailySummaries.values(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 9, 1), LocalDate(2026, 10, 31)).available())
            .isEqualTo(DailySeries(HealthSources.GOOGLE_HEALTH_RESTING_HEART_RATE, mapOf(LocalDate(2026, 10, 1) to 58L)))
        assertThat(inputs.heartRate.samples(everything).available())
            .isEqualTo(HeartRateSeries(HealthSources.GOOGLE_HEALTH_HEART_RATE, listOf(HeartRateSample(t0 + 1.minutes, 71))))
    }

    @Test
    fun `Health Connect sleep has no main or nap flag and takes its offsets from the zone`() = runTest {
        val inputs = InMemoryRealtimeInputs.fromEvents(
            listOf(event(EventType.SLEEP_SESSION, "healthconnect.sleep", SleepSessionPayload(), t0, t0 + 2.hours, zone = "Asia/Kolkata")),
        )

        val session = inputs.sleep.sessionsEnding(everything).available().sessions.single()
        assertThat(session.mainSleep).isNull()
        assertThat(session.nap).isNull()
        assertThat(session.endOffset).isEqualTo(UtcOffset(hours = 5, minutes = 30))
        assertThat(SleepNight.isNap(session)).isTrue()
    }

    @Test
    fun `step sources follow connector priority and an unknown source is appended last`() = runTest {
        val inputs = InMemoryRealtimeInputs.fromEvents(
            listOf(
                event(EventType.STEP_SAMPLE, HealthSources.PHONE_STEPS, StepsPayload(40), t0, t0 + 1.minutes),
                event(EventType.STEP_SAMPLE, HealthSources.GOOGLE_HEALTH_STEPS, StepsPayload(50), t0, t0 + 1.minutes),
                event(EventType.STEP_SAMPLE, HealthSources.GOOGLE_HEALTH_STEPS, StepsPayload(-1), t0 + 1.minutes, t0 + 2.minutes),
            ),
        )
        assertThat(inputs.steps.sources).containsExactly(
            StepSourceSpec(HealthSources.GOOGLE_HEALTH_STEPS, reportsTrueZeros = true),
            StepSourceSpec(HealthSources.PHONE_STEPS, reportsTrueZeros = false),
        ).inOrder()

        inputs.addEvents(listOf(event(EventType.STEP_SAMPLE, HealthSources.HEALTH_CONNECT_STEPS, StepsPayload(9), t0)))

        assertThat(inputs.steps.sources.last()).isEqualTo(StepSourceSpec(HealthSources.HEALTH_CONNECT_STEPS, reportsTrueZeros = false))
        assertThat(inputs.steps.intervalsOf(HealthSources.HEALTH_CONNECT_STEPS)).containsExactly(StepInterval(t0, t0, 9))
        // The negative count is not stored.
        assertThat(inputs.steps.intervalsOf(HealthSources.GOOGLE_HEALTH_STEPS)).containsExactly(StepInterval(t0, t0 + 1.minutes, 50))
    }

    @Test
    fun `every port can be made unavailable, failing or throwing, and every read is logged`() = runTest {
        val inputs = InMemoryRealtimeInputs()
        inputs.sleep.unavailable = MissingReason.SOURCE_DISCONNECTED
        inputs.history.failure = AppError.DatabaseError("SQLiteException")
        inputs.notifications.throwing = IllegalStateException("listener died")
        inputs.live.unavailableReads[LiveRead.CHARGING] = MissingReason.NO_PERMISSION
        inputs.live.failures[LiveRead.BATTERY] = AppError.Unexpected("RemoteException")

        assertThat(inputs.sleep.sessionsEnding(everything)).isEqualTo(InputAnswer.unavailable(MissingReason.SOURCE_DISCONNECTED))
        assertThat(inputs.history.latest(JitaiSelector.AnyJitai, 10)).isEqualTo(Outcome.failure(AppError.DatabaseError("SQLiteException")))
        val thrown = runCatching { inputs.notifications.posted(everything) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(inputs.live.charging()).isEqualTo(InputAnswer.unavailable(MissingReason.NO_PERMISSION))
        assertThat(inputs.live.batteryPercent()).isEqualTo(Outcome.failure(AppError.Unexpected("RemoteException")))
        inputs.live.failure = AppError.Unexpected("DeadObjectException")
        assertThat(inputs.live.interactive()).isEqualTo(Outcome.failure(AppError.Unexpected("DeadObjectException")))

        assertThat(inputs.log.reads).containsExactly(
            PortRead("sleep.sessionsEnding", insideSnapshot = false),
            PortRead("history.latest", insideSnapshot = false),
            PortRead("notifications.posted", insideSnapshot = false),
            PortRead("live.charging", insideSnapshot = false),
            PortRead("live.battery", insideSnapshot = false),
            PortRead("live.interactive", insideSnapshot = false),
        ).inOrder()
        assertThat(inputs.log.count("live.charging")).isEqualTo(1)
        inputs.log.read { inputs.live.bootCount() }
        assertThat(inputs.log.reads.last()).isEqualTo(PortRead("live.boot_count", insideSnapshot = true))
        assertThat(inputs.log.snapshots).isEqualTo(1)
        inputs.log.clear()
        assertThat(inputs.log.reads).isEmpty()
        assertThat(inputs.log.snapshots).isEqualTo(0)
        assertThat(inputs.ports).hasSize(11)
    }

    @Test
    fun `health ports filter by range and keep their canonical source`() = runTest {
        val inputs = InMemoryRealtimeInputs()
        inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 9, 30), 60)
        inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 10, 1), 61)
        inputs.dailySummaries.remove(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 9, 30))
        inputs.heartRate.rows += listOf(HeartRateSample(t0 + 2.minutes, 80), HeartRateSample(t0, 70), HeartRateSample(t0 + 2.hours, 90))
        inputs.steps.addAll(HealthSources.PHONE_STEPS, listOf(StepInterval(t0, t0 + 1.minutes, 12)))
        inputs.sourceCoverage.set(HealthSources.PHONE_STEPS, HealthMetric.STEPS, t0 + 1.minutes)

        assertThat(
            inputs.dailySummaries.values(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)).available().values,
        )
            .containsExactly(LocalDate(2026, 10, 1), 61L)
        assertThat(inputs.heartRate.samples(range(t0, t0 + 1.hours)).available().samples.map { it.bpm }).containsExactly(70L, 80L).inOrder()
        val fused = inputs.steps.fusedMinuteSeries(range(t0, t0 + 1.minutes)).available()
        assertThat(fused.segments.map { it.source }).containsExactly(HealthSources.PHONE_STEPS)
        assertThat(fused.coverageThrough).isEqualTo(t0 + 1.minutes)
        assertThat(
            inputs.sourceCoverage.coverageThrough(HealthSources.PHONE_STEPS, HealthMetric.STEPS),
        ).isEqualTo(Outcome.success(t0 + 1.minutes))
        inputs.sourceCoverage.set(HealthSources.PHONE_STEPS, HealthMetric.STEPS, null)
        assertThat(inputs.sourceCoverage.through(HealthSources.PHONE_STEPS, HealthMetric.STEPS)).isNull()
    }

    @Test
    fun `record ports answer by overlap, recording order and engine day`() = runTest {
        val inputs = InMemoryRealtimeInputs()
        inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(t0 - 2.hours, t0 - 1.hours))
        inputs.collectorCoverage.healthySince(CollectorIds.USAGE_EVENTS, t0)
        val old = DeliveryRecord("a", "r1", "SLEEP_WIND_DOWN", DeliveryState.DELIVERED, t0 - 1.hours, LocalDate(2026, 9, 30))
        val new = DeliveryRecord("b", "r2", "DIGITAL_WELLBEING", DeliveryState.DELIVERY_UNCERTAIN, t0, LocalDate(2026, 10, 1))
        inputs.history.rows += listOf(old, new)

        val coverage = inputs.collectorCoverage.intervals(CollectorIds.USAGE_EVENTS, range(t0 - 90.minutes, t0 - 30.minutes))
        assertThat(coverage).isEqualTo(Outcome.success(listOf(CoverageInterval(t0 - 2.hours, t0 - 1.hours))))
        assertThat(inputs.history.latest(JitaiSelector.AnyJitai, 1)).isEqualTo(Outcome.success(listOf(new)))
        assertThat(inputs.history.latest(JitaiSelector.Category("SLEEP_WIND_DOWN"), 5)).isEqualTo(Outcome.success(listOf(old)))
        assertThat(inputs.history.inEngineDays(JitaiSelector.AnyJitai, LocalDate(2026, 10, 1), LocalDate(2026, 10, 1)))
            .isEqualTo(Outcome.success(listOf(new)))
        inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        assertThat(
            inputs.collectorCoverage.intervals(CollectorIds.USAGE_EVENTS, everything),
        ).isEqualTo(Outcome.success(emptyList<CoverageInterval>()))
    }
}
