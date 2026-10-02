package dev.agentle.connectors.api

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.CapabilityAvailability
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.PlannedStatus
import org.junit.jupiter.api.Test

class CapabilityRegistryTest {
    private val registry = CapabilityRegistry.load()

    @Test
    fun `registry loads all 53 capabilities with unique ids`() {
        assertThat(registry.all).hasSize(53)
        assertThat(registry.all.map { it.id }.toSet()).hasSize(53)
    }

    @Test
    fun `every Permission Center category is represented`() {
        assertThat(registry.byCategory().keys).containsExactlyElementsIn(CapabilityCategory.entries)
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
