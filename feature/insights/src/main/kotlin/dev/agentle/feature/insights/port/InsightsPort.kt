package dev.agentle.feature.insights.port

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Insight
import dev.agentle.core.ui.navigation.AppRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * What the Insights screens read and do (spec §16, §22). Implemented by the APP-WIRING team on top of the analytics
 * insight store, the AI egress layer (`:ai:context`) and the consent settings.
 *
 * Main safety: every function may be called from the main thread; implementations move work off it. Read flows never
 * complete on their own and re-emit when the underlying data changes. They do not throw for expected situations
 * (no data, missing permission, disconnected source): those are part of the emitted value. An unexpected storage
 * failure may surface as an [dev.agentle.core.common.AppException] carrying [AppError.DatabaseError];
 * the screens then show their error state with a retry that collects the flow again.
 */
public interface InsightsPort {
    /**
     * The insight list: active insights, newest first, with what is missing for more of them.
     *
     * - No data yet: `insights` empty and `missingInputs` naming what would help (or empty when nothing can help yet).
     * - Missing permission or disconnected source: the insights computed from the other sources, plus one
     *   [MissingInput] per missing source with the route that fixes it.
     * - While the weekly computation runs: the last stored insights with `computing = true`.
     * Never contains dismissed or archived insights.
     */
    public fun insightFeed(): Flow<InsightFeed>

    /**
     * One insight with its method, chart data and the saved AI interpretation, if any. Emits `null` when the insight
     * does not exist (deleted, archived, or an unknown id from an old notification).
     */
    public fun insightDetail(insightId: String): Flow<InsightDetail?>

    /**
     * Builds, without sending anything, the exact request "Interpret with AI" would send for [insightId]: purpose,
     * data categories, date range and a plain-text preview of the payload (it goes through the same consent gate and
     * minimization as the real request). The preview is kept by the implementation under `previewId` so that
     * [interpret] sends exactly what the user saw.
     *
     * Failures: [AppError.AuthenticationRequired] or
     * [AppError.TokenExpired] (ChatGPT not connected), [AppError.ConsentViolation]
     * (a category of the insight is not allowed for AI; nothing is sent), [AppError.NotEligible]
     * or [AppError.RateLimited] (plan or usage limit), [AppError.UnsupportedFeature]
     * (AI interpretation is not available in this build), [AppError.ValidationError] with code `insight_not_found`
     * for an unknown insight.
     */
    public suspend fun interpretationPreview(insightId: String): Outcome<InterpretationPreview>

    /**
     * Sends the request prepared by [interpretationPreview] and streams the answer. Emits [InterpretationEvent.Delta]
     * for each new piece of text in order, then exactly one [InterpretationEvent.Completed] (the answer was checked by
     * the AI text policy and saved with the categories it was made from) or one [InterpretationEvent.Failed], and
     * completes. Cancelling the collection cancels the request; a cancelled or failed answer is never saved.
     *
     * Failure errors: the ones of [interpretationPreview] plus [AppError.NetworkUnavailable]
     * (offline), [AppError.RemoteServerError], [AppError.ParsingError]
     * (the answer failed the AI text policy) and [AppError.ValidationError] with code
     * `preview_expired` when `previewId` is unknown.
     */
    public fun interpret(previewId: String): Flow<InterpretationEvent>
}

/**
 * The list screen's data.
 *
 * @property insights active insights, newest first.
 * @property interpreted ids of insights that have a saved AI interpretation.
 * @property missingInputs sources whose absence limits the insights, each with its fix.
 * @property computing true while the insight computation runs.
 * @property zone the user's zone (`AgentleClock.zone()`) for every date on the screen.
 */
public data class InsightFeed(
    val insights: List<Insight>,
    val interpreted: Set<String> = emptySet(),
    val missingInputs: List<MissingInput> = emptyList(),
    val computing: Boolean = false,
    val zone: TimeZone,
)

/** A data source whose absence limits what the screen can show, and the one route that fixes it. */
public data class MissingInput(val category: DataCategory, val reason: MissingInputReason, val fix: AppRoute)

/** Why a source is missing. */
public enum class MissingInputReason {
    /** A runtime permission or special access is not granted. */
    PERMISSION_MISSING,

    /** The account or device that provides the data is not connected. */
    SOURCE_NOT_CONNECTED,

    /** The source is connected but has too few days of data yet. */
    NOT_ENOUGH_DATA,
}

/**
 * One insight with everything the detail screen shows.
 *
 * @property method how the finding was computed; null for descriptive insights and AI interpretations.
 * @property chart data for the simple chart; null when the insight has nothing to plot.
 * @property interpretation the saved AI interpretation (a completed answer only).
 * @property zone the user's zone for every date on the screen.
 */
public data class InsightDetail(
    val insight: Insight,
    val method: InsightMethod? = null,
    val chart: InsightChart? = null,
    val interpretation: SavedInterpretation? = null,
    val zone: TimeZone,
)

/**
 * The statistical method of a local insight (R10 §14.4), shown as for example "permutation test, q = 0.03".
 *
 * @property sampleSize nights or days compared.
 */
public data class InsightMethod(val test: InsightTest, val qValue: Double? = null, val pValue: Double? = null, val sampleSize: Int? = null)

/** Methods the insight engine uses. */
public enum class InsightTest {
    /** Permutation test of a rate difference with false-discovery-rate control. */
    PERMUTATION_TEST,

    /** Stratified (Mantel-Haenszel) rate difference with a confidence interval. */
    STRATIFIED_RATE_DIFFERENCE,

    /** Bootstrap interval of a difference of means or medians. */
    BOOTSTRAP_INTERVAL,

    /** A descriptive summary without a test. */
    DESCRIPTIVE,
}

/** What the detail chart draws. Labels come from the insight engine's fixed, non-causal templates. */
public sealed interface InsightChart {
    public val unit: ChartUnit

    /** Two or more bars to compare, for example "nights with 45+ minutes of late screen time" and "other nights". */
    public data class Comparison(val bars: List<ChartBar>, override val unit: ChartUnit) : InsightChart

    /** One value per day of the insight's period; a `null` value is a day without data. */
    public data class DailySeries(val points: List<DailyPoint>, override val unit: ChartUnit) : InsightChart
}

public data class ChartBar(val label: String, val value: Double)

public data class DailyPoint(val date: LocalDate, val value: Double?)

/** Unit of chart values. */
public enum class ChartUnit { PERCENT, MINUTES, STEPS, COUNT, BEATS_PER_MINUTE }

/**
 * Exactly what "Interpret with AI" would send.
 *
 * @property previewId handle for [InsightsPort.interpret]; it names the prepared request, not the insight.
 * @property categories every data category the request contains (each one allowed by the user's AI consent).
 * @property previewText the payload as plain text; personal data, shown with sensitive-content protection.
 */
public data class InterpretationPreview(
    val previewId: String,
    val purpose: AiPurpose,
    val categories: Set<DataCategory>,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val previewText: String,
)

/** Events of one interpretation request, in order. */
public sealed interface InterpretationEvent {
    /** The next piece of the answer (append it). Untrusted model text: render as plain text only. */
    public data class Delta(val text: String) : InterpretationEvent

    /** The full answer passed the AI text policy and was saved. */
    public data class Completed(val interpretation: SavedInterpretation) : InterpretationEvent

    /** The request ended without an answer; nothing was saved. */
    public data class Failed(val error: AppError) : InterpretationEvent
}

/**
 * A completed AI interpretation. [text] is untrusted model output: plain text only, never parsed as markup, links or
 * commands. [categories] are the data categories the request contained.
 */
public data class SavedInterpretation(val text: String, val categories: Set<DataCategory>, val createdAt: Instant)
