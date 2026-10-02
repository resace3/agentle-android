package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.analytics.features.realtime.testing.InMemoryRealtimeInputs
import dev.agentle.analytics.features.realtime.testing.StepSourceSpec
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Severity
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal object Zones {
    val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")
    val NEW_YORK: TimeZone = TimeZone.of("America/New_York")
    val SANTIAGO: TimeZone = TimeZone.of("America/Santiago")
    val LORD_HOWE: TimeZone = TimeZone.of("Australia/Lord_Howe")
    val KOLKATA: TimeZone = TimeZone.of("Asia/Kolkata")
}

internal const val INSTAGRAM = "com.instagram.android"
internal const val CHAT = "com.example.chat"
internal const val MAPS = "com.example.maps"

/**
 * Fixture F0 of R10 §12: Europe/Berlin, 2026-10-01 (Thursday), every access granted, every collector healthy for a
 * month, the device interactive, not charging, battery 80 %, interruption filter `ALL`, not in a call, no decisions.
 * Health sources have no coverage until a test declares it.
 */
internal class RealtimeFixture(
    val zone: TimeZone = Zones.BERLIN,
    start: String = "2026-10-01T22:30",
    var config: RealtimeFeatureConfig = RealtimeFeatureConfig(),
) {
    val clock: TestAgentleClock = TestAgentleClock(LocalDateTime.parse(start).toInstant(zone), zone)
    val inputs: InMemoryRealtimeInputs = InMemoryRealtimeInputs()
    val logs: MutableList<LogRecord> = mutableListOf()
    private val logger = Logger(listOf(LogSink { logs += it }), { 0L }, Severity.VERBOSE)

    init {
        inputs.collectorsHealthySince(clock.now() - 30.days)
    }

    val engine: RealtimeFeatureEngine get() = RealtimeFeatureEngine(inputs.toInputs(), clock, config, logger)

    val now: Instant get() = clock.now()

    fun local(text: String): Instant = LocalDateTime.parse(text).toInstant(zone)

    /** Moves the wall clock and the monotonic clock forward together to the local time [text]. */
    fun advanceTo(text: String) {
        clock.advanceBy(local(text) - clock.now())
    }

    suspend fun snapshot(vararg refs: FeatureRef): FeatureSnapshot = engine.resolve(refs.toSet(), clock.now())

    suspend fun resolve(ref: FeatureRef): FeatureValue = checkNotNull(engine.resolve(setOf(ref), clock.now())[ref])

    suspend fun value(id: String, vararg args: Pair<String, String>): FeatureValue = resolve(FeatureRef(id, mapOf(*args)))

    // ------------------------------------------------------------------ usage events (local times)

    fun usage(time: String, kind: UsageEventKind, packageName: String? = null, className: String? = null) {
        inputs.usage.rows += UsageEvent(local(time), kind, packageName, className)
    }

    fun resumed(packageName: String, time: String, className: String = "Main") =
        usage(time, UsageEventKind.ACTIVITY_RESUMED, packageName, className)

    fun paused(packageName: String, time: String, className: String = "Main") =
        usage(time, UsageEventKind.ACTIVITY_PAUSED, packageName, className)

    fun screenOn(time: String) = usage(time, UsageEventKind.SCREEN_INTERACTIVE)

    fun screenOff(time: String) = usage(time, UsageEventKind.SCREEN_NON_INTERACTIVE)

    // ------------------------------------------------------------------ steps

    /** Only [source] (canonical) feeds the fused series, with [reportsTrueZeros]. */
    fun onlyStepSource(source: String = HealthSources.GOOGLE_HEALTH_STEPS, reportsTrueZeros: Boolean = true) {
        inputs.steps.sources.clear()
        inputs.steps.sources += StepSourceSpec(source, reportsTrueZeros)
    }

    /** One interval per minute from [from] (local), each with [count] steps, for [minutes] minutes. */
    fun stepMinutes(source: String, from: String, minutes: Int, count: Long) {
        val start = local(from)
        repeat(minutes) { k -> inputs.steps.add(source, StepInterval(start + k.minutes, start + (k + 1).minutes, count)) }
    }

    fun stepsCoverage(source: String, through: String) = inputs.sourceCoverage.set(source, HealthMetric.STEPS, local(through))
}

internal fun knownInt(value: Long, asOf: Instant, quality: Quality = Quality.FINAL): FeatureValue =
    FeatureValue.Known(FeatureScalar.IntValue(value), asOf, quality)

internal fun missing(reason: MissingReason): FeatureValue = FeatureValue.Missing(reason)

/** The scalar of a `Known` value, or null. */
internal val FeatureValue.knownScalar: FeatureScalar? get() = (this as? FeatureValue.Known)?.value

/** The integer of a `Known` integer value, or null. */
internal val FeatureValue.knownLong: Long? get() = (knownScalar as? FeatureScalar.IntValue)?.value

internal fun minutesOf(duration: Duration): Long = duration.inWholeMinutes
