package dev.agentle.interventions.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.interventions.ports.InterventionActivityIntents
import dev.agentle.interventions.ports.InterventionResponse
import dev.agentle.interventions.ports.ResponseKind
import dev.agentle.interventions.ports.ResponseSurface
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.content.RenderedIntervention

/** What a notification action asks for; each one maps to a [ResponseKind]. */
enum class NotificationVerb(val wire: String, val intentAction: String, val kind: ResponseKind) {
    SNOOZE("snooze", "dev.agentle.interventions.action.SNOOZE", ResponseKind.SNOOZED),
    NOT_NOW("not_now", "dev.agentle.interventions.action.NOT_NOW", ResponseKind.NOT_NOW),
    STOP("stop", "dev.agentle.interventions.action.STOP", ResponseKind.STOP_JITAI),
    DISMISS("dismiss", "dev.agentle.interventions.action.DISMISS", ResponseKind.DISMISSED),
    ;

    companion object {
        fun fromWire(value: String): NotificationVerb? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Data URIs of the intervention intents. The decision key goes in through `appendPath` (keys contain `|` and `:`) and
 * comes back decoded through `pathSegments`. The URI differs per (verb, key, snooze option), so `Intent.filterEquals`
 * keeps every PendingIntent apart (R09 §10.1).
 */
object InterventionUris {
    const val SCHEME: String = "agentle-intervention"
    const val HOST_OPEN: String = "open"
    const val HOST_ACTION: String = "action"
    internal const val VIEW: String = "view"
    internal const val PLAY: String = "play"

    fun open(decisionKey: String, autoplay: Boolean): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST_OPEN).appendPath(if (autoplay) PLAY else VIEW).appendPath(decisionKey).build()

    fun action(verb: NotificationVerb, decisionKey: String, snooze: SnoozeOption? = null): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST_ACTION)
        .appendPath(verb.wire)
        .appendPath(decisionKey)
        .apply { if (snooze != null) appendPath(snooze.name) }
        .build()
}

/** Intent extras. The nonce never goes into the notification itself (listeners can read notification extras). */
object InterventionExtras {
    const val NONCE: String = "dev.agentle.interventions.extra.NONCE"
}

private const val MAX_KEY_LENGTH = 512
private const val MAX_NONCE_LENGTH = 128

/**
 * A tap on the notification (or Listen/Watch with [autoplay]), parsed from the intent the activity received. The
 * activity is exported as the launcher, so anything can send it an explicit intent: parse defensively and let
 * [dev.agentle.interventions.card.InterventionOpener] check the nonce before anything is recorded or opened.
 */
data class InterventionLink(val decisionKey: String, val nonce: String, val autoplay: Boolean) {
    val route: AppRoute.InterventionDetail get() = AppRoute.InterventionDetail(decisionKey)

    companion object {
        fun from(intent: Intent?): InterventionLink? {
            val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
            val segments = data.pathSegments.orEmpty()
            val ours = data.scheme == InterventionUris.SCHEME && data.authority == InterventionUris.HOST_OPEN && segments.size == 2
            val autoplay = when (segments.getOrNull(0)) {
                InterventionUris.VIEW -> false
                InterventionUris.PLAY -> true
                else -> null
            }
            val key = segments.getOrNull(1)?.let(::validKey)
            val nonce = nonceOf(intent)
            return if (ours && autoplay != null && key != null && nonce != null) InterventionLink(key, nonce, autoplay) else null
        }
    }
}

/** A notification action (or the delete intent) as the receiver got it. */
data class NotificationAction(val verb: NotificationVerb, val decisionKey: String, val nonce: String, val snooze: SnoozeOption?) {
    fun toResponse(): InterventionResponse = InterventionResponse(
        decisionKey = decisionKey,
        nonce = nonce,
        kind = verb.kind,
        surface = ResponseSurface.NOTIFICATION,
        snooze = when (verb) {
            NotificationVerb.SNOOZE -> snooze
            NotificationVerb.NOT_NOW -> SnoozeOption.UNTIL_WINDOW_END
            NotificationVerb.STOP, NotificationVerb.DISMISS -> null
        },
    )

    companion object {
        fun from(intent: Intent?): NotificationAction? {
            val data = intent?.data ?: return null
            val segments = data.pathSegments.orEmpty()
            val ours = data.scheme == InterventionUris.SCHEME && data.authority == InterventionUris.HOST_ACTION && segments.size in 2..3
            val verb = segments.getOrNull(0)?.let(NotificationVerb::fromWire)?.takeIf { it.intentAction == intent.action }
            val key = segments.getOrNull(1)?.let(::validKey)
            val snoozeName = segments.getOrNull(2)
            val snooze = snoozeName?.let { name -> SnoozeOption.entries.firstOrNull { it.name == name } }
            // SNOOZE needs a known option; every other verb must carry none.
            val snoozeValid = if (verb == NotificationVerb.SNOOZE) snooze != null else snoozeName == null
            val nonce = nonceOf(intent)
            return if (ours && verb != null && key != null && snoozeValid && nonce != null) {
                NotificationAction(verb, key, nonce, snooze)
            } else {
                null
            }
        }
    }
}

private fun validKey(value: String): String? = value.takeIf { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }

/** Reads the nonce extra; a malformed extras bundle from a foreign sender reads as no nonce. */
private fun nonceOf(intent: Intent): String? = try {
    intent.getStringExtra(InterventionExtras.NONCE)?.takeIf { it.isNotBlank() && it.length <= MAX_NONCE_LENGTH }
} catch (expected: RuntimeException) {
    null
}

/**
 * Builds every PendingIntent of an intervention: explicit, `FLAG_IMMUTABLE`, with the nonce extra (red team items 8
 * and 10). Activity intents go straight to the activity (no trampoline, Android 12); actions go to the non-exported
 * [NotificationActionReceiver].
 */
class InterventionIntents(private val context: Context, private val activityIntents: InterventionActivityIntents) {
    /** The content intent, or Listen/Watch with [autoplay]; null when the activity intent is not explicit. */
    fun open(intervention: RenderedIntervention, autoplay: Boolean): PendingIntent? {
        val base = activityIntents.launchIntent(context)
        if (base.component == null) return null
        val intent = base
            .setAction(Intent.ACTION_VIEW)
            .setData(InterventionUris.open(intervention.decisionKey, autoplay))
            .putExtra(InterventionExtras.NONCE, intervention.nonce)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, FLAGS)
    }

    fun action(intervention: RenderedIntervention, verb: NotificationVerb, snooze: SnoozeOption? = null): PendingIntent {
        val intent = Intent(verb.intentAction)
            .setClass(context, NotificationActionReceiver::class.java)
            .setData(InterventionUris.action(verb, intervention.decisionKey, snooze))
            .putExtra(InterventionExtras.NONCE, intervention.nonce)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, FLAGS)
    }

    private companion object {
        /** The data URI tells PendingIntents apart, so one request code is enough. */
        const val REQUEST_CODE = 0
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
