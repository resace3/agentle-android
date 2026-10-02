package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.time.AgentleClock
import java.security.SecureRandom
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The user's confirmation that one request of [purpose] may include individual events (docs/ARCHITECTURE.md
 * section 9). It comes from [ContextSelectionEngine.confirmRawEvents] when the user confirms on the preview. It can be
 * used for one request only, within [RawEventsConsentLedger.TTL].
 */
public class RawEventsConfirmation internal constructor(public val purpose: AiPurpose, internal val token: String) {
    override fun toString(): String = "RawEventsConfirmation(purpose=$purpose)"
}

/**
 * Raw-event confirmations, kept in memory only. Each token is random and single-use, and it expires after [TTL] of
 * elapsed (monotonic) time. The [ContextSelectionEngine] consumes a token for one request id, and the [EgressGuard]
 * then accepts raw events only for that request id. Share one ledger between the two (see [AiContext]).
 */
public class RawEventsConsentLedger(private val clock: AgentleClock, private val random: Random = SecureRandom()) {
    private val lock = Any()
    private val issued = LinkedHashMap<String, Pending>()
    private val confirmed = LinkedHashMap<String, AiPurpose>()

    private data class Pending(val purpose: AiPurpose, val issuedAt: Duration)

    internal fun issue(purpose: AiPurpose): RawEventsConfirmation {
        val token = RequestIds.next(random)
        synchronized(lock) {
            issued[token] = Pending(purpose, clock.elapsed())
            trim(issued)
        }
        return RawEventsConfirmation(purpose, token)
    }

    /** Uses [confirmation] for [requestId]. True once per confirmation, for its own purpose, before it expires. */
    internal fun consume(confirmation: RawEventsConfirmation, purpose: AiPurpose, requestId: String): Boolean = synchronized(lock) {
        val pending = issued.remove(confirmation.token)
        val valid = pending != null &&
            pending.purpose == purpose &&
            confirmation.purpose == purpose &&
            clock.elapsed() - pending.issuedAt <= TTL
        if (valid) {
            confirmed[requestId] = purpose
            trim(confirmed)
        }
        valid
    }

    /** True if [requestId] consumed a confirmation for [purpose]. */
    public fun wasConfirmed(requestId: String, purpose: AiPurpose): Boolean = synchronized(lock) { confirmed[requestId] == purpose }

    private fun <V> trim(map: LinkedHashMap<String, V>) {
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }

    public companion object {
        /** A confirmation not used within this time is void. */
        public val TTL: Duration = 10.minutes

        private const val MAX_ENTRIES = 64
    }
}

/** Random ids made only of letters (about 99 bits), so the log redactor never mistakes them for numbers. */
internal object RequestIds {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz"
    private const val LENGTH = 22

    fun next(random: Random): String = buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
