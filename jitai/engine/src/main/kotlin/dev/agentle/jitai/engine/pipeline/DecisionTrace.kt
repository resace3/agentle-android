package dev.agentle.jitai.engine.pipeline

import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.TreeTrace
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.gates.GateCheck
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Everything that explains one decision row (R10 §6.7): the two rule trees, every gate (all are evaluated, the first
 * failure is the recorded reason), the SUPPRESSION rules that blocked it and the micro-randomization draw. Drives the
 * "Why did I get this?" and "Why not?" screens.
 */
@Serializable
public data class DecisionTrace(
    val conditions: TreeTrace? = null,
    val context: TreeTrace? = null,
    val gates: List<GateCheck>? = null,
    val suppressedBy: List<String>? = null,
    val reason: ReasonCode? = null,
    val randProbability: Double? = null,
    val randDraw: Double? = null,
)

/** The part of a trace kept after 90 days (R10 §8.8): root results and the recorded reason. */
@Serializable
public data class DecisionTraceSummary(val conditions: Tri? = null, val context: Tri? = null, val reason: ReasonCode? = null)

/**
 * JSON form of [DecisionTrace], capped at [MAX_BYTES] (R10 §6.7). Truncation is deterministic: first each tree is cut to
 * its root and first failing branch, then to its root alone, then passing gates are dropped; the same input always gives
 * the same output.
 */
public object TraceCodec {
    public const val MAX_BYTES: Int = 8 * 1024

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    public fun encode(trace: DecisionTrace, maxBytes: Int = MAX_BYTES): String {
        val steps = sequenceOf<(DecisionTrace) -> DecisionTrace>(
            { it },
            { it.copy(conditions = it.conditions?.let(::branch), context = it.context?.let(::branch)) },
            { it.copy(conditions = it.conditions?.let(::rootOnly), context = it.context?.let(::rootOnly)) },
            { it.copy(gates = it.gates?.filter { gate -> !gate.passed }, suppressedBy = it.suppressedBy?.take(1)) },
        )
        var current = trace
        var encoded = ""
        for (step in steps) {
            current = step(current)
            encoded = json.encodeToString(DecisionTrace.serializer(), current)
            if (encoded.toByteArray(Charsets.UTF_8).size <= maxBytes) return encoded
        }
        return json.encodeToString(DecisionTraceSummary.serializer(), summaryOf(trace))
    }

    /** The trace of [text], or null when it is not a full trace (for example a summary after retention). */
    public fun decode(text: String): DecisionTrace? = try {
        json.decodeFromString(DecisionTrace.serializer(), text)
    } catch (expected: SerializationException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    public fun summary(trace: DecisionTrace): String = json.encodeToString(DecisionTraceSummary.serializer(), summaryOf(trace))

    private fun summaryOf(trace: DecisionTrace) = DecisionTraceSummary(trace.conditions?.result, trace.context?.result, trace.reason)

    private fun branch(tree: TreeTrace): TreeTrace = if (tree.truncated) tree else tree.firstFailingBranch()

    private fun rootOnly(tree: TreeTrace): TreeTrace = TreeTrace(tree.result, tree.nodes.take(1), truncated = true)
}
