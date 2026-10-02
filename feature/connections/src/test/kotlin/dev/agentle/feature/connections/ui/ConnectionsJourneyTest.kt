package dev.agentle.feature.connections.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.aisharing.AI_PREVIEW_TAG
import dev.agentle.feature.connections.aisharing.AiSharingRoute
import dev.agentle.feature.connections.aisharing.AiSharingViewModel
import dev.agentle.feature.connections.aisharing.categoryTag
import dev.agentle.feature.connections.chatgpt.ChatGptRoute
import dev.agentle.feature.connections.chatgpt.ChatGptViewModel
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.testing.ConnectionsFixtures
import dev.agentle.feature.connections.testing.FakeAiSharingPort
import dev.agentle.feature.connections.testing.FakeChatGptPort
import dev.agentle.feature.connections.testing.FakeWearablePort
import dev.agentle.feature.connections.wearable.WearableRoute
import dev.agentle.feature.connections.wearable.WearableViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journeys 3 and 8 (wearable), 4 and 9 (ChatGPT) and the AI Data Sharing flow, through the real ViewModels and fake
 * ports. Accessibility is asserted through semantics (roles, headings, descriptions, enabled state): Compose's
 * accessibility checks do not run on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ConnectionsJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val registry = FakeRegistry()

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun clickText(@StringRes id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun setWearable(port: FakeWearablePort) {
        val viewModel = WearableViewModel(port, ConnectionsFixtures.zonePort)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                WearableRoute(onBack = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    private fun consentIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun wearable_connectThroughConsent_syncs_thenDisconnectDeletesAndStopsSync() {
        val port = FakeWearablePort()
        port.authorizeResult = WearableAuthorizationResult.NeedsResolution(consentIntent())
        setWearable(port)
        compose.onNodeWithText(text(R.string.connections_wearable_title)).assert(isHeading())
        compose.onNodeWithContentDescription(text(R.string.connections_back)).assertIsEnabled()

        clickText(R.string.connections_wearable_connect)
        assertThat(registry.launches).isEqualTo(1)
        registry.answer(Activity.RESULT_OK)
        compose.waitForIdle()
        assertThat(port.consentResultCodes).containsExactly(Activity.RESULT_OK)
        compose.onNodeWithText(text(R.string.connections_wearable_connected_title)).assertIsDisplayed()

        clickText(R.string.connections_wearable_sync_now)
        assertThat(port.syncRuns).isEqualTo(1)

        clickText(R.string.connections_wearable_disconnect)
        compose.onNodeWithText(text(R.string.connections_wearable_disconnect_delete))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        clickDialogConfirm(R.string.connections_wearable_disconnect_confirm)
        assertThat(port.disconnectCalls).containsExactly(true)
        compose.onNodeWithText(text(R.string.connections_wearable_notice_disconnected_deleted)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connections_wearable_sync_now)).assertDoesNotExist()
        assertThat(port.syncRuns).isEqualTo(1)
    }

    @Test
    fun wearable_cancelledConsent_staysNotConnected() {
        val port = FakeWearablePort()
        port.authorizeResult = WearableAuthorizationResult.NeedsResolution(consentIntent())
        setWearable(port)
        clickText(R.string.connections_wearable_connect)
        registry.answer(Activity.RESULT_CANCELED)
        compose.waitForIdle()
        assertThat(port.consentResultCodes).containsExactly(Activity.RESULT_CANCELED)
        compose.onNodeWithTag(ConnectionsTags.NOTICE).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connections_wearable_connect)).performScrollTo().assertIsEnabled()
    }

    private fun clickDialogConfirm(@StringRes id: Int) {
        // The screen's button and the dialog's confirm share a label; the dialog's node comes last.
        val nodes = compose.onAllNodesWithText(text(id))
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    @Test
    fun chatGpt_waitsForBrowser_connects_thenDisconnectForgetsAtOnce() {
        val port = FakeChatGptPort()
        port.holdSignIn = true
        port.connectedState = ConnectionsFixtures.chatGptConnected()
        var openedSharing = 0
        val viewModel = ChatGptViewModel(port, ConnectionsFixtures.zonePort)
        compose.setContent { ChatGptRoute(onBack = {}, onOpenAiDataSharing = { openedSharing++ }, viewModel = viewModel) }
        compose.waitForIdle()

        clickText(R.string.connections_chatgpt_connect)
        compose.onNodeWithText(text(R.string.connections_chatgpt_waiting_title)).assertIsDisplayed()
        port.answer(ChatGptConnectResult.Connected(ConnectionsFixtures.CHATGPT_ACCOUNT))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connections_chatgpt_notice_connected)).assertIsDisplayed()
        compose.onNodeWithText(ConnectionsFixtures.CHATGPT_ACCOUNT, substring = true).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.connections_chatgpt_ai_sharing))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertThat(openedSharing).isEqualTo(1)

        clickText(R.string.connections_chatgpt_disconnect)
        compose.onNodeWithText(text(R.string.connections_chatgpt_disconnect_forget))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
            .assertIsOn()
        clickDialogConfirm(R.string.connections_chatgpt_disconnect_confirm)
        assertThat(port.disconnectCalls).containsExactly(true)
        compose.onNodeWithText(text(R.string.connections_chatgpt_connect)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chatGpt_cancelSignIn_returnsToDisconnected() {
        val port = FakeChatGptPort()
        port.holdSignIn = true
        val viewModel = ChatGptViewModel(port, ConnectionsFixtures.zonePort)
        compose.setContent { ChatGptRoute(onBack = {}, onOpenAiDataSharing = {}, viewModel = viewModel) }
        compose.waitForIdle()
        clickText(R.string.connections_chatgpt_connect)
        clickText(R.string.connections_chatgpt_cancel_sign_in)
        compose.waitForIdle()
        assertThat(port.cancelCalls).isEqualTo(1)
        compose.onNodeWithTag(ConnectionsTags.NOTICE).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connections_chatgpt_connect)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aiSharing_turnOnNeedsConfirm_previewShowsContent_noAccountOpensChatGpt() {
        val port = FakeAiSharingPort()
        val viewModel = AiSharingViewModel(port, ConnectionsFixtures.zonePort)
        compose.setContent { AiSharingRoute(onBack = {}, onOpenChatGpt = {}, viewModel = viewModel) }
        compose.waitForIdle()

        val sleep = compose.onNodeWithTag(categoryTag(AiSharingCategory.SLEEP))
        sleep.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        sleep.performClick()
        compose.waitForIdle()
        assertThat(port.setCalls).isEmpty()
        clickDialogConfirm(R.string.connections_ai_confirm_allow)
        assertThat(port.setCalls).containsExactly(AiSharingCategory.SLEEP to true)
        compose.onNodeWithTag(categoryTag(AiSharingCategory.SLEEP)).performScrollTo().assertIsOn()

        clickText(R.string.connections_ai_preview_show)
        assertThat(port.previewCalls).containsExactly(AiPurpose.entries.first())
        compose.onNodeWithTag(AI_PREVIEW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sleep.minutes", substring = true).assertExists()

        compose.onNodeWithTag(categoryTag(AiSharingCategory.SLEEP)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(port.setCalls.last()).isEqualTo(AiSharingCategory.SLEEP to false)
        compose.onNodeWithTag(AI_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun aiSharing_withoutAccount_offersChatGpt() {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(accountConnected = false))
        var opened = 0
        val viewModel = AiSharingViewModel(port, ConnectionsFixtures.zonePort)
        compose.setContent { AiSharingRoute(onBack = {}, onOpenChatGpt = { opened++ }, viewModel = viewModel) }
        compose.waitForIdle()
        clickText(R.string.connections_ai_open_chatgpt)
        assertThat(opened).isEqualTo(1)
    }

    /** Records launches and answers them, as the consent screen would. */
    private class FakeRegistry {
        var launches = 0
        private var lastCode = -1
        lateinit var registryRef: ActivityResultRegistry

        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    launches++
                    lastCode = requestCode
                }
            }.also { registryRef = it }
        }

        fun answer(resultCode: Int) {
            registryRef.dispatchResult(lastCode, resultCode, null)
        }
    }
}
