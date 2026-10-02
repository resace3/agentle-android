package dev.agentle.feature.insights.testing

import dev.agentle.ai.api.AiPurpose
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.Insight
import dev.agentle.core.model.InsightOrigin
import dev.agentle.core.model.SupportItem
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.builder.BuilderForm
import dev.agentle.feature.insights.builder.ConditionRow
import dev.agentle.feature.insights.builder.ConditionsForm
import dev.agentle.feature.insights.builder.ContentForm
import dev.agentle.feature.insights.builder.TriggerForm
import dev.agentle.feature.insights.builder.TriggerType
import dev.agentle.feature.insights.builder.toDefinition
import dev.agentle.feature.insights.port.BuilderEnvironment
import dev.agentle.feature.insights.port.ChartBar
import dev.agentle.feature.insights.port.ChartUnit
import dev.agentle.feature.insights.port.DeliveredContent
import dev.agentle.feature.insights.port.DeliveryRecord
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.InsightChart
import dev.agentle.feature.insights.port.InsightDetail
import dev.agentle.feature.insights.port.InsightFeed
import dev.agentle.feature.insights.port.InsightMethod
import dev.agentle.feature.insights.port.InsightTest
import dev.agentle.feature.insights.port.InterpretationPreview
import dev.agentle.feature.insights.port.InterventionDetail
import dev.agentle.feature.insights.port.JitaiOverview
import dev.agentle.feature.insights.port.JitaiSummary
import dev.agentle.feature.insights.port.MissingInput
import dev.agentle.feature.insights.port.MissingInputReason
import dev.agentle.feature.insights.port.OutcomeReason
import dev.agentle.feature.insights.port.ProposalReviewData
import dev.agentle.feature.insights.port.ProposalSource
import dev.agentle.feature.insights.port.SavedInterpretation
import dev.agentle.feature.insights.port.SuggestedJitai
import dev.agentle.feature.insights.port.TraceCondition
import dev.agentle.feature.insights.port.TraceResult
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.nl.AppLabelResolver
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.DiscoveryTier
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.nl.JitaiProposal
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.validation.FeatureAccess
import dev.agentle.jitai.dsl.validation.IdGenerator
import dev.agentle.jitai.dsl.validation.MediaLibrary
import dev.agentle.jitai.dsl.validation.ValidationContext
import dev.agentle.jitai.dsl.validation.ValidationSettings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Fixed data for the feature's tests: Thursday 2026-10-01 12:00 in Europe/Berlin, deterministic ids. */
internal object Fixtures {
    val ZONE: TimeZone = TimeZone.of("Europe/Berlin")
    val NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")

    const val RULE_ID = "00000000-0000-4000-8000-0000000000a1"
    const val OTHER_RULE_ID = "00000000-0000-4000-8000-0000000000a2"
    const val PROPOSAL_ID = "00000000-0000-4000-8000-0000000000b1"
    const val DECISION_KEY = "decision-1"
    const val INSIGHT_ID = "insight-1"

    val INSTAGRAM = InstalledApp("com.instagram.android", "Instagram", 300)
    val YOUTUBE = InstalledApp("com.google.android.youtube", "YouTube", 120)
    val INSTALLED: List<InstalledApp> = listOf(INSTAGRAM, YOUTUBE)

    const val REQUEST_WALK = "Encourage me to walk when I have fewer than 3,000 steps by 5 PM."
    const val REQUEST_INSTAGRAM = "Remind me to wind down if I use Instagram too much after 10 PM."

    fun clock(): TestAgentleClock = TestAgentleClock(NOW, ZONE)

    fun context(
        apps: List<InstalledApp> = INSTALLED,
        existing: List<JitaiDefinition> = emptyList(),
        settings: ValidationSettings = ValidationSettings(),
        featureAccess: FeatureAccess = FeatureAccess.ALL_READY,
        clock: TestAgentleClock = clock(),
    ): ValidationContext = ValidationContext(
        clock = clock,
        apps = AppLabelResolver { apps },
        existingJitais = existing,
        settings = settings,
        featureAccess = featureAccess,
        mediaLibrary = MediaLibrary.of(setOf("sunset_walk_01")),
        ids = SequentialIds(),
    )

    fun environment(context: ValidationContext = context(), notificationsAllowed: Boolean = true): BuilderEnvironment =
        BuilderEnvironment(context = context, notificationsAllowed = notificationsAllowed)

    fun renderOptions(): RenderOptions = RenderOptions(use24HourClock = false, zone = ZONE)

    /** A complete, valid manual rule: at 5 PM, if fewer than 3,000 steps today, a notification. */
    fun walkForm(id: String = RULE_ID): BuilderForm = BuilderForm.new(id).copy(
        name = "Afternoon walk",
        category = JitaiCategory.PHYSICAL_ACTIVITY,
        trigger = TriggerForm(TriggerType.DAILY_AT, dailyTimes = listOf("17:00")),
        conditions = ConditionsForm(
            rows = listOf(ConditionRow.forFeature(0, "steps_today").copy(operator = Operator.LT, value = "3000")),
        ),
        content = ContentForm(title = "Time for a walk?", body = "A short walk now keeps your day moving."),
    )

    /** The stored rule of [walkForm] in [status]. */
    fun walkRule(id: String = RULE_ID, name: String = "Afternoon walk", status: JitaiStatus = JitaiStatus.ACTIVE): JitaiDefinition =
        walkForm(id).copy(name = name).toDefinition(NOW, ZONE).definition.copy(status = status, enabled = status == JitaiStatus.ACTIVE)

    fun overview(
        rules: List<JitaiSummary> = listOf(JitaiSummary(walkRule(), deliveredToday = 1, deliveredThisWeek = 3)),
        notificationsAllowed: Boolean = true,
    ): JitaiOverview = JitaiOverview(
        rules = rules,
        renderOptions = renderOptions(),
        globalMaxPerDay = 6,
        deliveredToday = rules.sumOf { it.deliveredToday },
        notificationsAllowed = notificationsAllowed,
        zone = ZONE,
    )

    fun suggestion(): SuggestedJitai = SuggestedJitai(
        proposalId = PROPOSAL_ID,
        name = "Wind down after late screen time",
        rendering = "Every 30 minutes from 10:00 PM to 1:00 AM: if screen time since 10:00 PM is at least 45 min, a notification.",
        tier = DiscoveryTier.MODERATE,
        trialDays = 28,
        createdAt = NOW - 1.days,
    )

    fun record(
        key: String = DECISION_KEY,
        result: DeliveryResult = DeliveryResult.DELIVERED,
        reason: OutcomeReason? = null,
        at: Instant = NOW - 2.hours,
    ): DeliveryRecord = DeliveryRecord(key, RULE_ID, "Afternoon walk", at, DeliveryChannel.NOTIFICATION, result, reason)

    fun insight(id: String = INSIGHT_ID, origin: InsightOrigin = InsightOrigin.LOCAL): Insight = Insight(
        id = id,
        kind = "screen_bedtime",
        title = "Late screen time and bedtime",
        finding = "On nights with 45 minutes or more of screen time after 10 PM, you went to bed later more often.",
        supportingData = listOf(SupportItem("Nights", "60"), SupportItem("Later bedtime", "71% vs 25%")),
        periodStart = Instant.parse("2026-08-05T22:00:00Z"),
        periodEnd = Instant.parse("2026-10-01T22:00:00Z"),
        strength = EvidenceStrength.MODERATE,
        origin = origin,
        categories = setOf(DataCategory.SCREEN, DataCategory.SLEEP),
        createdAt = NOW - 1.days,
    )

    fun feed(
        insights: List<Insight> = listOf(insight()),
        missing: List<MissingInput> = emptyList(),
        interpreted: Set<String> = emptySet(),
    ): InsightFeed = InsightFeed(insights = insights, interpreted = interpreted, missingInputs = missing, zone = ZONE)

    val MISSING_SLEEP = MissingInput(DataCategory.SLEEP, MissingInputReason.SOURCE_NOT_CONNECTED, AppRoute.DataSources)

    fun detail(interpretation: SavedInterpretation? = null): InsightDetail = InsightDetail(
        insight = insight(),
        method = InsightMethod(InsightTest.PERMUTATION_TEST, qValue = 0.0185, pValue = 0.00103, sampleSize = 60),
        chart = InsightChart.Comparison(
            bars = listOf(ChartBar("45 min or more", 71.0), ChartBar("Less than 45 min", 25.0)),
            unit = ChartUnit.PERCENT,
        ),
        interpretation = interpretation,
        zone = ZONE,
    )

    fun preview(): InterpretationPreview = InterpretationPreview(
        previewId = "preview-1",
        purpose = AiPurpose.PATTERN_EXPLANATION,
        categories = setOf(DataCategory.SCREEN, DataCategory.SLEEP),
        rangeStart = Instant.parse("2026-08-05T22:00:00Z"),
        rangeEnd = Instant.parse("2026-10-01T22:00:00Z"),
        previewText = "Pattern: later bedtime on nights with 45+ minutes of late screen time (71% vs 25%, 60 nights).",
    )

    fun interpretation(text: String = "Late evenings on the phone and later bedtimes went together in your data."): SavedInterpretation =
        SavedInterpretation(text, setOf(DataCategory.SCREEN, DataCategory.SLEEP), NOW)

    fun intervention(
        result: DeliveryResult = DeliveryResult.DELIVERED,
        reason: OutcomeReason? = null,
        jitaiId: String? = RULE_ID,
    ): InterventionDetail = InterventionDetail(
        decisionKey = DECISION_KEY,
        jitaiId = jitaiId,
        jitaiName = "Afternoon walk",
        channel = DeliveryChannel.NOTIFICATION,
        decidedAt = Instant.parse("2026-10-01T15:00:00Z"),
        deliveredAt = Instant.parse("2026-10-01T15:00:05Z").takeIf { result != DeliveryResult.SUPPRESSED },
        result = result,
        reason = reason,
        content = DeliveredContent("Time for a walk?", "A short walk now keeps your day moving."),
        trace = listOf(
            TraceCondition(
                condition = Condition.compare(Operator.LT, "steps_today", RuleLiteral.NumberToken("3000")),
                observed = FeatureValue.Known(FeatureScalar.IntValue(2140), Instant.parse("2026-10-01T14:55:00Z")),
                result = TraceResult.TRUE,
            ),
            TraceCondition(Condition.LocalTimeIn("16:00", "18:00"), result = TraceResult.TRUE),
        ),
        renderOptions = renderOptions(),
    )

    // ---- proposals (R10 §13.6 and §14.7 fixtures, copied from :jitai:dsl's golden corpus)

    fun walkProposal(): JitaiProposal = RuleCodec.decodeProposal(resource("r10/example-13-6-2.json")).getOrThrow()

    fun instagramProposal(): JitaiProposal = RuleCodec.decodeProposal(resource("r10/example-13-6-1.json")).getOrThrow()

    fun discovered(): DiscoveredProposal = RuleCodec.decodeDiscovered(resource("r10/discovered-14-7.json")).getOrThrow()

    fun nlReview(proposal: JitaiProposal = walkProposal(), request: String = REQUEST_WALK): ProposalReviewData = ProposalReviewData(
        proposalId = PROPOSAL_ID,
        source = ProposalSource.NaturalLanguage(request, proposal, interpretation = "A daily 5 PM step check."),
        sourceCategories = setOf(DataCategory.ACTIVITY),
    )

    fun discoveredReview(): ProposalReviewData = ProposalReviewData(
        proposalId = PROPOSAL_ID,
        source = ProposalSource.Discovered(discovered()),
        sourceCategories = setOf(DataCategory.SCREEN, DataCategory.SLEEP),
    )

    fun resource(name: String): String {
        val stream = checkNotNull(Fixtures::class.java.getResourceAsStream("/$name")) { "missing test resource $name" }
        return stream.use { it.readBytes().decodeToString() }
    }
}

/** Lowercase UUID-shaped ids `00000000-0000-4000-8000-00000000000n`. */
internal class SequentialIds : IdGenerator {
    private var n = 0

    override fun newId(): String {
        n++
        return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    }
}

/** A day in the fixture zone, for chart points. */
internal fun day(isoDate: String): LocalDate = LocalDate.parse(isoDate)
