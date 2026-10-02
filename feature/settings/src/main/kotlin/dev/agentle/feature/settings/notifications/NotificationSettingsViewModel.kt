package dev.agentle.feature.settings.notifications

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.port.DeliveryLimits
import dev.agentle.feature.settings.port.InterventionChannel
import dev.agentle.feature.settings.port.NotificationSettingsPort
import dev.agentle.feature.settings.port.NotificationSettingsState
import dev.agentle.feature.settings.port.PauseOption
import dev.agentle.feature.settings.port.QuietHours
import dev.agentle.feature.settings.port.SystemSettingsPort
import dev.agentle.feature.settings.port.SystemSettingsTarget
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import dev.agentle.feature.settings.ui.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import javax.inject.Inject

internal data class NotificationSettingsUiState(
    /** The port's settings with the user's not yet stored limit and quiet-hours edits applied. */
    val content: Loadable<NotificationSettingsState> = Loadable.Loading,
    val zone: TimeZone = TimeZone.UTC,
)

internal sealed interface NotificationAction {
    data object Retry : NotificationAction

    data class Pause(val option: PauseOption) : NotificationAction

    data object Resume : NotificationAction

    data class SetQuietHoursEnabled(val enabled: Boolean) : NotificationAction

    /** Moves the start of quiet hours by 30 minutes; [later] false moves it earlier. */
    data class StepQuietHoursStart(val later: Boolean) : NotificationAction

    data class StepQuietHoursEnd(val later: Boolean) : NotificationAction

    data class StepDailyCap(val up: Boolean) : NotificationAction

    data class StepWeeklyCap(val up: Boolean) : NotificationAction

    data class StepMinGap(val up: Boolean) : NotificationAction

    data class StepChannelCap(val channel: InterventionChannel, val up: Boolean) : NotificationAction

    data class SetDetailed(val enabled: Boolean) : NotificationAction

    data class SetShowOnWearables(val enabled: Boolean) : NotificationAction

    data class OpenChannel(val channelId: String) : NotificationAction

    data object OpenAppNotificationSettings : NotificationAction

    /** The fix for missing notification access: the Permission Center when it can still ask, else system settings. */
    data object FixPermission : NotificationAction
}

/**
 * Notification settings. Limit and quiet-hours edits show at once and are written in order; every limit passes
 * through [coercedIntoBounds], so nothing above the hard ceilings is ever sent, and quiet hours never get
 * `start == end`.
 */
@HiltViewModel
internal class NotificationSettingsViewModel @Inject constructor(
    private val port: NotificationSettingsPort,
    private val systemSettings: SystemSettingsPort,
    @Suppress("UnusedPrivateProperty") private val zonePort: UserTimeZonePort,
) : EffectViewModel() {
    private val reloads = MutableStateFlow(0)
    private val pendingLimits = MutableStateFlow<DeliveryLimits?>(null)
    private val pendingQuietHours = MutableStateFlow<QuietHours?>(null)
    private val writes = Mutex()

    val state: StateFlow<NotificationSettingsUiState> = combine(
        reloads.reloading { port.state },
        pendingLimits,
        pendingQuietHours,
    ) { content, limits, quietHours ->
        val shown = if (content is Loadable.Ready) {
            Loadable.Ready(
                content.value.copy(
                    limits = limits ?: content.value.limits,
                    quietHours = quietHours ?: content.value.quietHours,
                ),
            )
        } else {
            content
        }
        NotificationSettingsUiState(shown, zonePort.zone())
    }.stateIn(viewModelScope, WhileUiSubscribed, NotificationSettingsUiState(zone = zonePort.zone()))

    fun onAction(action: NotificationAction) {
        when (action) {
            NotificationAction.Retry -> reloads.update { it + 1 }

            is NotificationAction.Pause -> write { port.pause(action.option) }

            NotificationAction.Resume -> write { port.resume() }

            is NotificationAction.SetQuietHoursEnabled -> editQuietHours { it.copy(enabled = action.enabled) }

            is NotificationAction.StepQuietHoursStart ->
                editQuietHours { it.copy(start = it.start.plusMinutesWrapped(quietStep(action.later))) }

            is NotificationAction.StepQuietHoursEnd -> editQuietHours { it.copy(end = it.end.plusMinutesWrapped(quietStep(action.later))) }

            is NotificationAction.StepDailyCap -> editLimits { it.withDailyCap(it.dailyCap + step(LimitSteps.DAILY_CAP, action.up)) }

            is NotificationAction.StepWeeklyCap -> editLimits { it.withWeeklyCap(it.weeklyCap + step(LimitSteps.WEEKLY_CAP, action.up)) }

            is NotificationAction.StepMinGap -> editLimits { it.withMinGap(it.minGapMinutes + step(LimitSteps.MIN_GAP_MINUTES, action.up)) }

            is NotificationAction.StepChannelCap -> editLimits {
                it.withChannelCap(action.channel, it.effectiveChannelCap(action.channel) + step(LimitSteps.CHANNEL_CAP, action.up))
            }

            is NotificationAction.SetDetailed -> write { port.setDetailedNotifications(action.enabled) }

            is NotificationAction.SetShowOnWearables -> write { port.setShowOnWearables(action.enabled) }

            is NotificationAction.OpenChannel ->
                openSystemPage(systemSettings.intentFor(SystemSettingsTarget.NotificationChannel(action.channelId)))

            NotificationAction.OpenAppNotificationSettings -> openSystemPage(
                systemSettings.intentFor(SystemSettingsTarget.AppNotifications),
            )

            NotificationAction.FixPermission -> fixPermission()
        }
    }

    private fun current(): NotificationSettingsState? = state.value.content.valueOrNull()

    private fun editLimits(edit: (DeliveryLimits) -> DeliveryLimits) {
        val limits = current()?.limits ?: return
        val next = edit(limits).coercedIntoBounds()
        if (next == limits) return
        pendingLimits.value = next
        viewModelScope.launch {
            val result = writes.withLock { port.setLimits(next) }
            pendingLimits.compareAndSet(next, null)
            if (result is Outcome.Failure) report(result.error)
        }
    }

    private fun editQuietHours(edit: (QuietHours) -> QuietHours) {
        val quietHours = current()?.quietHours ?: return
        val next = edit(quietHours)
        // Quiet hours from a time to the same time are invalid (R10 E025): such a step is skipped, not sent.
        if (next == quietHours || next.start == next.end) return
        pendingQuietHours.value = next
        viewModelScope.launch {
            val result = writes.withLock { port.setQuietHours(next) }
            pendingQuietHours.compareAndSet(next, null)
            if (result is Outcome.Failure) report(result.error)
        }
    }

    private fun write(block: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            val result = writes.withLock { block() }
            if (result is Outcome.Failure) report(result.error)
        }
    }

    private fun fixPermission() {
        if (current()?.access?.permission == PermissionState.DENIED) {
            send(SettingsEffect.Navigate(AppRoute.PermissionCenter(POST_NOTIFICATIONS_CAPABILITY)))
        } else {
            openSystemPage(systemSettings.intentFor(SystemSettingsTarget.AppNotifications))
        }
    }

    private companion object {
        /** The JITAI notification capability in `capabilities.json`. */
        const val POST_NOTIFICATIONS_CAPABILITY = "post_notifications_jitai"

        fun step(size: Int, up: Boolean): Int = if (up) size else -size

        fun quietStep(later: Boolean): Int = step(LimitSteps.QUIET_HOURS_MINUTES, later)
    }
}
