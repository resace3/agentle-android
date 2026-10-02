@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.context

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.AiEnvelopeJson
import dev.agentle.ai.api.AiImageResult
import dev.agentle.ai.api.AiProvider
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.ai.api.AiStructuredResult
import dev.agentle.ai.api.AiTextResult
import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.common.flatMap
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.core.time.EngineDay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The only path from the app to an [AiProvider] (docs/ARCHITECTURE.md section 9). The app uses it as its
 * [AiProvider], and the real provider is reachable only through it. Construct the provider with [providerFactory],
 * which receives this guard as the provider's [AiSendVerifier].
 *
 * For every request:
 * 1. Never alter the envelope: one over a limit (text length, items, blocks, the purpose's caps) is refused by the gate,
 *    so the bytes sent are exactly the bytes built and previewed, or nothing (privacy-ai-13).
 * 2. Re-check every block ([EnvelopeGate]) against a fresh consent read and this guard's own [AiSharingPolicy]. Envelopes
 *    older than [ENVELOPE_MAX_AGE], reused envelopes and schemas that are not the purpose's are refused.
 * 3. Background requests need a standing consent, stay within its daily budget and cadence (counted from the audit
 *    log), and are listed in the user-visible log (privacy-ai-01).
 * 4. Write the `ai_request` record (metadata only) before handing the request to the provider. If the record cannot be
 *    written, nothing is sent.
 * 5. While the provider runs, a consent change cancels the call and discards its answer.
 * 6. Immediately before its network send, the provider calls [verifyBeforeSend]. It accepts only an envelope in flight
 *    here, with the digest of the exact input. It reads the consent store again, independently of every policy object,
 *    and requires an explicit grant for every category the body carries (privacy-ai-03, round 4 correction 4).
 *
 * Failures are typed and fail closed. Logs and records hold codes and metadata, never a value.
 */
public class EgressGuard(
    providerFactory: (AiSendVerifier) -> AiProvider,
    private val consent: AiConsentRepository,
    private val account: AiAccountSource,
    private val auditLog: AiAuditLog,
    private val clock: AgentleClock,
    private val rawEventsLedger: RawEventsConsentLedger,
    private val sourcePolicy: SourceFamilyPolicy = SourceFamilyPolicy(),
    private val policy: AiSharingPolicy = DenyByDefaultSharingPolicy(consent.currentVersion, sourcePolicy),
    private val instructions: AiInstructionSet = AiInstructionSet(),
    private val logger: Logger = Logger.NONE,
    /** Salt of the account hash in audit records; the app passes a per-install secret so hashes stay comparable. */
    private val accountSalt: String = RequestIds.next(SecureRandom()),
) : AiProvider,
    AiSendVerifier {
    private class InFlight(val envelope: AiRequestEnvelope) {
        @Volatile
        var verified: Boolean = false
    }

    private val inFlight = ConcurrentHashMap<String, InFlight>()
    private val used = LinkedHashSet<String>()
    private val usedLock = Any()
    private val backgroundMutex = Mutex()
    private val sendTimeCheck = SendTimeConsentCheck(consent, account, sourcePolicy.healthConnectToAi)
    private val delegate: AiProvider = providerFactory(this)

    override val id: String get() = delegate.id

    override val state: StateFlow<AiProviderState> get() = delegate.state

    override fun capabilities(): AiCapabilities = delegate.capabilities()

    override suspend fun analyze(request: AiRequestEnvelope): Outcome<AiTextResult> = send(request, null) { delegate.analyze(it) }

    override suspend fun generateStructuredResult(request: AiRequestEnvelope, schema: OutputSchema): Outcome<AiStructuredResult> =
        send(request, schema) { delegate.generateStructuredResult(it, schema) }

    override suspend fun generateImage(request: AiRequestEnvelope): Outcome<AiImageResult> = send(request, null) {
        delegate.generateImage(it)
    }

    override suspend fun verifyBeforeSend(envelope: AiRequestEnvelope, sentInputSha256: String): Outcome<Unit> {
        val flight = inFlight[envelope.requestId]
        val verdict = when {
            flight == null -> sendTimeCheck.violation(envelope, GateCodes.NOT_IN_FLIGHT)

            flight.envelope.inputSha256 != sentInputSha256 || envelope.inputSha256 != sentInputSha256 ->
                sendTimeCheck.violation(envelope, GateCodes.DIGEST_MISMATCH)

            // The digest covers every sent byte; the lineage checked is the one admitted here.
            else -> sendTimeCheck.check(flight.envelope)
        }
        if (verdict is Outcome.Success) flight?.verified = true
        if (verdict is Outcome.Failure) {
            logger.w(
                COMPONENT,
                "send-time check refused",
                verdict.error,
                mapOf(
                    "requestId" to envelope.requestId,
                    "reason" to reasonOf(verdict.error),
                ),
            )
        }
        return verdict
    }

    private suspend fun <T> send(
        request: AiRequestEnvelope,
        schema: OutputSchema?,
        call: suspend (AiRequestEnvelope) -> Outcome<T>,
    ): Outcome<T> {
        // Never altered here: the bytes sent are the bytes built and previewed, or nothing (privacy-ai-13).
        val outgoing = request
        val flight = InFlight(outgoing)
        val createdAt = when (val admitted = admit(outgoing, schema).flatMap { decision -> begin(outgoing, decision, flight) }) {
            is Outcome.Success -> admitted.value

            is Outcome.Failure -> {
                // A reused envelope keeps the record of its first use.
                if (!isReuse(admitted.error)) finish(outgoing, admitted, verified = false, createdAt = clock.now())
                return admitted
            }
        }
        val result = try {
            watchConsent { guarded(call, outgoing) }
        } finally {
            inFlight.remove(outgoing.requestId, flight)
        }
        val final = if (result is Outcome.Success && !flight.verified) Outcome.Failure(AppError.Unexpected(SEND_NOT_VERIFIED)) else result
        finish(outgoing, final, flight.verified, createdAt)
        return final
    }

    /** Age, reuse, schema and the gate against a fresh consent read. */
    private suspend fun admit(envelope: AiRequestEnvelope, schema: OutputSchema?): Outcome<GateDecision> {
        val spec = PurposePolicy.spec(envelope.purpose)
        val age = clock.now() - envelope.createdAt
        val expected = spec.outputSchema
        val failure = when {
            age.isNegative() || age > ENVELOPE_MAX_AGE -> AppError.ValidationError(listOf(ENVELOPE_EXPIRED))

            isUsed(envelope.requestId) -> AppError.ValidationError(listOf(ENVELOPE_REUSED))

            schema != null && expected != null && (schema.name != expected.name || schema.version != expected.version) ->
                AppError.ValidationError(listOf(SCHEMA_NOT_FOR_PURPOSE))

            else -> null
        }
        if (failure != null) return Outcome.Failure(failure)
        return decide(envelope, spec).flatMap { decision -> EnvelopeGate.check(envelope, decision).flatMap { Outcome.Success(decision) } }
    }

    private suspend fun decide(envelope: AiRequestEnvelope, spec: PurposeSpec): Outcome<GateDecision> {
        val snapshot = when (val state = consent.read()) {
            is ConsentState.Readable -> state.snapshot
            is ConsentState.Unreadable -> return sendTimeCheck.violation(envelope, state.code)
        }
        val sub = account.activeAccountSub()
        val standing = if (envelope.mode == AiRequestMode.BACKGROUND) policy.standingConsent(snapshot, spec.purpose, sub) else null
        var categories = spec.categories.intersect(policy.allowedCategories(snapshot, spec.purpose, sub))
        var sources = policy.allowedSources()
        if (standing != null) {
            categories = categories.intersect(standing.categories)
            sources = sources.intersect(standing.sourceFamilies)
        }
        val lookback = minOf(spec.lookbackDays, standing?.lookbackDays ?: spec.lookbackDays)
        return Outcome.Success(
            GateDecision(
                spec = spec,
                mode = envelope.mode,
                categories = categories,
                sources = sources,
                rangeLimit = rangeLimit(lookback),
                rawEventsConfirmed = rawEventsLedger.wasConfirmed(envelope.requestId, envelope.purpose),
                standing = standing,
                instructions = instructions.forPurpose(spec.purpose),
            ),
        )
    }

    /** Ranges may start one engine day earlier than a fresh build would (a request built just before 04:00). */
    private fun rangeLimit(lookbackDays: Int): ClosedOpenRange? {
        if (lookbackDays == 0) return null
        val now = clock.now()
        val zone = clock.zone()
        val today = EngineDay.of(now, zone)
        return ClosedOpenRange(EngineDay.bounds(today.minus(DatePeriod(days = lookbackDays + 1)), zone).start, now)
    }

    /** Registers the request, checks background budgets and writes the in-flight record. Returns the record's creation time. */
    private suspend fun begin(envelope: AiRequestEnvelope, decision: GateDecision, flight: InFlight): Outcome<Instant> {
        if (inFlight.putIfAbsent(envelope.requestId, flight) != null) {
            return Outcome.Failure(AppError.ValidationError(listOf(ENVELOPE_REUSED)))
        }
        markUsed(envelope.requestId)
        val written = if (envelope.mode == AiRequestMode.BACKGROUND) {
            backgroundMutex.withLock { allowance(envelope, decision).flatMap { recordInFlight(envelope) } }
        } else {
            recordInFlight(envelope)
        }
        if (written is Outcome.Failure) inFlight.remove(envelope.requestId, flight)
        return written
    }

    private suspend fun recordInFlight(envelope: AiRequestEnvelope): Outcome<Instant> {
        val now = clock.now()
        return writeRecord(stamp(AiRequestRecord.of(envelope, delegate.id, AiRequestStatus.IN_FLIGHT, now))).flatMap {
            Outcome.Success(now)
        }
    }

    /** Daily budget (per engine day in the clock's zone) and cadence of the standing consent, counted from the audit log. */
    private suspend fun allowance(envelope: AiRequestEnvelope, decision: GateDecision): Outcome<Unit> {
        val standing = decision.standing ?: return sendTimeCheck.violation(envelope, GateCodes.NO_STANDING)
        val now = clock.now()
        val zone = clock.zone()
        val dayStart = EngineDay.bounds(EngineDay.of(now, zone), zone).start
        val since = minOf(dayStart, now - standing.cadence)
        // A log that cannot be read cannot prove the budget: nothing is sent.
        val records = when (val read = guardedCall { auditLog.records(envelope.purpose, AiRequestMode.BACKGROUND, since) }) {
            is Outcome.Success -> read
            is Outcome.Failure -> Outcome.Failure(AppError.DatabaseError(AUDIT_UNAVAILABLE))
        }
        return records.flatMap { list ->
            val sent = list.filter { it.status.mayHaveBeenSent && it.requestId != envelope.requestId }
            when {
                sent.count { it.createdAt >= dayStart } >= standing.dailyBudget -> Outcome.Failure(AppError.NotEligible(DAILY_BUDGET))
                sent.any { now - it.createdAt < standing.cadence } -> Outcome.Failure(AppError.NotEligible(CADENCE))
                else -> Outcome.Success(Unit)
            }
        }
    }

    private suspend fun writeRecord(record: AiRequestRecord): Outcome<Unit> = guardedCall { auditLog.record(record) }.let { written ->
        if (written is Outcome.Failure) Outcome.Failure(AppError.DatabaseError(AUDIT_UNAVAILABLE)) else written
    }

    private suspend fun <T> guardedCall(block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (expected: Exception) {
        Outcome.Failure(AppError.Unexpected(expected::class.simpleName))
    }

    private suspend fun <T> guarded(call: suspend (AiRequestEnvelope) -> Outcome<T>, envelope: AiRequestEnvelope): Outcome<T> =
        guardedCall { call(envelope) }

    /** Runs [call]; a consent change before it returns cancels it and gives `AppError.Cancelled`. */
    private suspend fun <T> watchConsent(call: suspend () -> Outcome<T>): Outcome<T> = coroutineScope {
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            consent.changes.catch { emit(Unit) }.firstOrNull() ?: awaitCancellation()
        }
        val work = async { call() }
        select<Outcome<T>> {
            work.onAwait { result ->
                changed.cancel()
                result
            }
            changed.onAwait {
                work.cancel()
                Outcome.Failure(AppError.Cancelled(CONSENT_CHANGED))
            }
        }
    }

    private suspend fun finish(envelope: AiRequestEnvelope, result: Outcome<*>, verified: Boolean, createdAt: Instant) {
        val error = (result as? Outcome.Failure)?.error
        val status = when {
            error == null -> AiRequestStatus.SENT

            error is AppError.Cancelled && error.detail == CONSENT_CHANGED -> AiRequestStatus.CANCELLED

            // A provider that answered without its send-time check may have sent: never report it as not sent.
            verified || (error is AppError.Unexpected && error.detail == SEND_NOT_VERIFIED) -> AiRequestStatus.FAILED

            error is AppError.ConsentViolation || error is AppError.ValidationError -> AiRequestStatus.DENIED

            error is AppError.NotEligible && (error.reason == DAILY_BUDGET || error.reason == CADENCE) -> AiRequestStatus.DENIED

            error is AppError.DatabaseError && error.detail == AUDIT_UNAVAILABLE -> AiRequestStatus.DENIED

            else -> AiRequestStatus.NOT_SENT
        }
        val record = stamp(AiRequestRecord.of(envelope, delegate.id, status, createdAt, clock.now()))
            .copy(errorCode = error?.code, reason = error?.let(::reasonOf), sendVerified = verified)
        val written = writeRecord(record)
        logger.log(
            severity = if (error == null) Severity.INFO else Severity.WARN,
            component = COMPONENT,
            message = "ai request finished",
            error = error,
            fields = mapOf(
                "requestId" to envelope.requestId,
                "purpose" to envelope.purpose,
                "mode" to envelope.mode,
                "status" to status,
                "reason" to error?.let(::reasonOf),
                "categories" to envelope.categories.namesSorted().joinToString(","),
                "bytes" to envelope.approximateBytes,
                "recorded" to (written is Outcome.Success),
            ),
        )
    }

    /** Adds the salted account hash; a failing account source leaves it null. */
    private suspend fun stamp(record: AiRequestRecord): AiRequestRecord {
        val sub = guardedCall { Outcome.Success(account.activeAccountSub()) }.let { (it as? Outcome.Success)?.value }
        return record.copy(accountSubHash = sub?.let { AiEnvelopeJson.sha256Hex(accountSalt + "\n" + it) })
    }

    private fun isUsed(requestId: String): Boolean = synchronized(usedLock) { requestId in used }

    private fun isReuse(error: AppError): Boolean = error is AppError.ValidationError && ENVELOPE_REUSED in error.codes

    private fun markUsed(requestId: String) {
        synchronized(usedLock) {
            used += requestId
            while (used.size > MAX_USED_IDS) used.remove(used.first())
        }
    }

    public companion object {
        /** Envelopes are built just before sending; an older one must be rebuilt (never persisted, privacy-ai-03). */
        public val ENVELOPE_MAX_AGE: Duration = 15.minutes

        public const val ENVELOPE_EXPIRED: String = "envelope_expired"
        public const val ENVELOPE_REUSED: String = "envelope_reused"
        public const val SCHEMA_NOT_FOR_PURPOSE: String = "schema_not_for_purpose"
        public const val SEND_NOT_VERIFIED: String = "send_not_verified"
        public const val CONSENT_CHANGED: String = "consent_changed"
        public const val DAILY_BUDGET: String = "background_daily_budget"
        public const val CADENCE: String = "background_cadence"
        public const val AUDIT_UNAVAILABLE: String = "audit_unavailable"

        private const val COMPONENT = "ai.egress"
        private const val MAX_USED_IDS = 512
    }
}

/**
 * The send-time consent check (privacy-ai-03, round 4 correction 4). It reads the consent store itself and decides
 * with its own rules, sharing no policy object with the engine or the guard, so a wrong default there cannot pass here:
 * - the envelope's consent version is the current one;
 * - the store is readable and an account is signed in;
 * - every category the body carries has an explicit grant for the request's purpose, current version and account, and
 *   none is third-party text;
 * - no source family is GH_API, and HEALTH_CONNECT only when the build allows it;
 * - a background request lies within a standing consent of the same version and account.
 */
internal class SendTimeConsentCheck(
    private val consent: AiConsentRepository,
    private val account: AiAccountSource,
    private val healthConnectToAi: Boolean,
) {
    suspend fun check(envelope: AiRequestEnvelope): Outcome<Unit> {
        val version = consent.currentVersion
        if (envelope.consentVersion != version) return violation(envelope, GateCodes.VERSION_CHANGED)
        val state = consent.read()
        val sub = account.activeAccountSub()
        val snapshot = (state as? ConsentState.Readable)?.snapshot
        return when {
            state is ConsentState.Unreadable -> violation(envelope, state.code)
            sub.isNullOrBlank() || snapshot == null -> violation(envelope, GateCodes.NO_ACCOUNT)
            else -> decide(envelope, snapshot, sub, version)
        }
    }

    private fun decide(envelope: AiRequestEnvelope, snapshot: ConsentSnapshot, sub: String, version: Int): Outcome<Unit> {
        val ungranted = envelope.categories.filter { category ->
            category.thirdPartyText ||
                snapshot.grants.none { grant ->
                    grant.category == category && grant.purpose == envelope.purpose && grant.consentVersion == version &&
                        grant.accountSub == sub
                }
        }
        val blockedSources = envelope.sourceFamilies.filter {
            it == SourceFamily.GH_API || (it == SourceFamily.HEALTH_CONNECT && !healthConnectToAi)
        }
        val standingOk = envelope.mode != AiRequestMode.BACKGROUND ||
            snapshot.standing.any { standing ->
                standing.purpose == envelope.purpose &&
                    standing.consentVersion == version &&
                    standing.accountSub == sub &&
                    standing.fields.containsAll(envelope.fields) &&
                    standing.categories.containsAll(envelope.categories) &&
                    standing.sourceFamilies.containsAll(envelope.sourceFamilies)
            }
        return when {
            ungranted.isNotEmpty() -> Outcome.Failure(AppError.ConsentViolation(ungranted.namesSorted(), GateCodes.CATEGORY))

            blockedSources.isNotEmpty() -> Outcome.Failure(
                AppError.ConsentViolation(
                    blockedSources.mapTo(sortedSetOf()) {
                        it.name
                    },
                    GateCodes.SOURCE,
                ),
            )

            !standingOk -> violation(envelope, GateCodes.OUTSIDE_STANDING)

            else -> Outcome.Success(Unit)
        }
    }

    fun violation(envelope: AiRequestEnvelope, code: String): Outcome.Failure =
        Outcome.Failure(AppError.ConsentViolation(envelope.categories.namesSorted(), code))
}
