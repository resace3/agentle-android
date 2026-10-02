package dev.agentle.feature.settings.port

import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * How long collected data is kept (spec §63, docs/ARCHITECTURE.md §5.5). Declared from the longest to the shortest,
 * so `ordinal` grows as the period shrinks.
 */
public enum class RetentionPeriod {
    /** The default: nothing is deleted automatically. */
    KEEP_INDEFINITELY,
    ONE_YEAR,
    DAYS_90,
    DAYS_30,
    ;

    /** True when switching from [current] to this period deletes data that [current] keeps. */
    public fun isShorterThan(current: RetentionPeriod): Boolean = ordinal > current.ordinal
}

/** The stored retention choice. */
public data class RetentionSettings(
    val period: RetentionPeriod,
    /** When the daily retention run last finished; null if it never ran (fresh install, or nothing to clean). */
    val lastCleanupAt: Instant?,
)

/** What switching to [period] would delete at the next daily run. */
public data class RetentionImpact(
    val period: RetentionPeriod,
    /** Rows older than [cutoff] the next run deletes: events plus the summaries and features derived only from them. */
    val recordsToDelete: Long,
    /** Data that started before this instant is deleted. */
    val cutoff: Instant,
)

/**
 * Data retention (`AppRoute.DataRetention`). One choice for every event family, applied by the daily `retention`
 * worker. Exempt from it (the screen says so): JITAI definitions and their versions, user goals, decision rows that
 * active cooldowns and caps need (last 8 days), and the content-free delivery ledger (kept 400 days). The run also
 * moves each stream's import floor to `now - retention`, so a later sync cannot bring older data back.
 */
public interface RetentionPort {
    /**
     * The stored choice; emits again when it changes. A fresh install emits `KEEP_INDEFINITELY` with
     * `lastCleanupAt = null`. Unaffected by connections, permissions or running syncs. Emits
     * `Outcome.Failure(AppError.DatabaseError)` / `UnsupportedFeature` when the setting cannot be read; the screen
     * then offers a retry, which collects the flow again.
     */
    public val settings: Flow<Outcome<RetentionSettings>>

    /**
     * Counts what the next daily run would delete with [period] (a read-only query). Returns `recordsToDelete = 0`
     * for `KEEP_INDEFINITELY` or when nothing is that old. Failures: `AppError.DatabaseError`, `UnsupportedFeature`.
     * Main-safe (the implementation moves the query off the main thread).
     */
    public suspend fun impactOf(period: RetentionPeriod): Outcome<RetentionImpact>

    /**
     * Stores [period] and lets the retention worker apply it (a shorter period deletes at the next daily run, which
     * the implementation may also enqueue right away). Failures: `AppError.DatabaseError` (write failed, nothing
     * changed), `UnsupportedFeature`. Main-safe; finishes the write even if the caller is cancelled.
     */
    public suspend fun setPeriod(period: RetentionPeriod): Outcome<Unit>
}
