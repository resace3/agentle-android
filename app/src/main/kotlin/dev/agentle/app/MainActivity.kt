package dev.agentle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.theme.AgentleTheme
import dev.agentle.feature.connections.navigation.connectionsEntries
import dev.agentle.feature.hub.hubEntries
import dev.agentle.feature.insights.insightsEntries
import dev.agentle.feature.onboarding.onboardingEntries
import dev.agentle.feature.settings.settingsEntries

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentleTheme {
                // The draft starts on the Dashboard: onboarding is not wired to real state yet.
                val backStack = rememberNavBackStack(AppRoute.Dashboard)
                val navigator = remember(backStack) { BackStackNavigator(backStack) }
                NavDisplay(
                    backStack = backStack,
                    onBack = { navigator.back() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        onboardingEntries(navigator)
                        hubEntries(navigator)
                        insightsEntries(navigator)
                        connectionsEntries(navigator)
                        settingsEntries(navigator)
                    },
                )
            }
        }
    }
}

/** [AppNavigator] over the activity's back stack. */
private class BackStackNavigator(private val backStack: NavBackStack<NavKey>) : AppNavigator {
    override fun navigate(route: AppRoute) {
        backStack.add(route)
    }

    override fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    override fun resetTo(route: AppRoute) {
        backStack.clear()
        backStack.add(route)
    }
}
