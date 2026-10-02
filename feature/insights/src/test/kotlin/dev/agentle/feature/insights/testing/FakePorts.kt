package dev.agentle.feature.insights.testing

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal val UNSUPPORTED = AppError.UnsupportedFeature("test")

/** A flow of [state] (null: never emits, i.e. loading) that throws [error] when set. */
private fun <T : Any> source(state: MutableStateFlow<T?>, error: () -> Throwable?): Flow<T> = flow {
    error()?.let { throw it }
    emitAll(state.filterNotNull())
}

internal class FakeInsightsPort : InsightsPort {
    val feed = MutableStateFlow<InsightFeed?>(null)
    var feedError: Throwable? = null
    val details = MutableStateFlow<Map<String, InsightDetail>>(emptyMap())
    var previewResult: Outcome<InterpretationPreview> = Outcome.failure(UNSUPPORTED)
    var answer: Flow<InterpretationEvent> = emptyFlow()
    val sent = mutableListOf<String>()

    override fun insightFeed(): Flow<InsightFeed> = source(feed) { feedError }
    override fun insightDetail(id: String): Flow<InsightDetail?> = details.map { it[id] }
    override suspend fun interpretationPreview(id: String): Outcome<InterpretationPreview> = previewResult
    override fun interpret(previewId: String): Flow<InterpretationEvent> = flow {
        sent += previewId
        emitAll(answer)
    }
}

internal class FakeJitaiListPort : JitaiListPort {
    val overview = MutableStateFlow<JitaiOverview?>(null)
    var jitaisError: Throwable? = null
    val suggestions = MutableStateFlow<List<SuggestedJitai>?>(emptyList())
    val history = MutableStateFlow<List<DeliveryRecord>?>(emptyList())
    var result: Outcome<Unit> = Outcome.success(Unit)
    val calls = mutableListOf<String>()

    override fun jitais(): Flow<JitaiOverview> = source(overview) { jitaisError }
    override fun suggestions(): Flow<List<SuggestedJitai>> = source(suggestions) { null }
    override fun history(): Flow<List<DeliveryRecord>> = source(history) { null }
    override suspend fun pause(jitaiId: String): Outcome<Unit> = result.also { calls += "pause:$jitaiId" }
    override suspend fun resume(jitaiId: String): Outcome<Unit> = result.also { calls += "resume:$jitaiId" }
    override suspend fun disable(jitaiId: String): Outcome<Unit> = result.also { calls += "disable:$jitaiId" }
}

internal class FakeJitaiBuilderPort(var environmentResult: Outcome<BuilderEnvironment> = Outcome.success(Fixtures.environment())) :
    JitaiBuilderPort {
    val definitions = mutableMapOf<String, JitaiDefinition>()
    var saveResult: Outcome<Unit> = Outcome.success(Unit)
    val saved = mutableListOf<JitaiDefinition>()
    var conversion: Outcome<NlConversion> = Outcome.failure(UNSUPPORTED)
    val requests = mutableListOf<String>()
    val answers = mutableListOf<Map<QuestionId, String>>()

    override suspend fun environment(): Outcome<BuilderEnvironment> = environmentResult
    override suspend fun definition(jitaiId: String): Outcome<JitaiDefinition> =
        definitions[jitaiId]?.let { Outcome.success(it) } ?: Outcome.failure(AppError.ValidationError(listOf("not_found")))
    override suspend fun save(definition: JitaiDefinition): Outcome<Unit> = saveResult.also { saved += definition }
    override suspend fun convertNaturalLanguage(request: String): Outcome<NlConversion> = conversion.also { requests += request }
    override suspend fun answerClarification(conversationId: String, answers: Map<QuestionId, String>): Outcome<NlConversion> =
        conversion.also { this.answers += answers }
}

internal class FakeProposalReviewPort : ProposalReviewPort {
    val proposals = MutableStateFlow<Map<String, ProposalReviewData>>(emptyMap())
    var environmentResult: Outcome<BuilderEnvironment> = Outcome.success(Fixtures.environment())
    var result: Outcome<Unit> = Outcome.success(Unit)
    val activated = mutableListOf<JitaiDefinition>()
    val rejected = mutableListOf<ProposalRejection>()
    val edited = mutableListOf<JitaiDefinition?>()

    override fun proposal(proposalId: String): Flow<ProposalReviewData?> = proposals.map { it[proposalId] }
    override suspend fun environment(): Outcome<BuilderEnvironment> = environmentResult
    override suspend fun activate(proposalId: String, definition: JitaiDefinition): Outcome<Unit> = result.also { activated += definition }
    override suspend fun reject(proposalId: String, rejection: ProposalRejection): Outcome<Unit> = result.also { rejected += rejection }
    override suspend fun draftForEditing(proposalId: String, definition: JitaiDefinition?): Outcome<String> {
        edited += definition
        return Outcome.success("draft-1")
    }
}

internal class FakeInterventionPort : InterventionPort {
    val details = MutableStateFlow<Map<String, InterventionDetail>>(emptyMap())
    var result: Outcome<Unit> = Outcome.success(Unit)
    val feedback = mutableListOf<Boolean>()
    val cardsShown = mutableListOf<String>()

    override fun intervention(decisionKey: String): Flow<InterventionDetail?> = details.map { it[decisionKey] }
    override suspend fun sendFeedback(decisionKey: String, helpful: Boolean): Outcome<Unit> = result.also { feedback += helpful }
    override suspend fun markCardShown(decisionKey: String): Outcome<Unit> = Outcome.success(Unit).also { cardsShown += decisionKey }
}
