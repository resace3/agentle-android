package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.time.AgentleClock
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.codec.Schemas
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.rule.Condition
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The verdict of [RuleValidator.revalidate] for one stored rule (integrator correction: re-validation after an app
 * upgrade). A rule that is no longer valid is paused with a notice ([dev.agentle.jitai.dsl.model.JitaiLifecycle.pauseIfInvalid]).
 *
 * @property catalogVersion the [RealtimeFeatureCatalog.VERSION] the verdict was computed with.
 */
public data class StoredRuleVerdict(val jitaiId: String, val catalogVersion: Int, val errors: List<ValidationIssue>) {
    val isValid: Boolean get() = errors.isEmpty()

    val codes: List<IssueCode> get() = errors.map { it.code }
}

/**
 * The one validator for every rule source (R10 §11): model replies, AI-discovered proposals, rule-editor output and
 * stored definitions. It runs the fixed pipeline S0-S9 of R10 §11.1 and never throws: an unexpected failure is reported
 * as E099 with the stage only (no exception text, red team privacy-ai-11). Model output is data: it is decoded into
 * the closed classes of this module and never executed (R10 §11.7).
 */
public object RuleValidator {
    /** Validates [request] against the catalog, the origin's limits and [context]. */
    public fun validate(request: ValidationRequest, context: ValidationContext): ValidationReport {
        val run = ValidationRun(request, context)
        return try {
            run.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            run.internalError(ignored)
        } catch (ignored: StackOverflowError) {
            run.internalError(ignored)
        }
    }

    /** A model reply of the natural-language path (origin AI, `createdBy = AI_NATURAL_LANGUAGE`). */
    public fun validateProposalText(
        text: String,
        context: ValidationContext,
        nlRequest: String? = null,
        appSelections: Map<String, String> = emptyMap(),
    ): ValidationReport = validate(
        ValidationRequest(ValidationInput.ProposalText(text), appSelections = appSelections, nlRequest = nlRequest),
        context,
    )

    /** A definition from the rule editor or storage; [origin] null means "from `createdBy`". */
    public fun validateDefinition(definition: JitaiDefinition, context: ValidationContext, origin: RuleOrigin? = null): ValidationReport =
        validate(ValidationRequest(ValidationInput.Definition(definition), origin = origin), context)

    /**
     * Re-validation of a stored rule (integrator correction jitai-correctness, R10 §7.5): a pure function of the
     * definition and the catalog of this build. It runs S1-S6 for errors only, without the device state: other rules
     * are not consulted (a `jitai` arg or suppression target only has to be a lowercase UUID), and the media library is
     * checked only when [mediaLibrary] is given. Call it at approval and for every stored rule after an app upgrade;
     * rules that fail are paused with a notice.
     */
    public fun revalidate(definition: JitaiDefinition, mediaLibrary: MediaLibrary? = null): StoredRuleVerdict {
        val sink = IssueSink()
        try {
            storedChecks(definition, mediaLibrary, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            sink.add(IssueCode.E099, Stage.S6, "", mapOf("stage" to Stage.S6.name))
        } catch (ignored: StackOverflowError) {
            sink.add(IssueCode.E099, Stage.S6, "", mapOf("stage" to Stage.S6.name))
        }
        return StoredRuleVerdict(definition.id, RealtimeFeatureCatalog.VERSION, sink.sorted(IssueSeverity.ERROR))
    }

    private fun storedChecks(definition: JitaiDefinition, mediaLibrary: MediaLibrary?, sink: IssueSink) {
        val origin = RuleOrigin.of(definition.createdBy)
        if (tooDeep(sink, "", origin, definition.conditions, definition.contextRequirements)) return
        val text = RuleCodec.encodeDefinition(definition)
        val decoded = RuleCodec.decodeInto(text, Schemas.definition, JitaiDefinition.serializer(), sink, Stage.S4) ?: return
        val context = ValidationContext(clock = FixedClock(decoded.modifiedAt), mediaLibrary = mediaLibrary ?: MediaLibrary.EMPTY)
        val scope = CheckScope(existingRulesKnown = false, mediaLibrary = mediaLibrary)
        RuleChecks(sink, RuleView.of(decoded), origin, context, scope).check()
    }

    /**
     * Guards decoded inputs before they are encoded: a tree deeper than [MAX_ENCODED_DEPTH] is rejected with E020
     * (text inputs are bounded by the reader's depth limit, E003).
     */
    internal fun tooDeep(
        sink: IssueSink,
        base: String,
        origin: RuleOrigin,
        conditions: Condition?,
        contextRequirements: Condition?,
    ): Boolean {
        val limits = RuleLimits.of(origin)
        var deep = false
        for ((name, tree) in listOf(RuleChecks.CONDITIONS to conditions, RuleChecks.CONTEXT_REQUIREMENTS to contextRequirements)) {
            if (tree == null) continue
            val depth = RuleAnalysis.depth(tree)
            if (depth > MAX_ENCODED_DEPTH) {
                val params = mapOf("tree" to name, "depth" to depth.toString(), "max" to limits.maxDepth.toString())
                sink.add(IssueCode.E020, Stage.S4, "$base/$name", params)
                deep = true
            }
        }
        return deep
    }

    /** Deepest tree a decoded input may have before encoding (the strict reader caps text inputs far lower). */
    internal const val MAX_ENCODED_DEPTH: Int = 64

    /** Log component of validator events. */
    internal const val LOG_COMPONENT: String = "jitai.dsl.validator"
}

/** The time source of [RuleValidator.revalidate]: a fixed instant, so the verdict depends on the definition only. */
internal class FixedClock(private val instant: Instant) : AgentleClock {
    override val wall: Clock = object : Clock {
        override fun now(): Instant = instant
    }

    override fun zone(): TimeZone = TimeZone.UTC

    override fun elapsed(): Duration = Duration.ZERO
}
