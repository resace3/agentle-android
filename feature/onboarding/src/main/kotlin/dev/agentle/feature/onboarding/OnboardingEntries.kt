package dev.agentle.feature.onboarding

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute

/** Registers the onboarding route (Journey 1); finishing resets the back stack to the Dashboard. */
public fun EntryProviderScope<NavKey>.onboardingEntries(navigator: AppNavigator) {
    entry<AppRoute.Onboarding> {
        OnboardingRoute(viewModel = hiltViewModel(), onFinished = { navigator.resetTo(AppRoute.Dashboard) })
    }
}
