package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.testing.LiveRead
import dev.agentle.core.common.AppError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UsageFeaturesTest {
    private val since22 = "22:00"

    private fun appSince(packageName: String = INSTAGRAM) = FeatureRef(
        "app_minutes_since",
        mapOf(
            "package" to packageName,
            "since" to since22,
        ),
    )

    private fun opens(packageName: String = INSTAGRAM) = FeatureRef("app_opens_last_60m", mapOf("package" to packageName))

    private fun categorySince(category: String) =
        FeatureRef("app_category_minutes_since", mapOf("category" to category, "since" to since22))

    /** R10 §12.H: evaluated at 23:00, `since 22:00`, screen on unless stated. */
    private fun h(): RealtimeFixture = RealtimeFixture(start = "2026-10-01T23:00")

    // ------------------------------------------------------------------ R10 §12.A and §12.B: screen minutes

    @ParameterizedTest(name = "{0}: screen on from {1}")
    @CsvSource(
        "A1, 2026-10-01T21:46:00, 44",
        "A2, 2026-10-01T21:45:00, 45",
        "A3, 2026-10-01T21:44:00, 46",
        "A4, 2026-10-01T21:45:00.001, 44",
    )
    fun `R10 12A screen_minutes_last_60m floors the interactive time in the window`(id: String, on: String, expected: Long) = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:30")
        f.screenOn(on)

        assertWithMessage(id).that(f.value("screen_minutes_last_60m")).isEqualTo(knownInt(expected, f.now))
    }

    @Test
    fun `R10 12A A7 revoked usage access makes every usage feature NO_PERMISSION`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:30")
        f.inputs.usage.unavailable = MissingReason.NO_PERMISSION

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.NO_PERMISSION))
        assertThat(f.resolve(appSince())).isEqualTo(missing(MissingReason.NO_PERMISSION))
        assertThat(f.value("foreground_app")).isEqualTo(missing(MissingReason.NO_PERMISSION))
    }

    @Test
    fun `R10 12A A8 a usage collector gap inside the window is COVERAGE_GAP, never a count`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T21:59")
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 30.days, f.local("2026-10-01T21:20")))
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.local("2026-10-01T21:40")))

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    // ------------------------------------------------------------------ R10 §12.H

    @Test
    fun `R10 12H H1 two foreground intervals give 30 minutes`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.paused(INSTAGRAM, "2026-10-01T22:20")
        f.resumed(INSTAGRAM, "2026-10-01T22:40")
        f.paused(INSTAGRAM, "2026-10-01T22:55")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(30, f.now))
    }

    @Test
    fun `R10 12H H2 29 minutes 59 999 seconds floor to 29`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.paused(INSTAGRAM, "2026-10-01T22:20")
        f.resumed(INSTAGRAM, "2026-10-01T22:40")
        f.paused(INSTAGRAM, "2026-10-01T22:54:59.999")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(29, f.now))
    }

    @Test
    fun `R10 12H H3 an interval that started before since is clipped at since`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T21:50")
        f.paused(INSTAGRAM, "2026-10-01T22:10")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(10, f.now))
        assertThat(f.resolve(opens())).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `R10 12H H4 a 1 5 second switch between two activities is one interval and one open`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05:00", className = "A")
        f.paused(INSTAGRAM, "2026-10-01T22:12:00", className = "A")
        f.resumed(INSTAGRAM, "2026-10-01T22:12:01.500", className = "B")
        f.paused(INSTAGRAM, "2026-10-01T22:20:00", className = "B")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(15, f.now))
        assertThat(f.resolve(opens())).isEqualTo(knownInt(1, f.now))
    }

    @Test
    fun `R10 12H H5 a 2 5 second gap gives two intervals and two opens`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05:00", className = "A")
        f.paused(INSTAGRAM, "2026-10-01T22:12:00", className = "A")
        f.resumed(INSTAGRAM, "2026-10-01T22:12:02.500", className = "B")
        f.paused(INSTAGRAM, "2026-10-01T22:20:00", className = "B")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(14, f.now))
        assertThat(f.resolve(opens())).isEqualTo(knownInt(2, f.now))
    }

    @Test
    fun `R10 12H H6 foreground time is intersected with interactive time`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.paused(INSTAGRAM, "2026-10-01T22:30")
        f.screenOff("2026-10-01T22:10")
        f.screenOn("2026-10-01T22:20")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(15, f.now))
        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(50, f.now))
    }

    @Test
    fun `R10 12H H7 split-screen use of two social apps counts once`() = runTest {
        val f = h()
        f.inputs.usage.categories += mapOf(INSTAGRAM to "SOCIAL", CHAT to "SOCIAL")
        f.resumed(INSTAGRAM, "2026-10-01T22:00")
        f.resumed(CHAT, "2026-10-01T22:00")
        f.paused(INSTAGRAM, "2026-10-01T22:10")
        f.paused(CHAT, "2026-10-01T22:10")

        assertThat(f.resolve(categorySince("SOCIAL"))).isEqualTo(knownInt(10, f.now))
        assertThat(f.resolve(appSince(INSTAGRAM))).isEqualTo(knownInt(10, f.now))
        assertThat(f.resolve(appSince(CHAT))).isEqualTo(knownInt(10, f.now))
    }

    @Test
    fun `R10 12H H8 without events in the window the live state holds for the whole window`() = runTest {
        val f = h()
        f.inputs.live.state = f.inputs.live.state.copy(foregroundApp = INSTAGRAM)

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(60, f.now))
        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(60, f.now))
        assertThat(f.value("foreground_app")).isEqualTo(FeatureValue.Known(FeatureScalar.PackageValue(INSTAGRAM), f.now))
    }

    @Test
    fun `R10 12H H9 a null queryEvents before the first unlock is LOCKED_AFTER_BOOT`() = runTest {
        val f = h()
        f.inputs.usage.unavailable = MissingReason.LOCKED_AFTER_BOOT

        for (ref in usageRefs()) assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.LOCKED_AFTER_BOOT))
    }

    @Test
    fun `R10 12H H10 usage access not granted is NO_PERMISSION`() = runTest {
        val f = h()
        f.inputs.usage.failure = AppError.PermissionDenied("app_usage_events")

        for (ref in usageRefs()) assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.NO_PERMISSION))
    }

    @Test
    fun `R10 12H H11 on API 28 screen features are computed and app features are API_LEVEL`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00", config = RealtimeFeatureConfig(apiLevel = 28))

        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(60, f.now))
        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(knownInt(60, f.now))
        for (ref in usageRefs().filterNot { it.featureId.startsWith("screen_") }) {
            assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.API_LEVEL))
        }
    }

    @Test
    fun `below API 28 screen features are API_LEVEL too`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00", config = RealtimeFeatureConfig(apiLevel = 27))

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.API_LEVEL))
    }

    // ------------------------------------------------------------------ R10 §12.B foreground_app

    @Test
    fun `R10 12B foreground_app is the resumed package while the screen is on, else NONE`() = runTest {
        val onScreen = h()
        onScreen.resumed(INSTAGRAM, "2026-10-01T22:50")
        assertThat(onScreen.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.PackageValue(INSTAGRAM))

        val another = h()
        another.resumed(MAPS, "2026-10-01T22:50")
        assertThat(another.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.PackageValue(MAPS))

        val screenOff = h()
        screenOff.resumed(INSTAGRAM, "2026-10-01T22:50")
        screenOff.screenOff("2026-10-01T22:55")
        assertThat(screenOff.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.NoPackage)
    }

    @Test
    fun `foreground_app is NONE when the app was paused before t`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:50")
        f.paused(INSTAGRAM, "2026-10-01T22:59")

        assertThat(f.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.NoPackage)
    }

    @Test
    fun `foreground_app prefers the latest resume and then the package name`() = runTest {
        val latest = h()
        latest.resumed(MAPS, "2026-10-01T22:50")
        latest.resumed(INSTAGRAM, "2026-10-01T22:55")
        assertThat(latest.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.PackageValue(INSTAGRAM))

        val tie = h()
        tie.resumed(MAPS, "2026-10-01T22:50")
        tie.resumed(CHAT, "2026-10-01T22:50")
        assertThat(tie.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.PackageValue(CHAT))
    }

    @Test
    fun `foreground_app falls back to the live read, closed by a shutdown`() = runTest {
        val live = h()
        live.inputs.live.state = live.inputs.live.state.copy(foregroundApp = MAPS)
        assertThat(live.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.PackageValue(MAPS))

        val nothing = h()
        assertThat(nothing.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.NoPackage)

        val shutdown = h()
        shutdown.inputs.live.state = shutdown.inputs.live.state.copy(foregroundApp = MAPS)
        shutdown.usage("2026-10-01T22:30", UsageEventKind.DEVICE_SHUTDOWN)
        assertThat(shutdown.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.NoPackage)
    }

    @Test
    fun `foreground_app is unknown when the live foreground read fails`() = runTest {
        val f = h()
        f.inputs.live.failures[LiveRead.FOREGROUND_APP] = AppError.Unexpected("SecurityException")

        assertThat(f.value("foreground_app")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(f.resolve(appSince())).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `the screen state needs the live read only when the screen has no event in the window`() = runTest {
        val withEvents = h()
        withEvents.screenOn("2026-10-01T22:30")
        withEvents.inputs.live.failures[LiveRead.INTERACTIVE] = AppError.Unexpected("DeadObjectException")
        assertThat(withEvents.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(30, withEvents.now))

        val withoutEvents = h()
        withoutEvents.inputs.live.failures[LiveRead.INTERACTIVE] = AppError.Unexpected("DeadObjectException")
        assertThat(withoutEvents.value("screen_minutes_since", "since" to since22)).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `a screen that stays off has zero screen minutes when the collector covers the window`() = runTest {
        val f = h()
        f.inputs.live.state = f.inputs.live.state.copy(interactive = false)

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `consecutive events asserting the same state are ignored`() = runTest {
        val f = h()
        f.screenOn("2026-10-01T22:10")
        f.screenOn("2026-10-01T22:20")
        f.screenOff("2026-10-01T22:40")
        f.screenOff("2026-10-01T22:50")

        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(30, f.now))
    }

    @Test
    fun `a device shutdown closes the screen and every open foreground interval`() = runTest {
        val f = h()
        f.screenOn("2026-10-01T22:05")
        f.resumed(INSTAGRAM, "2026-10-01T22:10")
        f.usage("2026-10-01T22:30", UsageEventKind.DEVICE_SHUTDOWN)
        f.usage("2026-10-01T22:35", UsageEventKind.DEVICE_STARTUP)
        f.screenOn("2026-10-01T22:35")

        // Off while the device is down; the screen event at the startup leaves no unknown span.
        assertThat(f.resolve(appSince())).isEqualTo(knownInt(20, f.now))
        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(50, f.now))
    }

    @Test
    fun `Q2 after a startup the screen is unknown until its first event, a gap for windows that overlap it`() = runTest {
        val f = h()
        f.screenOn("2026-10-01T22:05")
        f.usage("2026-10-01T22:30", UsageEventKind.DEVICE_SHUTDOWN)
        f.usage("2026-10-01T22:35", UsageEventKind.DEVICE_STARTUP)
        f.screenOn("2026-10-01T22:36")

        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(missing(MissingReason.COVERAGE_GAP))
        assertThat(f.resolve(appSince())).isEqualTo(missing(MissingReason.COVERAGE_GAP))
        // A window after the unknown span is known again.
        f.advanceTo("2026-10-01T23:40")
        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(knownInt(60, f.now))
    }

    @Test
    fun `Q2 a boot screen event never infers the time before the shutdown`() = runTest {
        // Mirror case: the screen was on at W0 with no screen event before the shutdown.
        val f = h()
        f.usage("2026-10-01T22:20", UsageEventKind.DEVICE_SHUTDOWN)
        f.usage("2026-10-01T22:21", UsageEventKind.DEVICE_STARTUP)
        f.screenOn("2026-10-01T22:21")

        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `Q2 probe J after a shutdown the live read gives the screen state at t`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:30")
        f.usage("2026-10-01T22:10", UsageEventKind.DEVICE_SHUTDOWN)
        f.resumed(INSTAGRAM, "2026-10-01T22:16")
        f.inputs.live.state = f.inputs.live.state.copy(interactive = true, foregroundApp = INSTAGRAM)

        assertThat(f.value("foreground_app")).isEqualTo(FeatureValue.Known(FeatureScalar.PackageValue(INSTAGRAM), f.now))
        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
        assertThat(f.value("app_minutes_last_60m", "package" to INSTAGRAM)).isEqualTo(missing(MissingReason.COVERAGE_GAP))

        f.inputs.live.state = f.inputs.live.state.copy(interactive = false)
        assertThat(f.value("foreground_app").knownScalar).isEqualTo(FeatureScalar.NoPackage)
    }

    @Test
    fun `R1-5 foreground_app needs usage coverage at t only`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:30")
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.healthySince(CollectorIds.USAGE_EVENTS, f.local("2026-10-01T22:20"))
        f.screenOn("2026-10-01T22:21")
        f.resumed(INSTAGRAM, "2026-10-01T22:25")
        f.inputs.live.state = f.inputs.live.state.copy(foregroundApp = INSTAGRAM)

        assertThat(f.value("foreground_app")).isEqualTo(FeatureValue.Known(FeatureScalar.PackageValue(INSTAGRAM), f.now))
        // Minute features keep the whole-window check.
        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `an app stopped instead of paused closes its interval`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.usage("2026-10-01T22:25", UsageEventKind.ACTIVITY_STOPPED, INSTAGRAM, "Main")

        assertThat(f.resolve(appSince())).isEqualTo(knownInt(20, f.now))
    }

    @Test
    fun `app minutes of a package that was not used are zero`() = runTest {
        val f = h()
        f.resumed(MAPS, "2026-10-01T22:05")

        assertThat(f.resolve(appSince(INSTAGRAM))).isEqualTo(knownInt(0, f.now))
        assertThat(f.value("app_minutes_last_60m", "package" to INSTAGRAM)).isEqualTo(knownInt(0, f.now))
    }

    // ------------------------------------------------------------------ categories

    @Test
    fun `category minutes include an app known only from the live read`() = runTest {
        val f = h()
        f.inputs.usage.categories[INSTAGRAM] = "SOCIAL"
        f.inputs.live.state = f.inputs.live.state.copy(foregroundApp = INSTAGRAM)

        assertThat(f.value("app_category_minutes_last_60m", "category" to "SOCIAL")).isEqualTo(knownInt(60, f.now))
        assertThat(f.value("app_category_minutes_last_60m", "category" to "GAME")).isEqualTo(knownInt(0, f.now))
    }

    @Test
    fun `packages without a known category count as UNDEFINED`() = runTest {
        val f = h()
        f.inputs.usage.categories[CHAT] = "NOT_A_CATEGORY"
        f.resumed(CHAT, "2026-10-01T22:05")
        f.paused(CHAT, "2026-10-01T22:15")
        f.resumed(MAPS, "2026-10-01T22:20")
        f.paused(MAPS, "2026-10-01T22:25")

        assertThat(f.resolve(categorySince("UNDEFINED"))).isEqualTo(knownInt(15, f.now))
    }

    @Test
    fun `category minutes with no app in the window are zero without reading categories`() = runTest {
        val f = h()

        assertThat(f.resolve(categorySince("SOCIAL"))).isEqualTo(knownInt(0, f.now))
        assertThat(f.inputs.log.count("usage.appCategories")).isEqualTo(0)
    }

    @Test
    fun `a failing category lookup makes category minutes unknown`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.inputs.usage.categoryFailure = AppError.DatabaseError("SQLiteException")

        assertThat(f.resolve(categorySince("SOCIAL"))).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(f.resolve(appSince())).isEqualTo(knownInt(55, f.now))
    }

    @Test
    fun `a failing live foreground read makes category minutes unknown`() = runTest {
        val f = h()
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.inputs.live.failures[LiveRead.FOREGROUND_APP] = AppError.Unexpected("SecurityException")

        assertThat(f.resolve(categorySince("SOCIAL"))).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    // ------------------------------------------------------------------ coverage (lifecycle-battery-01)

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowedUsageRefs")
    fun `every windowed usage feature is COVERAGE_GAP for a collector gap inside its window`(ref: FeatureRef) = runTest {
        val f = h()
        f.inputs.live.state = f.inputs.live.state.copy(interactive = false)
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 30.days, f.now - 30.minutes))
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 20.minutes))

        assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usageRefs")
    fun `every usage feature is COLLECTOR_INACTIVE when the collector is not healthy now`(ref: FeatureRef) = runTest {
        val f = h()
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 30.days, f.now - 5.minutes))

        assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.COLLECTOR_INACTIVE))
    }

    @Test
    fun `a window beyond the system retention and the local copy is COVERAGE_GAP`() = runTest {
        val f = h()
        f.inputs.usage.unavailable = MissingReason.COVERAGE_GAP

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `a collector that never reported coverage is COLLECTOR_INACTIVE`() = runTest {
        val f = h()
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.COLLECTOR_INACTIVE))
    }

    @Test
    fun `a failing coverage read is unknown`() = runTest {
        val f = h()
        f.inputs.collectorCoverage.failure = AppError.DatabaseError("SQLiteException")

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `coverage stitched from adjacent intervals covers the window`() = runTest {
        val f = h()
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 2.days, f.now - 40.minutes))
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 50.minutes, f.now - 10.minutes))
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 10.minutes, f.now + 1.minutes))

        assertThat(f.value("screen_minutes_last_60m")).isEqualTo(knownInt(60, f.now))
    }

    // ------------------------------------------------------------------ since windows (R10 §10.4, §10.9, §12.O)

    @Test
    fun `R10 12O O12 since 22 00 at 01 30 starts the previous evening, a 210-minute window`() = runTest {
        val f = RealtimeFixture(start = "2026-10-02T01:30")

        assertThat(f.value("screen_minutes_since", "since" to since22)).isEqualTo(knownInt(210, f.now))
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "Europe/Berlin, 2026-10-25T04:00, 22:00, 420",
        "America/New_York, 2026-11-01T02:00, 22:00, 300",
        "America/New_York, 2026-03-08T04:00, 22:00, 300",
        "Europe/Berlin, 2026-03-29T04:00, 02:30, 30",
        "America/Santiago, 2026-09-06T02:00, 00:00, 60",
    )
    fun `R10 10_9 since windows have their real DST length`(zone: String, local: String, since: String, expected: Long) = runTest {
        val f = RealtimeFixture(zone = kotlinx.datetime.TimeZone.of(zone), start = local)

        assertThat(f.value("screen_minutes_since", "since" to since)).isEqualTo(knownInt(expected, f.now))
    }

    @Test
    fun `R10 10_6 a since time in the autumn overlap takes the earlier offset`() = runTest {
        val f = RealtimeFixture(start = "2026-10-25T00:00")
        // 02:45+01:00, the second 02:45 of the night.
        f.clock.setWallClock(Instant.parse("2026-10-25T01:45:00Z"))

        // since* = 02:30+02:00 (00:30Z): 75 minutes.
        assertThat(f.value("screen_minutes_since", "since" to "02:30")).isEqualTo(knownInt(75, f.now))
    }

    @Test
    fun `a since window longer than a day on a fall-back night can exceed the valid range`() = runTest {
        // 2026-10-26 00:00+01:00; since 00:30 resolves to 2026-10-25 00:30+02:00, 24 h 30 min earlier.
        val f = RealtimeFixture(start = "2026-10-26T00:00")

        assertThat(f.value("screen_minutes_since", "since" to "00:30")).isEqualTo(missing(MissingReason.INVALID_VALUE))
    }

    companion object {
        @JvmStatic
        fun usageRefs(): List<FeatureRef> = windowedUsageRefs() + FeatureRef("foreground_app")

        @JvmStatic
        fun windowedUsageRefs(): List<FeatureRef> = listOf(
            FeatureRef("screen_minutes_last_60m"),
            FeatureRef("screen_minutes_since", mapOf("since" to "22:00")),
            FeatureRef("app_minutes_last_60m", mapOf("package" to INSTAGRAM)),
            FeatureRef("app_minutes_since", mapOf("package" to INSTAGRAM, "since" to "22:00")),
            FeatureRef("app_category_minutes_last_60m", mapOf("category" to "SOCIAL")),
            FeatureRef("app_category_minutes_since", mapOf("category" to "SOCIAL", "since" to "22:00")),
            FeatureRef("app_opens_last_60m", mapOf("package" to INSTAGRAM)),
        )
    }
}
