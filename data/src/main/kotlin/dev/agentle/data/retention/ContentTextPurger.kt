package dev.agentle.data.retention

import dev.agentle.core.database.TermKind
import dev.agentle.core.model.EventType
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Rows whose personal text a purge cleared; the content-free events stay. */
data class TextPurgeCounts(val notifications: Int, val calendarEvents: Int) {
    val total: Int get() = notifications + calendarEvents
}

/**
 * Clears captured personal text (red team privacy-ai-16): notification `title`/`text` and calendar `title`. The event
 * itself stays, content-free. Each purge is one transaction, idempotent (a second run clears 0 rows) and returns counts.
 */
interface ContentTextPurger {
    /** The user turned content capture off for [packageName]. */
    suspend fun purgeNotificationText(packageName: String): TextPurgeCounts

    /** [packageName] became the default SMS or dialer app (the collectors detect it and call this). */
    suspend fun purgeTextOfPackage(packageName: String): TextPurgeCounts

    /** Text of events that started before [cutoffMs] (the content-text retention, default 7 days, at most 30). */
    suspend fun purgeTextBefore(cutoffMs: Long): TextPurgeCounts
}

internal class RoomContentTextPurger(private val access: DataAccess) : ContentTextPurger {
    override suspend fun purgeNotificationText(packageName: String): TextPurgeCounts = access.write {
        TextPurgeCounts(clear(NOTIFICATION_TYPES, Long.MAX_VALUE, NOTIFICATION_FIELDS) { it.text("packageName") == packageName }, 0)
    }

    override suspend fun purgeTextOfPackage(packageName: String): TextPurgeCounts = purgeNotificationText(packageName)

    override suspend fun purgeTextBefore(cutoffMs: Long): TextPurgeCounts = access.write {
        TextPurgeCounts(
            notifications = clear(NOTIFICATION_TYPES, cutoffMs, NOTIFICATION_FIELDS) { true },
            calendarEvents = clear(listOf(EventType.CALENDAR_EVENT), cutoffMs, CALENDAR_FIELDS) { true },
        )
    }

    /** Removes [fields] from the payloads of [types] that started before [beforeMs] and match [filter]. */
    private suspend fun Tx.clear(types: List<EventType>, beforeMs: Long, fields: Set<String>, filter: (JsonObject) -> Boolean): Int {
        var cleared = 0
        for (type in types) {
            val typeId = access.terms.idOf(db.termDao(), TermKind.TYPE, type.name) ?: continue
            val rows = sql.query("SELECT seq, payload_json FROM event WHERE type = ? AND start_ms < ?", typeId, beforeMs) {
                it.long(0) to it.text(1)
            }
            val matching = rows.mapNotNull { (seq, json) -> parse(json)?.let { seq to it } }
                .filter { (_, payload) -> fields.any { it in payload } && filter(payload) }
            for ((seq, payload) in matching) {
                val scrubbed = JsonObject(payload - fields)
                sql.execute("UPDATE event SET payload_json = ? WHERE seq = ?", JSON.encodeToString(JsonObject.serializer(), scrubbed), seq)
                cleared++
            }
        }
        return cleared
    }

    private fun parse(json: String): JsonObject? = try {
        JSON.parseToJsonElement(json).jsonObject
    } catch (expected: SerializationException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON = Json
        val NOTIFICATION_TYPES = listOf(EventType.NOTIFICATION_POSTED, EventType.NOTIFICATION_REMOVED)
        val NOTIFICATION_FIELDS = setOf("title", "text")
        val CALENDAR_FIELDS = setOf("title")
    }
}
