package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.core.common.AppError
import dev.agentle.core.model.DataCategory
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class RealtimeFeatureEngineTest {
    private val jitaiId = "11111111-1111-4111-8111-111111111111"

    /** One valid ref of every catalog feature. */
    private val everyFeature: List<FeatureRef> = RealtimeFeatureCatalog.all.map { definition ->
        val args = definition.args.associate { arg ->
            arg.name to when (arg.name) {
                "since" -> "22:00"
                "package" -> INSTAGRAM
                "category" -> "SOCIAL"
                else -> jitaiId
            }
        }
        FeatureRef(definition.id, args)
    }

    // ------------------------------------------------------------------ availability, API level, arguments

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["G1", "G2", "G3", "G4", "G5"])
    fun `R10 12G location_class is unavailable in v1 whatever the transitions (lifecycle-battery-06)`(id: String) = runTest {
        val f = RealtimeFixture(start = "2026-10-01T21:00")

        assertWithMessage(id).that(f.value("location_class")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(f.inputs.log.reads).isEmpty()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "unknown feature | no_such_feature | ",
            "unexpected arg | screen_minutes_last_60m | since=22:00",
            "missing arg | app_minutes_since | package=com.instagram.android",
            "package without a dot | app_minutes_last_60m | package=instagram",
            "package with an empty segment | app_minutes_last_60m | package=com..instagram",
            "package starting with a digit | app_minutes_last_60m | package=1com.instagram",
            "since without a leading zero | screen_minutes_since | since=7:00",
            "since at 24 00 | screen_minutes_since | since=24:00",
            "since with 60 minutes | screen_minutes_since | since=22:60",
            "lowercase category | app_category_minutes_last_60m | category=social",
            "unknown category | app_category_minutes_last_60m | category=SHOPPING",
            "unbound self | deliveries_today | jitai=self",
            "unknown JITAI category | deliveries_today | jitai=category:FUN",
            "malformed JITAI id | deliveries_today | jitai=1111-2222",
        ],
    )
    fun `a bad ref is INVALID_VALUE`(case: String, featureId: String, args: String?) = runTest {
        val f = RealtimeFixture()
        val parsed = args.orEmpty().split(',').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }

        assertWithMessage(case).that(f.resolve(FeatureRef(featureId, parsed))).isEqualTo(missing(MissingReason.INVALID_VALUE))
    }

    @Test
    fun `a package name longer than 255 characters is invalid`() = runTest {
        val f = RealtimeFixture()
        val long = "com." + "a".repeat(252)

        assertThat(f.value("app_minutes_last_60m", "package" to long)).isEqualTo(missing(MissingReason.INVALID_VALUE))
        assertThat(FeatureArgParser.parsePackage("com." + "a".repeat(251))).isNotNull()
    }

    @Test
    fun `valid jitai args are any, a category or a UUID`() {
        assertThat(JitaiArgs.selectorOf("any")).isEqualTo(JitaiSelector.AnyJitai)
        assertThat(JitaiArgs.selectorOf("category:STRESS_BREAK")).isEqualTo(JitaiSelector.Category("STRESS_BREAK"))
        assertThat(JitaiArgs.selectorOf(jitaiId)).isEqualTo(JitaiSelector.Id(jitaiId))
        assertThat(JitaiArgs.selectorOf("self")).isNull()
    }

    @Test
    fun `refs with the same args in another order are one ref`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        val a = FeatureRef("app_minutes_since", mapOf("package" to INSTAGRAM, "since" to "22:00"))
        val b = FeatureRef("app_minutes_since", mapOf("since" to "22:00", "package" to INSTAGRAM))

        val snapshot = f.snapshot(a, b)

        assertThat(snapshot.values).hasSize(1)
        assertThat(snapshot[b]).isEqualTo(knownInt(0, f.now))
    }

    // ------------------------------------------------------------------ one pass: memoization and one snapshot

    @Test
    fun `every input is read at most once per pass, and every read is inside one snapshot (database-sync-05)`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.inputs.healthSyncedThrough(f.now)

        val snapshot = f.engine.resolve(everyFeature.toSet(), f.now)

        assertThat(snapshot.values).hasSize(everyFeature.size)
        assertThat(f.inputs.log.snapshots).isEqualTo(1)
        assertThat(f.inputs.log.reads.all { it.insideSnapshot }).isTrue()
        val perPort = f.inputs.log.reads.groupingBy { it.port }.eachCount()
        // Two usage windows (last 60 minutes and since 22:00, which here are the same instants) share one read.
        assertThat(perPort["usage.events"]).isEqualTo(1)
        assertThat(perPort["history.latest"]).isEqualTo(1)
        assertThat(perPort["live.interactive"]).isEqualTo(1)
        assertThat(perPort["live.foreground_app"]).isEqualTo(1)
        assertThat(perPort["dailySummaries.values"]).isEqualTo(1)
        assertThat(perPort["sleep.sessionsEnding"]).isEqualTo(1)
        // Three step windows: today, the last 60 minutes and the last 30 (shared by steps_last_30m and the activity level).
        assertThat(perPort["steps.fusedMinuteSeries"]).isEqualTo(3)
        assertThat(perPort.values.all { it <= 3 }).isTrue()
    }

    @Test
    fun `a pass never caches across resolves, so new data and a new engine day are seen at once (jitai-correctness-11)`() = runTest {
        val f = RealtimeFixture(start = "2026-10-02T03:59")
        val engine = f.engine
        f.inputs.healthSyncedThrough(f.now)
        val sleep = FeatureRef("sleep_minutes_last_night")
        val rhr = FeatureRef("resting_hr_today")
        val today = FeatureRef("deliveries_today", mapOf("jitai" to "any"))
        f.inputs.history.rows +=
            DeliveryRecord("k1", jitaiId, "GENERAL", DeliveryState.DELIVERED, f.now - 60.minutes, LocalDate(2026, 10, 1))

        val before = engine.resolve(setOf(sleep, rhr, today), f.now)
        assertThat(before[sleep]).isEqualTo(missing(MissingReason.NO_DATA))
        assertThat(before[rhr]).isEqualTo(missing(MissingReason.NO_DATA))
        assertThat(before[today]?.knownLong).isEqualTo(1)

        f.inputs.sleep.rows += SleepSessionRecord(
            f.local("2026-10-01T23:00"),
            f.local("2026-10-02T03:30"),
            kotlinx.datetime.UtcOffset.parse("+02:00"),
            kotlinx.datetime.UtcOffset.parse("+02:00"),
            minutesAsleep = 250,
            mainSleep = true,
            nap = false,
        )
        f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 10, 2), 57)
        f.advanceTo("2026-10-02T04:00")

        val after = engine.resolve(setOf(sleep, rhr, today), f.now)
        assertThat(after[sleep]?.knownLong).isEqualTo(250)
        assertThat(after[rhr]?.knownLong).isEqualTo(57)
        assertThat(after[today]?.knownLong).isEqualTo(0)
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a port that throws makes only its features unknown and logs no message`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.inputs.usage.throwing = IllegalStateException("payload {\"title\":\"secret\"}")

        val snapshot = f.snapshot(FeatureRef("screen_minutes_last_60m"), FeatureRef("local_time"))

        assertThat(snapshot[FeatureRef("screen_minutes_last_60m")]).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(snapshot[FeatureRef("local_time")]).isInstanceOf(FeatureValue.Known::class.java)
        val record = f.logs.single()
        assertThat(
            record.fields,
        ).containsExactly("feature", "screen_minutes_last_60m", "category", "SCREEN", "error", "IllegalStateException")
        assertThat(record.toString()).doesNotContain("secret")
    }

    @Test
    fun `a snapshot that cannot be opened makes every ref unknown`() = runTest {
        val f = RealtimeFixture()
        val inputs = f.inputs.toInputs()
        val failing = RealtimeFeatureInputs(
            inputs.usage, inputs.notifications, inputs.activity, inputs.steps, inputs.sleep, inputs.dailySummaries, inputs.heartRate,
            inputs.sourceCoverage, inputs.collectorCoverage, inputs.history, inputs.live,
            snapshots = object : SnapshotReads {
                override suspend fun <T> read(block: suspend () -> T): T = throw IllegalStateException("database is locked")
            },
        )
        val engine = RealtimeFeatureEngine(failing, f.clock)

        val snapshot = engine.resolve(setOf(FeatureRef("local_time"), FeatureRef("charging")), f.now)

        assertThat(snapshot.values.values.toSet()).containsExactly(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        val f = RealtimeFixture()
        f.inputs.live.throwing = CancellationException("cancelled")

        val result = runCatching { f.engine.resolve(setOf(FeatureRef("charging")), f.now) }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `port failures map to missing reasons by error class`() {
        assertThat(missingReasonFor(AppError.PermissionDenied("app_usage_events"))).isEqualTo(MissingReason.NO_PERMISSION)
        assertThat(missingReasonFor(AppError.PermissionPermanentlyDenied("app_usage_events"))).isEqualTo(MissingReason.NO_PERMISSION)
        assertThat(missingReasonFor(AppError.AuthenticationRequired("googlehealth"))).isEqualTo(MissingReason.SOURCE_DISCONNECTED)
        assertThat(missingReasonFor(AppError.TokenExpired("googlehealth"))).isEqualTo(MissingReason.SOURCE_DISCONNECTED)
        assertThat(missingReasonFor(AppError.DatabaseError())).isEqualTo(MissingReason.API_UNAVAILABLE)
        assertThat(missingReasonFor(AppError.NetworkUnavailable())).isEqualTo(MissingReason.API_UNAVAILABLE)
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `observed values of the wrong type, out of range or outside the enum are invalid`() {
        val at = kotlin.time.Instant.parse("2026-10-01T20:00:00Z")
        val battery = checkNotNull(RealtimeFeatureCatalog["battery_pct"])
        val dayType = checkNotNull(RealtimeFeatureCatalog["day_type"])
        val foreground = checkNotNull(RealtimeFeatureCatalog["foreground_app"])
        val minutesSince = checkNotNull(RealtimeFeatureCatalog["minutes_since_last_delivery"])
        val invalid = missing(MissingReason.INVALID_VALUE)

        assertThat(RealtimeFeatureEngine.validate(battery, FeatureValue.Known(FeatureScalar.BoolValue(true), at))).isEqualTo(invalid)
        assertThat(RealtimeFeatureEngine.validate(battery, FeatureValue.Stale(FeatureScalar.IntValue(-1), at, MissingReason.NOT_SYNCED)))
            .isEqualTo(invalid)
        assertThat(RealtimeFeatureEngine.validate(dayType, FeatureValue.Known(FeatureScalar.EnumValue("HOLIDAY"), at))).isEqualTo(invalid)
        assertThat(RealtimeFeatureEngine.validate(foreground, FeatureValue.Known(FeatureScalar.NoPackage, at)))
            .isEqualTo(FeatureValue.Known(FeatureScalar.NoPackage, at))
        assertThat(RealtimeFeatureEngine.validate(minutesSince, FeatureValue.Known(FeatureScalar.Never, at)))
            .isEqualTo(FeatureValue.Known(FeatureScalar.Never, at))
        assertThat(RealtimeFeatureEngine.validate(battery, missing(MissingReason.NO_DATA))).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `every catalog feature resolves to a value of its own type`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.inputs.healthSyncedThrough(f.now)
        f.inputs.steps.add(HealthSources.GOOGLE_HEALTH_STEPS, StepInterval(f.now - 20.minutes, f.now - 19.minutes, 120))
        f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 10, 1), 58)

        val snapshot = f.engine.resolve(everyFeature.toSet(), f.now)

        for (ref in everyFeature) {
            val definition = checkNotNull(RealtimeFeatureCatalog[ref.featureId])
            val value = checkNotNull(snapshot[ref])
            assertWithMessage(ref.key).that(RealtimeFeatureEngine.validate(definition, value)).isEqualTo(value)
            if (definition.isAvailable && ref.featureId != "resting_hr_delta_vs_28d" && !ref.featureId.startsWith("sleep") &&
                ref.featureId !in setOf("bedtime_last_night", "wake_time_today", "activity_state")
            ) {
                assertWithMessage(ref.key).that(value).isInstanceOf(FeatureValue.Known::class.java)
            }
        }
    }

    // ------------------------------------------------------------------ coverage (lifecycle-battery-01) and categories

    @Test
    fun `every catalog feature declares a coverage source that matches its freshness`() {
        assertThat(RealtimeFeatureCoverage.byFeature.keys).isEqualTo(RealtimeFeatureCatalog.ids)
        for (definition in RealtimeFeatureCatalog.all) {
            val coverage = RealtimeFeatureCoverage[definition.id]
            val availability = definition.availability
            val expected = when {
                availability is FeatureAvailability.Unavailable -> coverage == FeatureCoverage.NotCollected(availability.capabilityId)
                definition.freshness == Freshness.AlwaysKnown -> coverage == FeatureCoverage.Intrinsic
                definition.freshness == Freshness.LiveRead -> coverage == FeatureCoverage.LiveRead
                definition.freshness == Freshness.CollectorCoverage -> coverage is FeatureCoverage.Collector
                else -> coverage is FeatureCoverage.Source
            }
            assertWithMessage(definition.id).that(expected).isTrue()
        }
    }

    @Test
    fun `every resolved value traces to its data category, so a category delete can scrub snapshots (jitai-correctness-19)`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        val snapshot = f.engine.resolve(everyFeature.toSet(), f.now)

        for (ref in everyFeature) {
            val definition = checkNotNull(RealtimeFeatureCatalog[ref.featureId])
            assertWithMessage(ref.key).that(FeatureCategories.ofKey(ref.key)).isEqualTo(definition.category)
            assertWithMessage(ref.key).that(FeatureCategories.of(ref)).isEqualTo(definition.category)
            if (definition.group != dev.agentle.analytics.features.FeatureGroup.TIME) {
                assertWithMessage(ref.key).that(definition.category).isNotNull()
            }
        }
        val scrubbed = FeatureCategories.scrub(snapshot, DataCategory.APP_USAGE)
        assertThat(scrubbed.values.keys.map { FeatureCategories.ofKey(it) }).doesNotContain(DataCategory.APP_USAGE)
        assertThat(scrubbed.values.keys).containsAtLeast("screen_minutes_last_60m", "steps_today", "local_time")
        assertThat(FeatureCategories.ofKey("no_such_feature{a=b}")).isNull()
    }

    @Test
    fun `the engine reads the zone once per pass and records it in the snapshot`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.clock.setZone(Zones.NEW_YORK)

        val snapshot = f.snapshot(FeatureRef("local_time"), FeatureRef("day_of_week"))

        assertThat(snapshot.zoneId).isEqualTo("America/New_York")
        assertThat(snapshot[FeatureRef("local_time")]?.knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(17 * 60))
        assertThat(snapshot.at).isEqualTo(f.now)
    }

    @Test
    fun `collector coverage is required for the whole window even with data present`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T23:00")
        f.resumed(INSTAGRAM, "2026-10-01T22:30")
        f.inputs.collectorCoverage.clear(CollectorIds.USAGE_EVENTS)
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 1.days, f.now - 45.minutes))
        f.inputs.collectorCoverage.add(CollectorIds.USAGE_EVENTS, CoverageInterval(f.now - 44.minutes))

        assertThat(f.value("app_minutes_last_60m", "package" to INSTAGRAM)).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }
}
