package dev.agentle.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentle.core.ui.R
import dev.agentle.core.ui.icon.AgentleIcons
import dev.agentle.core.ui.theme.AgentleSpacing

/**
 * The screen frame: a top bar with an optional back button and the screen [title] as a heading, a snackbar host and
 * the [content] area. The top bar grows with the title instead of clipping it at large font scales.
 *
 * @param onBack shows a back button when not null (content description "Navigate up").
 * @param content receives the padding of the bars; apply it to the root of the content.
 */
@Composable
public fun AgentleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AgentleTopBar(title = title, onBack = onBack, actions = actions) },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(hostState = snackbarHostState) },
        content = content,
    )
}

/** The top bar of [AgentleScaffold]; usable alone in screens that build their own layout. */
@Composable
public fun AgentleTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = 64.dp)
                .padding(horizontal = AgentleSpacing.xs, vertical = AgentleSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.xs),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = AgentleIcons.arrowBack, contentDescription = stringResource(R.string.ui_action_navigate_up))
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack == null) AgentleSpacing.m else 0.dp)
                    .semantics { heading() },
            )
            actions()
        }
    }
}
