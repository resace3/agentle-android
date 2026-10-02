package dev.agentle.feature.settings

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.testing.RecordingNavigator
import org.junit.Test

class SettingsEntriesTest {
    @Test
    fun `every settings route resolves to an entry`() {
        val provider = entryProvider<NavKey> { settingsEntries(RecordingNavigator()) }
        val routes = listOf(
            AppRoute.Settings,
            AppRoute.DataRetention,
            AppRoute.DeleteData,
            AppRoute.BackgroundBehavior,
            AppRoute.NotificationSettings,
            AppRoute.Privacy,
            AppRoute.Diagnostics,
            AppRoute.DebugPanel,
        )
        routes.forEach { route -> assertThat(provider(route).contentKey).isNotNull() }
    }
}
