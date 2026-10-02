package dev.agentle.jitai.engine.testing

import dev.agentle.core.common.Logger
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.EnginePorts
import dev.agentle.jitai.engine.JitaiEngine
import dev.agentle.jitai.engine.ports.EngineSettings
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A complete engine on in-memory ports and a [VirtualDeviceClock]. [engine] is one process; [restart] builds a new
 * engine on the same stored state, like a process that died and came back.
 */
public class EngineHarness(
    start: Instant,
    zone: TimeZone,
    definitions: List<JitaiDefinition> = emptyList(),
    settings: EngineSettings = EngineSettings(),
) {
    public val clock: VirtualDeviceClock = VirtualDeviceClock(start, zone)
    public val repository: InMemoryJitaiRepository = InMemoryJitaiRepository(definitions)
    public val store: InMemoryDecisionStore = InMemoryDecisionStore(repository::definitionState)
    public val delivery: FakeDeliveryPort = FakeDeliveryPort()
    public val settings: FakeSettingsPort = FakeSettingsPort(settings)
    public val features: FakeFeatureResolver = FakeFeatureResolver(clock::zone)
    public val events: InMemoryTriggerEventFeed = InMemoryTriggerEventFeed { store.markDirty() }
    public val aiTexts: InMemoryAiTextPool = InMemoryAiTextPool()
    public val nonces: SeededNonceSource = SeededNonceSource()
    public val ports: EnginePorts = EnginePorts(repository, store, delivery, this.settings, features, events, aiTexts)

    public var engine: JitaiEngine = newEngine()
        private set

    /** A new process on the same database, notification shade and settings. */
    public fun restart(logger: Logger = Logger.NONE): JitaiEngine {
        engine = newEngine(logger)
        return engine
    }

    public fun newEngine(logger: Logger = Logger.NONE): JitaiEngine = JitaiEngine(ports, clock, clock, nonces, logger)
}
