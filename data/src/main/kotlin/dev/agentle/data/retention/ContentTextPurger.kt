package dev.agentle.data.retention

import dev.agentle.core.database.TermKind
import dev.agentle.core.model.EventType
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx

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
        TextPurgeCounts(clearNotifications("AND json_extract(payload_json, '$.packageName') = ?", packageName), 0)
    }

    override suspend fun purgeTextOfPackage(packageName: String): TextPurgeCounts = purgeNotificationText(packageName)

    override suspend fun purgeTextBefore(cutoffMs: Long): TextPurgeCounts = access.write {
        val notifications = clearNotifications("AND start_ms < ?", cutoffMs)
        val calendar = typeIds(EventType.CALENDAR_EVENT).sumOf { type ->
            sql.execute(
                "UPDATE event SET payload_json = json_remove(payload_json, '$.title') " +
                    "WHERE type = ? AND start_ms < ? AND json_extract(payload_json, '$.title') IS NOT NULL",
                type,
                cutoffMs,
            )
        }
        TextPurgeCounts(notifications, calendar)
    }

    private suspend fun Tx.clearNotifications(filter: String, arg: Any): Int =
        typeIds(EventType.NOTIFICATION_POSTED, EventType.NOTIFICATION_REMOVED).sumOf { type ->
            sql.execute(
                "UPDATE event SET payload_json = json_remove(payload_json, '$.title', '$.text') WHERE type = ? $filter " +
                    "AND (json_extract(payload_json, '$.title') IS NOT NULL OR json_extract(payload_json, '$.text') IS NOT NULL)",
                type,
                arg,
            )
        }

    private suspend fun Tx.typeIds(vararg types: EventType): List<Long> =
        types.mapNotNull { access.terms.idOf(db.termDao(), TermKind.TYPE, it.name) }
}
