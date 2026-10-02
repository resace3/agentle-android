package dev.agentle.interventions.di

import android.content.Context
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.Logger
import dev.agentle.core.common.getOrNull
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.SystemAgentleClock
import dev.agentle.interventions.card.InterventionCards
import dev.agentle.interventions.delivery.InterventionDeliveryPort
import dev.agentle.interventions.delivery.NotificationStateReader
import dev.agentle.interventions.image.ImagePreparer
import dev.agentle.interventions.image.TemplateRenderer
import dev.agentle.interventions.notification.InterventionChannels
import dev.agentle.interventions.notification.InterventionIntents
import dev.agentle.interventions.notification.InterventionNotifier
import dev.agentle.interventions.ports.CardDecisions
import dev.agentle.interventions.ports.InterventionActivityIntents
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.interventions.ports.InterventionResponseRecorder
import dev.agentle.interventions.ports.InterventionSettingsSource
import dev.agentle.interventions.ports.MediaMetadataStore
import dev.agentle.interventions.ports.TtsEngineConsent
import dev.agentle.interventions.response.InterventionOpener
import dev.agentle.interventions.response.InterventionResponses
import dev.agentle.interventions.storage.BundledMedia
import dev.agentle.interventions.storage.FileProviderShareUris
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPaths
import dev.agentle.interventions.storage.MediaPictures
import dev.agentle.interventions.storage.MediaSharing
import dev.agentle.interventions.storage.PendingDeliveries
import dev.agentle.interventions.video.Media3VideoComposer
import dev.agentle.interventions.video.VideoComposer
import dev.agentle.interventions.video.VideoPreparer
import dev.agentle.interventions.video.VideoStudio
import dev.agentle.interventions.voice.TtsSynthesizer
import dev.agentle.interventions.voice.VoicePreparer
import dev.agentle.jitai.engine.ports.DeliveryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/** The scope of short receiver work (`goAsync`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InterventionsScope

/** Hilt access for the non-injectable [dev.agentle.interventions.notification.NotificationActionReceiver]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InterventionsEntryPoint {
    fun interventionResponses(): InterventionResponses

    @InterventionsScope
    fun interventionScope(): CoroutineScope
}

/**
 * The module's graph. Ports (recorder, card decisions, settings, engine consent, activity intents, media metadata, card
 * store) come from the app; clock, logger and dispatchers are optional so the app's own bindings win and nothing is bound
 * twice. Binds the engine's [DeliveryPort] to [InterventionDeliveryPort].
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("AbstractClassCanBeInterface")
abstract class InterventionsModule {
    @BindsOptionalOf
    abstract fun optionalClock(): AgentleClock

    @BindsOptionalOf
    abstract fun optionalLogger(): Logger

    @BindsOptionalOf
    abstract fun optionalDispatchers(): AppDispatchers

    @Binds
    abstract fun deliveryPort(port: InterventionDeliveryPort): DeliveryPort

    companion object {
        private fun Optional<AgentleClock>.orSystem(): AgentleClock = orElseGet { SystemAgentleClock() }

        private fun Optional<Logger>.orNone(): Logger = orElse(Logger.NONE)

        private fun Optional<AppDispatchers>.orDefault(): AppDispatchers = orElseGet { AppDispatchers() }

        @Provides
        @Singleton
        @InterventionsScope
        fun scope(dispatchers: Optional<AppDispatchers>): CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.orDefault().default)

        @Provides
        @Singleton
        fun channels(@ApplicationContext context: Context): InterventionChannels = InterventionChannels(context)

        @Provides
        @Singleton
        fun notifier(
            @ApplicationContext context: Context,
            channels: InterventionChannels,
            activityIntents: InterventionActivityIntents,
            clock: Optional<AgentleClock>,
            logger: Optional<Logger>,
        ): InterventionNotifier =
            InterventionNotifier(context, channels, InterventionIntents(context, activityIntents), clock.orSystem(), logger.orNone())

        @Provides
        @Singleton
        fun stateReader(@ApplicationContext context: Context, channels: InterventionChannels): NotificationStateReader =
            NotificationStateReader(context, channels)

        @Provides
        @Singleton
        fun paths(@ApplicationContext context: Context): MediaPaths = MediaPaths.from(context)

        @Provides
        @Singleton
        fun bundled(@ApplicationContext context: Context): BundledMedia = BundledMedia(context.assets)

        @Provides
        @Singleton
        fun library(
            paths: MediaPaths,
            store: MediaMetadataStore,
            notifier: InterventionNotifier,
            cards: InterventionCardStore,
            clock: Optional<AgentleClock>,
            logger: Optional<Logger>,
            dispatchers: Optional<AppDispatchers>,
        ): MediaLibrary {
            val pending = PendingDeliveries { notifier.activeTags() + cards.all().getOrNull().orEmpty().map { it.decisionKey } }
            return MediaLibrary(paths, store, clock.orSystem(), pending, logger.orNone(), io = dispatchers.orDefault().io)
        }

        @Provides
        @Singleton
        fun sharing(@ApplicationContext context: Context, library: MediaLibrary, paths: MediaPaths): MediaSharing =
            MediaSharing(library, paths, FileProviderShareUris(context))

        @Provides
        @Singleton
        fun pictures(library: MediaLibrary, bundled: BundledMedia): MediaPictures = MediaPictures(library, bundled)

        @Provides
        @Singleton
        fun images(
            @ApplicationContext context: Context,
            library: MediaLibrary,
            bundled: BundledMedia,
            settings: InterventionSettingsSource,
            logger: Optional<Logger>,
        ): ImagePreparer = ImagePreparer(TemplateRenderer(context), library, bundled, settings, logger.orNone())

        @Provides
        @Singleton
        fun voices(
            @ApplicationContext context: Context,
            library: MediaLibrary,
            settings: InterventionSettingsSource,
            consent: TtsEngineConsent,
            logger: Optional<Logger>,
        ): VoicePreparer = VoicePreparer(
            TtsSynthesizer(context),
            library,
            settings,
            consent,
            { context.resources.configuration.locales[0] },
            logger.orNone(),
        )

        @Provides
        @Singleton
        fun videos(library: MediaLibrary, bundled: BundledMedia): VideoPreparer = VideoPreparer(library, bundled)

        @Provides
        @Singleton
        fun composer(@ApplicationContext context: Context): VideoComposer = Media3VideoComposer(context)

        @Provides
        @Singleton
        fun studio(composer: VideoComposer, library: MediaLibrary): VideoStudio = VideoStudio(composer, library)

        @Provides
        @Singleton
        @Suppress("LongParameterList")
        fun port(
            notifier: InterventionNotifier,
            access: NotificationStateReader,
            cards: InterventionCardStore,
            images: ImagePreparer,
            voices: VoicePreparer,
            videos: VideoPreparer,
            library: MediaLibrary,
            pictures: MediaPictures,
            clock: Optional<AgentleClock>,
            logger: Optional<Logger>,
        ): InterventionDeliveryPort = InterventionDeliveryPort(
            notifier,
            access,
            cards,
            images,
            voices,
            videos,
            library,
            pictures,
            clock.orSystem(),
            logger.orNone(),
        )

        @Provides
        @Singleton
        fun responses(
            recorder: InterventionResponseRecorder,
            notifier: InterventionNotifier,
            cards: InterventionCardStore,
            logger: Optional<Logger>,
        ): InterventionResponses = InterventionResponses(recorder, notifier, cards, logger.orNone())

        @Provides
        fun opener(responses: InterventionResponses): InterventionOpener = InterventionOpener(responses)

        @Provides
        @Singleton
        fun cards(
            store: InterventionCardStore,
            decisions: CardDecisions,
            responses: InterventionResponses,
            library: MediaLibrary,
            clock: Optional<AgentleClock>,
            logger: Optional<Logger>,
        ): InterventionCards = InterventionCards(store, decisions, responses, library, clock.orSystem(), logger.orNone())
    }
}
