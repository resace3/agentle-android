package dev.agentle.analytics.features.realtime.testing

import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.ActivityTransition
import dev.agentle.analytics.features.realtime.ActivityTransitionPort
import dev.agentle.analytics.features.realtime.AudioMode
import dev.agentle.analytics.features.realtime.AudioOutputType
import dev.agentle.analytics.features.realtime.InputAnswer
import dev.agentle.analytics.features.realtime.InterruptionFilter
import dev.agentle.analytics.features.realtime.LiveDeviceReads
import dev.agentle.analytics.features.realtime.NotificationEventPort
import dev.agentle.analytics.features.realtime.NotificationPost
import dev.agentle.analytics.features.realtime.UsageEvent
import dev.agentle.analytics.features.realtime.UsageEventPort
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange

/**
 * Test support: usage events and app categories in memory. Packages without a category are `UNDEFINED`. When set,
 * [categoryFailure] fails only the category lookup.
 */
public class InMemoryUsageEvents(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    UsageEventPort {
    public val rows: MutableList<UsageEvent> = mutableListOf()

    public val categories: MutableMap<String, String> = mutableMapOf()

    public var categoryFailure: AppError? = null

    override suspend fun events(range: ClosedOpenRange): Outcome<InputAnswer<List<UsageEvent>>> = answer("usage.events") {
        InputAnswer.Available(rows.filter { it.at in range }.sortedBy { it.at })
    }

    override suspend fun appCategories(packages: Set<String>): Outcome<InputAnswer<Map<String, String>>> {
        val outcome = answer("usage.appCategories") { InputAnswer.Available(packages.associateWith { categories[it] ?: UNDEFINED }) }
        return categoryFailure?.let { Outcome.failure(it) } ?: outcome
    }

    private companion object {
        const val UNDEFINED = "UNDEFINED"
    }
}

/** Test support: POSTED notification rows in memory (one row per key and lifetime, as the collector writes them). */
public class InMemoryNotifications(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    NotificationEventPort {
    public val rows: MutableList<NotificationPost> = mutableListOf()

    override suspend fun posted(range: ClosedOpenRange): Outcome<InputAnswer<List<NotificationPost>>> =
        answer("notifications.posted") { InputAnswer.Available(rows.filter { it.at in range }.sortedBy { it.at }) }
}

/** Test support: Activity Recognition transitions in memory. */
public class InMemoryActivityTransitions(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    ActivityTransitionPort {
    public val rows: MutableList<ActivityTransition> = mutableListOf()

    override suspend fun transitions(range: ClosedOpenRange): Outcome<InputAnswer<List<ActivityTransition>>> =
        answer("activity.transitions") { InputAnswer.Available(rows.filter { it.at in range }.sortedBy { it.at }) }
}

/** The live device state a [FakeLiveDeviceReads] answers; the defaults are fixture F0 of R10 §12. */
public data class LiveDeviceState(
    val charging: Boolean = false,
    val batteryPercent: Int = 80,
    val interactive: Boolean = true,
    val interruptionFilter: InterruptionFilter = InterruptionFilter.ALL,
    val audioMode: AudioMode = AudioMode.NORMAL,
    val audioOutputs: Set<AudioOutputType> = setOf(AudioOutputType.BUILTIN_SPEAKER),
    val foregroundApp: String? = null,
    val bootCount: Int = 1,
)

/** The live reads of [LiveDeviceReads], to make one of them unavailable or failing. */
public enum class LiveRead { CHARGING, BATTERY, INTERACTIVE, INTERRUPTION_FILTER, AUDIO_MODE, AUDIO_OUTPUTS, FOREGROUND_APP, BOOT_COUNT }

/**
 * Test support: live device reads answering [state]. A read listed in [failures] fails with that error, one listed in
 * [unavailableReads] answers `Unavailable` with that reason; [InMemoryPort.failure] and [InMemoryPort.unavailable]
 * apply to every read.
 */
public class FakeLiveDeviceReads(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    LiveDeviceReads {
    public var state: LiveDeviceState = LiveDeviceState()

    public val failures: MutableMap<LiveRead, AppError> = mutableMapOf()

    public val unavailableReads: MutableMap<LiveRead, MissingReason> = mutableMapOf()

    override suspend fun charging(): Outcome<InputAnswer<Boolean>> = live(LiveRead.CHARGING) { state.charging }

    override suspend fun batteryPercent(): Outcome<InputAnswer<Int>> = live(LiveRead.BATTERY) { state.batteryPercent }

    override suspend fun interactive(): Outcome<InputAnswer<Boolean>> = live(LiveRead.INTERACTIVE) { state.interactive }

    override suspend fun interruptionFilter(): Outcome<InputAnswer<InterruptionFilter>> =
        live(LiveRead.INTERRUPTION_FILTER) { state.interruptionFilter }

    override suspend fun audioMode(): Outcome<InputAnswer<AudioMode>> = live(LiveRead.AUDIO_MODE) { state.audioMode }

    override suspend fun audioOutputs(): Outcome<InputAnswer<Set<AudioOutputType>>> = live(LiveRead.AUDIO_OUTPUTS) { state.audioOutputs }

    override suspend fun foregroundApp(): Outcome<InputAnswer<String?>> = live(LiveRead.FOREGROUND_APP) { state.foregroundApp }

    override suspend fun bootCount(): Outcome<InputAnswer<Int>> = live(LiveRead.BOOT_COUNT) { state.bootCount }

    private fun <T> live(read: LiveRead, value: () -> T): Outcome<InputAnswer<T>> {
        val outcome = answer("live.${read.name.lowercase()}") { InputAnswer.Available(value()) }
        if (outcome is Outcome.Failure) return outcome
        failures[read]?.let { return Outcome.failure(it) }
        unavailableReads[read]?.let { return InputAnswer.unavailable(it) }
        return outcome
    }
}
