package dev.agentle.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import dev.agentle.core.database.entity.EventEntity

/** A change-feed row: everything a change reader needs, without the payload. */
data class EventChangeRow(
    val seq: Long,
    val type: Long,
    val source: Long,
    val account: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val subject: String?,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
    @ColumnInfo(name = "change_seq") val changeSeq: Long,
    @ColumnInfo(name = "change_kind") val changeKind: Int,
)

/** One numeric sample for query-time fusion. */
data class SampleRow(
    val source: Long,
    val account: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
)

/** Row count and time span of one term (a source or a type). */
data class TermCountRow(
    val term: Long,
    @ColumnInfo(name = "row_count") val rowCount: Long,
    @ColumnInfo(name = "first_ms") val firstMs: Long?,
    @ColumnInfo(name = "last_ms") val lastMs: Long?,
)

/** The identity columns of a stored event, enough to diff a window without reading payloads. */
data class EventKeyRow(
    val seq: Long,
    val type: Long,
    val source: Long,
    val account: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "local_date") val localDate: String?,
    @ColumnInfo(name = "dedup_hash") val dedupHash: Long,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
)

/**
 * The `event` table. All writes go through the serialized writer of `:data` (one transaction per batch of at most 500
 * rows); this DAO has no transaction logic of its own. Timeline pages are keyset pages over (`start_ms`, `seq`) with an
 * upper bound, newest first.
 */
@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity): Int

    @Query("SELECT * FROM event WHERE seq = :seq")
    suspend fun bySeq(seq: Long): EventEntity?

    @Query("SELECT * FROM event WHERE dedup_hash IN (:hashes)")
    suspend fun byDedupHashes(hashes: List<Long>): List<EventEntity>

    @Query("SELECT * FROM event WHERE source = :source AND account = :account AND start_ms >= :fromMs AND start_ms < :toMs")
    suspend fun inWindow(source: Long, account: Long, fromMs: Long, toMs: Long): List<EventEntity>

    @Query(
        "SELECT seq, type, source, account, start_ms, end_ms, zone_id, local_date, dedup_hash, dedup_key FROM event " +
            "WHERE seq IN (:seqs)",
    )
    suspend fun keysBySeq(seqs: List<Long>): List<EventKeyRow>

    @Query("DELETE FROM event WHERE seq IN (:seqs)")
    suspend fun deleteBySeq(seqs: List<Long>): Int

    @Query("SELECT COUNT(*) FROM event")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM event WHERE source IN (:sources)")
    suspend fun countBySources(sources: List<Long>): Long

    @Query("SELECT COUNT(*) FROM event WHERE type IN (:types)")
    suspend fun countByTypes(types: List<Long>): Long

    @Query(
        "SELECT source AS term, COUNT(*) AS row_count, MIN(start_ms) AS first_ms, MAX(start_ms) AS last_ms " +
            "FROM event GROUP BY source",
    )
    suspend fun countsPerSource(): List<TermCountRow>

    @Query(
        "SELECT type AS term, COUNT(*) AS row_count, MIN(start_ms) AS first_ms, MAX(start_ms) AS last_ms " +
            "FROM event GROUP BY type",
    )
    suspend fun countsPerType(): List<TermCountRow>

    // ------------------------------------------------------------------------------------------- range queries

    @Query("SELECT * FROM event WHERE type = :type AND start_ms >= :fromMs AND start_ms < :toMs ORDER BY start_ms, seq")
    suspend fun byTypeStartingIn(type: Long, fromMs: Long, toMs: Long): List<EventEntity>

    @Query(
        "SELECT * FROM event WHERE type = :type AND account IN (:accounts) AND start_ms >= :fromMs AND start_ms < :toMs " +
            "ORDER BY start_ms, seq",
    )
    suspend fun byTypeStartingInForAccounts(type: Long, accounts: List<Long>, fromMs: Long, toMs: Long): List<EventEntity>

    /** Events of [type] that overlap `[fromMs, toMs)`: intervals by their end (index on (type, end_ms)), points by start. */
    @Query(
        "SELECT * FROM event WHERE type = :type AND (" +
            "(end_ms IS NOT NULL AND end_ms > :fromMs AND start_ms < :toMs) OR " +
            "(end_ms IS NULL AND start_ms >= :fromMs AND start_ms < :toMs)) ORDER BY start_ms, seq",
    )
    suspend fun byTypeOverlapping(type: Long, fromMs: Long, toMs: Long): List<EventEntity>

    @Query(
        "SELECT source, account, start_ms, end_ms, value_num FROM event WHERE type = :type AND account IN (:accounts) AND (" +
            "(end_ms IS NOT NULL AND end_ms > :fromMs AND start_ms < :toMs) OR " +
            "(end_ms IS NULL AND start_ms >= :fromMs AND start_ms < :toMs)) ORDER BY start_ms, seq",
    )
    suspend fun samplesOverlapping(type: Long, accounts: List<Long>, fromMs: Long, toMs: Long): List<SampleRow>

    /**
     * Events of [types] and [accounts] overlapping `[fromMs, toMs)` (the analytics `EventQuery`): intervals by
     * `start < to AND end > from` (index on (type, end_ms)), points by their start (index on (type, start_ms)).
     */
    @Query(
        "SELECT * FROM event WHERE type IN (:types) AND account IN (:accounts) AND (" +
            "(end_ms IS NOT NULL AND end_ms > :fromMs AND start_ms < :toMs) OR " +
            "(end_ms IS NULL AND start_ms >= :fromMs AND start_ms < :toMs)) ORDER BY start_ms, seq",
    )
    suspend fun overlappingOfTypes(types: List<Long>, accounts: List<Long>, fromMs: Long, toMs: Long): List<EventEntity>

    /** As [overlappingOfTypes], restricted to [sources] (index on (source, start_ms) for points). */
    @Query(
        "SELECT * FROM event WHERE type IN (:types) AND source IN (:sources) AND account IN (:accounts) AND (" +
            "(end_ms IS NOT NULL AND end_ms > :fromMs AND start_ms < :toMs) OR " +
            "(end_ms IS NULL AND start_ms >= :fromMs AND start_ms < :toMs)) ORDER BY start_ms, seq",
    )
    suspend fun overlappingOfTypesFromSources(
        types: List<Long>,
        sources: List<Long>,
        accounts: List<Long>,
        fromMs: Long,
        toMs: Long,
    ): List<EventEntity>

    /** As [overlappingOfTypes], restricted to one [subject] (package, place class, activity, metric). */
    @Query(
        "SELECT * FROM event WHERE subject = :subject AND type IN (:types) AND account IN (:accounts) AND (" +
            "(end_ms IS NOT NULL AND end_ms > :fromMs AND start_ms < :toMs) OR " +
            "(end_ms IS NULL AND start_ms >= :fromMs AND start_ms < :toMs)) ORDER BY start_ms, seq",
    )
    suspend fun overlappingOfTypesWithSubject(types: List<Long>, subject: String, accounts: List<Long>, fromMs: Long, toMs: Long): List<EventEntity>

    /** Interval events of [type] whose end lies in `[fromMs, toMs)` (sleep is attributed by its end; index on (type, end_ms)). */
    @Query(
        "SELECT * FROM event WHERE type = :type AND account IN (:accounts) AND end_ms >= :fromMs AND end_ms < :toMs " +
            "ORDER BY end_ms, seq",
    )
    suspend fun byTypeEndingIn(type: Long, accounts: List<Long>, fromMs: Long, toMs: Long): List<EventEntity>

    @Query(
        "SELECT * FROM event WHERE type = :type AND account IN (:accounts) AND local_date >= :fromDate AND local_date <= :toDate " +
            "ORDER BY local_date, seq",
    )
    suspend fun byTypeOnDatesForAccounts(type: Long, accounts: List<Long>, fromDate: String, toDate: String): List<EventEntity>

    @Query("SELECT * FROM event WHERE source = :source AND start_ms >= :fromMs AND start_ms < :toMs ORDER BY start_ms, seq")
    suspend fun bySourceStartingIn(source: Long, fromMs: Long, toMs: Long): List<EventEntity>

    @Query("SELECT * FROM event WHERE type = :type ORDER BY start_ms DESC, seq DESC LIMIT 1")
    suspend fun latestOfType(type: Long): EventEntity?

    @Query("SELECT * FROM event WHERE type = :type AND account IN (:accounts) ORDER BY start_ms DESC, seq DESC LIMIT 1")
    suspend fun latestOfTypeForAccounts(type: Long, accounts: List<Long>): EventEntity?

    @Query("SELECT COUNT(*) FROM event WHERE type = :type AND start_ms >= :fromMs AND start_ms < :toMs")
    suspend fun countOfTypeStartingIn(type: Long, fromMs: Long, toMs: Long): Long

    @Query(
        "SELECT COALESCE(SUM(value_num), 0) FROM event WHERE type = :type AND source = :source AND start_ms >= :fromMs " +
            "AND start_ms < :toMs",
    )
    suspend fun sumOfTypeFromSource(type: Long, source: Long, fromMs: Long, toMs: Long): Double

    @Query("SELECT * FROM event WHERE type = :type AND local_date >= :fromDate AND local_date <= :toDate ORDER BY local_date, seq")
    suspend fun byTypeOnDates(type: Long, fromDate: String, toDate: String): List<EventEntity>

    // ------------------------------------------------------------------------------------- keyset timeline pages

    /** First page: the newest events at or before [upperMs]. */
    @Query("SELECT * FROM event WHERE start_ms <= :upperMs ORDER BY start_ms DESC, seq DESC LIMIT :limit")
    suspend fun firstPage(upperMs: Long, limit: Int): List<EventEntity>

    /** The page after the row (`afterStartMs`, `afterSeq`), newest first. */
    @Query(
        "SELECT * FROM event WHERE start_ms <= :afterStartMs AND (start_ms < :afterStartMs OR seq < :afterSeq) " +
            "ORDER BY start_ms DESC, seq DESC LIMIT :limit",
    )
    suspend fun pageAfter(afterStartMs: Long, afterSeq: Long, limit: Int): List<EventEntity>

    @Query("SELECT * FROM event WHERE type IN (:types) AND start_ms <= :upperMs ORDER BY start_ms DESC, seq DESC LIMIT :limit")
    suspend fun firstPageOfTypes(types: List<Long>, upperMs: Long, limit: Int): List<EventEntity>

    @Query(
        "SELECT * FROM event WHERE type IN (:types) AND start_ms <= :afterStartMs AND (start_ms < :afterStartMs OR seq < :afterSeq) " +
            "ORDER BY start_ms DESC, seq DESC LIMIT :limit",
    )
    suspend fun pageOfTypesAfter(types: List<Long>, afterStartMs: Long, afterSeq: Long, limit: Int): List<EventEntity>

    // ------------------------------------------------------------------------------------------- change feed

    @Query(
        "SELECT seq, type, source, account, start_ms, end_ms, zone_id, subject, value_num, change_seq, change_kind FROM event " +
            "WHERE change_seq > :afterChangeSeq ORDER BY change_seq LIMIT :limit",
    )
    suspend fun changesAfter(afterChangeSeq: Long, limit: Int): List<EventChangeRow>

    // -------------------------------------------------------------------------------------- retention (chunked)

    @Query("SELECT seq FROM event WHERE source IN (:sources) AND start_ms < :beforeMs LIMIT :limit")
    suspend fun seqsOfSourcesBefore(sources: List<Long>, beforeMs: Long, limit: Int): List<Long>
}
