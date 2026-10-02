package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.testing.LiveRead
import dev.agentle.core.common.AppError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HistoryFeaturesTest {
    private val r1 = "11111111-1111-4111-8111-111111111111"
    private val r3 = "33333333-3333-4333-8333-333333333333"
    private val walk = "55555555-5555-4555-8555-555555555555"

    private fun ref(id: String, jitai: String = r1) = FeatureRef(id, mapOf("jitai" to jitai))

    private var keys = 0

    private fun RealtimeFixture.delivery(
        jitai: String = r1,
        at: Instant = now,
        category: String = "DIGITAL_WELLBEING",
        state: DeliveryState = DeliveryState.DELIVERED,
        response: InterventionResponse = InterventionResponse.NONE,
        elapsed: Duration? = clock.elapsed() - (now - at),
        boot: Int? = inputs.live.state.bootCount,
        engineDay: LocalDate = LocalTimeRules.engineDay(at, zone, config.engineDayRollover),
    ) {
        inputs.history.rows += DeliveryRecord("v1|$jitai|${keys++}", jitai, category, state, at, engineDay, elapsed, boot, response)
    }

    @Test
    fun `R10 12L L1 never delivered is NEVER and NONE`() = runTest {
        val f = RealtimeFixture()

        assertThat(f.resolve(ref("minutes_since_last_delivery"))).isEqualTo(FeatureValue.Known(FeatureScalar.Never, f.now))
        assertThat(f.resolve(ref("last_response"))).isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue("NONE"), f.now))
        assertThat(f.resolve(ref("consecutive_ignored"))).isEqualTo(knownInt(0, f.now))
        assertThat(f.resolve(ref("deliveries_today"))).isEqualTo(knownInt(0, f.now))
        assertThat(f.resolve(ref("deliveries_last_7d"))).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `R10 12L L2 and 12B minutes since a delivery at 20 00 are 119 at 21 59 59 and 120 at 22 00`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T20:00")
        f.delivery()

        f.advanceTo("2026-10-01T21:59:59")
        assertThat(f.resolve(ref("minutes_since_last_delivery"))).isEqualTo(knownInt(119, f.now))

        f.advanceTo("2026-10-01T22:00:00")
        assertThat(f.resolve(ref("minutes_since_last_delivery"))).isEqualTo(knownInt(120, f.now))
    }

    @Test
    fun `R10 12L L3 deliveries_today counts DELIVERED and DELIVERY_UNCERTAIN rows of the category today`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.delivery(r1, f.local("2026-10-01T22:00"), state = DeliveryState.DELIVERED)
        f.delivery(r3, f.local("2026-10-01T22:30"), state = DeliveryState.DELIVERY_UNCERTAIN)
        // R3's SUPPRESSED decision is not a delivery and never reaches the history port.
        f.delivery(walk, f.local("2026-10-01T17:00"), category = "PHYSICAL_ACTIVITY")
        f.delivery(r1, f.local("2026-09-30T22:00"))

        assertThat(f.resolve(ref("deliveries_today", "category:DIGITAL_WELLBEING"))).isEqualTo(knownInt(2, f.now))
        assertThat(f.resolve(ref("deliveries_today", "any"))).isEqualTo(knownInt(3, f.now))
        assertThat(f.resolve(ref("deliveries_today", r1))).isEqualTo(knownInt(1, f.now))
        assertThat(f.resolve(ref("deliveries_today", "category:SLEEP_WIND_DOWN"))).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `R10 12L L4 consecutive_ignored counts the newest IGNORED or DISMISSED in a row`() = runTest {
        val f = RealtimeFixture()
        listOf(InterventionResponse.OPENED, InterventionResponse.IGNORED, InterventionResponse.DISMISSED, InterventionResponse.IGNORED)
            .forEachIndexed { i, response -> f.delivery(at = f.now - (4 - i).days, response = response) }

        assertThat(f.resolve(ref("consecutive_ignored"))).isEqualTo(knownInt(3, f.now))
        assertThat(f.resolve(ref("last_response"))).isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue("IGNORED"), f.now))
    }

    @Test
    fun `a pending newest delivery breaks the streak and has no response yet`() = runTest {
        val f = RealtimeFixture()
        f.delivery(at = f.now - 2.days, response = InterventionResponse.IGNORED)
        f.delivery(at = f.now - 10.minutes, response = InterventionResponse.NONE)

        assertThat(f.resolve(ref("consecutive_ignored"))).isEqualTo(knownInt(0, f.now))
        assertThat(f.resolve(ref("last_response")).knownScalar).isEqualTo(FeatureScalar.EnumValue("NONE"))
    }

    @Test
    fun `consecutive_ignored saturates at its query limit of 1000`() = runTest {
        val f = RealtimeFixture()
        repeat(HistoryFeatures.LATEST_LIMIT + 1) { f.delivery(at = f.now - (2000 - it).minutes, response = InterventionResponse.IGNORED) }

        assertThat(f.resolve(ref("consecutive_ignored"))).isEqualTo(knownInt(1000, f.now))
    }

    // ------------------------------------------------------------------ R10 §8.6 clocks, §12.O O8-O10

    /** R10 §12.O O8-O10: last delivery at elapsed E0, boot 41, wall 22:00. */
    private fun o(): RealtimeFixture = RealtimeFixture(start = "2026-10-01T21:00").apply {
        inputs.live.state = inputs.live.state.copy(bootCount = 41)
        advanceTo("2026-10-01T22:00")
        delivery(boot = 41)
    }

    @Test
    fun `R10 12O O8 a clock set back an hour does not shorten the elapsed time`() = runTest {
        val f = o()
        f.clock.advanceBy(65.minutes)
        f.clock.setWallClock(f.local("2026-10-01T22:05"))

        assertThat(f.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(65)
    }

    @Test
    fun `R10 12O O9 a clock set forward an hour does not lengthen the elapsed time`() = runTest {
        val f = o()
        f.clock.advanceBy(10.minutes)
        f.clock.setWallClock(f.local("2026-10-01T23:10"))

        assertThat(f.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(10)
    }

    @Test
    fun `R10 12O O10 after a reboot the wall-clock difference applies`() = runTest {
        val f = o()
        f.clock.setWallClock(f.local("2026-10-01T22:30"))
        f.inputs.live.state = f.inputs.live.state.copy(bootCount = 42)

        assertThat(f.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(30)
    }

    @Test
    fun `the wall-clock difference is clamped at 0`() = runTest {
        val f = o()
        f.inputs.live.state = f.inputs.live.state.copy(bootCount = 42)
        f.clock.setWallClock(f.local("2026-10-01T21:00"))

        assertThat(f.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(0)
    }

    @Test
    fun `an unreadable boot count or a row without clocks falls back to the wall clock`() = runTest {
        val unreadable = o()
        unreadable.clock.advanceBy(65.minutes)
        unreadable.clock.setWallClock(unreadable.local("2026-10-01T22:05"))
        unreadable.inputs.live.failures[LiveRead.BOOT_COUNT] = AppError.Unexpected("SettingNotFoundException")
        assertThat(unreadable.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(5)

        val noClocks = RealtimeFixture(start = "2026-10-01T22:30")
        noClocks.delivery(at = noClocks.local("2026-10-01T22:00"), elapsed = null, boot = null)
        assertThat(noClocks.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(30)
    }

    @Test
    fun `minutes_since_last_delivery saturates at one year`() = runTest {
        val f = RealtimeFixture()
        f.delivery(at = f.now - 400.days, elapsed = null, boot = null)

        assertThat(f.resolve(ref("minutes_since_last_delivery"))).isEqualTo(knownInt(HistoryFeatures.MAX_MINUTES, f.now))
    }

    @Test
    fun `the latest delivery is the latest recorded one, even after the clock moved back`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:00")
        f.delivery()
        f.clock.advanceBy(30.minutes)
        f.clock.setWallClock(f.local("2026-10-01T21:30"))
        f.delivery()
        f.clock.advanceBy(5.minutes + 30.seconds)

        assertThat(f.resolve(ref("minutes_since_last_delivery")).knownLong).isEqualTo(5)
    }

    // ------------------------------------------------------------------ engine days (R10 §10.2)

    @Test
    fun `R10 12M M10 deliveries_today counts by engine day across midnight`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:30")
        f.delivery(r3)

        f.advanceTo("2026-10-02T01:30")
        assertThat(f.resolve(ref("deliveries_today", r3))).isEqualTo(knownInt(1, f.now))

        f.advanceTo("2026-10-02T04:00")
        assertThat(f.resolve(ref("deliveries_today", r3))).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `R10 12M M11 deliveries_last_7d counts engine days today-6 to today`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T12:00")
        for (day in listOf(24, 26, 30)) f.delivery(at = f.local("2026-09-${day}T12:00"))

        assertThat(f.resolve(ref("deliveries_last_7d"))).isEqualTo(knownInt(2, f.now))

        f.advanceTo("2026-10-03T12:00")
        assertThat(f.resolve(ref("deliveries_last_7d"))).isEqualTo(knownInt(1, f.now))
    }

    @Test
    fun `a failing history read is unknown`() = runTest {
        val f = RealtimeFixture()
        f.inputs.history.failure = AppError.DatabaseError("SQLiteException")

        assertThat(f.resolve(ref("minutes_since_last_delivery"))).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(f.resolve(ref("deliveries_today"))).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `self must be bound to the evaluated rule before resolving`() = runTest {
        val f = RealtimeFixture()
        f.delivery(r1, at = f.now - 30.minutes)
        val unbound = ref("minutes_since_last_delivery", JitaiArgs.SELF)

        assertThat(f.resolve(unbound)).isEqualTo(missing(MissingReason.INVALID_VALUE))
        assertThat(f.resolve(JitaiArgs.bindSelf(unbound, r1)).knownLong).isEqualTo(30)
        assertThat(JitaiArgs.bindSelf(FeatureRef("local_time"), r1)).isEqualTo(FeatureRef("local_time"))
    }
}
