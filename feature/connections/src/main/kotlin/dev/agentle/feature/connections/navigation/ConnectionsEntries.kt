package dev.agentle.feature.connections.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.connections.aisharing.AiSharingRoute
import dev.agentle.feature.connections.chatgpt.ChatGptRoute
import dev.agentle.feature.connections.wearable.WearableRoute

/**
 * Registers the connection screens: [AppRoute.Wearable] (Google Health API), [AppRoute.ChatGpt] (Sign in with
 * ChatGPT) and [AppRoute.AiDataSharing]. Each entry gets its ViewModel from Hilt, scoped to the entry when `:app`'s
 * `NavDisplay` adds the ViewModel store decorator.
 */
public fun EntryProviderScope<NavKey>.connectionsEntries(navigator: AppNavigator) {
    entry<AppRoute.Wearable> {
        WearableRoute(onBack = navigator::back, viewModel = hiltViewModel())
    }
    entry<AppRoute.ChatGpt> {
        ChatGptRoute(
            onBack = navigator::back,
            onOpenAiDataSharing = { navigator.navigate(AppRoute.AiDataSharing) },
            viewModel = hiltViewModel(),
        )
    }
    entry<AppRoute.AiDataSharing> {
        AiSharingRoute(
            onBack = navigator::back,
            onOpenChatGpt = { navigator.navigate(AppRoute.ChatGpt) },
            viewModel = hiltViewModel(),
        )
    }
}
