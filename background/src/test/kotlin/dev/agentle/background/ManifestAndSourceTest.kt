package dev.agentle.background

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.receiver.SystemEventReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class ManifestAndSourceTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { list ->
        (0 until list.length).map { list.item(it) as org.w3c.dom.Element }
    }

    @Test
    fun `manifest declares only RECEIVE_BOOT_COMPLETED and a non-exported receiver`() {
        assertThat(elements("uses-permission").map { it.getAttributeNS(ns, "name") })
            .containsExactly("android.permission.RECEIVE_BOOT_COMPLETED")
        val receiver = elements("receiver").single()
        assertThat(receiver.getAttributeNS(ns, "exported")).isEqualTo("false")
        assertThat(elements("service")).isEmpty()
        val actions = elements("action").map { it.getAttributeNS(ns, "name") }
        assertThat(actions).containsExactly(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            SystemEventReceiver.ACTION_TIMEZONE_OFFSET_CHANGED,
        )
        actions.forEach { assertThat(SystemEventReceiver.reasonFor(it)).isNotNull() }
    }

    @Test
    fun `receiver allow-list maps actions and ignores everything else`() {
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_BOOT_COMPLETED)).isEqualTo(ReconcileReason.BOOT)
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_TIME_CHANGED)).isEqualTo(ReconcileReason.CLOCK)
        assertThat(SystemEventReceiver.reasonFor(SystemEventReceiver.ACTION_TIMEZONE_OFFSET_CHANGED)).isEqualTo(ReconcileReason.OFFSET)
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_BOOT_COMPLETED)!!.debounced).isFalse()
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_MY_PACKAGE_REPLACED)!!.debounced).isFalse()
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_TIMEZONE_CHANGED)!!.debounced).isTrue()
        assertThat(SystemEventReceiver.reasonFor(Intent.ACTION_SCREEN_ON)).isNull()
        assertThat(SystemEventReceiver.reasonFor(null)).isNull()
    }

    private val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.associateWith { it.readText() }

    @Test
    fun `periodic work is never enqueued with REPLACE`() {
        sources.values.forEach { assertThat(it).doesNotContain("ExistingPeriodicWorkPolicy.REPLACE") }
        sources.values.forEach { assertThat(it).doesNotContain("CANCEL_AND_REENQUEUE") }
    }

    @Test
    fun `only the gateway calls WorkManager enqueue, update and cancel`() {
        val calls = Regex("""\.(enqueueUnique\w*|enqueue\(|updateWork|cancel\w*Work\w*)""")
        val offenders = sources.filter { (file, text) -> file.name != "WorkGateway.kt" && calls.containsMatchIn(text) }
        assertThat(offenders.keys.map { it.name }).isEmpty()
        assertThat(sources.keys.map { it.name }).contains("WorkGateway.kt")
    }

    @Test
    fun `no exception messages reach logs`() {
        sources.values.forEach { assertThat(it).doesNotContain(".message") }
        sources.values.forEach { assertThat(it).doesNotContain("System.currentTimeMillis") }
    }
}
