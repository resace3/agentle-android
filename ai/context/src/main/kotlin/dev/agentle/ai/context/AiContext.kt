package dev.agentle.ai.context

import dev.agentle.ai.api.AiProvider
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import java.security.SecureRandom
import java.util.Random

/** Build-time choices of the AI context layer. */
public data class AiContextOptions(
    /** Whether Health Connect data may be sent at all. It is false in Play builds (R04 section 3.8, control 10). */
    val healthConnectToAi: Boolean = false,
    val instructions: AiInstructionSet = AiInstructionSet(),
    val random: Random = SecureRandom(),
)

/**
 * The wiring of the AI context layer for `:app`. It creates one consent repository and one raw-event ledger, then the
 * [engine] and the [guard], each with its own sharing policy instance. The real provider is created by
 * [providerFactory], which receives the guard as its send verifier, so the provider is reachable only through [guard].
 */
public class AiContext(
    dataSource: AiContextDataSource,
    consentStore: AiConsentStore,
    account: AiAccountSource,
    auditLog: AiAuditLog,
    clock: AgentleClock,
    providerFactory: (AiSendVerifier) -> AiProvider,
    options: AiContextOptions = AiContextOptions(),
    logger: Logger = Logger.NONE,
) {
    public val consent: AiConsentRepository = AiConsentRepository(consentStore, account, clock, logger)

    public val rawEvents: RawEventsConsentLedger = RawEventsConsentLedger(clock, options.random)

    private val sources = SourceFamilyPolicy(options.healthConnectToAi)

    public val engine: ContextSelectionEngine = ContextSelectionEngine(
        dataSource = dataSource,
        consent = consent,
        account = account,
        clock = clock,
        rawEventsLedger = rawEvents,
        policy = DenyByDefaultSharingPolicy(consent.currentVersion, sources),
        instructions = options.instructions,
        logger = logger,
        random = options.random,
    )

    public val guard: EgressGuard = EgressGuard(
        providerFactory = providerFactory,
        consent = consent,
        account = account,
        auditLog = auditLog,
        clock = clock,
        rawEventsLedger = rawEvents,
        sourcePolicy = sources,
        policy = DenyByDefaultSharingPolicy(consent.currentVersion, sources),
        instructions = options.instructions,
        logger = logger,
    )
}
