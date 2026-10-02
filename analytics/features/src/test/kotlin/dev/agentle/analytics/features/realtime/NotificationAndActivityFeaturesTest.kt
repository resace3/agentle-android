package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.TransitionKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class NotificationAndActivityFeaturesTest {
    // ------------------------------------------------------------------ D. notifications (lifecycle-battery-02)

    private fun RealtimeFixture.posted(
        time: String,
        key: String,
        packageName: String = CHAT,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) {
        inputs.notifications.rows += NotificationPost(local(time), packageName, key, ongoing, groupSummary)
    }

    @Test
    fun `notifications_last_60m counts distinct keys first posted in the window by other apps`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.posted("2026-10-01T21:59", "k-before")
        f.posted("2026-10-01T22:00", "k1")
        f.posted("2026-10-01T22:10", "k2", packageName = MAPS)
        f.posted("2026-10-01T22:20", "k1") // the same key again (a new lifetime): still one key
        f.posted("2026-10-01T22:30", "k-ongoing", ongoing = true)
        f.posted("2026-10-01T22:40", "k-summary", groupSummary = true)
        f.posted("2026-10-01T22:50", "k-own", packageName = "dev.agentle.app")
        f.posted("2026-10-01T23:00", "k-at-t")

        assertThat(f.value("notifications_last_60m")).isEqualTo(knownInt(2, f.now))
    }

    @Test
    fun `a key whose first post was ongoing does not count even if a later row is not`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.posted("2026-10-01T22:10", "k1", ongoing = true)
        f.posted("2026-10-01T22:20", "k1")

        assertThat(f.value("notifications_last_60m")).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `no notification with a healthy listener is a true zero`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")

        assertThat(f.value("notifications_last_60m")).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `a listener disconnected for the whole window is unknown, never 0 (lifecycle-battery-01)`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.inputs.collectorCoverage.clear(CollectorIds.NOTIFICATION_LISTENER)
        f.inputs.collectorCoverage.add(CollectorIds.NOTIFICATION_LISTENER, CoverageInterval(f.now - 3.days, f.now - 2.hours))

        assertThat(f.value("notifications_last_60m")).isEqualTo(missing(MissingReason.COLLECTOR_INACTIVE))
    }

    @Test
    fun `a listener reconnected inside the window is COVERAGE_GAP`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.posted("2026-10-01T22:50", "k1")
        f.inputs.collectorCoverage.clear(CollectorIds.NOTIFICATION_LISTENER)
        f.inputs.collectorCoverage.add(CollectorIds.NOTIFICATION_LISTENER, CoverageInterval(f.now - 20.minutes))

        assertThat(f.value("notifications_last_60m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `notifications without notification access are NO_PERMISSION`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.inputs.notifications.unavailable = MissingReason.NO_PERMISSION

        assertThat(f.value("notifications_last_60m")).isEqualTo(missing(MissingReason.NO_PERMISSION))
    }

    // ------------------------------------------------------------------ F. activity_state

    private fun RealtimeFixture.transition(time: String, activity: ActivityKind, kind: TransitionKind) {
        inputs.activity.rows += ActivityTransition(local(time), activity, kind)
    }

    @Test
    fun `activity_state is the activity of the latest ENTER`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.transition("2026-10-01T17:00", ActivityKind.STILL, TransitionKind.ENTER)
        f.transition("2026-10-01T17:30", ActivityKind.STILL, TransitionKind.EXIT)
        f.transition("2026-10-01T17:30", ActivityKind.WALKING, TransitionKind.ENTER)
        f.transition("2026-10-01T17:45", ActivityKind.TILTING, TransitionKind.ENTER)

        assertThat(f.value("activity_state")).isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue("WALKING"), f.now))
    }

    @Test
    fun `activity_state after the EXIT of the latest ENTER is NO_DATA`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.transition("2026-10-01T17:30", ActivityKind.RUNNING, TransitionKind.ENTER)
        f.transition("2026-10-01T17:50", ActivityKind.RUNNING, TransitionKind.EXIT)

        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `activity_state without any transition in the lookback is NO_DATA`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.transition("2026-09-30T17:00", ActivityKind.IN_VEHICLE, TransitionKind.ENTER)

        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `activity_state needs the subscription active since the latest ENTER only`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.transition("2026-10-01T17:00", ActivityKind.ON_BICYCLE, TransitionKind.ENTER)
        f.inputs.collectorCoverage.clear(CollectorIds.ACTIVITY_TRANSITIONS)
        f.inputs.collectorCoverage.add(CollectorIds.ACTIVITY_TRANSITIONS, CoverageInterval(f.local("2026-10-01T16:30")))

        assertThat(f.value("activity_state").knownScalar).isEqualTo(FeatureScalar.EnumValue("ON_BICYCLE"))

        f.inputs.collectorCoverage.clear(CollectorIds.ACTIVITY_TRANSITIONS)
        f.inputs.collectorCoverage.add(
            CollectorIds.ACTIVITY_TRANSITIONS,
            CoverageInterval(f.local("2026-10-01T16:30"), f.local("2026-10-01T17:20")),
        )
        f.inputs.collectorCoverage.add(CollectorIds.ACTIVITY_TRANSITIONS, CoverageInterval(f.local("2026-10-01T17:25")))

        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `activity_state is COLLECTOR_INACTIVE when the subscription is not active now`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.transition("2026-10-01T17:00", ActivityKind.WALKING, TransitionKind.ENTER)
        f.inputs.collectorCoverage.clear(CollectorIds.ACTIVITY_TRANSITIONS)
        f.inputs.collectorCoverage.add(CollectorIds.ACTIVITY_TRANSITIONS, CoverageInterval(f.now - 2.days, f.now - 10.minutes))

        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.COLLECTOR_INACTIVE))
    }

    @Test
    fun `without an ENTER the whole lookback must be covered before NO_DATA is claimed`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.inputs.collectorCoverage.clear(CollectorIds.ACTIVITY_TRANSITIONS)
        f.inputs.collectorCoverage.add(CollectorIds.ACTIVITY_TRANSITIONS, CoverageInterval(f.now - 1.hours))

        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `activity_state without the permission or Play services is unknown`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.inputs.activity.unavailable = MissingReason.NO_PERMISSION
        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.NO_PERMISSION))

        f.inputs.activity.unavailable = null
        f.inputs.activity.failure = AppError.UnsupportedFeature("play_services")
        assertThat(f.value("activity_state")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }
}
