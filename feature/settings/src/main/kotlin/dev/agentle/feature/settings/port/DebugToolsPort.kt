package dev.agentle.feature.settings.port

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.EventType
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/** A selectable debug option: [id] is what the port expects back, [label] a developer-facing name. */
public data class DebugOption(val id: String, val label: String)

/** Faults the fake build can force (docs/ARCHITECTURE.md §4, spec §65). */
public enum class FailureKind {
    DATABASE_EXCEPTION,
    GOOGLE_HEALTH_HTTP_ERROR,
    OPENAI_HTTP_ERROR,
    EXPIRED_TOKEN,
    NETWORK_LOSS,
    INVALID_AI_OUTPUT,
    PERMISSION_LOSS,
}

/** What the debug panel shows. */
public data class DebugToolsState(
    val wearableScenarios: List<DebugOption>,
    val selectedWearableScenario: String?,
    val chatGptScenarios: List<DebugOption>,
    val selectedChatGptScenario: String?,
    /** Event types the synthetic event generator can produce. */
    val syntheticEventTypes: List<EventType>,
    /** JITAIs the trigger simulator can fire. */
    val jitais: List<DebugOption>,
    /** The app clock's current time (with the override applied). */
    val appTime: Instant,
    /** The offset added to the real clock; null when there is no override. */
    val timeOffset: Duration?,
    /** Connectors "force sync" can run. */
    val syncTargets: List<DebugOption>,
    /** Unique works "force worker" can run. */
    val workers: List<DebugOption>,
    val activeFailures: Set<FailureKind>,
)

/**
 * Debug tools (`AppRoute.DebugPanel`, spec §56). The wiring team binds a working implementation only in fake debug
 * builds; every other build binds one whose [available] is false, and the screen then renders "not available" and
 * calls nothing else. Production builds must never expose these controls.
 */
public interface DebugToolsPort {
    /** Whether the debug tools exist in this build. Constant for the life of the process. */
    public val available: Boolean

    /** The tools' state; emits again after every action. Emits `Failure(UnsupportedFeature)` when not [available]. */
    public val state: Flow<Outcome<DebugToolsState>>

    /** Selects the fake Google Health server scenario by id. Failures: `ValidationError` (unknown id). */
    public suspend fun selectWearableScenario(id: String): Outcome<Unit>

    /** Selects the fake ChatGPT server scenario by id. Failures: `ValidationError` (unknown id). */
    public suspend fun selectChatGptScenario(id: String): Outcome<Unit>

    /** Inserts [count] synthetic events of [type] around now; returns how many were inserted. Failures: `DatabaseError`. */
    public suspend fun generateSyntheticEvents(type: EventType, count: Int): Outcome<Int>

    /** Generates the deterministic 90-day synthetic user, anchored at the real now; returns the events inserted. */
    public suspend fun generateDataset(days: Int): Outcome<Int>

    /** Fires the trigger of JITAI [jitaiId] through the engine; returns the decision state code (e.g. `DELIVERED`). */
    public suspend fun simulateJitaiTrigger(jitaiId: String): Outcome<String>

    /** Moves the app clock by [offset] from the real clock. */
    public suspend fun setTimeOffset(offset: Duration): Outcome<Unit>

    /** Removes the time override. */
    public suspend fun clearTimeOffset(): Outcome<Unit>

    /** Runs connector [connectorId]'s sync now. Failures: the sync's own `AppError`. */
    public suspend fun forceSync(connectorId: String): Outcome<Unit>

    /** Runs unique work [uniqueName] now. */
    public suspend fun forceWorker(uniqueName: String): Outcome<Unit>

    /** Deletes every row of the database (keeps the schema and the keys). Failures: `DatabaseError`. */
    public suspend fun clearDatabase(): Outcome<Unit>

    /** Turns forced failure [kind] on or off. */
    public suspend fun setFailureInjection(kind: FailureKind, enabled: Boolean): Outcome<Unit>
}
