package dev.agentle.data.retention

import dev.agentle.core.database.DataCategoryRegistry
import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.TermKind
import dev.agentle.core.datastore.SettingsStore
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.deletion.MediaFileDeleter
import dev.agentle.data.ingest.RetentionFamily
import dev.agentle.data.records.AiTextPoolStore
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Rows (or text fields) each retention rule removed in one run. */
data class RetentionReport(val removed: Map<String, Int>) {
    val total: Int get() = removed.values.sum()
}

/**
 * The daily retention run (docs/ARCHITECTURE.md §5.5; round 2 correction 4; red team privacy-ai-16). Idempotent: a
 * second run on the same day removes nothing. Raw events go by family (the user's periods; forever by default); the
 * retention cutoffs also act as import floors at ingest, so expired data never comes back. Fixed rules: decision
 * ledger, outcomes and responses 400 days, evaluation log 30 days, diagnostics newest 5,000 rows, tombstones 30 days,
 * the AI text pool 24 hours, expired media, and captured personal text after `contentTextDays` (default 7, max 30).
 * Every delete runs in chunks of at most 500 rows per transaction.
 */
interface RetentionService {
    suspend fun run(): RetentionReport
}

internal class RoomRetentionService(
    private val access: DataAccess,
    private val settings: SettingsStore,
    private val clock: AgentleClock,
    private val textPurger: ContentTextPurger,
    private val pool: AiTextPoolStore,
    private val media: MediaFileDeleter,
) : RetentionService {
    override suspend fun run(): RetentionReport {
        val now = clock.now()
        val nowMs = now.toEpochMilliseconds()
        val retention = settings.current().retention.sanitized()
        val removed = linkedMapOf<String, Int>()
        for (family in RetentionFamily.entries) {
            val days = family.period(retention).days ?: continue
            val sources = sourceIdsOf(family).ifEmpty { listOf(NO_SOURCE) }
            val marks = sources.joinToString(",") { "?" }
            removed["event:${family.name}"] = chunked(
                "event",
                "source IN ($marks) AND start_ms < ?",
                sources + (nowMs - days.days.inWholeMilliseconds),
            )
        }
        val ledgerCutoff = nowMs - LEDGER_DAYS.days.inWholeMilliseconds
        removed["jitai_decision"] = chunked("jitai_decision", "decided_ms < ?", listOf(ledgerCutoff))
        removed["intervention_outcome"] = chunked("intervention_outcome", "computed_ms < ?", listOf(ledgerCutoff))
        removed["jitai_response_log"] = chunked("jitai_response_log", "at_ms < ?", listOf(ledgerCutoff))
        removed["jitai_eval_log"] = access.write { db.jitaiDao().pruneEvalLog(nowMs - EVAL_LOG_DAYS.days.inWholeMilliseconds) }
        removed["diagnostic_log"] = access.write { db.systemDao().trimDiagnostics(DIAGNOSTICS_KEEP) }
        removed["event_tombstone"] = access.write { db.stateDao().pruneTombstones(nowMs - TOMBSTONE_DAYS.days.inWholeMilliseconds) }
        removed["ai_text_pool"] = pool.purgeExpired(nowMs - AiTextPoolStore.MAX_AGE_HOURS.hours.inWholeMilliseconds)
        val expiredMedia = access.read {
            sql.queryTexts("SELECT local_uri FROM media_artifact WHERE expires_ms IS NOT NULL AND expires_ms < ?", nowMs)
        }
        expiredMedia.forEach { media.delete(it) }
        removed["media_artifact"] = chunked("media_artifact", "expires_ms IS NOT NULL AND expires_ms < ?", listOf(nowMs))
        removed["content_text"] = textPurger.purgeTextBefore(nowMs - retention.contentTextDays.days.inWholeMilliseconds).total
        access.write { db.stateDao().putState(EngineStateKeys.LAST_RETENTION_MS, nowMs, null) }
        return RetentionReport(removed)
    }

    private suspend fun sourceIdsOf(family: RetentionFamily): List<Long> = access.read {
        db.termDao().all().filter {
            it.kind == TermKind.SOURCE && RetentionFamily.of(it.value.substringBefore('.')) == family
        }.map { it.id }
    }

    private suspend fun chunked(table: String, predicate: String, args: List<Any?>): Int {
        var total = 0
        while (true) {
            val rows = access.write {
                @Suppress("SpreadOperator") // SQL bind arguments are varargs; the array is small.
                sql.execute(
                    "DELETE FROM $table WHERE rowid IN " +
                        "(SELECT rowid FROM $table WHERE $predicate LIMIT ${DataCategoryRegistry.CHUNK_ROWS})",
                    *args.toTypedArray(),
                )
                // execute() counts result rows; changes() is the number of rows the DELETE removed.
                sql.changes().toInt()
            }
            total += rows
            if (rows == 0) return total
        }
    }

    companion object {
        /** No term has this id, so a family without sources deletes nothing. */
        private const val NO_SOURCE: Long = -1
        const val LEDGER_DAYS: Int = 400
        const val EVAL_LOG_DAYS: Int = 30
        const val TOMBSTONE_DAYS: Int = 30
        const val DIAGNOSTICS_KEEP: Int = 5_000
    }
}
