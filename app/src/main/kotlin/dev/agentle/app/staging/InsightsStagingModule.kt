package dev.agentle.app.staging

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.insights.port.BuilderEnvironment
import dev.agentle.feature.insights.port.DeliveryRecord
import dev.agentle.feature.insights.port.InsightDetail
import dev.agentle.feature.insights.port.InsightFeed
import dev.agentle.feature.insights.port.InsightsPort
import dev.agentle.feature.insights.port.InterpretationEvent
import dev.agentle.feature.insights.port.InterpretationPreview
import dev.agentle.feature.insights.port.InterventionDetail
import dev.agentle.feature.insights.port.InterventionPort
import dev.agentle.feature.insights.port.JitaiBuilderPort
import dev.agentle.feature.insights.port.JitaiListPort
import dev.agentle.feature.insights.port.JitaiOverview
import dev.agentle.feature.insights.port.NlConversion
import dev.agentle.feature.insights.port.ProposalRejection
import dev.agentle.feature.insights.port.ProposalReviewData
import dev.agentle.feature.insights.port.ProposalReviewPort
import dev.agentle.feature.insights.port.SuggestedJitai
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.QuestionId
import dev.agentle.jitai.dsl.render.RenderOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.TimeZone

/** Placeholder bindings for :feature:insights ports until APP-WIRING implements them: empty data, UnsupportedFeature. */
@Module
@InstallIn(SingletonComponent::class)
object InsightsStagingModule {
    private val unavailable = AppError.UnsupportedFeature("insights_staging")

    @Provides
    fun insightsPort(): InsightsPort = object : InsightsPort {
        override fun insightFeed(): Flow<InsightFeed> = flowOf(InsightFeed(insights = emptyList(), zone = TimeZone.UTC))
        override fun insightDetail(id: String): Flow<InsightDetail?> = flowOf(null)
        override suspend fun interpretationPreview(id: String): Outcome<InterpretationPreview> = Outcome.failure(unavailable)
        override fun interpret(previewId: String): Flow<InterpretationEvent> = flowOf(InterpretationEvent.Failed(unavailable))
    }

    @Provides
    fun jitaiListPort(): JitaiListPort = object : JitaiListPort {
        override fun jitais(): Flow<JitaiOverview> =
            flowOf(JitaiOverview(emptyList(), RenderOptions(), 0, 0, notificationsAllowed = false, zone = TimeZone.UTC))
        override fun suggestions(): Flow<List<SuggestedJitai>> = flowOf(emptyList())
        override fun history(): Flow<List<DeliveryRecord>> = flowOf(emptyList())
        override suspend fun pause(jitaiId: String): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun resume(jitaiId: String): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun disable(jitaiId: String): Outcome<Unit> = Outcome.failure(unavailable)
    }

    @Provides
    fun jitaiBuilderPort(): JitaiBuilderPort = object : JitaiBuilderPort {
        override suspend fun environment(): Outcome<BuilderEnvironment> = Outcome.failure(unavailable)
        override suspend fun definition(jitaiId: String): Outcome<JitaiDefinition> = Outcome.failure(unavailable)
        override suspend fun save(definition: JitaiDefinition): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun convertNaturalLanguage(request: String): Outcome<NlConversion> = Outcome.failure(unavailable)
        override suspend fun answerClarification(conversationId: String, answers: Map<QuestionId, String>): Outcome<NlConversion> =
            Outcome.failure(unavailable)
    }

    @Provides
    fun proposalReviewPort(): ProposalReviewPort = object : ProposalReviewPort {
        override fun proposal(proposalId: String): Flow<ProposalReviewData?> = flowOf(null)
        override suspend fun environment(): Outcome<BuilderEnvironment> = Outcome.failure(unavailable)
        override suspend fun activate(proposalId: String, definition: JitaiDefinition): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun reject(proposalId: String, rejection: ProposalRejection): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun draftForEditing(proposalId: String, definition: JitaiDefinition?): Outcome<String> =
            Outcome.failure(unavailable)
    }

    @Provides
    fun interventionPort(): InterventionPort = object : InterventionPort {
        override fun intervention(decisionKey: String): Flow<InterventionDetail?> = flowOf(null)
        override suspend fun sendFeedback(decisionKey: String, helpful: Boolean): Outcome<Unit> = Outcome.failure(unavailable)
        override suspend fun markCardShown(decisionKey: String): Outcome<Unit> = Outcome.failure(unavailable)
    }
}
