package dev.agentle.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import dev.agentle.app.shell.DashboardSpec
import dev.agentle.app.shell.DashboardStore
import dev.agentle.app.shell.SensorsRoute
import dev.agentle.app.shell.SensorsScreen
import dev.agentle.app.shell.UserDashboardRoute
import dev.agentle.app.shell.UserDashboardScreen
import dev.agentle.core.ui.icon.AgentleIcons
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

/** A screen in the sidebar's More section: its label and the screen it opens. */
private data class Tab(val label: String, val route: NavKey)

private val moreTabs = listOf(
    Tab("Today", AppRoute.Dashboard),
    Tab("Timeline", AppRoute.Timeline()),
    Tab("Insights", AppRoute.Insights),
    Tab("JITAIs", AppRoute.Jitais()),
    Tab("Data sources", AppRoute.DataSources),
    Tab("Phone sensors", SensorsRoute),
    Tab("ChatGPT connection", AppRoute.ChatGpt),
)

private val WIDE_LAYOUT = 600.dp
private val SIDEBAR_WIDTH = 300.dp
private const val MODAL_SIDEBAR_SHARE = 0.85f

/**
 * The app frame: the dashboards sidebar on the left and the open screen with its blue top bar. On a phone the sidebar
 * slides in from the menu button; on a screen at least [WIDE_LAYOUT] wide it stays open.
 */
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
    val title = titleOf(current, dashboards)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= WIDE_LAYOUT) {
            PermanentNavigationDrawer(
                drawerContent = { PermanentDrawerSheet(Modifier.width(SIDEBAR_WIDTH)) { Sidebar(current, dashboards, open) } },
            ) {
                ShellContent(backStack, navigator, title, onMenu = null)
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(Modifier.width(minOf(SIDEBAR_WIDTH, maxWidth * MODAL_SIDEBAR_SHARE))) {
                        Sidebar(current, dashboards, open)
                    }
                },
            ) {
                ShellContent(backStack, navigator, title, onMenu = { scope.launch { drawer.open() } })
            }
            BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        }
    }
}

/** The sidebar, laid out like the mockup: Dashboards with +, New Dashboard (the chat), the dashboards, Settings. */
@Composable
private fun Sidebar(current: NavKey?, dashboards: List<DashboardSpec>, open: (NavKey) -> Unit) {
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AgentleIcons.gridView, contentDescription = null)
                Text("Dashboards", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                IconButton(onClick = { open(ChatRoute) }) { Icon(AgentleIcons.add, contentDescription = "New dashboard") }
            }
            SidebarItem("New Dashboard", AgentleIcons.chatBubble, selected = current == ChatRoute) { open(ChatRoute) }
            dashboards.forEach { spec ->
                val route = UserDashboardRoute(spec.id)
                SidebarItem(spec.title, AgentleIcons.barChart, selected = current == route) { open(route) }
            }
            if (dashboards.isEmpty()) {
                Text(
                    "Ask ChatGPT for a dashboard and it appears here.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val showMore = moreOpen || moreTabs.any { it.route == current }
            NavigationDrawerItem(
                label = { Text("More") },
                icon = { Icon(AgentleIcons.expandMore, contentDescription = null, modifier = Modifier.rotate(if (showMore) 180f else 0f)) },
                selected = false,
                onClick = { moreOpen = !showMore },
            )
            if (showMore) {
                moreTabs.forEach { tab ->
                    NavigationDrawerItem(
                        label = { Text(tab.label) },
                        selected = current == tab.route,
                        onClick = { open(tab.route) },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SidebarItem("Settings", AgentleIcons.settings, selected = current == AppRoute.Settings) { open(AppRoute.Settings) }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
    )
}

/** The open screen under the blue top bar; [onMenu] is the sidebar button, null while the sidebar stays open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellContent(backStack: SnapshotStateList<NavKey>, navigator: BackStackNavigator, title: String, onMenu: (() -> Unit)?) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onMenu != null) {
                        IconButton(onClick = onMenu) { Icon(AgentleIcons.menu, contentDescription = "Open sidebar") }
                    }
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
                entry<ChatRoute> {
                    ChatScreen(
                        viewModel = hiltViewModel(),
                        onConnect = { navigator.navigate(AppRoute.ChatGpt) },
                        onOpenDashboard = { id -> navigator.resetToKey(UserDashboardRoute(id)) },
                    )
                }
                entry<SensorsRoute> { SensorsScreen(viewModel = hiltViewModel()) }
                entry<UserDashboardRoute> { key ->
                    UserDashboardScreen(
                        key.id,
                        viewModel = hiltViewModel(),
                        onChange = { navigator.resetToKey(ChatRoute) },
                        onRemoved = { navigator.resetToKey(ChatRoute) },
                    )
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

private fun titleOf(route: NavKey?, dashboards: List<DashboardSpec>): String = when (route) {
    ChatRoute -> "Chat"
    AppRoute.Settings -> "Settings"
    is UserDashboardRoute -> dashboards.firstOrNull { it.id == route.id }?.title ?: "Dashboard"
    else -> moreTabs.firstOrNull { it.route == route }?.label ?: "Agentle"
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
