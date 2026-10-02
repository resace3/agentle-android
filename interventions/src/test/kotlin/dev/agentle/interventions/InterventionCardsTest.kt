package dev.agentle.interventions

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.interventions.card.InMemoryInterventionCardStore
import dev.agentle.interventions.card.InterventionCard
import dev.agentle.interventions.card.InterventionCards
import dev.agentle.interventions.notification.InterventionChannels
import dev.agentle.interventions.notification.InterventionIntents
import dev.agentle.interventions.notification.InterventionNotifier
import dev.agentle.interventions.ports.CardDecisions
import dev.agentle.interventions.ports.CardDisplay
import dev.agentle.interventions.ports.ResponseVerdict
import dev.agentle.interventions.response.InterventionResponses
import dev.agentle.interventions.storage.InMemoryMediaMetadataStore
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPaths
import dev.agentle.interventions.storage.PendingDeliveries
import dev.agentle.jitai.engine.delivery.PendingCard
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class InterventionCardsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Fixtures.clock()
    private val store = InMemoryInterventionCardStore()
    private val engineCard = Fixtures.intervention().copy(title = "Engine title")
    private var listed = listOf(
        PendingCard(engineCard.decisionKey, "jitai-1", engineCard.category, engineCard.channel, engineCard, clock.now(), clock.now() + 2.hours),
    )
    private var displayCalls = 0
    private val decisions = object : CardDecisions {
        override suspend fun pendingCards(): Outcome<List<PendingCard>> = Outcome.success(listed)

        override suspend fun markDisplayed(decisionKey: String): Outcome<CardDisplay> {
            displayCalls++
            return Outcome.success(CardDisplay.SHOWN)
        }
    }
    private val notifier = InterventionNotifier(
        context,
        InterventionChannels(context),
        InterventionIntents(context) { Intent().setClassName(it, "dev.agentle.app.MainActivity") },
        clock,
        Logger.NONE,
    )
    private val library = MediaLibrary(
        MediaPaths({ context.noBackupFilesDir }, { context.cacheDir }),
        InMemoryMediaMetadataStore(),
        clock,
        PendingDeliveries { emptySet() },
        Logger.NONE,
    )
    private val responses = InterventionResponses({ Outcome.success(ResponseVerdict.RECORDED) }, notifier, store, Logger.NONE)
    private val cards = InterventionCards(store, decisions, responses, library, clock, Logger.NONE)

    @Test
    fun `text and expiry come from the engine, media from keepAsCard`() = runTest {
        store.put(InterventionCard.kept(PreparedDelivery(Fixtures.intervention(), "asset:img/walk.png"), clock.now()))
        val shown = cards.pending().first().single()
        assertThat(shown.title).isEqualTo("Engine title")
        assertThat(shown.expiresAt).isEqualTo(clock.now() + 2.hours)
        assertThat(shown.mediaRef).isEqualTo("asset:img/walk.png")
    }

    @Test
    fun `a kept card the engine does not list is neither shown nor kept`() = runTest {
        listed = emptyList()
        store.put(InterventionCard.kept(PreparedDelivery(Fixtures.intervention()), clock.now()))
        assertThat(cards.pending().first()).isEmpty()
        assertThat(cards.refresh()).isEqualTo(Outcome.success(1))
        assertThat(store.all()).isEqualTo(Outcome.success(emptyList<InterventionCard>()))
    }

    @Test
    fun `a shown card stays after the engine stops listing it until its expiry`() = runTest {
        assertThat(cards.onDisplayed(engineCard.decisionKey)).isEqualTo(Outcome.success(CardDisplay.SHOWN))
        listed = emptyList()
        assertThat(cards.pending().first().single().title).isEqualTo("Engine title")
        assertThat(cards.onDisplayed(engineCard.decisionKey)).isEqualTo(Outcome.success(CardDisplay.SHOWN))
        assertThat(displayCalls).isEqualTo(1)
        clock.advanceBy(3.hours)
        assertThat(cards.pending().first()).isEmpty()
    }
}
