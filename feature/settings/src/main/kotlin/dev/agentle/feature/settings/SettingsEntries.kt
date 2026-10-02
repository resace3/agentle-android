package dev.agentle.feature.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.background.BackgroundBehaviorRoute
import dev.agentle.feature.settings.debug.DebugPanelRoute
import dev.agentle.feature.settings.deletion.DeleteDataRoute
import dev.agentle.feature.settings.diagnostics.DiagnosticsRoute
import dev.agentle.feature.settings.hub.SettingsHubRoute
import dev.agentle.feature.settings.notifications.NotificationSettingsRoute
import dev.agentle.feature.settings.privacy.PrivacyRoute
import dev.agentle.feature.settings.retention.RetentionRoute

/**
 * Registers every settings route. [AppRoute.DebugPanel] is registered in every build: its screen asks
 * `DebugToolsPort.available` and shows "not available" (with no control) unless the fake debug build bound the tools;
 * the settings hub hides its link in that case.
 */
public fun EntryProviderScope<NavKey>.settingsEntries(navigator: AppNavigator) {
    entry<AppRoute.Settings> { SettingsHubRoute(navigator) }
    entry<AppRoute.DataRetention> { RetentionRoute(navigator) }
    entry<AppRoute.DeleteData> { DeleteDataRoute(navigator) }
    entry<AppRoute.BackgroundBehavior> { BackgroundBehaviorRoute(navigator) }
    entry<AppRoute.NotificationSettings> { NotificationSettingsRoute(navigator) }
    entry<AppRoute.Privacy> { PrivacyRoute(navigator) }
    entry<AppRoute.Diagnostics> { DiagnosticsRoute(navigator) }
    entry<AppRoute.DebugPanel> { DebugPanelRoute(navigator) }
}
