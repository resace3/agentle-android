package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.agentle.core.database.entity.DedupCollisionEntity
import dev.agentle.core.database.entity.DirtyDayEntity
import dev.agentle.core.database.entity.EngineStateEntity
import dev.agentle.core.database.entity.EventTombstoneEntity
import dev.agentle.core.database.entity.IngestFloorEntity
import dev.agentle.core.database.entity.TermEntity
import kotlinx.coroutines.flow.Flow

/** The integer dictionary for event types, sources and accounts. */
@Dao
interface TermDao {
    @Query("SELECT * FROM term")
    suspend fun all(): List<TermEntity>

    @Query("SELECT * FROM term WHERE kind = :kind AND value = :value")
    suspend fun find(kind: Int, value: String): TermEntity?

    @Insert
    suspend fun insert(term: TermEntity): Long

    /** Account terms no event refers to any more (removed by deletions of the account's data). */
    @Query("DELETE FROM term WHERE kind = :kind AND id NOT IN (SELECT DISTINCT account FROM event)")
    suspend fun deleteUnreferencedAccounts(kind: Int): Int
}

/**
 * Engine bookkeeping: single values (`engine_state`), dirty engine days, import floors, tombstones and dedup-hash
 * collisions. None of it holds personal values.
 */
@Dao
interface StateDao {
    @Query("SELECT * FROM engine_state WHERE name = :name")
    suspend fun state(name: String): EngineStateEntity?

    @Query("SELECT int_value FROM engine_state WHERE name = :name")
    fun observeInt(name: String): Flow<Long?>

    @Query("INSERT OR REPLACE INTO engine_state(name, int_value, text_value) VALUES (:name, :intValue, :textValue)")
    suspend fun putState(name: String, intValue: Long?, textValue: String?)

    @Query("DELETE FROM engine_state WHERE name = :name")
    suspend fun deleteState(name: String): Int

    // ------------------------------------------------------------------------------------------------ dirty days

    /** Marks [engineDay] dirty with [generation] (a value of the global change counter, so it never repeats). */
    @Query("INSERT OR REPLACE INTO dirty_day(engine_day, generation) VALUES (:engineDay, :generation)")
    suspend fun markDirty(engineDay: String, generation: Long)

    @Query("SELECT * FROM dirty_day ORDER BY engine_day")
    suspend fun dirtyDays(): List<DirtyDayEntity>

    @Query("SELECT * FROM dirty_day ORDER BY engine_day")
    fun observeDirtyDays(): Flow<List<DirtyDayEntity>>

    /** Compare-and-clear: removes the mark only if no newer change re-marked the day since it was read. */
    @Query("DELETE FROM dirty_day WHERE engine_day = :engineDay AND generation = :generation")
    suspend fun clearDirtyIf(engineDay: String, generation: Long): Int

    // ---------------------------------------------------------------------------------------------- import floors

    @Query("SELECT * FROM ingest_floor")
    suspend fun floors(): List<IngestFloorEntity>

    /** Raises the floor of [scope] to [floorMs]; a floor never moves down. */
    @Query(
        "INSERT OR REPLACE INTO ingest_floor(scope, floor_ms, updated_ms) VALUES (:scope, " +
            "MAX(:floorMs, COALESCE((SELECT floor_ms FROM ingest_floor WHERE scope = :scope), :floorMs)), :updatedMs)",
    )
    suspend fun raiseFloor(scope: String, floorMs: Long, updatedMs: Long)

    // ------------------------------------------------------------------------------------------------- tombstones

    @Insert
    suspend fun insertTombstones(rows: List<EventTombstoneEntity>)

    @Query("SELECT * FROM event_tombstone WHERE change_seq > :afterChangeSeq ORDER BY change_seq LIMIT :limit")
    suspend fun tombstonesAfter(afterChangeSeq: Long, limit: Int): List<EventTombstoneEntity>

    @Query("DELETE FROM event_tombstone WHERE deleted_ms < :beforeMs")
    suspend fun pruneTombstones(beforeMs: Long): Int

    // ---------------------------------------------------------------------------------------- dedup collisions

    @Query("SELECT * FROM dedup_collision WHERE account = :account AND dedup_key IN (:keys)")
    suspend fun collisions(account: Long, keys: List<String>): List<DedupCollisionEntity>

    @Insert
    suspend fun insertCollision(row: DedupCollisionEntity)

    @Query("SELECT COUNT(*) FROM event WHERE dedup_hash = :hash")
    suspend fun hashTaken(hash: Long): Long

    /** Collision records whose event is gone. */
    @Query("DELETE FROM dedup_collision WHERE dedup_hash NOT IN (SELECT dedup_hash FROM event)")
    suspend fun deleteOrphanCollisions(): Int
}
