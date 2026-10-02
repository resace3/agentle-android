package dev.agentle.app.staging

import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.app.MainActivity
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.interventions.card.InMemoryInterventionCardStore
import dev.agentle.interventions.ports.CardDecisions
import dev.agentle.interventions.ports.CardDisplay
import dev.agentle.interventions.ports.InterventionActivityIntents
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.interventions.ports.InterventionResponseRecorder
import dev.agentle.interventions.ports.InterventionSettings
import dev.agentle.interventions.ports.InterventionSettingsSource
import dev.agentle.interventions.ports.MediaMetadataStore
import dev.agentle.interventions.ports.TtsEngineConsent
import dev.agentle.interventions.storage.InMemoryMediaMetadataStore
import javax.inject.Singleton

/**
 * Placeholders for the :interventions ports until the wiring team binds the engine, DataStore and Room versions
 * (EngineResponseRecorder, EngineCardDecisions, settings, MediaDao). Nothing here invents data: responses and card
 * displays fail as not wired, settings are the privacy defaults, stores are empty and in memory.
 */
@Module
@InstallIn(SingletonComponent::class)
object InterventionsStagingModule {
    private const val NOT_WIRED = "not wired"

    @Provides
    @Singleton
    fun recorder(): InterventionResponseRecorder =
        InterventionResponseRecorder { Outcome.failure(AppError.UnsupportedFeature("intervention_responses", NOT_WIRED)) }

    @Provides
    @Singleton
    fun cardDecisions(): CardDecisions = object : CardDecisions {
        override suspend fun pendingKeys(): Outcome<Set<String>> = Outcome.success(emptySet())

        override suspend fun markDisplayed(decisionKey: String): Outcome<CardDisplay> =
            Outcome.failure(AppError.UnsupportedFeature("intervention_cards", NOT_WIRED))
    }

    @Provides
    fun settings(): InterventionSettingsSource = InterventionSettingsSource { Outcome.success(InterventionSettings()) }

    @Provides
    fun engineConsent(): TtsEngineConsent = TtsEngineConsent { Outcome.success(emptySet()) }

    @Provides
    fun activityIntents(): InterventionActivityIntents = InterventionActivityIntents { context ->
        Intent(context, MainActivity::class.java)
    }

    @Provides
    @Singleton
    fun mediaMetadata(): MediaMetadataStore = InMemoryMediaMetadataStore()

    @Provides
    @Singleton
    fun cardStore(): InterventionCardStore = InMemoryInterventionCardStore()
}
