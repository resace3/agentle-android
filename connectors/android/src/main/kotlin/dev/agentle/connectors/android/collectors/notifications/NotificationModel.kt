package dev.agentle.connectors.android.collectors.notifications

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What Agentle reads from one `StatusBarNotification`. Title and text are read only for packages whose content the user
 * opted in to, stay in memory, and are stored only when content capture applies (never the default SMS app or dialer
 * unless the user allowed it); [hasText] is known either way. [toString] never prints them.
 */
public class NotificationSnapshot(
    public val key: String,
    public val packageName: String,
    public val postTimeMs: Long,
    public val channelId: String?,
    public val category: String?,
    public val ongoing: Boolean,
    public val groupSummary: Boolean,
    public val foregroundService: Boolean,
    public val localOnly: Boolean,
    public val hasText: Boolean,
    public val title: String? = null,
    public val text: String? = null,
) {
    override fun toString(): String = "NotificationSnapshot(package=$packageName, ongoing=$ongoing)"

    public companion object {
        /** Reads [sbn], keeping title and text only when [includeText]; never throws (unreadable text is no text). */
        @Suppress("TooGenericExceptionCaught")
        public fun of(sbn: StatusBarNotification, includeText: Boolean): NotificationSnapshot {
            val notification: Notification? = sbn.notification
            val flags = notification?.flags ?: 0
            val extras = try {
                notification?.extras
            } catch (ignored: RuntimeException) {
                null
            }
            val title = try {
                extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            } catch (ignored: RuntimeException) {
                null
            }
            val text = try {
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            } catch (ignored: RuntimeException) {
                null
            }
            return NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                postTimeMs = sbn.postTime,
                channelId = notification?.channelId,
                category = notification?.category,
                ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
                groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
                foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                localOnly = flags and Notification.FLAG_LOCAL_ONLY != 0,
                hasText = !title.isNullOrBlank() || !text.isNullOrBlank(),
                title = if (includeText) title else null,
                text = if (includeText) text else null,
            )
        }
    }
}

/** One active notification key (hashed) as the collector last wrote it. */
@Serializable
public data class ActiveEntry(
    val firstPostMs: Long,
    val pkg: String,
    val updates: Int = 0,
    val lastUpdateMs: Long? = null,
    val channelHash: String? = null,
    val category: String? = null,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
)

/** The `("android", "notifications")` cursor: the active keys, so a reconnect can diff (removals while unbound). */
@Serializable
public data class ActiveState(val active: Map<String, ActiveEntry> = emptyMap(), val v: Int = 1) {
    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Suppress("TooGenericExceptionCaught")
        public fun decode(text: String?): ActiveState = try {
            text?.let { json.decodeFromString(serializer(), it) } ?: ActiveState()
        } catch (ignored: RuntimeException) {
            ActiveState()
        }
    }
}

/** The default SMS app and the default dialer: never captured unless the user allowed it (docs/research/01 §3.34). */
public fun interface DefaultHandlers {
    public fun packages(): Set<String>
}

public class PlatformDefaultHandlers(private val context: Context) : DefaultHandlers {
    @Suppress("TooGenericExceptionCaught")
    override fun packages(): Set<String> {
        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (ignored: RuntimeException) {
            null
        }
        val dialer = try {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (ignored: RuntimeException) {
            null
        }
        return setOfNotNull(sms, dialer)
    }
}
