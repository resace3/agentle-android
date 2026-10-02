package dev.agentle.interventions.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import dev.agentle.interventions.R
import dev.agentle.jitai.dsl.model.JitaiCategory

/**
 * One notification channel per intervention category, in one group (R10 §8.5). Users can change or block each
 * channel, so the importance is read before every post ([blockedCategories]).
 */
class InterventionChannels(private val context: Context) {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun channelId(category: JitaiCategory): String = CHANNEL_IDS.getValue(category)

    /** Creates the group and the channels. Idempotent: an existing channel keeps the importance the user chose. */
    fun ensure() {
        val nm = manager
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, context.getString(R.string.interventions_channel_group)))
        nm.createNotificationChannels(JitaiCategory.entries.map(::channel))
    }

    /** Categories whose channel has importance NONE; all of them when the whole group is blocked. */
    fun blockedCategories(): Set<JitaiCategory> {
        val nm = manager
        if (nm.getNotificationChannelGroup(GROUP_ID)?.isBlocked == true) return JitaiCategory.entries.toSet()
        return JitaiCategory.entries.filterTo(mutableSetOf()) { category ->
            nm.getNotificationChannel(channelId(category))?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun channel(category: JitaiCategory): NotificationChannel =
        NotificationChannel(channelId(category), context.getString(channelName(category)), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.interventions_channel_description)
            group = GROUP_ID
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

    private fun channelName(category: JitaiCategory): Int = when (category) {
        JitaiCategory.PHYSICAL_ACTIVITY -> R.string.interventions_channel_physical_activity
        JitaiCategory.SLEEP_WIND_DOWN -> R.string.interventions_channel_sleep_wind_down
        JitaiCategory.DIGITAL_WELLBEING -> R.string.interventions_channel_digital_wellbeing
        JitaiCategory.STRESS_BREAK -> R.string.interventions_channel_stress_break
        JitaiCategory.GENERAL -> R.string.interventions_channel_general
    }

    companion object {
        const val GROUP_ID: String = "jitai"

        /** R10 §8.5 channel ids. Never rename one: the user's per-channel settings are keyed by it. */
        val CHANNEL_IDS: Map<JitaiCategory, String> = mapOf(
            JitaiCategory.PHYSICAL_ACTIVITY to "jitai_physical_activity",
            JitaiCategory.SLEEP_WIND_DOWN to "jitai_sleep_wind_down",
            JitaiCategory.DIGITAL_WELLBEING to "jitai_digital_wellbeing",
            JitaiCategory.STRESS_BREAK to "jitai_stress_break",
            JitaiCategory.GENERAL to "jitai_general",
        )
    }
}
