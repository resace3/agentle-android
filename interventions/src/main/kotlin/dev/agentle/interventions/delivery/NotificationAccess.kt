package dev.agentle.interventions.delivery

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.agentle.interventions.notification.InterventionChannels
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.InterruptionFilter

/** Why a notification cannot be shown (the in-app card and the settings screen explain it with this). */
enum class BlockReason { PERMISSION_DENIED, APP_NOTIFICATIONS_OFF, NOTIFICATIONS_PAUSED, CHANNEL_BLOCKED }

/**
 * What the platform allows right now. [prerequisite] is the engine's delivery prerequisite of one category
 * (jitai-correctness-13): POST_NOTIFICATIONS granted (API 33+) and app notifications enabled, the category channel's
 * importance not NONE (a blocked channel group blocks every category), and notifications not paused.
 */
data class NotificationAccess(
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val notificationsPaused: Boolean,
    val blockedCategories: Set<JitaiCategory>,
    val interruptionFilter: InterruptionFilter,
) {
    /** The first reason [category] cannot be posted, or null when it can. */
    fun blockReason(category: JitaiCategory): BlockReason? = when {
        !permissionGranted -> BlockReason.PERMISSION_DENIED
        !appNotificationsEnabled -> BlockReason.APP_NOTIFICATIONS_OFF
        notificationsPaused -> BlockReason.NOTIFICATIONS_PAUSED
        category in blockedCategories -> BlockReason.CHANNEL_BLOCKED
        else -> null
    }

    fun allows(category: JitaiCategory): Boolean = blockReason(category) == null

    /** The engine's view; `prerequisite(category).met == allows(category)`. */
    fun prerequisite(category: JitaiCategory): DeliveryPrerequisite = DeliveryPrerequisite(
        notificationsEnabled = permissionGranted && appNotificationsEnabled,
        channelImportanceNone = category in blockedCategories,
        notificationsPaused = notificationsPaused,
    )
}

/** Reads [NotificationAccess] from the platform. None of these calls needs a permission. */
class NotificationStateReader(private val context: Context, private val channels: InterventionChannels) {
    fun read(): NotificationAccess {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return NotificationAccess(
            permissionGranted = permission,
            appNotificationsEnabled = manager.areNotificationsEnabled(),
            notificationsPaused = paused(manager),
            blockedCategories = channels.blockedCategories(),
            interruptionFilter = filterOf(manager.currentInterruptionFilter),
        )
    }

    /** `getCurrentInterruptionFilter()` for the engine's `NotificationSystemState` (G07); UNKNOWN counts as DND on. */
    fun interruptionFilter(): InterruptionFilter =
        filterOf(context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)

    /**
     * `areNotificationsPaused()` (API 29: the package is suspended, "Pause app"). A failing system call counts as paused,
     * so the prerequisite fails closed like the engine's `DeliveryPrerequisite.UNKNOWN`.
     */
    private fun paused(manager: NotificationManager): Boolean = try {
        manager.areNotificationsPaused()
    } catch (expected: RuntimeException) {
        true
    }

    private fun filterOf(value: Int): InterruptionFilter = when (value) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionFilter.ALL
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionFilter.PRIORITY
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionFilter.ALARMS
        NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionFilter.NONE
        else -> InterruptionFilter.UNKNOWN
    }
}
