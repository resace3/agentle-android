package dev.agentle.interventions.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import dev.agentle.interventions.R
import dev.agentle.interventions.delivery.InterventionCodes
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlin.time.Duration.Companion.minutes

/** Result of [InterventionNotifier.post]. */
sealed interface NotifyResult {
    data object Posted : NotifyResult

    /** `notify` threw a SecurityException: POST_NOTIFICATIONS went away between the check and the post. */
    data object PermissionMissing : NotifyResult

    data class Failed(val code: String) : NotifyResult
}

/** One notification action. */
sealed interface ActionSpec {
    /** Listen (VOICE) or Watch (VIDEO): opens the app, which plays only while visible. */
    data class Play(val video: Boolean) : ActionSpec

    data class Snooze(val option: SnoozeOption) : ActionSpec

    /** Snooze until the active window ends. */
    data object NotNow : ActionSpec

    data object Stop : ActionSpec
}

/**
 * Which actions a notification carries. Phones show at most three, so: media gets [Listen|Watch, Snooze, Stop]; text
 * gets [Snooze, Not now, Stop]. Snooze is the rule's first option other than `UNTIL_WINDOW_END` (that one is "Not now").
 * Opening is the content intent (a tap); the in-app card and the detail screen offer every snooze option.
 */
object NotificationActions {
    const val MAX_VISIBLE: Int = 3

    fun select(intervention: RenderedIntervention, mediaReady: Boolean): List<ActionSpec> {
        val snooze = intervention.snoozeOptions.firstOrNull { it != SnoozeOption.UNTIL_WINDOW_END }
        return buildList {
            if (mediaReady) add(ActionSpec.Play(video = intervention.channel == DeliveryChannel.VIDEO))
            if (snooze != null) add(ActionSpec.Snooze(snooze))
            if (!mediaReady || snooze == null) add(ActionSpec.NotNow)
            add(ActionSpec.Stop)
        }.take(MAX_VISIBLE)
    }
}

/**
 * Posts intervention notifications (spec §21; R09 §10; red team corrections 6-10):
 * - `tag = decisionKey`, `id = 1`, so a re-post updates in place and [isActive] answers crash recovery;
 * - the posted text is `postedTitle` / `postedBody` (generic unless detailed notifications are on,
 *   jitai-correctness-18); the lock-screen (public) version is always generic;
 * - `setLocalOnly(localOnly)`: local-only unless the user let posts reach wearables;
 * - `CATEGORY_REMINDER`, `setOnlyAlertOnce`, auto-cancel, and the rule's timeout as `setTimeoutAfter`;
 * - explicit, immutable PendingIntents with the delivery nonce; the delete intent records the dismissal.
 */
class InterventionNotifier(
    private val context: Context,
    private val channels: InterventionChannels,
    private val intents: InterventionIntents,
    private val clock: AgentleClock,
    private val logger: Logger,
) {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Posts [prepared]. [picture] is the IMAGE big picture; it is shown only when the delivery is `detailed`. */
    fun post(prepared: PreparedDelivery, picture: Bitmap? = null): NotifyResult {
        val intervention = prepared.intervention
        val content = intents.open(intervention, autoplay = false)
            ?: return NotifyResult.Failed(InterventionCodes.ACTIVITY_INTENT_NOT_EXPLICIT)
        channels.ensure()
        val notification = build(prepared, picture, content)
        return try {
            manager.notify(intervention.notificationTag, NOTIFICATION_ID, notification)
            NotifyResult.Posted
        } catch (e: SecurityException) {
            logger.w(COMPONENT, "notify refused", fields = mapOf("error" to e::class.simpleName))
            NotifyResult.PermissionMissing
        }
    }

    fun cancel(tag: String) {
        manager.cancel(tag, NOTIFICATION_ID)
    }

    fun isActive(tag: String): Boolean = tag in activeTags()

    /** Tags of this module's notifications that are showing now. */
    fun activeTags(): Set<String> {
        val ours = InterventionChannels.CHANNEL_IDS.values.toSet()
        return manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.notification.channelId in ours }
            .mapNotNullTo(mutableSetOf()) { it.tag }
    }

    private fun build(prepared: PreparedDelivery, picture: Bitmap?, content: PendingIntent): Notification {
        val intervention = prepared.intervention
        val channelId = channels.channelId(intervention.category)
        val mediaReady = prepared.mediaRef != null && intervention.channel in PLAYABLE
        val title = intervention.postedTitle.ifBlank { context.getString(R.string.interventions_public_title) }
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_intervention)
            .setContentTitle(title)
            .setContentText(intervention.postedBody)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(channelId))
            .setContentIntent(content)
            .setDeleteIntent(intents.action(intervention, NotificationVerb.DISMISS))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(intervention.localOnly)
            .setShowWhen(true)
            .setWhen(clock.now().toEpochMilliseconds())
        intervention.timeoutMinutes?.takeIf { it > 0 }?.let { builder.setTimeoutAfter(it.minutes.inWholeMilliseconds) }
        when {
            picture != null && intervention.detailed -> builder.applyPicture(picture)
            intervention.detailed -> builder.setStyle(Notification.BigTextStyle().bigText(intervention.postedBody))
        }
        NotificationActions.select(intervention, mediaReady).forEach { spec -> action(intervention, spec)?.let { builder.addAction(it) } }
        return builder.build()
    }

    /** BigPictureStyle; collapsed big picture and its description need API 31, a large-icon thumbnail before. */
    private fun Notification.Builder.applyPicture(picture: Bitmap) {
        val style = Notification.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            style.showBigPictureWhenCollapsed(true)
            style.setContentDescription(context.getString(R.string.interventions_picture_description))
        } else {
            setLargeIcon(picture)
        }
        setStyle(style)
    }

    /** The lock-screen version: the app name and a neutral line, nothing about the content. */
    private fun publicVersion(channelId: String): Notification = Notification.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_intervention)
        .setContentTitle(context.getString(R.string.interventions_public_title))
        .setContentText(context.getString(R.string.interventions_public_text))
        .setCategory(Notification.CATEGORY_REMINDER)
        .build()

    private fun action(intervention: RenderedIntervention, spec: ActionSpec): Notification.Action? {
        val (icon, label, intent) = when (spec) {
            is ActionSpec.Play -> Triple(
                R.drawable.ic_intervention_play,
                if (spec.video) R.string.interventions_action_watch else R.string.interventions_action_listen,
                intents.open(intervention, autoplay = true) ?: return null,
            )
            is ActionSpec.Snooze -> Triple(
                R.drawable.ic_intervention_snooze,
                snoozeLabel(spec.option),
                intents.action(intervention, NotificationVerb.SNOOZE, spec.option),
            )
            ActionSpec.NotNow -> Triple(
                R.drawable.ic_intervention_snooze,
                R.string.interventions_action_not_now,
                intents.action(intervention, NotificationVerb.NOT_NOW),
            )
            ActionSpec.Stop -> Triple(
                R.drawable.ic_intervention_stop,
                R.string.interventions_action_stop,
                intents.action(intervention, NotificationVerb.STOP),
            )
        }
        return Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), intent).build()
    }

    companion object {
        /** R10 §8.5: `notify(tag = decisionKey, id = 1)`. */
        const val NOTIFICATION_ID: Int = 1
        private const val COMPONENT = "interventions.notify"
        private val PLAYABLE = setOf(DeliveryChannel.VOICE, DeliveryChannel.VIDEO)

        fun snoozeLabel(option: SnoozeOption): Int = when (option) {
            SnoozeOption.MINUTES_30 -> R.string.interventions_snooze_30_minutes
            SnoozeOption.MINUTES_60 -> R.string.interventions_snooze_60_minutes
            SnoozeOption.MINUTES_120 -> R.string.interventions_snooze_120_minutes
            SnoozeOption.UNTIL_WINDOW_END -> R.string.interventions_snooze_until_window_end
            SnoozeOption.UNTIL_TOMORROW -> R.string.interventions_snooze_until_tomorrow
        }
    }
}
