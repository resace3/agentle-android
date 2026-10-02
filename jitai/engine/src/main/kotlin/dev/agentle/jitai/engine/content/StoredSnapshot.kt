package dev.agentle.jitai.engine.content

import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.engine.decision.DecisionContent
import dev.agentle.jitai.engine.eval.TraceValue
import dev.agentle.jitai.engine.eval.TreeTrace
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.ports.EvalLogEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * One stored snapshot value with its data category (`FeatureDefinition.category`, null for clock and calendar features;
 * jitai-correctness-19). Once the user deletes the category, [value] is gone and [deleted] is set.
 */
@Serializable
public data class StoredValue(val category: DataCategory? = null, val value: FeatureValue? = null, val deleted: Boolean = false)

/**
 * The snapshot subset a decision row stores (`snapshotJson`, R10 §8.3), every value tagged with its data category so a
 * category deletion can scrub it ([CategoryScrubber]).
 */
@Serializable
public data class StoredSnapshot(val at: Instant, val zoneId: String, val catalogVersion: Int, val values: Map<String, StoredValue>) {
    /** The values still present, as a [FeatureSnapshot] (rendering reads placeholders from it; deleted values are absent). */
    public fun toFeatureSnapshot(): FeatureSnapshot = FeatureSnapshot(
        at = at,
        zoneId = zoneId,
        values = values.mapNotNull { (key, stored) -> stored.value?.let { key to it } }.toMap(),
        catalogVersion = catalogVersion,
    )
}

/** Encoding of [StoredSnapshot] and the category of a reference key. */
public object StoredSnapshots {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** The data category of the feature a snapshot key (`featureId` or `featureId{args}`) names. */
    public fun categoryOf(refKey: String): DataCategory? = RealtimeFeatureCatalog[refKey.substringBefore('{')]?.category

    public fun of(snapshot: FeatureSnapshot): StoredSnapshot = StoredSnapshot(
        at = snapshot.at,
        zoneId = snapshot.zoneId,
        catalogVersion = snapshot.catalogVersion,
        values = snapshot.values.mapValues { (key, value) -> StoredValue(categoryOf(key), value) },
    )

    public fun encode(snapshot: StoredSnapshot): String = json.encodeToString(StoredSnapshot.serializer(), snapshot)

    /** The stored snapshot in [text], or null when it is not one. */
    public fun decode(text: String): StoredSnapshot? = try {
        json.decodeFromString(StoredSnapshot.serializer(), text)
    } catch (expected: SerializationException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }
}

/**
 * Pure scrubbing of stored snapshots and traces for deleted data categories (jitai-correctness-19). ANDROID-DATA calls
 * these inside the transaction that deletes a category's data, for every decision row and evaluation-log entry: each
 * value of a deleted category becomes the deleted marker; everything else (structure, results, reasons, gate details)
 * stays, so "Why did I get this?" still explains which rule fired without revealing the deleted data.
 */
public object CategoryScrubber {
    /** [text] (a [StoredSnapshot]) with the values of [deleted] categories replaced; unchanged when it is not one. */
    public fun scrubSnapshot(text: String, deleted: Set<DataCategory>): String {
        val snapshot = StoredSnapshots.decode(text) ?: return text
        val scrubbed = scrub(snapshot, deleted) ?: return text
        return StoredSnapshots.encode(scrubbed)
    }

    /** [text] (a full decision trace) with leaf values of [deleted] categories replaced; summaries are returned unchanged. */
    public fun scrubTrace(text: String, deleted: Set<DataCategory>): String {
        val trace = TraceCodec.decode(text) ?: return text
        val conditions = trace.conditions?.let { scrub(it, deleted) }
        val context = trace.context?.let { scrub(it, deleted) }
        if (conditions == trace.conditions && context == trace.context) return text
        return TraceCodec.encode(trace.copy(conditions = conditions, context = context))
    }

    /**
     * A decision row's content with snapshot and trace scrubbed. The snapshot hash is dropped when a value was scrubbed,
     * because a hash over few, low-entropy values could be brute-forced back.
     */
    public fun scrubContent(content: DecisionContent, deleted: Set<DataCategory>): DecisionContent {
        if (deleted.isEmpty()) return content
        val snapshot = content.snapshotJson?.let { scrubSnapshot(it, deleted) }
        val changed = snapshot != content.snapshotJson
        return content.copy(
            snapshotJson = snapshot,
            snapshotHash = if (changed) null else content.snapshotHash,
            traceJson = content.traceJson?.let { scrubTrace(it, deleted) },
        )
    }

    /** An evaluation-log entry with its trace scrubbed. */
    public fun scrubEvalLog(entry: EvalLogEntry, deleted: Set<DataCategory>): EvalLogEntry =
        entry.copy(traceJson = entry.traceJson?.let { scrubTrace(it, deleted) })

    private fun scrub(snapshot: StoredSnapshot, deleted: Set<DataCategory>): StoredSnapshot? {
        val hit = snapshot.values.any { (_, stored) -> stored.category in deleted && !stored.deleted }
        if (!hit) return null
        return snapshot.copy(
            values = snapshot.values.mapValues { (_, stored) ->
                if (stored.category in deleted) StoredValue(stored.category, value = null, deleted = true) else stored
            },
        )
    }

    private fun scrub(tree: TreeTrace, deleted: Set<DataCategory>): TreeTrace {
        if (tree.nodes.none { it.category in deleted && it.value != null && it.value != TraceValue.DELETED }) return tree
        return tree.copy(nodes = tree.nodes.map { node -> if (node.category in deleted) node.copy(value = TraceValue.DELETED) else node })
    }
}
