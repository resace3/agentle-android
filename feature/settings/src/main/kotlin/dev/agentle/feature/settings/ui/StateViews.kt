package dev.agentle.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentle.core.common.AppError
import dev.agentle.feature.settings.R

/** Shown while a screen's data loads. */
@Composable
internal fun LoadingView(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.settings_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

/** Shown when a screen's data cannot be read; offers a retry. */
@Composable
internal fun ErrorView(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(
        kind = StatusKind.ERROR,
        title = stringResource(R.string.settings_load_failed),
        body = stringResource(R.string.settings_error_code, error.code),
        modifier = modifier,
    ) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(horizontal = RowPadding, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_retry))
        }
    }
}

/** Shown when this build cannot provide a screen's data (the port reported `UnsupportedFeature`). */
@Composable
internal fun NotAvailableView(modifier: Modifier = Modifier) {
    NoticeCard(
        kind = StatusKind.INFO,
        title = stringResource(R.string.settings_not_available_title),
        body = stringResource(R.string.settings_not_available_body),
        modifier = modifier,
    )
}

/** Renders the shared states of a [Loadable] and hands a ready value to [content]. */
@Composable
internal fun <T> LoadableContent(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> LoadingView()
        Loadable.NotAvailable -> NotAvailableView()
        is Loadable.Failed -> ErrorView(state.error, onRetry)
        is Loadable.Ready -> content(state.value)
    }
}

/** A confirmation dialog; [destructive] colors the confirm button as an error action (the label says what it does). */
@Composable
internal fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrolls at large font scales instead of pushing the buttons off the dialog.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(confirmLabel) }
            } else {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
