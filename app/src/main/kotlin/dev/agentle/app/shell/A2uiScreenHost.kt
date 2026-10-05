package dev.agentle.app.shell

import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.runtime.LocalA2uiReadinessEvaluator
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.asReadinessEvaluator
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.agentle.ai.api.screen.A2uiScreenMessages
import dev.agentle.ai.api.screen.ScreenSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Google's A2UI message processor for the screens one view draws, alive as long as [scope]. It knows Agentle's catalog
 * only, and every message it gets is built by the app from a checked [ScreenSpec] ([A2uiScreenMessages]); nothing from
 * the model reaches it directly. A2UI's own checks run on top of ChatReplySchema's: a part that fails them reports an
 * error ([failures]) and draws a note instead.
 */
class A2uiScreenHost(scope: CoroutineScope) {
    private val processor = A2uiMessageProcessor(catalogs = listOf(AgentleA2uiCatalog.catalog))
    private val parser = A2uiMessageParser()
    private val shown = HashMap<String, ScreenSpec>()
    private val mutableFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The surfaces A2UI created, one per shown screen id. */
    val surfaces: StateFlow<List<A2uiSurfaceModel>> = processor.activeSurfaces

    /** The last A2UI error code per surface id, for a screen that could not be drawn. */
    val failures: StateFlow<Map<String, String>> = mutableFailures.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { event ->
                if (event is A2uiClientErrorMessage) mutableFailures.update { it + (event.surfaceId to event.code) }
            }
        }
        // Google's guidance: process messages off the main thread, for as long as the owner lives.
        scope.launch(Dispatchers.Default) { processor.collectMessages() }
    }

    /** Draws [screen] on the surface [surfaceId], creating it the first time; a changed screen replaces its parts. Main thread. */
    fun show(surfaceId: String, screen: ScreenSpec) {
        val previous = shown[surfaceId]
        if (previous == screen) return
        if (previous == null) processor.processInput(parser, A2uiScreenMessages.createSurface(surfaceId))
        mutableFailures.update { it - surfaceId }
        processor.processInput(parser, A2uiScreenMessages.updateComponents(surfaceId, screen))
        shown[surfaceId] = screen
    }
}

/**
 * One A2UI surface drawn with Agentle's catalog, following Google's surface pattern: the catalog's readiness check is
 * provided, then the root part is resolved and handed to A2UI's component router.
 */
@Composable
fun A2uiScreen(surface: A2uiSurfaceModel, modifier: Modifier = Modifier) {
    val readiness = remember { AgentleA2uiCatalog.catalog.asReadinessEvaluator() }
    CompositionLocalProvider(LocalA2uiReadinessEvaluator provides readiness) {
        when (val root = observeA2uiComponentState(surface = surface)) {
            is A2uiComponentState.Success -> A2uiComponent(component = root.component, modifier = modifier)
            is A2uiComponentState.Error -> Note("This screen couldn't be drawn (${root.exception.code}).", modifier)
            A2uiComponentState.Loading -> Note("Loading…", modifier)
        }
    }
}
