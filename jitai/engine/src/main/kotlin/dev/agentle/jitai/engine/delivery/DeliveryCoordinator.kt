package dev.agentle.jitai.engine.delivery

import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.outcomeOf
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.EnginePorts
import dev.agentle.jitai.engine.content.ContentRef
import dev.agentle.jitai.engine.content.ContentRenderer
import dev.agentle.jitai.engine.content.DefaultPlaceholderFormatter
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.RecoveryReport
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.PooledText
import dev.agentle.jitai.engine.ports.PostResult
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import dev.agentle.jitai.engine.schedule.Effectiveness
import dev.agentle.jitai.engine.time.EngineClock
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.elapsedBetween
import dev.agentle.jitai.engine.time.isBefore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Constants of the delivery protocol (R10 §8.5). */
public object DeliveryProtocol {
    /** A claimed row is owned by its worker for this long; afterwards recovery decides by tag lookup. */
    public val LEASE: Duration = 2.minutes

    /** Downgrade reason when VOICE text cannot be synthesized (red team lifecycle-battery-18). */
    public const val TTS_UNAVAILABLE: String = "TTS_UNAVAILABLE"

    /** Downgrade reason when an IMAGE or VIDEO asset cannot be prepared. */
    public const val MEDIA_UNAVAILABLE: String = "MEDIA_UNAVAILABLE"

    /** Fail-closed notification state used when the live state cannot be read (G05 and G07 then block). */
    public val UNKNOWN_NOTIFICATION_STATE: NotificationSystemState = NotificationSystemState(
        permissionGranted = false,
        appNotificationsEnabled = false,
        interruptionFilter = dev.agentle.jitai.engine.ports.InterruptionFilter.UNKNOWN,
        notificationListenerConnected = false,
    )
}

/**
 * The two-phase delivery protocol and crash recovery (R10 §8.5 with red team lifecycle-battery-05/18):
 *
 * 1. Checks before the claim: the delivery deadline (EXPIRED), that the JITAI is still effective (CANCELLED) and, for
 *    event decisions, that the live state the event implied still holds (SUPPRESSED(STATE_CHANGED)).
 * 2. Content is chosen and rendered from the stored snapshot, and slow media (TTS) is prepared **before** the lease, with
 *    a downgrade to a plain notification when it is unavailable.
 * 3. Claim: conditional update DECIDED -> DELIVERING with the 2-minute lease and the content ref; zero rows changed means
 *    another worker owns the row.
 * 4. Notification permission and channel state are re-checked (FAILED(NOTIFICATIONS_BLOCKED)), then the notification is
 *    posted with `tag = decisionKey` and the row becomes DELIVERED.
 *
 * Recovery handles DECIDED rows (continue or expire) and DELIVERING rows whose lease expired: an active notification with
 * the tag means DELIVERED (`deliveredAt = claimedAt`, recovered); otherwise DELIVERY_UNCERTAIN, never re-posted and still
 * counted. Port failures leave the row where it is, so a later run retries within the deadline.
 */
public class DeliveryCoordinator(
    private val ports: EnginePorts,
    private val clock: EngineClock,
    private val logger: Logger = Logger.NONE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Delivers the DECIDED row [record]. [definitions] are the current definitions by id. */
    public suspend fun deliver(
        record: DecisionRecord,
        definitions: Map<String, JitaiDefinition>,
        settings: EngineSettings,
    ): DeliveryResult {
        if (record.state != DecisionState.DECIDED) return DeliveryResult.Skipped(record.decisionKey, record.state)
        val definition = definitions[record.jitaiId]
        val deadline = deadlineOf(definition)
        val start = clock.now()
        if (elapsedBetween(record.decided, start) > deadline) return end(record, DecisionState.EXPIRED, ReasonCode.DEADLINE_PASSED, start)
        if (definition == null || !Effectiveness.isEffective(definition, start.wall)) {
            return end(record, DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED, start)
        }
        record.impliedState?.let { implied ->
            val holds = impliedHolds(implied, start) ?: return DeliveryResult.Error(record.decisionKey, "implied_state_unreadable")
            if (!holds) return end(record, DecisionState.SUPPRESSED, ReasonCode.STATE_CHANGED, start)
        }
        val rendered = render(record, definition, settings, start)
        val prepared = prepare(rendered) ?: return DeliveryResult.Error(record.decisionKey, "prepare_failed")
        return claimAndPost(record, definition, deadline, prepared)
    }

    private suspend fun claimAndPost(
        record: DecisionRecord,
        definition: JitaiDefinition,
        deadline: Duration,
        prepared: PreparedDelivery,
    ): DeliveryResult {
        val key = record.decisionKey
        val now = clock.now()
        if (elapsedBetween(record.decided, now) > deadline) {
            discard(prepared)
            return end(record, DecisionState.EXPIRED, ReasonCode.DEADLINE_PASSED, now)
        }
        val claimed = record.copy(
            state = DecisionState.DELIVERING,
            claimed = now,
            leaseUntil = now + DeliveryProtocol.LEASE,
            content = record.content.copy(contentRef = prepared.intervention.contentRef.encoded),
        )
        when (val claim = ports.store.compareAndSet(DecisionState.DECIDED, claimed)) {
            is Outcome.Failure -> {
                discard(prepared)
                return DeliveryResult.Error(key, claim.error.code)
            }

            is Outcome.Success -> if (!claim.value) {
                discard(prepared)
                return DeliveryResult.Skipped(key, ports.store.decision(key).getOrNull()?.state)
            }
        }
        val notifications = ports.settings.notificationState().getOrNull() ?: DeliveryProtocol.UNKNOWN_NOTIFICATION_STATE
        if (notifications.blocks(definition.category)) {
            discard(prepared)
            return finish(claimed, DecisionState.FAILED, ReasonCode.NOTIFICATIONS_BLOCKED, null)
        }
        val posted = outcomeOf(mapError = { AppError.Unexpected("post:${it::class.simpleName}") }) { ports.delivery.post(prepared) }
        return when (posted) {
            // The row stays DELIVERING; recovery after the lease decides by tag lookup (R10 §8.5).
            is Outcome.Failure -> DeliveryResult.Error(key, posted.error.detail ?: posted.error.code)

            is Outcome.Success -> when (val result = posted.value) {
                PostResult.Posted -> delivered(claimed, prepared.intervention)
                PostResult.Blocked -> finish(claimed, DecisionState.FAILED, ReasonCode.NOTIFICATIONS_BLOCKED, null)
                is PostResult.Failed -> finish(claimed, DecisionState.FAILED, ReasonCode.POST_FAILED, result.code)
            }
        }
    }

    private suspend fun delivered(claimed: DecisionRecord, intervention: RenderedIntervention): DeliveryResult {
        val done = claimed.copy(state = DecisionState.DELIVERED, delivered = clock.now())
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, done)) {
            is Outcome.Failure -> DeliveryResult.Error(claimed.decisionKey, update.error.code)

            is Outcome.Success -> {
                (intervention.contentRef as? ContentRef.AiPooled)?.let { ref ->
                    ports.aiTexts.markUsed(ref.itemId, claimed.decisionKey).onFailureLog("mark_used")
                }
                if (update.value) {
                    DeliveryResult.Delivered(claimed.decisionKey, recovered = false, downgradeReason = intervention.downgradeReason)
                } else {
                    DeliveryResult.Skipped(claimed.decisionKey, ports.store.decision(claimed.decisionKey).getOrNull()?.state)
                }
            }
        }
    }

    /**
     * Crash recovery (R10 §8.5): continues or expires DECIDED rows and resolves DELIVERING rows whose lease expired by
     * looking up the notification tag. Rows still inside their lease are left to their worker.
     */
    public suspend fun recover(definitions: Map<String, JitaiDefinition>, settings: EngineSettings): RecoveryReport {
        val start = clock.now()
        val rows = when (val found = ports.store.decisionsInStates(setOf(DecisionState.DECIDED, DecisionState.DELIVERING))) {
            is Outcome.Failure -> return RecoveryReport(start.wall, emptyList(), error = found.error.code)
            is Outcome.Success -> found.value.sortedBy { it.sequence }
        }
        val results = rows.map { row ->
            when (row.state) {
                DecisionState.DECIDED -> deliver(row, definitions, settings)
                else -> recoverClaimed(row)
            }
        }
        return RecoveryReport(start.wall, results)
    }

    private suspend fun recoverClaimed(row: DecisionRecord): DeliveryResult {
        val now = clock.now()
        val lease = row.leaseUntil
        if (lease != null && isBefore(now, lease)) return DeliveryResult.Skipped(row.decisionKey, row.state)
        val active = outcomeOf(mapError = { AppError.Unexpected("is_active:${it::class.simpleName}") }) {
            ports.delivery.isActive(row.notificationTag)
        }
        val isActive = when (active) {
            is Outcome.Failure -> return DeliveryResult.Error(row.decisionKey, active.error.detail ?: active.error.code)

            is Outcome.Success -> when (val inner = active.value) {
                is Outcome.Failure -> return DeliveryResult.Error(row.decisionKey, inner.error.code)
                is Outcome.Success -> inner.value
            }
        }
        if (!isActive) return finish(row, DecisionState.DELIVERY_UNCERTAIN, ReasonCode.NOT_FOUND_AFTER_LEASE, null)
        val recovered = row.copy(state = DecisionState.DELIVERED, delivered = row.claimed ?: now, recovered = true)
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, recovered)) {
            is Outcome.Failure -> DeliveryResult.Error(row.decisionKey, update.error.code)

            is Outcome.Success ->
                if (update.value) {
                    DeliveryResult.Delivered(
                        row.decisionKey,
                        recovered = true,
                    )
                } else {
                    DeliveryResult.Skipped(row.decisionKey, null)
                }
        }
    }

    /** Cancels the unclaimed DECIDED rows of [jitaiId] (disable, R10 §9.5 and §12.N6). */
    public suspend fun cancelUnclaimed(jitaiId: String): List<DeliveryResult> {
        val now = clock.now()
        val rows = ports.store.decisionsInStates(setOf(DecisionState.DECIDED)).getOrNull().orEmpty().filter { it.jitaiId == jitaiId }
        return rows.map { end(it, DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED, now) }
    }

    /** DECIDED -> [state] with [reason]. */
    private suspend fun end(record: DecisionRecord, state: DecisionState, reason: ReasonCode, now: MonotonicStamp): DeliveryResult {
        val updated = record.copy(state = state, reason = reason, finishedAt = now.wall)
        return when (val update = ports.store.compareAndSet(DecisionState.DECIDED, updated)) {
            is Outcome.Failure -> DeliveryResult.Error(record.decisionKey, update.error.code)

            is Outcome.Success ->
                if (update.value) {
                    DeliveryResult.Ended(
                        record.decisionKey,
                        state,
                        reason,
                    )
                } else {
                    DeliveryResult.Skipped(record.decisionKey, null)
                }
        }
    }

    /** DELIVERING -> [state] with [reason]. */
    private suspend fun finish(claimed: DecisionRecord, state: DecisionState, reason: ReasonCode, detail: String?): DeliveryResult {
        val updated = claimed.copy(state = state, reason = reason, reasonDetail = detail, finishedAt = clock.now().wall)
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, updated)) {
            is Outcome.Failure -> DeliveryResult.Error(claimed.decisionKey, update.error.code)

            is Outcome.Success ->
                if (update.value) {
                    DeliveryResult.Ended(
                        claimed.decisionKey,
                        state,
                        reason,
                    )
                } else {
                    DeliveryResult.Skipped(claimed.decisionKey, null)
                }
        }
    }

    /**
     * Whether the live value of [implied] still equals what the event implied (red team lifecycle-battery-05): only a
     * Known equal value holds; a changed, stale or missing value does not. Null when the resolver failed unexpectedly.
     */
    private suspend fun impliedHolds(implied: ImpliedState, now: MonotonicStamp): Boolean? {
        val snapshot = outcomeOf(mapError = { AppError.Unexpected("implied:${it::class.simpleName}") }) {
            ports.features.resolve(setOf(implied.ref), now.wall)
        }.getOrNull() ?: return null
        val value = snapshot[implied.ref]
        return value is FeatureValue.Known && value.value == implied.expected
    }

    private suspend fun render(
        record: DecisionRecord,
        definition: JitaiDefinition,
        settings: EngineSettings,
        now: MonotonicStamp,
    ): RenderedIntervention {
        val renderer = ContentRenderer(DefaultPlaceholderFormatter(settings.display))
        val pool = if (definition.content is ContentStrategy.AiText) {
            ports.aiTexts.pooled(
                definition.id,
            ).getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val previous = (ports.store.readSnapshot { it.countedCount(definition.id) }.getOrNull() ?: 1L) - 1L
        val ref = renderer.choose(definition, previous.coerceAtLeast(0), pool, record.content.snapshotHash, now.wall, settings.aiConsent)
        val pooled: PooledText? = (ref as? ContentRef.AiPooled)?.let { chosen -> pool.firstOrNull { it.id == chosen.itemId } }
        return renderer.render(
            ContentRenderer.RenderInput(
                definition = definition,
                decisionKey = record.decisionKey,
                channel = record.channel,
                snapshot = record.content.snapshotJson?.let(::decodeSnapshot),
                snapshotHash = record.content.snapshotHash,
                ref = ref,
                pooled = pooled,
                nonce = record.nonce.orEmpty(),
                now = now.wall,
                consent = settings.aiConsent,
            ),
        )
    }

    /** Prepares media before the lease; downgrades VOICE, IMAGE and VIDEO to a plain notification when unavailable. */
    private suspend fun prepare(rendered: RenderedIntervention): PreparedDelivery? {
        val first = outcomeOf(mapError = { AppError.Unexpected("prepare:${it::class.simpleName}") }) { ports.delivery.prepare(rendered) }
            .getOrNull() ?: return null
        return when (first) {
            is PrepareResult.Ready -> first.prepared

            is PrepareResult.Unavailable -> {
                if (rendered.channel == DeliveryChannel.NOTIFICATION) return PreparedDelivery(rendered)
                val reason = if (rendered.channel ==
                    DeliveryChannel.VOICE
                ) {
                    DeliveryProtocol.TTS_UNAVAILABLE
                } else {
                    DeliveryProtocol.MEDIA_UNAVAILABLE
                }
                val downgraded = rendered.downgraded(reason)
                logger.w(COMPONENT, "delivery downgraded", fields = mapOf("reason" to reason, "code" to first.code))
                val second = outcomeOf(mapError = {
                    AppError.Unexpected("prepare:${it::class.simpleName}")
                }) { ports.delivery.prepare(downgraded) }
                    .getOrNull()
                (second as? PrepareResult.Ready)?.prepared ?: PreparedDelivery(downgraded)
            }
        }
    }

    private suspend fun discard(prepared: PreparedDelivery) {
        outcomeOf(mapError = { AppError.Unexpected("discard:${it::class.simpleName}") }) { ports.delivery.discard(prepared) }
            .onFailureLog("discard")
    }

    private fun decodeSnapshot(text: String): FeatureSnapshot? = try {
        json.decodeFromString(FeatureSnapshot.serializer(), text)
    } catch (expected: SerializationException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    private fun <T> Outcome<T>.onFailureLog(step: String) {
        if (this is Outcome.Failure) logger.w(COMPONENT, "delivery side step failed", error, mapOf("step" to step))
    }

    private fun deadlineOf(definition: JitaiDefinition?): Duration =
        (definition?.delivery?.deliveryDeadlineMinutes ?: dev.agentle.jitai.dsl.model.Delivery.DEFAULT_DELIVERY_DEADLINE_MINUTES)
            .coerceAtLeast(0).minutes

    private companion object {
        const val COMPONENT = "jitai-delivery"
    }
}
