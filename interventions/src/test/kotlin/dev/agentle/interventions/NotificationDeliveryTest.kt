package dev.agentle.interventions

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.interventions.card.InMemoryInterventionCardStore
import dev.agentle.interventions.delivery.NotificationStateReader
import dev.agentle.interventions.notification.InterventionChannels
import dev.agentle.interventions.notification.InterventionIntents
import dev.agentle.interventions.notification.InterventionLink
import dev.agentle.interventions.notification.InterventionNotifier
import dev.agentle.interventions.notification.NotificationActionReceiver
import dev.agentle.interventions.notification.NotifyResult
import dev.agentle.interventions.ports.InterventionResponse
import dev.agentle.interventions.ports.ResponseKind
import dev.agentle.interventions.ports.ResponseSurface
import dev.agentle.interventions.ports.ResponseVerdict
import dev.agentle.interventions.response.InterventionResponses
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/** ShadowNotificationManager has no `areNotificationsPaused`. */
@Implements(NotificationManager::class)
@Suppress("ProtectedMemberInFinalClass")
class PausableShadowNotificationManager : ShadowNotificationManager() {
    @Implementation(minSdk = Build.VERSION_CODES.Q)
    protected fun areNotificationsPaused(): Boolean = paused

    companion object {
        var paused = false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37], shadows = [PausableShadowNotificationManager::class])
class NotificationDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channels = InterventionChannels(context)
    private val notifier = InterventionNotifier(
        context,
        channels,
        InterventionIntents(context) { Intent().setClassName(it, "dev.agentle.app.MainActivity") },
        Fixtures.clock(),
        Logger.NONE,
    )
    private val reader = NotificationStateReader(context, channels)

    @Before
    fun grant() {
        PausableShadowNotificationManager.paused = false
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(key: String = Fixtures.intervention().decisionKey): Notification =
        shadowOf(manager).getNotification(key, InterventionNotifier.NOTIFICATION_ID)

    @Test
    fun `posts generic text with tag, local only and a generic public version`() {
        val intervention = Fixtures.intervention()
        assertThat(notifier.post(PreparedDelivery(intervention))).isEqualTo(NotifyResult.Posted)
        val n = posted()
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo(Fixtures.GENERIC_TITLE)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo(Fixtures.GENERIC_BODY)
        assertThat(n.flags and Notification.FLAG_LOCAL_ONLY).isNotEqualTo(0)
        assertThat(n.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        val public = n.publicVersion.extras
        assertThat(public.getCharSequence(Notification.EXTRA_TITLE).toString()).doesNotContain("4,210")
        assertThat(public.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).doesNotContain("84")
        assertThat(notifier.isActive(intervention.decisionKey)).isTrue()
    }

    @Test
    fun `detailed posts show the in-app text and bridge when allowed`() {
        notifier.post(PreparedDelivery(Fixtures.intervention(detailed = true, localOnly = false)))
        val n = posted()
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo(Fixtures.IN_APP_TITLE)
        assertThat(n.flags and Notification.FLAG_LOCAL_ONLY).isEqualTo(0)
    }

    @Test
    fun `re-post with the same tag updates in place`() {
        notifier.post(PreparedDelivery(Fixtures.intervention()))
        notifier.post(PreparedDelivery(Fixtures.intervention()))
        assertThat(shadowOf(manager).allNotifications).hasSize(1)
    }

    @Test
    fun `every pending intent is explicit and immutable and carries the nonce`() {
        notifier.post(PreparedDelivery(Fixtures.intervention()))
        val n = posted()
        val content = shadowOf(n.contentIntent)
        assertThat(content.isImmutable).isTrue()
        assertThat(content.savedIntent.component).isNotNull()
        assertThat(InterventionLink.from(content.savedIntent)?.nonce).isEqualTo("nonce-1")
        val actions = n.actions.map { shadowOf(it.actionIntent) } + shadowOf(n.deleteIntent)
        actions.forEach {
            assertThat(it.isImmutable).isTrue()
            assertThat(it.isBroadcast).isTrue()
            assertThat(it.savedIntent.component?.className).isEqualTo(NotificationActionReceiver::class.java.name)
        }
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Snooze 30 min", "Not now", "Stop this JITAI").inOrder()
    }

    @Test
    fun `prerequisite fails when notifications are off, paused or the channel is blocked`() {
        val category = Fixtures.intervention().category
        assertThat(reader.read().prerequisite(category).met).isTrue()
        PausableShadowNotificationManager.paused = true
        assertThat(reader.read().prerequisite(category).notificationsPaused).isTrue()
        PausableShadowNotificationManager.paused = false
        shadowOf(manager).setNotificationsEnabled(false)
        assertThat(reader.read().prerequisite(category).met).isFalse()
        shadowOf(manager).setNotificationsEnabled(true)
        channels.ensure()
        manager.getNotificationChannel(channels.channelId(category)).also {
            it.importance = NotificationManager.IMPORTANCE_NONE
            manager.createNotificationChannel(it)
        }
        shadowOf(manager).createNotificationChannel(
            manager.getNotificationChannel(channels.channelId(category)).apply { importance = NotificationManager.IMPORTANCE_NONE },
        )
        assertThat(reader.read().prerequisite(category).channelImportanceNone).isTrue()
    }

    @Test
    @Config(sdk = [37])
    fun `denied permission fails the prerequisite`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(reader.read().prerequisite(Fixtures.intervention().category).notificationsEnabled).isFalse()
    }

    @Test
    fun `a wrong nonce changes nothing, an accepted response cancels`() = runTest {
        val cards = InMemoryInterventionCardStore()
        var verdict = ResponseVerdict.REJECTED
        val responses = InterventionResponses({ Outcome.success(verdict) }, notifier, cards, Logger.NONE)
        val key = Fixtures.intervention().decisionKey
        notifier.post(PreparedDelivery(Fixtures.intervention()))
        responses.apply(InterventionResponse(key, "bad", ResponseKind.DISMISSED, ResponseSurface.NOTIFICATION))
        assertThat(notifier.isActive(key)).isTrue()
        verdict = ResponseVerdict.RECORDED
        responses.apply(InterventionResponse(key, "nonce-1", ResponseKind.OPENED, ResponseSurface.NOTIFICATION))
        assertThat(notifier.isActive(key)).isFalse()
    }

    @Test
    fun `the action receiver is not exported`() {
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, NotificationActionReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        assertThat(info.exported).isFalse()
    }
}
