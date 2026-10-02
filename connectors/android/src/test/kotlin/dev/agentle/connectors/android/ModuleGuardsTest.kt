package dev.agentle.connectors.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityRegistry
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Plain JVM guards: manifest audit, coverage-id ownership, and JUnit 4 only. */
class ModuleGuardsTest {
    private val moduleDir: File = listOf(File("."), File("connectors/android")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(moduleDir, "src/main/AndroidManifest.xml")).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `the manifest requests exactly the expected permissions`() {
        val permissions = manifest().children("uses-permission").map { it.getAttributeNS(ns, "name") }.toSet()
        assertThat(permissions).containsNoneOf(
            "android.permission.READ_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.QUERY_ALL_PACKAGES",
        )
        assertThat(permissions).containsAtLeast(
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.READ_CALENDAR",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )
    }

    @Test
    fun `every component is unexported except the Bluetooth ACL receiver and nothing runs in another process`() {
        val root = manifest()
        val components = listOf("activity", "service", "receiver", "provider").flatMap { root.children(it) }
        assertThat(components).isNotEmpty()
        components.forEach { component ->
            val name = component.getAttributeNS(ns, "name")
            val expected = if (name == ".receivers.BluetoothAclReceiver") "true" else "false"
            assertWithMessage(name).that(component.getAttributeNS(ns, "exported")).isEqualTo(expected)
            assertWithMessage(name).that(component.hasAttributeNS(ns, "process")).isFalse()
        }
    }

    @Test
    fun `coverage ids are registry capability ids with exactly one owner`() {
        val registry = CapabilityRegistry.load().all.map { it.id }.toSet()
        val owners = CoverageIds.OWNERS
        assertThat(registry).containsAtLeastElementsIn(owners.keys)
        // Pinned (coverage-id correction): these three belong to usage, notifications and call state.
        assertThat(owners[CapabilityIds.APP_USAGE_EVENTS]).isEqualTo(AndroidConnectorIds.USAGE)
        assertThat(owners[CapabilityIds.NOTIFICATION_EVENTS_METADATA]).isEqualTo(AndroidConnectorIds.NOTIFICATIONS)
        assertThat(owners[CapabilityIds.CALL_STATE]).isEqualTo(AndroidConnectorIds.CALL)
    }

    @Test
    fun `tests use JUnit 4 only`() {
        val offenders = File(moduleDir, "src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ModuleGuardsTest.kt" }
            .filter { "org.junit." + "jupiter" in it.readText() }
            .map { it.name }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
