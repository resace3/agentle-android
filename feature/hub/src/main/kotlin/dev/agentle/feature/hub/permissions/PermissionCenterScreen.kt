package dev.agentle.feature.hub.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.common.AppError
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.component.AgentleScaffold
import dev.agentle.core.ui.component.EmptyState
import dev.agentle.core.ui.component.ErrorState
import dev.agentle.core.ui.component.SectionHeader
import dev.agentle.core.ui.component.SettingsSwitchRow
import dev.agentle.core.ui.format.relativeTimeText
import dev.agentle.core.ui.permission.openSettingsScreen
import dev.agentle.core.ui.permission.rememberPermissionRequester
import dev.agentle.core.ui.status.StatusChip
import dev.agentle.core.ui.status.toStatusSpec
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.feature.hub.CapabilityAction
import dev.agentle.feature.hub.Load
import dev.agentle.feature.hub.LoadContent
import dev.agentle.feature.hub.R
import dev.agentle.feature.hub.isBestEffort
import dev.agentle.feature.hub.isInfoOnly
import dev.agentle.feature.hub.isPlannedForUse
import dev.agentle.feature.hub.permissionLabel
import dev.agentle.feature.hub.port.CapabilityItem
import dev.agentle.feature.hub.primaryAction
import dev.agentle.feature.hub.specialAccessRes
import dev.agentle.feature.hub.whyRes
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

@Composable
internal fun PermissionCenterRoute(viewModel: PermissionCenterViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requester = rememberPermissionRequester(viewModel::onPermissionResult)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is PermissionCenterEffect.Request -> requester.request(effect.permissions)
                is PermissionCenterEffect.OpenSettings -> context.openSettingsScreen(effect.intent)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val permissions = (state.content as? Load.Ready)?.value.orEmpty()
            .flatMap { group -> group.items.flatMap { it.capability.runtimePermissions } }.distinct()
        viewModel.refresh(requester.showRationale(permissions))
    }
    PermissionCenterScreen(
        state = state,
        now = viewModel.clock.now(),
        zone = viewModel.clock.zone(),
        actions = PermissionCenterActions(
            back = onBack,
            retry = viewModel::retry,
            request = viewModel::request,
            openSettings = viewModel::openSettings,
            setEnabled = viewModel::setCollectionEnabled,
            revokeHelp = viewModel::showRevokeHelp,
            dismissError = viewModel::dismissActionError,
        ),
    )
}

internal data class PermissionCenterActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val request: (CapabilityItem) -> Unit = {},
    val openSettings: (CapabilityItem) -> Unit = {},
    val setEnabled: (CapabilityItem, Boolean) -> Unit = { _, _ -> },
    val revokeHelp: (CapabilityItem?) -> Unit = {},
    val dismissError: () -> Unit = {},
)

@Composable
internal fun PermissionCenterScreen(state: PermissionCenterUiState, now: Instant, zone: TimeZone, actions: PermissionCenterActions) {
    AgentleScaffold(title = stringResource(R.string.hub_permissions_title), onBack = actions.back) { padding ->
        LoadContent(state.content, padding, actions.retry) { groups ->
            if (groups.isEmpty()) {
                EmptyState(title = stringResource(R.string.hub_permissions_empty), modifier = Modifier.padding(padding))
                return@LoadContent
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex(groups, state.focusId))
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + AgentleSpacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
            ) {
                item {
                    Text(
                        stringResource(R.string.hub_permissions_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = AgentleSpacing.screenGutter),
                    )
                }
                groups.forEach { group ->
                    item(key = "h-${group.category}") { SectionHeader(group.category.label) }
                    items(group.items, key = { it.capability.id }) { item ->
                        CapabilityCard(item, highlighted = item.capability.id == state.focusId, now, zone, actions)
                    }
                }
            }
        }
    }
    state.revokeHelpFor?.let { item -> RevokeDialog(item, actions) }
    state.actionError?.let { error -> ActionErrorDialog(error, actions.dismissError) }
}

/** List index of the focused capability's section header (the intro is item 0), or 0. */
internal fun focusIndex(groups: List<CapabilityGroup>, focusId: String?): Int {
    if (focusId == null) return 0
    var index = 1
    for (group in groups) {
        val position = group.items.indexOfFirst { it.capability.id == focusId }
        if (position >= 0) return index + position
        index += group.items.size + 1
    }
    return 0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityCard(item: CapabilityItem, highlighted: Boolean, now: Instant, zone: TimeZone, actions: PermissionCenterActions) {
    val capability = item.capability
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AgentleSpacing.screenGutter).testTag("capability-${capability.id}"),
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(AgentleSpacing.l), verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
            Text(capability.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            StatusChip(item.status.state.toStatusSpec())
            Text(stringResource(R.string.hub_cap_data, capability.name), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(capability.category.whyRes()), style = MaterialTheme.typography.bodyMedium)
            Text(accessText(item), style = MaterialTheme.typography.bodySmall)
            Text(
                item.lastUsedAt?.let { stringResource(R.string.hub_cap_last_used, relativeTimeText(it, now, zone)) }
                    ?: stringResource(R.string.hub_cap_never_used),
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                item.isInfoOnly() -> Note(stringResource(R.string.hub_cap_info_only))

                !capability.isPlannedForUse() -> Note(stringResource(R.string.hub_cap_not_in_version))

                item.status.state == PermissionState.RESTRICTED_BY_ANDROID ->
                    Note(stringResource(R.string.hub_cap_restricted))
            }
            if (item.isBestEffort()) Note(stringResource(R.string.hub_cap_best_effort))
            if (capability.isPlannedForUse() && !item.isInfoOnly()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.hub_cap_collect),
                    checked = item.collectionEnabled,
                    onCheckedChange = { actions.setEnabled(item, it) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
                when (primaryAction(item)) {
                    CapabilityAction.REQUEST -> Button(
                        onClick = { actions.request(item) },
                        modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget),
                    ) { Text(stringResource(R.string.hub_cap_allow)) }

                    CapabilityAction.OPEN_SETTINGS -> Button(
                        onClick = { actions.openSettings(item) },
                        modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget),
                    ) { Text(stringResource(R.string.hub_cap_open_settings)) }

                    CapabilityAction.NONE -> Unit
                }
                if (capability.isPlannedForUse() && !item.isInfoOnly()) {
                    OutlinedButton(
                        onClick = { actions.revokeHelp(item) },
                        modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget),
                    ) { Text(stringResource(R.string.hub_cap_how_revoke)) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun accessText(item: CapabilityItem): String {
    val parts = item.capability.runtimePermissions.map(::permissionLabel) +
        listOfNotNull(item.capability.specialAccess?.let { stringResource(specialAccessRes(it)) })
    return if (parts.isEmpty()) {
        stringResource(R.string.hub_cap_access_none)
    } else {
        stringResource(R.string.hub_cap_access, parts.joinToString(", "))
    }
}

@Composable
private fun RevokeDialog(item: CapabilityItem, actions: PermissionCenterActions) {
    val capability = item.capability
    val text = when {
        capability.specialAccess != null -> R.string.hub_cap_revoke_special
        capability.runtimePermissions.isNotEmpty() -> R.string.hub_cap_revoke_runtime
        else -> R.string.hub_cap_revoke_none
    }
    val canOpen = capability.specialAccess != null || capability.runtimePermissions.isNotEmpty()
    AlertDialog(
        onDismissRequest = { actions.revokeHelp(null) },
        title = { Text(capability.name, modifier = Modifier.semantics { heading() }) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            if (canOpen) {
                TextButton(onClick = {
                    actions.revokeHelp(null)
                    actions.openSettings(item)
                }) { Text(stringResource(R.string.hub_cap_open_settings)) }
            }
        },
        dismissButton = { TextButton(onClick = { actions.revokeHelp(null) }) { Text(stringResource(R.string.hub_close)) } },
    )
}

@Composable
internal fun ActionErrorDialog(error: AppError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { ErrorState(error = error) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_ok)) } },
    )
}
