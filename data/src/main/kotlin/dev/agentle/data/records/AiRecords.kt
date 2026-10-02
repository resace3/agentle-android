package dev.agentle.data.records

import dev.agentle.core.database.LineageCodec
import dev.agentle.core.database.entity.AiRequestEntity
import dev.agentle.core.database.entity.AiResultMetaEntity
import dev.agentle.core.database.entity.AiTextPoolEntity
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.hours

/** Who started an AI request (red team privacy-ai-13). */
enum class AiInitiator { USER, BACKGROUND }

/**
 * What the AI layer records when it sends a request (docs/ARCHITECTURE.md §5.2; R04 SEC-AI-06; red team privacy-ai-13):
 * purpose, the category names present in the request, the time range, sizes, the SHA-256 of the exact body bytes sent,
 * who started it, the consent and prompt versions. [accountSub] is the raw ChatGPT account id: the repository stores
 * only a salted hash of it. Never a payload, a prompt, a response or a value.
 */
data class AiRequestStart(
    val id: String,
    val purpose: String,
    val categories: Set<DataCategory>,
    val rangeStartMs: Long?,
    val rangeEndMs: Long?,
    val rawEventsSent: Boolean,
    val aggregatesSent: Boolean,
    val model: String?,
    val createdMs: Long,
    val bytesSent: Long,
    val consentVersion: Int?,
    /** Lowercase hex SHA-256 of the exact request body bytes sent. */
    val payloadSha256: String,
    val initiator: AiInitiator,
    val accountSub: String?,
    val promptVersion: String?,
)

/** One stored AI request (metadata only); [accountSubHash] is the salted hash, never the account id. */
data class AiRequestRecord(
    val id: String,
    val purpose: String,
    val categories: Set<DataCategory>,
    val rangeStartMs: Long?,
    val rangeEndMs: Long?,
    val rawEventsSent: Boolean,
    val aggregatesSent: Boolean,
    val model: String?,
    val status: String,
    val errorCode: String?,
    val createdMs: Long,
    val bytesSent: Long,
    val consentVersion: Int?,
    val payloadSha256: String?,
    val initiator: AiInitiator,
    val accountSubHash: String?,
    val promptVersion: String?,
    val providerRequestId: String?,
)

/** Validation metadata of an AI result: schema id, validity and closed error codes, never model text. */
data class AiResultRecord(
    val requestId: String,
    val schema: String,
    val valid: Boolean,
    val validationErrors: List<String>,
    val producedEntityId: String?,
    val createdMs: Long,
)

/** The AI audit log (metadata only). */
interface AiAuditRepository {
    /** Records a request as SENT. */
    suspend fun recordRequest(request: AiRequestStart)

    /**
     * Sets the final status of a request: a closed status and error code, the model id and the provider's request id
     * (OpenAI `x-request-id`) when it returned one.
     */
    suspend fun finishRequest(id: String, status: String, errorCode: String?, model: String?, providerRequestId: String? = null)

    suspend fun recordResult(record: AiResultRecord)

    suspend fun recent(limit: Int): List<AiRequestRecord>

    fun observeRecent(limit: Int): Flow<List<AiRequestRecord>>

    suspend fun result(requestId: String): AiResultRecord?
}

/** [subHasher] turns the ChatGPT account id into its salted per-install hash (`InstallIdProvider.pseudonymize`). */
internal class RoomAiAuditRepository(private val access: DataAccess, private val subHasher: (String) -> String) : AiAuditRepository {
    override suspend fun recordRequest(request: AiRequestStart) {
        val entity = entityOf(request, request.accountSub?.let(subHasher))
        access.write { db.aiDao().insertRequest(entity) }
    }

    override suspend fun finishRequest(id: String, status: String, errorCode: String?, model: String?, providerRequestId: String?) {
        access.write {
            db.aiDao().finishRequest(
                id,
                closedCode(status),
                errorCode?.let(::closedCode),
                model?.let(::closedCode),
                providerRequestId?.let(::closedCode),
            )
        }
    }

    override suspend fun recordResult(record: AiResultRecord) {
        access.write {
            db.aiDao().insertResultMeta(
                AiResultMetaEntity(
                    requestId = record.requestId,
                    schema = closedCode(record.schema),
                    valid = record.valid,
                    validationErrors = record.validationErrors.map(::closedCode).joinToString(","),
                    producedEntityId = record.producedEntityId,
                    createdMs = record.createdMs,
                ),
            )
        }
    }

    override suspend fun recent(limit: Int): List<AiRequestRecord> = access.read { db.aiDao().recentRequests(limit).map(::recordOf) }

    override fun observeRecent(limit: Int): Flow<List<AiRequestRecord>> = flow {
        emitAll(access.database().aiDao().observeRequests(limit).map { rows -> rows.map(::recordOf) })
    }

    override suspend fun result(requestId: String): AiResultRecord? = access.read {
        db.aiDao().resultMeta(requestId)?.let { row ->
            AiResultRecord(
                requestId = row.requestId,
                schema = row.schema,
                valid = row.valid,
                validationErrors = row.validationErrors.split(',').filter { it.isNotEmpty() },
                producedEntityId = row.producedEntityId,
                createdMs = row.createdMs,
            )
        }
    }

    companion object {
        const val STATUS_SENT: String = "SENT"
        private const val MAX_CODE_LENGTH = 64
        private val CODE = Regex("[A-Za-z0-9_.:-]{1,$MAX_CODE_LENGTH}")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** Closed codes only: anything that is not a short identifier is stored as `REDACTED` (no free text). */
        fun closedCode(value: String): String = if (CODE.matches(value)) value else "REDACTED"

        fun entityOf(request: AiRequestStart, accountSubHash: String?): AiRequestEntity = AiRequestEntity(
            id = request.id,
            purpose = closedCode(request.purpose),
            categories = LineageCodec.encodeCategories(request.categories),
            rangeStartMs = request.rangeStartMs,
            rangeEndMs = request.rangeEndMs,
            rawEventsSent = request.rawEventsSent,
            aggregatesSent = request.aggregatesSent,
            model = request.model?.let(::closedCode),
            status = STATUS_SENT,
            errorCode = null,
            createdMs = request.createdMs,
            bytesSent = request.bytesSent,
            consentVersion = request.consentVersion,
            payloadSha256 = request.payloadSha256.lowercase().takeIf { SHA256_HEX.matches(it) },
            initiator = request.initiator.name,
            accountSubHash = accountSubHash,
            promptVersion = request.promptVersion?.let(::closedCode),
            providerRequestId = null,
        )

        fun recordOf(row: AiRequestEntity): AiRequestRecord = AiRequestRecord(
            id = row.id,
            purpose = row.purpose,
            categories = LineageCodec.decode(row.categories).categories,
            rangeStartMs = row.rangeStartMs,
            rangeEndMs = row.rangeEndMs,
            rawEventsSent = row.rawEventsSent,
            aggregatesSent = row.aggregatesSent,
            model = row.model,
            status = row.status,
            errorCode = row.errorCode,
            createdMs = row.createdMs,
            bytesSent = row.bytesSent,
            consentVersion = row.consentVersion,
            payloadSha256 = row.payloadSha256,
            initiator = AiInitiator.entries.firstOrNull { it.name == row.initiator } ?: AiInitiator.BACKGROUND,
            accountSubHash = row.accountSubHash,
            promptVersion = row.promptVersion,
            providerRequestId = row.providerRequestId,
        )
    }
}

/**
 * One pooled AI-written intervention text (R10 §3.3), keyed by the content hash of the rule version it was written
 * for. [consentRevision] is the AI consent revision (`AiConsentSnapshot.revision`) the request was approved at.
 */
data class PooledTextRecord(
    val id: String,
    val jitaiId: String,
    val contentHash: String,
    val title: String,
    val body: String,
    val consentRevision: Int,
    val categories: Set<DataCategory>,
    val snapshotHash: String?,
    val createdMs: Long,
    val expiresMs: Long,
    val usedDecisionKey: String? = null,
    val usedMs: Long? = null,
)

/**
 * The AI text pool (`ai_text_pool`; round 1 correction 3). Texts live at most [MAX_AGE_HOURS] hours. Every consent
 * change voids the pool: [pooled] and [get] first delete the items approved at another consent revision than the one
 * in force (an unreadable consent store voids everything), so a text is never delivered after its consent changed.
 * Retention and every deletion purge the pool too.
 */
interface AiTextPoolStore {
    /** Adds [item]; its expiry is capped at [MAX_AGE_HOURS] after creation. */
    suspend fun add(item: PooledTextRecord)

    /** Unused, unexpired items written for the rule content [contentHash]. */
    suspend fun pooled(contentHash: String): List<PooledTextRecord>

    suspend fun get(id: String): PooledTextRecord?

    /** Marks [id] used by [decisionKey]; false when it was already used or is gone. */
    suspend fun markUsed(id: String, decisionKey: String): Boolean

    /** Deletes the items of [jitaiId] written for other content than [keepContentHash] (all of them when null). */
    suspend fun purge(jitaiId: String, keepContentHash: String?): Int

    /** Deletes the items created before [createdBeforeMs] (the 24-hour limit). */
    suspend fun purgeExpired(createdBeforeMs: Long): Int

    /** Deletes every item (a consent change, a deletion). */
    suspend fun clear(): Int

    companion object {
        const val MAX_AGE_HOURS: Int = 24
    }
}

internal class RoomAiTextPoolStore(private val access: DataAccess, private val consent: AiConsentStore, private val clock: AgentleClock) :
    AiTextPoolStore {
    override suspend fun add(item: PooledTextRecord) {
        val cap = item.createdMs + AiTextPoolStore.MAX_AGE_HOURS.hours.inWholeMilliseconds
        access.write { db.aiDao().insertPooled(entityOf(item.copy(expiresMs = minOf(item.expiresMs, cap)))) }
    }

    override suspend fun pooled(contentHash: String): List<PooledTextRecord> {
        val revision = currentRevision()
        val now = clock.now().toEpochMilliseconds()
        return access.write {
            purgeOtherRevisions(revision)
            db.aiDao().pooledByContent(contentHash, now).map(::recordOf)
        }
    }

    override suspend fun get(id: String): PooledTextRecord? {
        val revision = currentRevision()
        return access.write {
            purgeOtherRevisions(revision)
            db.aiDao().pooledItem(id)?.let(::recordOf)
        }
    }

    override suspend fun markUsed(id: String, decisionKey: String): Boolean =
        access.write { db.aiDao().markUsed(id, decisionKey, clock.now().toEpochMilliseconds()) > 0 }

    override suspend fun purge(jitaiId: String, keepContentHash: String?): Int = access.write {
        db.aiDao().purgePool(jitaiId, keepContentHash)
    }

    override suspend fun purgeExpired(createdBeforeMs: Long): Int = access.write {
        db.aiDao().purgePoolCreatedBefore(createdBeforeMs) +
            db.aiDao().deleteExpiredPooled(clock.now().toEpochMilliseconds(), createdBeforeMs)
    }

    override suspend fun clear(): Int = access.write { db.aiDao().clearPool() }

    private suspend fun currentRevision(): Int {
        val snapshot = consent.snapshot()
        return if (snapshot.readable) snapshot.revision else UNREADABLE_REVISION
    }

    private suspend fun dev.agentle.data.Tx.purgeOtherRevisions(revision: Int) {
        sql.execute("DELETE FROM ai_text_pool WHERE consent_version != ?", revision)
    }

    companion object {
        /** No stored item carries it, so an unreadable consent store voids the whole pool. */
        private const val UNREADABLE_REVISION = -1

        fun entityOf(item: PooledTextRecord): AiTextPoolEntity = AiTextPoolEntity(
            id = item.id,
            jitaiId = item.jitaiId,
            contentHash = item.contentHash,
            title = item.title,
            body = item.body,
            consentVersion = item.consentRevision,
            categories = LineageCodec.encodeCategories(item.categories),
            snapshotHash = item.snapshotHash,
            createdMs = item.createdMs,
            expiresMs = item.expiresMs,
            usedDecisionKey = item.usedDecisionKey,
            usedMs = item.usedMs,
            lineage = LineageCodec.encode(Lineage(categories = item.categories)),
        )

        fun recordOf(row: AiTextPoolEntity): PooledTextRecord = PooledTextRecord(
            id = row.id,
            jitaiId = row.jitaiId,
            contentHash = row.contentHash,
            title = row.title,
            body = row.body,
            consentRevision = row.consentVersion,
            categories = LineageCodec.decode(row.categories).categories,
            snapshotHash = row.snapshotHash,
            createdMs = row.createdMs,
            expiresMs = row.expiresMs,
            usedDecisionKey = row.usedDecisionKey,
            usedMs = row.usedMs,
        )
    }
}
