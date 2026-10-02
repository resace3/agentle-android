package dev.agentle.feature.settings.notifications

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.DeliveryLimitBounds
import dev.agentle.feature.settings.port.DeliveryLimits
import dev.agentle.feature.settings.port.InterventionChannel
import dev.agentle.feature.settings.port.JitaiPause
import dev.agentle.feature.settings.port.NotificationAccess
import dev.agentle.feature.settings.port.NotificationChannelInfo
import dev.agentle.feature.settings.port.PauseOption
import dev.agentle.feature.settings.port.QuietHours
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.ButtonRow
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.NavigationRow
import dev.agentle.feature.settings.ui.NoticeCard
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.StepperRow
import dev.agentle.feature.settings.ui.SwitchRow
import dev.agentle.feature.settings.ui.rememberDisplayFormats

@Composable
internal fun NotificationSettingsRoute(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    NotificationSettingsScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/**
 * Notification settings: notification access with its fix, pausing, quiet hours, limits (lower only, the hard
 * ceilings are the maximum), what notifications show, and the system notification categories.
 */
@Composable
internal fun NotificationSettingsScreen(
    state: NotificationSettingsUiState,
    onAction: (NotificationAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDisplayFormats(state.zone)
    SettingsScaffold(
        title = stringResource(R.string.settings_notifications_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        LoadableContent(state.content, onRetry = { onAction(NotificationAction.Retry) }) { content ->
            AccessSection(content.access, onAction)
            PauseSection(content.pause, formats, onAction)
            QuietHoursSection(content.quietHours, formats, onAction)
            LimitsSection(content.limits, formats, onAction)
            ContentSection(content.detailedNotifications, content.showOnWearables, onAction)
            ChannelsSection(content.channels, onAction)
        }
    }
}

@Composable
private fun AccessSection(access: NotificationAccess?, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_access))
    when {
        access == null -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_notifications_access_unknown))

        access.canPost -> StatusLine(StatusKind.OK, stringResource(R.string.settings_notifications_access_ok))

        access.permission != PermissionState.ALLOWED -> NoticeCard(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.settings_notifications_permission_missing),
            body = stringResource(R.string.settings_notifications_fallback_cards),
        ) {
            ButtonRow {
                Button(onClick = { onAction(NotificationAction.FixPermission) }) {
                    Text(stringResource(R.string.settings_notifications_allow))
                }
            }
        }

        !access.appNotificationsEnabled -> NoticeCard(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.settings_notifications_app_disabled),
            body = stringResource(R.string.settings_notifications_fallback_cards),
        ) {
            ButtonRow {
                Button(onClick = { onAction(NotificationAction.OpenAppNotificationSettings) }) {
                    Text(stringResource(R.string.settings_notifications_open_settings))
                }
            }
        }

        else -> Unit
    }
    if (access?.pausedBySystem == true) {
        NoticeCard(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.settings_notifications_paused_by_system),
            body = stringResource(R.string.settings_notifications_paused_by_system_body),
        )
    }
}

@Composable
private fun PauseSection(pause: JitaiPause, formats: DisplayFormats, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_pause))
    when (pause) {
        JitaiPause.NotPaused -> {
            BodyText(stringResource(R.string.settings_notifications_pause_body))
            ButtonRow {
                PauseOption.entries.forEach { option ->
                    OutlinedButton(onClick = { onAction(NotificationAction.Pause(option)) }) {
                        Text(pauseOptionLabel(option))
                    }
                }
            }
        }

        is JitaiPause.Until, JitaiPause.UntilResumed -> {
            StatusLine(StatusKind.OFF, pauseText(pause, formats).orEmpty())
            ButtonRow {
                Button(onClick = { onAction(NotificationAction.Resume) }) {
                    Text(stringResource(R.string.settings_notifications_resume))
                }
            }
        }
    }
}

@Composable
private fun QuietHoursSection(quietHours: QuietHours, formats: DisplayFormats, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_quiet))
    SwitchRow(
        title = stringResource(R.string.settings_notifications_quiet_switch),
        summary = stringResource(
            R.string.settings_notifications_quiet_summary,
            formats.time(quietHours.start),
            formats.time(quietHours.end),
        ),
        checked = quietHours.enabled,
        onCheckedChange = { onAction(NotificationAction.SetQuietHoursEnabled(it)) },
    )
    if (quietHours.enabled) {
        val step = LimitSteps.QUIET_HOURS_MINUTES
        StepperRow(
            title = stringResource(R.string.settings_notifications_quiet_start),
            valueText = formats.time(quietHours.start),
            canDecrement = quietHours.start.plusMinutesWrapped(-step) != quietHours.end,
            canIncrement = quietHours.start.plusMinutesWrapped(step) != quietHours.end,
            onDecrement = { onAction(NotificationAction.StepQuietHoursStart(later = false)) },
            onIncrement = { onAction(NotificationAction.StepQuietHoursStart(later = true)) },
        )
        StepperRow(
            title = stringResource(R.string.settings_notifications_quiet_end),
            valueText = formats.time(quietHours.end),
            canDecrement = quietHours.end.plusMinutesWrapped(-step) != quietHours.start,
            canIncrement = quietHours.end.plusMinutesWrapped(step) != quietHours.start,
            onDecrement = { onAction(NotificationAction.StepQuietHoursEnd(later = false)) },
            onIncrement = { onAction(NotificationAction.StepQuietHoursEnd(later = true)) },
        )
    }
    BodyText(stringResource(R.string.settings_notifications_quiet_body))
}

@Composable
private fun LimitsSection(limits: DeliveryLimits, formats: DisplayFormats, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_limits))
    BodyText(
        stringResource(
            R.string.settings_notifications_limits_body,
            DeliveryLimitBounds.DAILY_CAP_MAX,
            DeliveryLimitBounds.WEEKLY_CAP_MAX,
            DeliveryLimitBounds.MIN_GAP_MINUTES_MIN,
        ),
    )
    StepperRow(
        title = stringResource(R.string.settings_notifications_daily_cap),
        valueText = formats.count(limits.dailyCap.toLong()),
        canDecrement = limits.dailyCap > DeliveryLimitBounds.dailyCapRange.first,
        canIncrement = limits.dailyCap < DeliveryLimitBounds.DAILY_CAP_MAX,
        onDecrement = { onAction(NotificationAction.StepDailyCap(up = false)) },
        onIncrement = { onAction(NotificationAction.StepDailyCap(up = true)) },
    )
    StepperRow(
        title = stringResource(R.string.settings_notifications_weekly_cap),
        valueText = formats.count(limits.weeklyCap.toLong()),
        canDecrement = limits.weeklyCap > DeliveryLimitBounds.weeklyCapRange.first,
        canIncrement = limits.weeklyCap < DeliveryLimitBounds.WEEKLY_CAP_MAX,
        onDecrement = { onAction(NotificationAction.StepWeeklyCap(up = false)) },
        onIncrement = { onAction(NotificationAction.StepWeeklyCap(up = true)) },
    )
    StepperRow(
        title = stringResource(R.string.settings_notifications_min_gap),
        valueText = pluralStringResource(R.plurals.settings_minutes, limits.minGapMinutes, formats.count(limits.minGapMinutes.toLong())),
        canDecrement = limits.minGapMinutes > DeliveryLimitBounds.MIN_GAP_MINUTES_MIN,
        canIncrement = limits.minGapMinutes < DeliveryLimitBounds.MIN_GAP_MINUTES_MAX,
        onDecrement = { onAction(NotificationAction.StepMinGap(up = false)) },
        onIncrement = { onAction(NotificationAction.StepMinGap(up = true)) },
    )
    SectionHeader(stringResource(R.string.settings_notifications_section_channel_caps))
    BodyText(stringResource(R.string.settings_notifications_channel_caps_body))
    InterventionChannel.entries.forEach { channel ->
        val cap = limits.effectiveChannelCap(channel)
        StepperRow(
            title = channelCapLabel(channel),
            valueText = formats.count(cap.toLong()),
            canDecrement = cap > 0,
            canIncrement = cap < limits.dailyCap,
            onDecrement = { onAction(NotificationAction.StepChannelCap(channel, up = false)) },
            onIncrement = { onAction(NotificationAction.StepChannelCap(channel, up = true)) },
        )
    }
}

@Composable
private fun ContentSection(detailed: Boolean, showOnWearables: Boolean, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_content))
    SwitchRow(
        title = stringResource(R.string.settings_notifications_detailed),
        summary = stringResource(
            if (detailed) R.string.settings_notifications_detailed_on else R.string.settings_notifications_detailed_off,
        ),
        checked = detailed,
        onCheckedChange = { onAction(NotificationAction.SetDetailed(it)) },
    )
    BodyText(stringResource(R.string.settings_notifications_detailed_lock_screen))
    SwitchRow(
        title = stringResource(R.string.settings_notifications_wearables),
        summary = stringResource(
            if (showOnWearables) R.string.settings_notifications_wearables_on else R.string.settings_notifications_wearables_off,
        ),
        checked = showOnWearables,
        onCheckedChange = { onAction(NotificationAction.SetShowOnWearables(it)) },
    )
}

@Composable
private fun ChannelsSection(channels: List<NotificationChannelInfo>?, onAction: (NotificationAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_notifications_section_channels))
    BodyText(stringResource(R.string.settings_notifications_channels_body))
    if (channels == null) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_notifications_channels_unknown))
    } else {
        channels.forEach { channel ->
            NavigationRow(
                title = channel.name,
                summary = stringResource(
                    if (channel.blocked) R.string.settings_notifications_channel_blocked else R.string.settings_notifications_channel_on,
                ),
                onClick = { onAction(NotificationAction.OpenChannel(channel.id)) },
            )
        }
    }
    NavigationRow(
        title = stringResource(R.string.settings_notifications_all_settings),
        summary = stringResource(R.string.settings_notifications_all_settings_summary),
        onClick = { onAction(NotificationAction.OpenAppNotificationSettings) },
    )
}

/** "JITAIs are paused until Oct 2, 2026, 15:00", or null when not paused. */
@Composable
internal fun pauseText(pause: JitaiPause, formats: DisplayFormats): String? = when (pause) {
    is JitaiPause.Until -> stringResource(R.string.settings_notifications_paused_until, formats.dateTime(pause.until))
    JitaiPause.UntilResumed -> stringResource(R.string.settings_notifications_paused_until_resumed)
    JitaiPause.NotPaused -> null
}

/** The hub's summary: the pause, or the daily cap; null while neither is known. */
@Composable
internal fun notificationsSummary(pause: JitaiPause?, dailyCap: Int?, formats: DisplayFormats): String? =
    pause?.let { pauseText(it, formats) }
        ?: dailyCap?.let { pluralStringResource(R.plurals.settings_hub_daily_cap, it, formats.count(it.toLong())) }

@Composable
private fun pauseOptionLabel(option: PauseOption): String = stringResource(
    when (option) {
        PauseOption.ONE_HOUR -> R.string.settings_notifications_pause_1h
        PauseOption.TWO_HOURS -> R.string.settings_notifications_pause_2h
        PauseOption.UNTIL_TOMORROW -> R.string.settings_notifications_pause_tomorrow
        PauseOption.UNTIL_RESUMED -> R.string.settings_notifications_pause_until_resumed
    },
)

@Composable
private fun channelCapLabel(channel: InterventionChannel): String = stringResource(
    when (channel) {
        InterventionChannel.NOTIFICATION -> R.string.settings_channel_cap_notification
        InterventionChannel.IMAGE -> R.string.settings_channel_cap_image
        InterventionChannel.VOICE -> R.string.settings_channel_cap_voice
        InterventionChannel.VIDEO -> R.string.settings_channel_cap_video
    },
)
