package dev.agentle.connectors.android.collectors.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.agentle.connectors.android.AndroidCollectors
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.StateStream
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType

/**
 * Notification metadata (and opt-in content) from Agentle's notification listener. Collection is real time; [sync] only
 * writes what is still batched. The listener's connect/disconnect callbacks open and close coverage.
 */
public class NotificationsConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    public val collector: NotificationCollector,
) : AndroidConnector(
    id = AndroidConnectorIds.NOTIFICATIONS,
    name = "Notifications",
    supportedEventTypes = setOf(EventType.NOTIFICATION_POSTED, EventType.NOTIFICATION_REMOVED),
    capabilityIds = listOf(CapabilityIds.NOTIFICATION_EVENTS_METADATA, CapabilityIds.NOTIFICATION_CONTENT),
    runtime = runtime,
    permissions = permissions,
) {
    /** Coverage is the listener's ([NotificationCollector]: open while connected), not this connector's. */
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.NOTIFICATION_EVENTS_METADATA)

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        collector.refreshHandlers()
        val result = collector.flush()
        return CollectOutcome(committed = StateStream.committedRows(result), error = (result as? WriteResult.Unavailable)?.error)
    }

    override suspend fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) collector.dropPending()
    }
}

/**
 * Agentle's `NotificationListenerService` (not exported and guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`: only the
 * system binds it, as in the platform documentation's manifest example). It stays thin: every callback is handed to
 * [NotificationCollector], which enqueues and returns at once.
 * Notification access is a state the Permission Center reports, never a crash: reads that need the binding are guarded.
 */
public class AgentleNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val graph = AndroidCollectors.graph(applicationContext) ?: return
        graph.listenerConnection.onConnected()
        val collector = graph.notifications.collector
        collector.onListenerConnected(readActive(collector))
        readInterruptionFilter()?.let(graph::onInterruptionFilterChanged)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val graph = AndroidCollectors.graph(applicationContext) ?: return
        graph.listenerConnection.onDisconnected(graph.runtime.clock.now())
        graph.notifications.collector.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val notification = sbn ?: return
        val collector = AndroidCollectors.graph(applicationContext)?.notifications?.collector ?: return
        collector.onPosted(NotificationSnapshot.of(notification, collector.wantsContent(notification.packageName)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val notification = sbn ?: return
        val collector = AndroidCollectors.graph(applicationContext)?.notifications?.collector ?: return
        collector.onRemoved(NotificationSnapshot.of(notification, includeText = false), reason)
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        AndroidCollectors.graph(applicationContext)?.onInterruptionFilterChanged(interruptionFilter)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readActive(collector: NotificationCollector): List<NotificationSnapshot>? = try {
        activeNotifications?.map { NotificationSnapshot.of(it, collector.wantsContent(it.packageName)) }
    } catch (ignored: RuntimeException) {
        // SecurityException while the binding is not complete: the diff waits for the next connect.
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readInterruptionFilter(): Int? = try {
        currentInterruptionFilter.takeIf { it != INTERRUPTION_FILTER_UNKNOWN }
    } catch (ignored: RuntimeException) {
        null
    }
}
