package dev.agentle.connectors.android.permissions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.agentle.connectors.android.collectors.activity.ActivityTransitionReceiver

/**
 * The only place `:connectors:android` creates PendingIntents (docs/ARCHITECTURE.md §14, docs/research/02 §6.9,
 * T-ARCH-04). Every PendingIntent is immutable and explicit, except the Activity Recognition one: Play services fills in
 * the `ActivityTransitionResult` extras, so it is mutable on API 31+ and always explicit (an implicit mutable
 * PendingIntent throws for apps targeting 34+). Robolectric does not enforce either rule, so tests assert the flags.
 */
public class PendingIntentFactory(private val context: Context) {
    /** The Activity Recognition transitions callback: explicit component, FLAG_UPDATE_CURRENT, FLAG_MUTABLE on 31+. */
    public fun activityTransitions(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ACTIVITY_TRANSITIONS,
        activityTransitionsIntent(context),
        activityTransitionsFlags(Build.VERSION.SDK_INT),
    )

    /** An immutable broadcast PendingIntent; [intent] must name a component of this app. */
    public fun immutableBroadcast(requestCode: Int, intent: Intent): PendingIntent {
        requireExplicit(intent)
        return PendingIntent.getBroadcast(context, requestCode, intent, IMMUTABLE_FLAGS)
    }

    /** An immutable activity PendingIntent (notification content intents); [intent] must name a component. */
    public fun immutableActivity(requestCode: Int, intent: Intent): PendingIntent {
        requireExplicit(intent)
        return PendingIntent.getActivity(context, requestCode, intent, IMMUTABLE_FLAGS)
    }

    private fun requireExplicit(intent: Intent) {
        require(intent.component != null) { "PendingIntents must be explicit" }
    }

    public companion object {
        public const val REQUEST_ACTIVITY_TRANSITIONS: Int = 0x4147_0001
        public const val ACTION_ACTIVITY_TRANSITIONS: String = "dev.agentle.connectors.android.action.ACTIVITY_TRANSITIONS"

        /** `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`. */
        public const val IMMUTABLE_FLAGS: Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** `FLAG_UPDATE_CURRENT`, plus `FLAG_MUTABLE` on API 31+ (mutable is the default below 31). */
        public fun activityTransitionsFlags(sdkInt: Int): Int = if (sdkInt >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        /** The explicit intent behind [activityTransitions]; the action lets the receiver's allow-list match it. */
        public fun activityTransitionsIntent(context: Context): Intent =
            Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION_ACTIVITY_TRANSITIONS).setPackage(context.packageName)
    }
}
