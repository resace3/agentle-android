package dev.agentle.core.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.core.ui.component.SectionHeader
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.core.ui.theme.AgentleSpacing
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of every status chip and badge (R08 §9): light and dark at font scale 1.0 and 2.0 on a medium phone. Pinned
 * to SDK 37 so every Robolectric matrix verifies the same images; recorded by the CI `record_screenshots` dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = RobolectricDeviceQualifiers.MediumPhone)
class StatusComponentsScreenshotTest {
    @Test
    fun permissionChips_light() = capture(dark = false, fontScale = 1f) { PermissionChips() }

    @Test
    fun permissionChips_dark() = capture(dark = true, fontScale = 1f) { PermissionChips() }

    @Test
    fun permissionChips_light_fontScale2() = capture(dark = false, fontScale = 2f) { PermissionChips() }

    @Test
    fun permissionChips_dark_fontScale2() = capture(dark = true, fontScale = 2f) { PermissionChips() }

    @Test
    fun permissionBadges_light() = capture(dark = false, fontScale = 1f) { PermissionBadges() }

    @Test
    fun permissionBadges_dark() = capture(dark = true, fontScale = 1f) { PermissionBadges() }

    @Test
    fun permissionBadges_light_fontScale2() = capture(dark = false, fontScale = 2f) { PermissionBadges() }

    @Test
    fun permissionBadges_dark_fontScale2() = capture(dark = true, fontScale = 2f) { PermissionBadges() }

    @Test
    fun connectionStates_light() = capture(dark = false, fontScale = 1f) { ConnectionStates() }

    @Test
    fun connectionStates_dark() = capture(dark = true, fontScale = 1f) { ConnectionStates() }

    @Test
    fun connectionStates_light_fontScale2() = capture(dark = false, fontScale = 2f) { ConnectionStates() }

    @Test
    fun connectionStates_dark_fontScale2() = capture(dark = true, fontScale = 2f) { ConnectionStates() }

    @Test
    fun syncStates_light() = capture(dark = false, fontScale = 1f) { SyncStates() }

    @Test
    fun syncStates_dark() = capture(dark = true, fontScale = 1f) { SyncStates() }

    @Test
    fun syncStates_light_fontScale2() = capture(dark = false, fontScale = 2f) { SyncStates() }

    @Test
    fun syncStates_dark_fontScale2() = capture(dark = true, fontScale = 2f) { SyncStates() }

    /** One golden per test method, named after the test (`<package>.<class>.<method>.png` in `src/screenshots`). */
    private fun capture(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        captureRoboImage { AgentleTestFrame(darkTheme = dark, fontScale = fontScale, content = content) }
    }
}

@Composable
private fun PermissionChips() {
    Group("Permission states") { Pills(PermissionState.entries.map { it.toStatusSpec() }, badge = false) }
}

@Composable
private fun PermissionBadges() {
    Group("Permission states (badges)") { Pills(PermissionState.entries.map { it.toStatusSpec() }, badge = true) }
}

@Composable
private fun ConnectionStates() {
    Group("Connection states") {
        Pills(ConnectionStatus.entries.map { it.toStatusSpec() }, badge = false)
        Pills(ConnectionStatus.entries.map { it.toStatusSpec() }, badge = true)
    }
}

@Composable
private fun SyncStates() {
    Group("Sync states") {
        Pills(SyncStatus.entries.map { it.toStatusSpec() }, badge = false)
        Pills(SyncStatus.entries.map { it.toStatusSpec() }, badge = true)
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = AgentleSpacing.l), verticalArrangement = Arrangement.spacedBy(AgentleSpacing.m)) {
        SectionHeader(title)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(specs: List<StatusSpec>, badge: Boolean) {
    FlowRow(
        modifier = Modifier.padding(horizontal = AgentleSpacing.screenGutter),
        horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
        verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
    ) {
        specs.forEach { spec -> if (badge) StatusBadge(spec) else StatusChip(spec) }
    }
}
