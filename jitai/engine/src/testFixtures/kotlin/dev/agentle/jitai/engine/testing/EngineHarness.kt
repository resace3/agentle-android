package dev.agentle.jitai.engine.testing

import dev.agentle.core.common.Logger
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.EnginePorts
import dev.agentle.jitai.engine.JitaiEngine
import dev.agentle.jitai.engine.pipeline.TimerReport
import dev.agentle.jitai.engine.ports.EngineSettings
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A complete engine on in-memory ports and a [VirtualDeviceClock]. [engine] is one process; [restart] builds a new
 * engine on the same stored state, like a process that died and came back. Definition changes delete the JITAI's timer
 * rows, as the repository's own transaction does.
 *
 * @param refreshDailyFeatures wire [dailyFeatures] as the engine's [dev.agentle.jitai.engine.ports.DailyFeatureRefresher].
 */
public class EngineHarness(
    start: Instant,
    zone: TimeZone,
    definitions: List<JitaiDefinition> = emptyList(),
    settings: EngineSettings = EngineSettings(),
    refreshDailyFeatures: Boolean = true,
) {
    public val death: ProcessDeath = ProcessDeath()
    public val clock: VirtualDeviceClock = VirtualDeviceClock(start, zone)
    private var storeRef: InMemoryDecisionStore? = null
    public val repository: InMemoryJitaiRepository = InMemoryJitaiRepository(definitions) { id -> storeRef?.deleteTimersOf(id) }
    public val store: InMemoryDecisionStore = InMemoryDecisionStore(repository::definitionState, death = death).also { storeRef = it }
    public val delivery: FakeDeliveryPort = FakeDeliveryPort(death)
    public val settings: FakeSettingsPort = FakeSettingsPort(settings)
    public val features: FakeFeatureResolver = FakeFeatureResolver(clock::zone)
    public val dailyFeatures: FakeDailyFeatures = FakeDailyFeatures(features)
    public val events: InMemoryTriggerEventFeed = InMemoryTriggerEventFeed { store.markDirty() }
    public val aiTexts: InMemoryAiTextPool = InMemoryAiTextPool()
    public val outcomes: FakeOutcomeData = FakeOutcomeData()
    public val nonces: SeededNonceSource = SeededNonceSource()
    public val ports: EnginePorts = EnginePorts(
        repository = repository,
        store = store,
        delivery = delivery,
        settings = this.settings,
        features = features,
        events = events,
        aiTexts = aiTexts,
        outcomeData = outcomes,
        dailyRefresh = if (refreshDailyFeatures) dailyFeatures else null,
    )

    public var engine: JitaiEngine = newEngine()
        private set

    /** A new process on the same database, notification shade and settings. */
    public fun restart(logger: Logger = Logger.NONE): JitaiEngine {
        death.restart()
        engine = newEngine(logger)
        return engine
    }

    /** Another engine instance on the same ports: a second worker process (no in-process lock is shared). */
    public fun newEngine(logger: Logger = Logger.NONE): JitaiEngine = JitaiEngine(ports, clock, clock, nonces, logger)

    /**
     * The `jitai-timer` work until [end]: runs the timer now, then moves the clock to each reported `nextDueAt` and runs
     * it again while that instant is not after [end]; finally the clock is moved to [end]. Returns every report.
     */
    public suspend fun runTimerUntil(end: Instant, maxRuns: Int = MAX_RUNS): List<TimerReport> {
        val reports = mutableListOf(engine.runTimer().getOrThrow())
        var next = reports.last().nextDueAt
        while (reports.size < maxRuns && next != null && next <= end) {
            clock.advanceTo(next)
            reports += engine.runTimer().getOrThrow()
            next = reports.last().nextDueAt
        }
        check(reports.size < maxRuns) { "the timer did not settle" }
        clock.advanceTo(end)
        return reports
    }

    private companion object {
        const val MAX_RUNS = 500
    }
}
