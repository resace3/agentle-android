package dev.agentle.feature.onboarding

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.common.AppError
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.component.AgentleScaffold
import dev.agentle.core.ui.component.ErrorState
import dev.agentle.core.ui.component.LoadingState
import dev.agentle.core.ui.component.SettingsSwitchRow
import dev.agentle.core.ui.icon.AgentleIcons
import dev.agentle.core.ui.permission.appDetailsSettingsIntent
import dev.agentle.core.ui.permission.openSettingsScreen
import dev.agentle.core.ui.permission.rememberPermissionRequester
import dev.agentle.core.ui.status.StatusChip
import dev.agentle.core.ui.status.toStatusSpec
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep

/** Stateful wrapper: wires the view model, the permission dialog, Android Settings and resume re-checks. */
@Composable
internal fun OnboardingRoute(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requester = rememberPermissionRequester(viewModel::onPermissionResult)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is OnboardingEffect.RequestPermission -> requester.request(listOf(effect.permission))
                OnboardingEffect.OpenAppSettings -> context.openSettingsScreen(context.appDetailsSettingsIntent())
                OnboardingEffect.Finished -> onFinished()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume(
            Primer.entries.associate {
                it.permission to (ContextCompat.checkSelfPermission(context, it.permission) == PackageManager.PERMISSION_GRANTED)
            },
        )
    }
    OnboardingScreen(state = state, actions = viewModel.asActions())
}

internal data class OnboardingActions(
    val next: () -> Unit = {},
    val back: () -> Unit = {},
    val toggleSource: (OnboardingSource) -> Unit = {},
    val request: () -> Unit = {},
    val skip: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val finish: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

private fun OnboardingViewModel.asActions() = OnboardingActions(
    next = ::next,
    back = ::back,
    toggleSource = ::toggleSource,
    request = ::requestCurrent,
    skip = ::skipCurrent,
    openSettings = ::openSettings,
    finish = ::finish,
    dismissError = ::dismissError,
)

@Composable
internal fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions) {
    val onBack = actions.back.takeIf { state.step != OnboardingStep.WELCOME && !state.loading }
    AgentleScaffold(title = stringResource(R.string.onboarding_title), onBack = onBack) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.loading -> LoadingState(modifier)
            state.error != null -> OnboardingError(state.error, actions, modifier)
            else -> Column(
                modifier = modifier.verticalScroll(rememberScrollState()).padding(AgentleSpacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(AgentleSpacing.l),
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> Welcome(actions)
                    OnboardingStep.PROMISES -> Promises(actions)
                    OnboardingStep.SOURCES -> Sources(state, actions)
                    OnboardingStep.PERMISSIONS -> PermissionPrimer(state, actions)
                    OnboardingStep.SUMMARY -> Summary(state, actions)
                }
            }
        }
    }
}

@Composable
private fun OnboardingError(error: AppError, actions: OnboardingActions, modifier: Modifier) {
    ErrorState(error = error, modifier = modifier, onRetry = actions.dismissError)
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = AgentleSpacing.minTouchTarget)) { Text(text) }
}

@Composable
private fun Welcome(actions: OnboardingActions) {
    Icon(AgentleIcons.sparkle, contentDescription = null, modifier = Modifier.size(AgentleSpacing.iconLarge))
    Heading(stringResource(R.string.onboarding_title))
    Body(stringResource(R.string.onboarding_welcome_body))
    PrimaryButton(stringResource(R.string.onboarding_start), actions.next)
}

@Composable
private fun Promises(actions: OnboardingActions) {
    Heading(stringResource(R.string.onboarding_promises_title))
    Promise(AgentleIcons.phone, R.string.onboarding_promise_local_title, R.string.onboarding_promise_local_body)
    Promise(AgentleIcons.sparkle, R.string.onboarding_promise_ai_title, R.string.onboarding_promise_ai_body)
    Promise(AgentleIcons.tune, R.string.onboarding_promise_permissions_title, R.string.onboarding_promise_permissions_body)
    Promise(AgentleIcons.shield, R.string.onboarding_promise_sharing_title, R.string.onboarding_promise_sharing_body)
    PrimaryButton(stringResource(R.string.onboarding_next), actions.next)
}

@Composable
private fun Promise(icon: ImageVector, title: Int, body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.m), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(AgentleSpacing.xs)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Sources(state: OnboardingUiState, actions: OnboardingActions) {
    Heading(stringResource(R.string.onboarding_sources_title))
    Body(stringResource(R.string.onboarding_sources_body))
    OnboardingSource.entries.forEach { source ->
        SettingsSwitchRow(
            title = stringResource(source.title()),
            subtitle = stringResource(source.body()),
            icon = source.icon(),
            checked = source in state.sources,
            onCheckedChange = { actions.toggleSource(source) },
        )
    }
    PrimaryButton(stringResource(R.string.onboarding_next), actions.next)
}

@Composable
private fun PermissionPrimer(state: OnboardingUiState, actions: OnboardingActions) {
    val current = state.currentPrimer ?: return
    Text(
        stringResource(R.string.onboarding_primer_step, state.primerIndex + 1, state.primers.size),
        style = MaterialTheme.typography.labelLarge,
    )
    Icon(current.primer.icon(), contentDescription = null, modifier = Modifier.size(AgentleSpacing.iconLarge))
    Heading(stringResource(current.primer.title()))
    Body(stringResource(current.primer.body()))
    current.state?.let { StatusChip(it.toStatusSpec()) }
    when (current.state) {
        PermissionState.DENIED_PERMANENTLY -> {
            Body(stringResource(R.string.onboarding_primer_denied_permanently))
            PrimaryButton(stringResource(R.string.onboarding_open_settings), actions.openSettings)
        }
        PermissionState.DENIED -> {
            Body(stringResource(R.string.onboarding_primer_denied))
            PrimaryButton(stringResource(R.string.onboarding_primer_retry), actions.request)
        }
        else -> PrimaryButton(stringResource(R.string.onboarding_primer_allow), actions.request)
    }
    OutlinedButton(onClick = actions.skip, modifier = Modifier.fillMaxWidth().heightIn(min = AgentleSpacing.minTouchTarget)) {
        Text(stringResource(R.string.onboarding_primer_not_now))
    }
}

@Composable
private fun Summary(state: OnboardingUiState, actions: OnboardingActions) {
    Heading(stringResource(R.string.onboarding_summary_title))
    Body(stringResource(R.string.onboarding_summary_body))
    state.primers.forEach { primer ->
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.m),
        ) {
            Text(stringResource(primer.primer.title()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val primerState = primer.state
            if (primerState == null) {
                Text(stringResource(R.string.onboarding_summary_not_asked), style = MaterialTheme.typography.labelLarge)
            } else {
                StatusChip(primerState.toStatusSpec())
            }
        }
    }
    Text(stringResource(R.string.onboarding_summary_sources), style = MaterialTheme.typography.titleMedium)
    val sources = OnboardingSource.entries.filter { it in state.sources }
    Body(
        if (sources.isEmpty()) {
            stringResource(R.string.onboarding_summary_no_sources)
        } else {
            sources.map { stringResource(it.title()) }.joinToString(", ")
        },
    )
    Spacer(Modifier.size(AgentleSpacing.s))
    PrimaryButton(stringResource(R.string.onboarding_finish), actions.finish)
    TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)) {
        Text(stringResource(R.string.onboarding_back))
    }
}

private fun OnboardingSource.title() = when (this) {
    OnboardingSource.PHONE -> R.string.onboarding_source_phone
    OnboardingSource.WEARABLE -> R.string.onboarding_source_wearable
    OnboardingSource.CHATGPT -> R.string.onboarding_source_chatgpt
}

private fun OnboardingSource.body() = when (this) {
    OnboardingSource.PHONE -> R.string.onboarding_source_phone_body
    OnboardingSource.WEARABLE -> R.string.onboarding_source_wearable_body
    OnboardingSource.CHATGPT -> R.string.onboarding_source_chatgpt_body
}

private fun OnboardingSource.icon() = when (this) {
    OnboardingSource.PHONE -> AgentleIcons.phone
    OnboardingSource.WEARABLE -> AgentleIcons.watch
    OnboardingSource.CHATGPT -> AgentleIcons.sparkle
}

private fun Primer.title() = when (this) {
    Primer.NOTIFICATIONS -> R.string.onboarding_primer_notifications_title
    Primer.ACTIVITY -> R.string.onboarding_primer_activity_title
}

private fun Primer.body() = when (this) {
    Primer.NOTIFICATIONS -> R.string.onboarding_primer_notifications_body
    Primer.ACTIVITY -> R.string.onboarding_primer_activity_body
}

private fun Primer.icon() = when (this) {
    Primer.NOTIFICATIONS -> AgentleIcons.notifications
    Primer.ACTIVITY -> AgentleIcons.schedule
}
