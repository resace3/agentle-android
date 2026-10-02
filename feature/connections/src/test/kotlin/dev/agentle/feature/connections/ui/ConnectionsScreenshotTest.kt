package dev.agentle.feature.connections.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.feature.connections.aisharing.AiSharingAction
import dev.agentle.feature.connections.aisharing.AiSharingRoute
import dev.agentle.feature.connections.aisharing.AiSharingViewModel
import dev.agentle.feature.connections.chatgpt.ChatGptAction
import dev.agentle.feature.connections.chatgpt.ChatGptRoute
import dev.agentle.feature.connections.chatgpt.ChatGptViewModel
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.WearableConnectionState
import dev.agentle.feature.connections.testing.ConnectionsFixtures
import dev.agentle.feature.connections.testing.FakeAiSharingPort
import dev.agentle.feature.connections.testing.FakeChatGptPort
import dev.agentle.feature.connections.testing.FakeWearablePort
import dev.agentle.feature.connections.wearable.WearableRoute
import dev.agentle.feature.connections.wearable.WearableViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens for every required state, light and dark, at font scale 1.0 and 2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h1400dp-xhdpi")
class ConnectionsScreenshotTest {
    private fun capture(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            for (scale in listOf(1f, 2f)) {
                val variant = (if (dark) "dark" else "light") + "_font" + (if (scale == 1f) "100" else "200")
                captureRoboImage("src/screenshots/Connections.${name}_$variant.png") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { content() }
                    }
                }
            }
        }
    }

    private fun wearable(name: String, state: WearableConnectionState) = capture("wearable_$name") {
        WearableRoute(onBack = {}, viewModel = remember { WearableViewModel(FakeWearablePort(state), ConnectionsFixtures.zonePort) })
    }

    @Test fun wearableNotConnected() = wearable("not_connected", ConnectionsFixtures.wearableNotConnected())

    @Test fun wearableConnected() = wearable("connected", ConnectionsFixtures.wearableConnected())

    @Test fun wearablePartial() = wearable(
        "partial_scopes",
        ConnectionsFixtures.wearableConnected(denied = ConnectionsFixtures.PARTIAL_DENIED),
    )

    @Test fun wearableNeedsReauth() = wearable("needs_reauth", ConnectionsFixtures.wearableNeedsReauth())

    @Test fun wearableNotAvailable() = wearable("not_available", ConnectionsFixtures.wearableNotAvailable())

    private fun chatGpt(name: String, port: FakeChatGptPort, action: ChatGptAction? = null) = capture("chatgpt_$name") {
        val viewModel = remember { ChatGptViewModel(port, ConnectionsFixtures.zonePort).also { vm -> action?.let(vm::onAction) } }
        ChatGptRoute(onBack = {}, onOpenAiDataSharing = {}, viewModel = viewModel)
    }

    @Test fun chatGptDisconnected() = chatGpt("disconnected", FakeChatGptPort())

    @Test fun chatGptWaiting() = chatGpt("waiting_for_browser", FakeChatGptPort().apply { holdSignIn = true }, ChatGptAction.Connect)

    @Test fun chatGptConnected() = chatGpt(
        "connected",
        FakeChatGptPort(ConnectionsFixtures.chatGptConnected(lastRequest = ConnectionsFixtures.request())),
    )

    @Test fun chatGptError() = chatGpt(
        "error",
        FakeChatGptPort().apply { connectResult = ChatGptConnectResult.NotCompleted },
        ChatGptAction.Connect,
    )

    private fun aiSharing(name: String, action: AiSharingAction? = null) = capture("ai_sharing_$name") {
        val viewModel = remember { aiSharingViewModel(action) }
        AiSharingRoute(onBack = {}, onOpenChatGpt = {}, viewModel = viewModel)
    }

    private fun aiSharingViewModel(action: AiSharingAction?): AiSharingViewModel {
        val port = FakeAiSharingPort(
            ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.SLEEP, AiSharingCategory.SCREEN_TIME_TOTALS)),
            history = listOf(ConnectionsFixtures.request()),
        )
        return AiSharingViewModel(port, ConnectionsFixtures.zonePort).also { vm -> action?.let(vm::onAction) }
    }

    @Test fun aiSharingMixed() = aiSharing("mixed_toggles")

    @Test fun aiSharingPreview() = aiSharing("request_preview", AiSharingAction.BuildPreview)
}
