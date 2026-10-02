package dev.agentle.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.agentle.core.common.AppError
import dev.agentle.core.ui.R
import dev.agentle.core.ui.icon.AgentleIcons
import dev.agentle.core.ui.theme.AgentleSpacing

/** A labelled button for the empty and error states. */
public data class StateAction(val label: String, val onClick: () -> Unit)

/**
 * Nothing to show yet, with the reason in [message] and the one [action] that changes it (for example "Open Permission
 * Center"). Use it for "no data yet", which is never shown as zero.
 */
@Composable
public fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = AgentleIcons.info,
    action: StateAction? = null,
    secondaryAction: StateAction? = null,
) {
    StateLayout(modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AgentleSpacing.iconLarge),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        StateButtons(primary = action, secondary = secondaryAction)
    }
}

/**
 * An error the user can understand: the message for [errorCode] (an `AppError.code`), the code itself in small print
 * for support, and a retry button when [onRetry] is given. The developer detail of an error is never shown.
 */
@Composable
public fun ErrorState(
    errorCode: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.ui_error_title),
    onRetry: (() -> Unit)? = null,
    secondaryAction: StateAction? = null,
) {
    StateLayout(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Icon(
            imageVector = AgentleIcons.error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(AgentleSpacing.iconLarge),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(errorMessageRes(errorCode)),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.ui_error_code, errorCode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        StateButtons(
            primary = onRetry?.let { StateAction(stringResource(R.string.ui_action_retry), it) },
            secondary = secondaryAction,
        )
    }
}

/** [ErrorState] for an [AppError]; retry is offered when [onRetry] is given. */
@Composable
public fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    secondaryAction: StateAction? = null,
) {
    ErrorState(errorCode = error.code, modifier = modifier, onRetry = onRetry, secondaryAction = secondaryAction)
}

/** Content is loading; announced to screen readers as "Loading". */
@Composable
public fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    val label = message ?: stringResource(R.string.ui_loading)
    StateLayout(modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StateLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgentleSpacing.xl, vertical = AgentleSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AgentleSpacing.m),
    ) {
        content()
    }
}

@Composable
private fun StateButtons(primary: StateAction?, secondary: StateAction?) {
    if (primary != null) {
        Button(onClick = primary.onClick, modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)) {
            Text(primary.label)
        }
    }
    if (secondary != null) {
        OutlinedButton(onClick = secondary.onClick, modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)) {
            Text(secondary.label)
        }
    }
}
