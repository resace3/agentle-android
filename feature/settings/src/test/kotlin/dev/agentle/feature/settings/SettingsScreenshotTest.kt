package dev.agentle.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.Severity
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.PermissionState
import dev.agentle.feature.settings.background.BackgroundBehaviorScreen
import dev.agentle.feature.settings.background.BackgroundUiState
import dev.agentle.feature.settings.debug.DebugPanelScreen
import dev.agentle.feature.settings.debug.DebugPanelUiState
import dev.agentle.feature.settings.deletion.DeleteDataScreen
import dev.agentle.feature.settings.deletion.DeleteDataUiState
import dev.agentle.feature.settings.diagnostics.DiagnosticsScreen
import dev.agentle.feature.settings.diagnostics.DiagnosticsUiState
import dev.agentle.feature.settings.hub.SettingsHubScreen
import dev.agentle.feature.settings.hub.SettingsHubUiState
import dev.agentle.feature.settings.notifications.NotificationSettingsScreen
import dev.agentle.feature.settings.notifications.NotificationSettingsUiState
import dev.agentle.feature.settings.port.AiSharingSummary
import dev.agentle.feature.settings.port.AppBuildInfo
import dev.agentle.feature.settings.port.BackgroundBehaviorState
import dev.agentle.feature.settings.port.BatteryOptimization
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.DiagnosticError
import dev.agentle.feature.settings.port.DiagnosticsSnapshot
import dev.agentle.feature.settings.port.PrivacyState
import dev.agentle.feature.settings.port.RecordCounts
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.port.StandbyBucket
import dev.agentle.feature.settings.port.WorkerRunState
import dev.agentle.feature.settings.port.WorkerStatus
import dev.agentle.feature.settings.privacy.PrivacyScreen
import dev.agentle.feature.settings.privacy.PrivacyUiState
import dev.agentle.feature.settings.retention.RetentionScreen
import dev.agentle.feature.settings.retention.RetentionUiState
import dev.agentle.feature.settings.testing.Fixtures
import dev.agentle.feature.settings.testing.now
import dev.agentle.feature.settings.ui.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.hours

/** Key settings screens, light and dark, at font scale 1.0 and 2.0 on a medium phone. Data from fixed fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class SettingsScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(dark: Boolean, large: Boolean, content: @Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        if (large) RuntimeEnvironment.setFontScale(2f)
        rule.setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { content() }
        }
        rule.onRoot().captureRoboImage()
    }

    private val sources = listOf(
        ConnectorMetadata("googlehealth", "Google Health", true, ConnectionStatus.CONNECTED, PermissionState.ALLOWED, now - 2.hours),
        ConnectorMetadata("android", "This phone", false, ConnectionStatus.NOT_CONNECTED, PermissionState.DENIED),
    )

    @Composable private fun Hub() = SettingsHubScreen(
        SettingsHubUiState(RetentionPeriod.ONE_YEAR, CollectionProfile.BALANCED, Fixtures.notifications.pause, 6, debugAvailable = true),
        {},
        {},
    )

    @Composable private fun Retention() = RetentionScreen(
        RetentionUiState(settings = Loadable.Ready(RetentionSettings(RetentionPeriod.ONE_YEAR, now - 6.hours))),
        {
        },
        {},
        SnackbarHostState(),
    )

    @Composable private fun DeleteIdle() = DeleteDataScreen(DeleteDataUiState(overview = Loadable.Ready(Fixtures.overview)), {
    }, {}, SnackbarHostState())

    @Composable private fun DeleteRunning() = DeleteDataScreen(
        DeleteDataUiState(overview = Loadable.Ready(Fixtures.overview), deleteAll = Fixtures.running),
        {},
        {},
        SnackbarHostState(),
    )

    @Composable private fun DeleteRemoteWarning() = DeleteDataScreen(
        DeleteDataUiState(overview = Loadable.Ready(Fixtures.overview), deleteAll = Fixtures.verifiedWithRemoteWarning),
        {
        },
        {},
        SnackbarHostState(),
    )

    @Composable private fun Background() = BackgroundBehaviorScreen(
        BackgroundUiState(
            content = Loadable.Ready(
                BackgroundBehaviorState(
                    profile = CollectionProfile.BALANCED,
                    effectiveProfile = CollectionProfile.LOW,
                    batterySaverOn = true,
                    batterySaverAdaptationActive = true,
                    batteryOptimization = BatteryOptimization.OPTIMIZED,
                    backgroundRestricted = false,
                    standbyBucket = StandbyBucket.WORKING_SET,
                    sources = sources,
                ),
            ),
        ),
        {},
        {},
        SnackbarHostState(),
    )

    @Composable private fun Notifications() = NotificationSettingsScreen(
        NotificationSettingsUiState(content = Loadable.Ready(Fixtures.notifications)),
        {},
        {},
        SnackbarHostState(),
    )

    @Composable private fun Privacy() = PrivacyScreen(
        PrivacyUiState(
            content = Loadable.Ready(
                PrivacyState(
                    databaseEncrypted = true,
                    aiSharing = AiSharingSummary(AiProviderState.Disconnected, setOf(DataCategory.SLEEP), null),
                    detailedNotifications = false,
                    showOnWearables = false,
                    protectAllScreens = false,
                    recentsPreviewHidden = true,
                ),
            ),
        ),
        {},
        {},
        SnackbarHostState(),
    )

    @Composable private fun Diagnostics() = DiagnosticsScreen(
        DiagnosticsUiState(
            content = Loadable.Ready(
                DiagnosticsSnapshot(
                    build = AppBuildInfo("1.0.0", 1, "debug", "fake"),
                    databaseVersion = 1,
                    androidApi = 37,
                    permissions = listOf(CapabilityStatus("post_notifications_jitai", PermissionState.DENIED, evaluatedAt = now)),
                    connectors = sources,
                    lastSuccessfulSync = now - 2.hours,
                    lastWearableSync = now - 2.hours,
                    chatGpt = AiProviderState.Disconnected,
                    workers = listOf(WorkerStatus("sync-googlehealth", WorkerRunState.ENQUEUED, 0, now + 1.hours)),
                    counts = RecordCounts(events = 20_610, insights = 36, jitais = 4),
                    recentErrors = listOf(DiagnosticError(now - 1.hours, Severity.WARN, "sync.googlehealth", "rate_limited", 429)),
                ),
            ),
        ),
        {},
        {},
        SnackbarHostState(),
    )

    @Composable private fun Debug() = DebugPanelScreen(
        DebugPanelUiState.Available(tools = Loadable.Ready(Fixtures.debugTools)),
        {},
        {},
        SnackbarHostState(),
    )

    @Test
    fun hubLightFont1() = shoot(dark = false, large = false) { Hub() }

    @Test
    fun hubLightFont2() = shoot(dark = false, large = true) { Hub() }

    @Test
    fun hubDarkFont1() = shoot(dark = true, large = false) { Hub() }

    @Test
    fun hubDarkFont2() = shoot(dark = true, large = true) { Hub() }

    @Test
    fun retentionLightFont1() = shoot(dark = false, large = false) { Retention() }

    @Test
    fun retentionLightFont2() = shoot(dark = false, large = true) { Retention() }

    @Test
    fun retentionDarkFont1() = shoot(dark = true, large = false) { Retention() }

    @Test
    fun retentionDarkFont2() = shoot(dark = true, large = true) { Retention() }

    @Test
    fun deleteIdleLightFont1() = shoot(dark = false, large = false) { DeleteIdle() }

    @Test
    fun deleteIdleLightFont2() = shoot(dark = false, large = true) { DeleteIdle() }

    @Test
    fun deleteIdleDarkFont1() = shoot(dark = true, large = false) { DeleteIdle() }

    @Test
    fun deleteIdleDarkFont2() = shoot(dark = true, large = true) { DeleteIdle() }

    @Test
    fun deleteRunningLightFont1() = shoot(dark = false, large = false) { DeleteRunning() }

    @Test
    fun deleteRunningLightFont2() = shoot(dark = false, large = true) { DeleteRunning() }

    @Test
    fun deleteRunningDarkFont1() = shoot(dark = true, large = false) { DeleteRunning() }

    @Test
    fun deleteRunningDarkFont2() = shoot(dark = true, large = true) { DeleteRunning() }

    @Test
    fun deleteRemoteWarningLightFont1() = shoot(dark = false, large = false) { DeleteRemoteWarning() }

    @Test
    fun deleteRemoteWarningLightFont2() = shoot(dark = false, large = true) { DeleteRemoteWarning() }

    @Test
    fun deleteRemoteWarningDarkFont1() = shoot(dark = true, large = false) { DeleteRemoteWarning() }

    @Test
    fun deleteRemoteWarningDarkFont2() = shoot(dark = true, large = true) { DeleteRemoteWarning() }

    @Test
    fun backgroundLightFont1() = shoot(dark = false, large = false) { Background() }

    @Test
    fun backgroundLightFont2() = shoot(dark = false, large = true) { Background() }

    @Test
    fun backgroundDarkFont1() = shoot(dark = true, large = false) { Background() }

    @Test
    fun backgroundDarkFont2() = shoot(dark = true, large = true) { Background() }

    @Test
    fun notificationsLightFont1() = shoot(dark = false, large = false) { Notifications() }

    @Test
    fun notificationsLightFont2() = shoot(dark = false, large = true) { Notifications() }

    @Test
    fun notificationsDarkFont1() = shoot(dark = true, large = false) { Notifications() }

    @Test
    fun notificationsDarkFont2() = shoot(dark = true, large = true) { Notifications() }

    @Test
    fun privacyLightFont1() = shoot(dark = false, large = false) { Privacy() }

    @Test
    fun privacyLightFont2() = shoot(dark = false, large = true) { Privacy() }

    @Test
    fun privacyDarkFont1() = shoot(dark = true, large = false) { Privacy() }

    @Test
    fun privacyDarkFont2() = shoot(dark = true, large = true) { Privacy() }

    @Test
    fun diagnosticsLightFont1() = shoot(dark = false, large = false) { Diagnostics() }

    @Test
    fun diagnosticsLightFont2() = shoot(dark = false, large = true) { Diagnostics() }

    @Test
    fun diagnosticsDarkFont1() = shoot(dark = true, large = false) { Diagnostics() }

    @Test
    fun diagnosticsDarkFont2() = shoot(dark = true, large = true) { Diagnostics() }

    @Test
    fun debugLightFont1() = shoot(dark = false, large = false) { Debug() }

    @Test
    fun debugLightFont2() = shoot(dark = false, large = true) { Debug() }

    @Test
    fun debugDarkFont1() = shoot(dark = true, large = false) { Debug() }

    @Test
    fun debugDarkFont2() = shoot(dark = true, large = true) { Debug() }
}
