package dev.agentle.analytics.insights

import dev.agentle.core.model.Lineage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * Builds the stored object of an AI-discovered proposal (docs/research/10 §14.7): ids, tier, the template text with its
 * evidence (numbers kept to three significant digits), the hypothesis's rule draft (§14.5), the expected outcome, the
 * data required and the 28-day trial with the optional experiment offer. No model is involved.
 *
 * The evidence object also carries `lineage` (data categories and source families of every daily row behind it,
 * integrator correction 3), an addition to the §14.7 example.
 */
public object DiscoveredProposals {
    public const val METHOD: String = "exact-stratified-permutation-v1"
    public const val DELIVER_PROBABILITY: Double = 0.5

    /**
     * The proposal for [result] of [run] (a positive MODERATE or STRONG finding seen in [consecutiveRuns] runs). The
     * draft must pass the structural check of [ProposalSchema]; a template that fails it is a defect.
     */
    public fun build(
        result: HypothesisResult,
        run: PatternRun,
        proposalId: String,
        createdAt: Instant,
        config: InsightConfig = InsightConfig(),
        consecutiveRuns: Int = 2,
    ): JsonObject {
        require(result.isClaim && (result.riskDifferenceMh ?: 0.0) > 0) { "only positive findings are proposed" }
        val hypothesis = result.hypothesis
        val draft = RuleTemplates.draft(hypothesis, config.trialDays)
        val issues = ProposalSchema.checkEnvelope(ProposalSchema.envelope(draft))
        check(issues.isEmpty()) { "rule template ${hypothesis.id} does not conform: ${issues.map { it.path }}" }
        return buildJsonObject {
            put("proposalId", proposalId)
            put("patternId", requireNotNull(result.patternId))
            put("hypothesisId", hypothesis.id)
            put("exposure", hypothesis.exposure.id)
            put("outcome", hypothesis.outcome.id)
            // The validator requires yyyy-MM-ddTHH:mm:ssZ: whole seconds only.
            put("createdAt", Instant.fromEpochSeconds(createdAt.epochSeconds).toString())
            put("tier", result.tier.name)
            put("approvalRequired", true)
            putJsonObject("whyProposed") {
                put("text", PatternText.finding(result))
                put("evidence", evidence(result, run, consecutiveRuns))
            }
            put("jitai", draft)
            put("expectedOutcome", RuleTemplates.expectedOutcome(hypothesis))
            put("dataRequired", JsonArray(RuleTemplates.dataRequired(hypothesis).map { JsonPrimitive(it) }))
            putJsonObject("trial") {
                put("days", config.trialDays)
                putJsonObject("experimentOffer") {
                    put("mode", "MICRO_RANDOMIZED")
                    put("deliverProbability", DELIVER_PROBABILITY)
                    put("requiresConsent", true)
                }
            }
        }
    }

    /** The evidence object of §14.7. */
    public fun evidence(result: HypothesisResult, run: PatternRun, consecutiveRuns: Int): JsonObject {
        val t = result.table
        val ci = requireNotNull(result.interval)
        return buildJsonObject {
            putJsonObject("analysisWindow") {
                put("firstNight", run.firstNight.toString())
                put("lastNight", run.lastNight.toString())
            }
            put("nightsComplete", t.n)
            put("exposed", arm(t.exposed, t.a))
            put("unexposed", arm(t.unexposed, t.c))
            put("rateExposed", sig(requireNotNull(t.rateExposed)))
            put("rateUnexposed", sig(requireNotNull(t.rateUnexposed)))
            put("riskDifference", sig(requireNotNull(t.riskDifference)))
            put("riskDifferenceCi95", JsonArray(listOf(sig(ci.lower), sig(ci.upper))))
            put("lift", t.lift?.let { sig(it) } ?: JsonNull)
            put("riskDifferenceMh", sig(requireNotNull(result.riskDifferenceMh)))
            put("pExactStratified", sig(result.pValue))
            put("qBenjaminiHochberg", sig(result.qValue))
            put("familySize", HypothesisFamily.size)
            put(
                "strata",
                JsonArray(
                    result.strata.map { s ->
                        buildJsonObject {
                            put("nightType", s.type.name)
                            put("exposed", arm(s.table.exposed, s.table.a))
                            put("unexposed", arm(s.table.unexposed, s.table.c))
                        }
                    },
                ),
            )
            putJsonObject("checks") {
                put("strataSign", result.strataCheck)
                put("splitHalf", result.splitHalfCheck)
            }
            put("consecutiveRuns", consecutiveRuns)
            put("method", METHOD)
            put("lineage", lineage(result.lineage.takeUnless { it.isEmpty } ?: Lineage.UNKNOWN))
        }
    }

    private fun arm(nights: Int, withOutcome: Int): JsonObject = buildJsonObject {
        put("nights", nights)
        put("withOutcome", withOutcome)
    }

    private fun sig(x: Double): JsonElement = JsonPrimitive(PatternStatistics.significant(x))

    private fun lineage(lineage: Lineage): JsonObject = buildJsonObject {
        put("categories", JsonArray(lineage.categories.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
        put("sourceFamilies", JsonArray(lineage.sourceFamilies.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
    }
}
