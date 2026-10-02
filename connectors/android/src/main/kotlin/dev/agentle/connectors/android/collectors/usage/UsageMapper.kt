package dev.agentle.connectors.android.collectors.usage

import android.app.usage.UsageEvents
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.EventFactory
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.StandbyBucketPayload
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The mapped output of one usage window. */
public data class UsageWindow(val events: List<PersonalEvent>, val open: List<OpenSession>, val screenOnMs: Long?)

/**
 * Maps raw usage events to [PersonalEvent]s (docs/research/02 A1-A4, J1).
 *
 * Dedup keys (red team database-sync-17): `usage|<timestampMs>|<type>|<package>|<class>|<n>`, where n is the
 * occurrence index among identical `(timestamp, type, package, class)` tuples of the query. Identical tuples share one
 * timestamp, so a window either holds all of them or none, and the index is stable across overlapping sweeps. Sessions
 * use the key of the event that opened them with the type `SESSION` or `SCREEN_SESSION`.
 *
 * Point events are emitted for the whole window (re-reads converge by key); session state only advances with events at
 * or after [sinceMs] (the previous high-water mark), whose earlier effect is already in [open] and [screenOnMs].
 */
public class UsageMapper(private val events: EventFactory, private val categories: AppCategorySource) {
    public fun map(raw: List<RawUsageEvent>, sinceMs: Long?, open: List<OpenSession>, screenOnMs: Long?): UsageWindow {
        val sorted = raw.sortedWith(compareBy<RawUsageEvent> { it.timestampMs })
        val occurrences = HashMap<String, Int>()
        val out = ArrayList<PersonalEvent>()
        val sessions = LinkedHashMap<String, OpenSession>().apply { open.forEach { put(sessionKey(it.pkg, it.cls), it) } }
        var screenOn = screenOnMs
        for (event in sorted) {
            val tuple = "${event.timestampMs}|${event.type}|${event.packageName}|${event.className.orEmpty()}"
            val index = occurrences[tuple] ?: 0
            occurrences[tuple] = index + 1
            pointEvent(event, index)?.let(out::add)
            if (sinceMs != null && event.timestampMs < sinceMs) continue
            when (event.type) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    sessions[sessionKey(event.packageName, event.className)] =
                        OpenSession(event.packageName, event.className, event.timestampMs, index)

                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    sessions.remove(sessionKey(event.packageName, event.className))?.let { out += session(it, event.timestampMs) }

                UsageEvents.Event.SCREEN_INTERACTIVE -> if (screenOn == null) screenOn = event.timestampMs

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn?.let { start ->
                    out += screenSession(start, event.timestampMs)
                    screenOn = null
                }

                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> {
                    // Nothing stays open across a reboot: close at shutdown, drop at startup (the end is unknown).
                    if (event.type == UsageEvents.Event.DEVICE_SHUTDOWN) sessions.values.forEach { out += session(it, event.timestampMs) }
                    sessions.clear()
                    screenOn = null
                }
            }
        }
        val newest = sorted.lastOrNull()?.timestampMs
        val fresh = sessions.values.filter { newest == null || newest - it.startMs < STALE_SESSION_MS }
        return UsageWindow(out, fresh, screenOn?.takeIf { newest == null || newest - it < STALE_SESSION_MS })
    }

    private fun pointEvent(event: RawUsageEvent, index: Int): PersonalEvent? {
        val type: EventType
        val source: DataSourceId
        val payload: EventPayload
        when (event.type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                type = EventType.APP_FOREGROUND
                source = AndroidSources.USAGE
                payload = AppUsagePayload(event.packageName, appCategory = categories.category(event.packageName))
            }

            UsageEvents.Event.ACTIVITY_PAUSED -> {
                type = EventType.APP_BACKGROUND
                source = AndroidSources.USAGE
                payload = AppUsagePayload(event.packageName, appCategory = categories.category(event.packageName))
            }

            UsageEvents.Event.STANDBY_BUCKET_CHANGED -> {
                type = EventType.STANDBY_BUCKET_CHANGED
                source = AndroidSources.USAGE
                payload = StandbyBucketPayload(event.standbyBucket ?: return null, event.packageName)
            }

            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                type = EventType.SCREEN_ON
                source = AndroidSources.SCREEN
                payload = ScreenPayload(interactive = true)
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                type = EventType.SCREEN_OFF
                source = AndroidSources.SCREEN
                payload = ScreenPayload(interactive = false)
            }

            UsageEvents.Event.KEYGUARD_HIDDEN -> {
                type = EventType.DEVICE_UNLOCK
                source = AndroidSources.SCREEN
                payload = NoPayload
            }

            UsageEvents.Event.KEYGUARD_SHOWN -> {
                type = EventType.DEVICE_LOCK
                source = AndroidSources.SCREEN
                payload = NoPayload
            }

            UsageEvents.Event.DEVICE_STARTUP -> {
                type = EventType.BOOT_COMPLETED
                source = AndroidSources.SYSTEM
                payload = NoPayload
            }

            UsageEvents.Event.DEVICE_SHUTDOWN -> {
                type = EventType.SHUTDOWN
                source = AndroidSources.SYSTEM
                payload = NoPayload
            }

            else -> return null
        }
        val key = key(event.timestampMs, event.type.toString(), event.packageName, event.className, index)
        return events.create(type, source, Instant.fromEpochMilliseconds(event.timestampMs), payload, key)
    }

    private fun session(open: OpenSession, endMs: Long): PersonalEvent {
        val duration = (endMs - open.startMs).coerceAtLeast(0)
        return events.create(
            type = EventType.APP_SESSION,
            source = AndroidSources.USAGE,
            start = Instant.fromEpochMilliseconds(open.startMs),
            end = Instant.fromEpochMilliseconds(open.startMs + duration),
            payload = AppUsagePayload(open.pkg, durationMs = duration, appCategory = categories.category(open.pkg)),
            dedupKey = key(open.startMs, "SESSION", open.pkg, open.cls, open.index),
        )
    }

    private fun screenSession(startMs: Long, endMs: Long): PersonalEvent {
        val duration = (endMs - startMs).coerceAtLeast(0)
        return events.create(
            type = EventType.SCREEN_SESSION,
            source = AndroidSources.SCREEN,
            start = Instant.fromEpochMilliseconds(startMs),
            end = Instant.fromEpochMilliseconds(startMs + duration),
            payload = ScreenPayload(interactive = true, durationMs = duration),
            dedupKey = key(startMs, "SCREEN_SESSION", ANDROID_PACKAGE, null, 0),
        )
    }

    public companion object {
        private const val ANDROID_PACKAGE = "android"

        /** Sessions open longer than this without a closing event are dropped (the closing event was lost). */
        private val STALE_SESSION_MS = 24.hours.inWholeMilliseconds

        public fun key(timestampMs: Long, type: String, packageName: String, className: String?, index: Int): String =
            "usage|$timestampMs|$type|$packageName|${className.orEmpty()}|$index"

        private fun sessionKey(packageName: String, className: String?) = "$packageName/${className.orEmpty()}"
    }
}
