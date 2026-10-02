package dev.agentle.connectors.api

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.CapabilityAvailability
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PlannedStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CapabilityRegistryTest {
    private val registry = CapabilityRegistry.load()

    @Test
    fun `registry loads all 53 capabilities with unique ids`() {
        assertThat(registry.all).hasSize(53)
        assertThat(registry.all.map { it.id }.toSet()).hasSize(53)
    }

    @Test
    fun `planned status counts match docs research 01 summary`() {
        assertThat(registry.byStatus(PlannedStatus.IMPLEMENT)).hasSize(38)
        assertThat(registry.byStatus(PlannedStatus.IMPLEMENT_DEBUG_ONLY)).hasSize(3)
        assertThat(registry.byStatus(PlannedStatus.DEFER)).hasSize(9)
        assertThat(registry.byStatus(PlannedStatus.DOCUMENT_UNAVAILABLE)).hasSize(3)
        assertThat(registry.implemented(includeDebugOnly = false)).hasSize(38)
        assertThat(registry.implemented(includeDebugOnly = true)).hasSize(41)
    }

    @Test
    fun `every Permission Center category is represented`() {
        assertThat(registry.byCategory().keys).containsExactlyElementsIn(CapabilityCategory.entries)
    }

    @Test
    fun `lookup by category returns that group in registry order`() {
        val bluetooth = registry.byCategory(CapabilityCategory.BLUETOOTH).map { it.id }

        assertThat(bluetooth)
            .containsExactly("bluetooth_adapter_state", "bluetooth_connected_devices", "bluetooth_nearby_scan")
            .inOrder()
        assertThat(registry.byCategory(CapabilityCategory.HEALTH).map { it.id }).contains("health_connect_on_device_steps")
    }

    @Test
    fun `lookup by id finds known ids and rejects unknown ones`() {
        assertThat(registry["call_state"]?.category).isEqualTo(CapabilityCategory.COMMUNICATION)
        assertThat("call_state" in registry).isTrue()
        assertThat(registry["no_such_capability"]).isNull()
        assertThrows<IllegalStateException> { registry.require("no_such_capability") }
    }

    @Test
    fun `ids that are not snake_case are rejected`() {
        val bad = DataCapability(id = "Bad-Id", name = "x", category = CapabilityCategory.APPS, plannedStatus = PlannedStatus.DEFER)

        assertThrows<IllegalArgumentException> { CapabilityRegistry(listOf(bad)) }
    }

    @Test
    fun `duplicate ids are rejected`() {
        val cap = DataCapability(id = "dup_id", name = "x", category = CapabilityCategory.APPS, plannedStatus = PlannedStatus.DEFER)

        assertThrows<IllegalArgumentException> { CapabilityRegistry(listOf(cap, cap.copy(name = "y"))) }
    }

    @Test
    fun `every registry id matches the id syntax`() {
        assertThat(registry.ids.filterNot { CapabilityRegistry.ID_PATTERN.matches(it) }).isEmpty()
    }

    @Test
    fun `CapabilityIds constants and the bundled registry are identical`() {
        assertThat(CapabilityIds.ALL).containsExactlyElementsIn(registry.ids).inOrder()
    }

    @Test
    fun `notification listener is special access with background support`() {
        val cap = registry.require("notification_events_metadata")
        assertThat(cap.requiresSettingsGrant).isTrue()
        assertThat(cap.requiresRuntimePermission).isFalse()
        assertThat(cap.status).isEqualTo(CapabilityAvailability.AVAILABLE_WITH_SPECIAL_ACCESS)
    }

    @Test
    fun `sms and call log are documented as unavailable`() {
        assertThat(registry.require("sms_metadata").status).isEqualTo(CapabilityAvailability.UNAVAILABLE)
        assertThat(registry.require("call_log_metadata").plannedStatus).isEqualTo(PlannedStatus.DOCUMENT_UNAVAILABLE)
    }

    @Test
    fun `none special access is normalized`() {
        val battery = registry.require("battery_state")
        assertThat(battery.specialAccess).isNull()
        assertThat(battery.requiresSettingsGrant).isFalse()
    }
}
