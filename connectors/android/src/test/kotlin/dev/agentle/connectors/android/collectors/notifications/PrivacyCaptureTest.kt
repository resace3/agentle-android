package dev.agentle.connectors.android.collectors.notifications

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.android.TestRuntime
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.NotificationContentPurger
import dev.agentle.core.model.NotificationPayload
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class PrivacyCaptureTest {
    @Test
    fun `one-time codes are recognised in English and the device language`() {
        assertThat(OneTimeCodeFilter.matches("Your verification code is 482913", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("G-123456 is your Google code", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("OTP: 1234", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Ihr Bestätigungscode lautet 123 456", Locale.GERMANY)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Votre code de vérification : 87654321", Locale.FRANCE)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Lunch at 12 with Sam?", Locale.US)).isFalse()
        assertThat(OneTimeCodeFilter.matches("Order 123456789012 shipped", Locale.US)).isFalse()
        assertThat(OneTimeCodeFilter.matches(null, Locale.US)).isFalse()
        // A Spanish 2FA message on an en_US device, and alphanumeric codes.
        assertThat(OneTimeCodeFilter.matches("Tu código de verificación es 482913", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Su clave de acceso: X7K-9Q2", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Your login code: AB12CD", Locale.US)).isTrue()
        assertThat(OneTimeCodeFilter.matches("Meet at gate B12 for the security check", Locale.US)).isFalse()
    }

    private fun snapshot(pkg: String, key: String, text: String?) = NotificationSnapshot(
        key = key,
        packageName = pkg,
        postTimeMs = 1_000,
        channelId = "c",
        category = null,
        ongoing = false,
        groupSummary = false,
        foregroundService = false,
        localOnly = false,
        hasText = text != null,
        title = text?.let { "Title" },
        text = text,
    )

    @Test
    fun `the default SMS app and dialer never have content stored and a new default is purged once`() = runTest {
        val t = TestRuntime(backgroundScope)
        t.settings.update {
            it.copy(notificationContentPackages = setOf("sms.a", "sms.b", "chat"), notificationContentFromSmsAndDialer = true)
        }
        var defaults = setOf("sms.a")
        val purged = ArrayList<String>()
        val collector = NotificationCollector(
            t.runtime,
            handlers = { defaults },
            sdkInt = 34,
            purger = NotificationContentPurger { purged += it },
        )
        collector.start()

        collector.onPosted(snapshot("sms.a", "k1", "hello"))
        collector.onPosted(snapshot("chat", "k2", "hi"))
        runCurrent()
        collector.flush()
        assertThat(purged).containsExactly("sms.a")

        defaults = setOf("sms.b")
        collector.refreshHandlers()
        collector.refreshHandlers()
        collector.onPosted(snapshot("sms.b", "k3", "secret"))
        runCurrent()
        collector.flush()

        assertThat(purged).containsExactly("sms.a", "sms.b").inOrder()
        val payloads = t.writer.rows.values.map { it.payload as NotificationPayload }.associateBy { it.packageName }
        assertThat(payloads.getValue("sms.a").text).isNull()
        assertThat(payloads.getValue("sms.b").text).isNull()
        assertThat(payloads.getValue("chat").text).isEqualTo("hi")
        assertThat(payloads.getValue("sms.b").hasText).isTrue()
    }

    @Test
    fun `the known default set survives a restart`() = runTest {
        val t = TestRuntime(backgroundScope)
        val purged = ArrayList<String>()
        NotificationCollector(t.runtime, handlers = { setOf("sms") }, purger = NotificationContentPurger { purged += it })
            .refreshHandlers()
        NotificationCollector(t.runtime, handlers = { setOf("sms") }, purger = NotificationContentPurger { purged += it })
            .refreshHandlers()
        assertThat(purged).containsExactly("sms")
        assertThat(CollectionSettings().calendarTitles).isFalse()
    }
}
