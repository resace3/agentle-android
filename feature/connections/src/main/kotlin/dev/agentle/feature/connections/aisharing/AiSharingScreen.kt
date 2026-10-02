package dev.agentle.feature.connections.aisharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.ai.api.AiPurpose
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.AiCategoryRestriction
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.ui.AiRequestSummary
import dev.agentle.feature.connections.ui.BusyRow
import dev.agentle.feature.connections.ui.ChoiceRow
import dev.agentle.feature.connections.ui.ConnectionsScaffold
import dev.agentle.feature.connections.ui.InstantFormatter
import dev.agentle.feature.connections.ui.LoadFailedContent
import dev.agentle.feature.connections.ui.LoadingContent
import dev.agentle.feature.connections.ui.NoticeCard
import dev.agentle.feature.connections.ui.PrimaryAction
import dev.agentle.feature.connections.ui.SectionHeader
import dev.agentle.feature.connections.ui.StatusCard
import dev.agentle.feature.connections.ui.StatusRow
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.categoryBody
import dev.agentle.feature.connections.ui.categoryTitle
import dev.agentle.feature.connections.ui.errorText
import dev.agentle.feature.connections.ui.purposeLabel
import dev.agentle.feature.connections.ui.rememberInstantFormatter

/** Test tag of a category's row: `ai_category_<CATEGORY>`. */
internal fun categoryTag(category: AiSharingCategory): String = "ai_category_${category.name}"

@Composable
internal fun AiSharingRoute(onBack: () -> Unit, onOpenChatGpt: () -> Unit, viewModel: AiSharingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AiSharingScreen(state = state, onAction = viewModel::onAction, onBack = onBack, onOpenChatGpt = onOpenChatGpt)
}

/** The stateless AI Data Sharing screen. */
@Composable
internal fun AiSharingScreen(state: AiSharingUiState, onAction: (AiSharingAction) -> Unit, onBack: () -> Unit, onOpenChatGpt: () -> Unit) {
    val formatter = rememberInstantFormatter(state.zone)
    ConnectionsScaffold(title = stringResource(R.string.connections_ai_title), onBack = onBack) {
        state.notice?.let { notice ->
            val (text, tone) = aiSharingNoticeText(notice)
            NoticeCard(text = text, tone = tone, onDismiss = { onAction(AiSharingAction.DismissNotice) })
        }
        when (state.phase) {
            AiSharingPhase.LOADING -> LoadingContent()

            AiSharingPhase.LOAD_FAILED -> LoadFailedContent(onRetry = { onAction(AiSharingAction.Retry) })

            AiSharingPhase.NOT_AVAILABLE -> StatusCard(
                icon = R.drawable.connections_ic_block,
                tone = Tone.NEUTRAL,
                title = stringResource(R.string.connections_ai_not_available_title),
                body = stringResource(R.string.connections_ai_not_available_body),
            )

            AiSharingPhase.READY -> {
                RecipientCard(state, formatter)
                ConsentProblems(state, onAction, onOpenChatGpt)
                CategoriesSection(state, formatter, onAction)
                PreviewSection(state, formatter, onAction)
                HistorySection(state, formatter)
            }
        }
    }
    state.confirm?.let { ConfirmDialog(category = it, disclosure = state.disclosure, onAction = onAction) }
}

@Composable
private fun RecipientCard(state: AiSharingUiState, formatter: InstantFormatter) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(text = stringResource(R.string.connections_ai_recipient_header))
            Text(text = disclosureText(state.disclosure), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.connections_ai_consent_version, state.consentVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = state.lastChangedAt?.let { stringResource(R.string.connections_ai_last_changed, formatter.format(it)) }
                    ?: stringResource(R.string.connections_ai_never_changed),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun disclosureText(disclosure: String): String = disclosure.ifBlank { stringResource(R.string.connections_ai_disclosure_fallback) }

@Composable
private fun ConsentProblems(state: AiSharingUiState, onAction: (AiSharingAction) -> Unit, onOpenChatGpt: () -> Unit) {
    if (state.readFailed) {
        StatusCard(
            icon = R.drawable.connections_ic_error,
            tone = Tone.ERROR,
            title = stringResource(R.string.connections_ai_read_failed),
        ) {
            PrimaryAction(text = stringResource(R.string.connections_retry), onClick = { onAction(AiSharingAction.Retry) })
        }
    }
    if (!state.accountConnected) {
        StatusCard(
            icon = R.drawable.connections_ic_info,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_ai_no_account_title),
            body = stringResource(R.string.connections_ai_no_account_body),
        ) {
            PrimaryAction(text = stringResource(R.string.connections_ai_open_chatgpt), onClick = onOpenChatGpt)
        }
    }
}

@Composable
private fun CategoriesSection(state: AiSharingUiState, formatter: InstantFormatter, onAction: (AiSharingAction) -> Unit) {
    SectionHeader(text = stringResource(R.string.connections_ai_categories_header))
    Text(
        text = pluralStringResource(
            R.plurals.connections_ai_categories_summary,
            state.categories.size,
            state.allowedCount,
            state.categories.size,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Column {
        state.categories.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            CategoryRow(row = row, accountConnected = state.accountConnected, formatter = formatter, onAction = onAction)
        }
    }
}

@Composable
private fun CategoryRow(row: AiCategoryRow, accountConnected: Boolean, formatter: InstantFormatter, onAction: (AiSharingAction) -> Unit) {
    // Turning off always works; turning on needs a connected account and a category this version can send.
    val enabled = if (row.allowed) !row.pending else row.canTurnOn(accountConnected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = row.allowed,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onAction(AiSharingAction.Toggle(row.category, it)) },
            )
            .padding(vertical = 12.dp)
            .testTag(categoryTag(row.category)),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(categoryTitle(row.category)), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(categoryBody(row.category)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            restrictionNote(row.restriction)?.let { Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall) }
            if (row.outdatedGrant) {
                StatusRow(
                    icon = R.drawable.connections_ic_warning,
                    text = stringResource(R.string.connections_ai_category_outdated),
                    tone = Tone.WARNING,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val grantedAt = row.grantedAt
            when {
                row.pending -> Text(
                    text = stringResource(R.string.connections_ai_category_saving),
                    style = MaterialTheme.typography.bodySmall,
                )

                row.allowed && grantedAt != null -> Text(
                    text = stringResource(R.string.connections_ai_category_on_since, formatter.format(grantedAt)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Switch(checked = row.allowed, onCheckedChange = null, enabled = enabled)
    }
}

private fun restrictionNote(restriction: AiCategoryRestriction): Int? = when (restriction) {
    AiCategoryRestriction.NONE -> null
    AiCategoryRestriction.NEVER_SENT -> R.string.connections_ai_restriction_never_sent
    AiCategoryRestriction.WEARABLE_API_EXCLUDED -> R.string.connections_ai_restriction_wearable
}

@Composable
private fun PreviewSection(state: AiSharingUiState, formatter: InstantFormatter, onAction: (AiSharingAction) -> Unit) {
    SectionHeader(text = stringResource(R.string.connections_ai_preview_header))
    Text(text = stringResource(R.string.connections_ai_preview_body), style = MaterialTheme.typography.bodyMedium)
    Column(modifier = Modifier.selectableGroup()) {
        AiPurpose.entries.forEach { purpose ->
            ChoiceRow(
                selected = purpose == state.purpose,
                text = stringResource(purposeLabel(purpose)),
                onClick = { onAction(AiSharingAction.SelectPurpose(purpose)) },
            )
        }
    }
    PrimaryAction(
        text = stringResource(R.string.connections_ai_preview_show),
        onClick = { onAction(AiSharingAction.BuildPreview) },
        enabled = state.preview !is AiPreviewUi.Building,
    )
    when (val preview = state.preview) {
        AiPreviewUi.Hidden -> Unit

        is AiPreviewUi.Building -> BusyRow(text = stringResource(R.string.connections_ai_preview_building))

        is AiPreviewUi.Shown -> AiPreviewCard(
            preview = preview.preview,
            formatter = formatter,
            onClose = { onAction(AiSharingAction.ClosePreview) },
        )

        is AiPreviewUi.Failed -> StatusCard(
            icon = R.drawable.connections_ic_error,
            tone = Tone.ERROR,
            title = stringResource(R.string.connections_ai_preview_failed, errorText(preview.error)),
        )
    }
}

@Composable
private fun HistorySection(state: AiSharingUiState, formatter: InstantFormatter) {
    SectionHeader(text = stringResource(R.string.connections_ai_history_header))
    when {
        state.historyUnavailable -> StatusRow(
            icon = R.drawable.connections_ic_warning,
            text = stringResource(R.string.connections_ai_history_unavailable),
            tone = Tone.WARNING,
        )

        state.history.isEmpty() -> Text(
            text = stringResource(R.string.connections_ai_history_none),
            style = MaterialTheme.typography.bodyMedium,
        )

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.history.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider()
                AiRequestSummary(record = record, formatter = formatter)
            }
        }
    }
    Text(
        text = stringResource(R.string.connections_ai_history_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConfirmDialog(category: AiSharingCategory, disclosure: String, onAction: (AiSharingAction) -> Unit) {
    val title = stringResource(categoryTitle(category))
    AlertDialog(
        onDismissRequest = { onAction(AiSharingAction.DismissConfirm) },
        title = { Text(text = stringResource(R.string.connections_ai_confirm_title, title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(categoryBody(category)))
                Text(text = disclosureText(disclosure))
                Text(text = stringResource(R.string.connections_ai_confirm_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AiSharingAction.ConfirmTurnOn) }) {
                Text(text = stringResource(R.string.connections_ai_confirm_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AiSharingAction.DismissConfirm) }) {
                Text(text = stringResource(R.string.connections_cancel))
            }
        },
    )
}
