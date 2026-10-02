package dev.agentle.feature.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.core.common.AppError
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.feature.hub.dashboard.DashboardActions
import dev.agentle.feature.hub.dashboard.DashboardScreen
import dev.agentle.feature.hub.dashboard.DashboardUiState
import dev.agentle.feature.hub.permissions.CapabilityGroup
import dev.agentle.feature.hub.permissions.PermissionCenterActions
import dev.agentle.feature.hub.permissions.PermissionCenterScreen
import dev.agentle.feature.hub.permissions.PermissionCenterUiState
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.sources.DataSourcesActions
import dev.agentle.feature.hub.sources.DataSourcesScreen
import dev.agentle.feature.hub.sources.DataSourcesUiState
import dev.agentle.feature.hub.timeline.TimelineActions
import dev.agentle.feature.hub.timeline.TimelineScreen
import dev.agentle.feature.hub.timeline.toRows
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/** Hub screens with fixed fake data and a fixed clock (2 Oct 2026, Europe/Berlin): light/dark, font 1.0/2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = RobolectricDeviceQualifiers.MediumPhone)
class HubScreenshotTest {
    private fun capture(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        captureRoboImage { AgentleTestFrame(darkTheme = dark, fontScale = fontScale, content = content) }
    }

    @Composable
    private fun Dashboard(data: DashboardData) = DashboardScreen(DashboardUiState(Load.Ready(data)), NOW, BERLIN, DashboardActions())

    @Test fun dashboardEmpty_light() = capture(false, 1f) { Dashboard(DashboardData()) }

    @Test fun dashboardEmpty_dark_fontScale2() = capture(true, 2f) { Dashboard(DashboardData()) }

    @Test fun dashboardFull_light() = capture(false, 1f) { Dashboard(fullDashboard()) }

    @Test fun dashboardFull_dark() = capture(true, 1f) { Dashboard(fullDashboard()) }

    @Test fun dashboardFull_light_fontScale2() = capture(false, 2f) { Dashboard(fullDashboard()) }

    @Test fun dashboardError_light() = capture(false, 1f) {
        DashboardScreen(DashboardUiState(Load.Failed(AppError.DatabaseError())), NOW, BERLIN, DashboardActions())
    }

    @Composable
    private fun Sources() = DataSourcesScreen(DataSourcesUiState(Load.Ready(sourceItems())), NOW, BERLIN, DataSourcesActions())

    @Test fun dataSources_light() = capture(false, 1f) { Sources() }

    @Test fun dataSources_dark() = capture(true, 1f) { Sources() }

    @Test fun dataSources_dark_fontScale2() = capture(true, 2f) { Sources() }

    @Composable
    private fun Permissions() {
        val groups = mixedCapabilities().groupBy { it.capability.category }.toSortedMap().map { CapabilityGroup(it.key, it.value) }
        PermissionCenterScreen(PermissionCenterUiState(Load.Ready(groups)), NOW, BERLIN, PermissionCenterActions())
    }

    @Test fun permissionCenter_light() = capture(false, 1f) { Permissions() }

    @Test fun permissionCenter_dark() = capture(true, 1f) { Permissions() }

    @Test fun permissionCenter_light_fontScale2() = capture(false, 2f) { Permissions() }

    @Test fun permissionCenter_dark_fontScale2() = capture(true, 2f) { Permissions() }

    @Composable
    private fun Timeline(events: List<PersonalEvent>, coverage: Map<LocalDate, DayCoverage> = emptyMap()) {
        val rows = remember(events) { flowOf(PagingData.from(events).toRows(BERLIN)) }.collectAsLazyPagingItems()
        TimelineScreen(rows, TimelineFilter(), listOf("android.screen"), coverage, BERLIN, null, TimelineActions())
    }

    private val normalDay = listOf(
        event("n1", "2026-10-01T18:40:00Z"),
        event("n2", "2026-10-01T12:05:00Z"),
        event("n3", "2026-10-01T06:15:00Z"),
    )

    // 25 Oct 2026 in Berlin has 25 hours; 01:30 UTC and 00:30 UTC are both 02:30 local, shown with their offsets.
    private val longDay = listOf(
        event("l1", "2026-10-25T20:00:00Z"),
        event("l2", "2026-10-25T01:30:00Z"),
        event("l3", "2026-10-25T00:30:00Z"),
    )

    private val gapDays = listOf(
        event("g1", "2026-09-30T09:00:00Z"),
        event("g2", "2026-09-26T09:00:00Z"),
    )

    @Test fun timelineNormal_light() = capture(false, 1f) { Timeline(normalDay) }

    @Test fun timelineNormal_dark_fontScale2() = capture(true, 2f) { Timeline(normalDay) }

    @Test fun timeline25HourBerlin_light() = capture(false, 1f) { Timeline(longDay) }

    @Test fun timeline25HourBerlin_dark() = capture(true, 1f) { Timeline(longDay) }

    @Test fun timelineGapDay_light() = capture(false, 1f) {
        Timeline(gapDays, mapOf(LocalDate(2026, 9, 30) to DayCoverage.PARTIAL))
    }

    @Test fun timelineGapDay_light_fontScale2() = capture(false, 2f) {
        Timeline(gapDays, mapOf(LocalDate(2026, 9, 30) to DayCoverage.PARTIAL))
    }

    private fun event(id: String, at: String) = PersonalEvent(
        id = EventId(id),
        type = EventType.SCREEN_ON,
        source = DataSourceId("android.screen"),
        startTime = Instant.parse(at),
        zoneId = "Europe/Berlin",
        payload = NoPayload,
        dedupKey = id,
        metadata = EventMetadata(ingestedAt = Instant.parse(at)),
    )
}
