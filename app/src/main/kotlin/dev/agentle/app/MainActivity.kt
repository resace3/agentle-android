package dev.agentle.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.agentle.app.shell.ChatRoute
import dev.agentle.app.shell.ChatScreen
import dev.agentle.app.shell.DashboardStore
import dev.agentle.app.shell.SensorsRoute
import dev.agentle.app.shell.SensorsScreen
import dev.agentle.app.shell.UserDashboardRoute
import dev.agentle.app.shell.UserDashboardScreen
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.theme.AgentleTheme
import dev.agentle.feature.connections.navigation.connectionsEntries
import dev.agentle.feature.hub.hubEntries
import dev.agentle.feature.insights.insightsEntries
import dev.agentle.feature.onboarding.onboardingEntries
import dev.agentle.feature.settings.settingsEntries
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var dashboardStore: DashboardStore

    // The draft starts on the ChatGPT chat: onboarding is not wired to real state yet.
    private val backStack = mutableStateListOf<NavKey>(ChatRoute)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRequestedTab(intent)
        setContent {
            AgentleTheme {
                val navigator = remember(backStack) { BackStackNavigator(backStack) }
                AppShell(backStack, navigator, dashboardStore)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedTab(intent)
    }

    /** `am start ... --es agentle.open sensors` opens the Phone sensors tab (used to check a phone over adb). */
    private fun openRequestedTab(intent: Intent?) {
        val route = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_SENSORS -> SensorsRoute
            else -> return
        }
        backStack.clear()
        backStack.add(route)
    }

    companion object {
        const val EXTRA_OPEN: String = "agentle.open"
        const val OPEN_SENSORS: String = "sensors"
    }
}

/** A sidebar tab: its label and the screen it opens. */
private data class Tab(val label: String, val route: NavKey)

private val mainTabs = listOf(
    Tab("Chat", ChatRoute),
    Tab("Today", AppRoute.Dashboard),
    Tab("Timeline", AppRoute.Timeline()),
    Tab("Insights", AppRoute.Insights),
    Tab("JITAIs", AppRoute.Jitais()),
    Tab("Data sources", AppRoute.DataSources),
    Tab("Phone sensors", SensorsRoute),
    Tab("ChatGPT connection", AppRoute.ChatGpt),
    Tab("Settings", AppRoute.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(backStack: SnapshotStateList<NavKey>, navigator: BackStackNavigator, store: DashboardStore) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val dashboards by store.dashboards.collectAsStateWithLifecycle()
    val current = backStack.lastOrNull()
    val open: (NavKey) -> Unit = { route ->
        navigator.resetToKey(route)
        scope.launch { drawer.close() }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("Agentle", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                    mainTabs.forEach { tab ->
                        NavigationDrawerItem(label = { Text(tab.label) }, selected = current == tab.route, onClick = { open(tab.route) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("My dashboards", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                    if (dashboards.isEmpty()) {
                        Text(
                            "Ask ChatGPT in the chat to make one.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    dashboards.forEach { spec ->
                        val route = UserDashboardRoute(spec.id)
                        NavigationDrawerItem(label = { Text(spec.title) }, selected = current == route, onClick = { open(route) })
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(titleOf(current, dashboards.associate { it.id to it.title })) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = { navigator.back() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<ChatRoute> { ChatScreen(viewModel = hiltViewModel(), onConnect = { navigator.navigate(AppRoute.ChatGpt) }) }
                    entry<SensorsRoute> { SensorsScreen(viewModel = hiltViewModel()) }
                    entry<UserDashboardRoute> { key ->
                        UserDashboardScreen(key.id, viewModel = hiltViewModel(), onRemoved = { navigator.resetToKey(ChatRoute) })
                    }
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

private fun titleOf(route: NavKey?, dashboards: Map<String, String>): String = when (route) {
    is UserDashboardRoute -> dashboards[route.id] ?: "Dashboard"
    else -> mainTabs.firstOrNull { it.route == route }?.label ?: "Agentle"
}

/** [AppNavigator] over the activity's back stack. */
private class BackStackNavigator(private val backStack: SnapshotStateList<NavKey>) : AppNavigator {
    override fun navigate(route: AppRoute) {
        backStack.add(route)
    }

    override fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    override fun resetTo(route: AppRoute) = resetToKey(route)

    fun resetToKey(route: NavKey) {
        backStack.clear()
        backStack.add(route)
    }
}
