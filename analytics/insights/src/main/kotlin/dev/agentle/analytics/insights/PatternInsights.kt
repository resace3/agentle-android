package dev.agentle.analytics.insights

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.Insight
import dev.agentle.core.model.InsightOrigin
import dev.agentle.core.model.Lineage
import dev.agentle.core.time.EngineDay
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Insight cards of a run's findings (docs/research/10 §14.4, §14.8): one per MODERATE or STRONG hypothesis, with the
 * fixed non-causal template text, the supporting numbers and the lineage of the daily rows it was computed from.
 * WEAK, NONE and INSUFFICIENT results are never shown as findings.
 */
public object PatternInsights {
    /** The insight of [result], or null when it is not a finding. Its period is the run's nights in [zone]. */
    public fun of(result: HypothesisResult, run: PatternRun, zone: TimeZone, now: Instant): Insight? {
        if (!result.isClaim) return null
        val lineage = result.lineage.takeUnless { it.isEmpty } ?: Lineage.UNKNOWN
        return Insight(
            id = "pattern-${run.lastNight}-${result.patternId}",
            kind = "pattern.${result.hypothesis.id}",
            title = PatternText.title(result.tier),
            finding = PatternText.finding(result),
            supportingData = PatternText.supportItems(result),
            periodStart = EngineDay.bounds(run.firstNight, zone).start,
            periodEnd = EngineDay.bounds(run.lastNight, zone).end,
            strength = if (result.tier == PatternTier.STRONG) EvidenceStrength.STRONG else EvidenceStrength.MODERATE,
            confidence = null,
            origin = InsightOrigin.LOCAL,
            categories = lineage.categories,
            createdAt = now,
            sourceFamilies = lineage.sourceFamilies,
        )
    }

    /** The insights of every finding of [run]. */
    public fun of(run: PatternRun, zone: TimeZone, now: Instant): List<Insight> = run.results.mapNotNull { of(it, run, zone, now) }
}

/** Fields an insight wording request may carry: counts, rates and codes only (docs/research/10 §14.9). */
public enum class InsightAiField(public val code: String) {
    TIER("tier"),
    EXPOSURE("exposure"),
    OUTCOME("outcome"),
    NIGHTS_COMPLETE("nights_complete"),
    EXPOSED_NIGHTS("exposed_nights"),
    EXPOSED_WITH_OUTCOME("exposed_with_outcome"),
    OTHER_NIGHTS("other_nights"),
    OTHER_WITH_OUTCOME("other_with_outcome"),
    PERCENT_EXPOSED("percent_exposed"),
    PERCENT_OTHER("percent_other"),
    RANGE_LOW_POINTS("difference_range_low_points"),
    RANGE_HIGH_POINTS("difference_range_high_points"),
}

/** One aggregate value of an [InsightAiRequest]. No variant can hold a date, an app name or event data. */
public sealed interface InsightAiValue {
    public val field: InsightAiField

    /** A whole number (nights, percentages, percentage points). */
    public data class Number(override val field: InsightAiField, val value: Int) : InsightAiValue

    /** An app-defined upper-case code (tier, exposure and outcome ids). */
    public data class Code(override val field: InsightAiField, val code: String) : InsightAiValue {
        init {
            require(CODE.matches(code)) { "codes are app-defined identifiers" }
        }
    }

    private companion object {
        val CODE = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    }
}

/**
 * An aggregate-only request to reword one finding (docs/research/10 §14.9, off by default): the app's own template
 * sentence and the evidence numbers, never dates, app or package names, notification text or raw events. The
 * ContextSelectionEngine of `:ai:context` turns it into the provider envelope after the consent gate checks [lineage];
 * any reply is used only after the shared AiTextPolicy (lint plus "every number must appear in the evidence") accepts it.
 *
 * @property numbers the numbers a reply may contain (those of [template] and [values]).
 */
public data class InsightAiRequest(
    val purpose: AiPurpose,
    val instructions: String,
    val template: String,
    val values: List<InsightAiValue>,
    val lineage: Lineage,
) {
    public val numbers: Set<String>
        get() = DIGITS.findAll(template).map { it.value.replace(",", "") }.toSet() +
            values.filterIsInstance<InsightAiValue.Number>().map { it.value.toString() }

    private companion object {
        val DIGITS = Regex("\\d[\\d,]*")
    }
}

/** Builds [InsightAiRequest]s from findings. */
public object InsightAiRequestBuilder {
    /** App-constant instructions; they never contain personal data. */
    public const val INSTRUCTIONS: String =
        "Reword the finding below in a friendly tone in at most three sentences. Use only the numbers given. " +
            "Describe it as a pattern in the person's own data that may have other explanations, never as a cause or a proof, " +
            "and give no medical advice."

    /** The request for [result], or null when it is not a finding. */
    public fun build(result: HypothesisResult): InsightAiRequest? {
        if (!result.isClaim) return null
        val t = result.table
        val values = buildList {
            add(InsightAiValue.Code(InsightAiField.TIER, result.tier.name))
            add(InsightAiValue.Code(InsightAiField.EXPOSURE, result.hypothesis.exposure.id))
            add(InsightAiValue.Code(InsightAiField.OUTCOME, result.hypothesis.outcome.id))
            add(InsightAiValue.Number(InsightAiField.NIGHTS_COMPLETE, t.n))
            add(InsightAiValue.Number(InsightAiField.EXPOSED_NIGHTS, t.exposed))
            add(InsightAiValue.Number(InsightAiField.EXPOSED_WITH_OUTCOME, t.a))
            add(InsightAiValue.Number(InsightAiField.OTHER_NIGHTS, t.unexposed))
            add(InsightAiValue.Number(InsightAiField.OTHER_WITH_OUTCOME, t.c))
            add(InsightAiValue.Number(InsightAiField.PERCENT_EXPOSED, PatternStatistics.percent(requireNotNull(t.rateExposed))))
            add(InsightAiValue.Number(InsightAiField.PERCENT_OTHER, PatternStatistics.percent(requireNotNull(t.rateUnexposed))))
            result.interval?.let { ci ->
                add(InsightAiValue.Number(InsightAiField.RANGE_LOW_POINTS, Math.round(ci.lower * PERCENT).toInt()))
                add(InsightAiValue.Number(InsightAiField.RANGE_HIGH_POINTS, Math.round(ci.upper * PERCENT).toInt()))
            }
        }
        val lineage = result.lineage.takeUnless { it.isEmpty } ?: Lineage.UNKNOWN
        return InsightAiRequest(AiPurpose.PATTERN_EXPLANATION, INSTRUCTIONS, PatternText.finding(result), values, lineage)
    }

    private const val PERCENT = 100.0
}
