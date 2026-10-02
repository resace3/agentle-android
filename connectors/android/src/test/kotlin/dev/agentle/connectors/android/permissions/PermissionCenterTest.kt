package dev.agentle.connectors.android.permissions

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.android.AndroidCollectorsGraph
import dev.agentle.connectors.android.CollectorPorts
import dev.agentle.connectors.android.FakeWriter
import dev.agentle.connectors.android.MemorySettingsStore
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityRegistry
import dev.agentle.connectors.api.PermissionRequestStore
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PlannedStatus
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class MemoryPermissionRequestStore : PermissionRequestStore {
    val requested = LinkedHashSet<String>()
    val visited = LinkedHashSet<String>()
    val permanent = LinkedHashSet<String>()

    override suspend fun requestedPermissions(): Set<String> = requested.toSet()

    override suspend fun markRequested(permissions: Collection<String>) {
        requested += permissions
    }

    override suspend fun settingsVisited(): Set<String> = visited.toSet()

    override suspend fun markSettingsVisited(specialAccess: String) {
        visited += specialAccess
    }

    override suspend fun permanentlyDenied(): Set<String> = permanent.toSet()

    override suspend fun recordUiVerdicts(verdicts: Map<String, Boolean>) {
        verdicts.forEach { (permission, permanentlyDenied) -> if (permanentlyDenied) permanent += permission else permanent -= permission }
    }
}

/** The resolver matrix over the whole registry, and the background vs UI pass split (red team testing-build-15). */
@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class PermissionCenterTest {
    private val registry = CapabilityRegistry.load()

    private fun TestScope.graph(store: MemoryPermissionRequestStore = MemoryPermissionRequestStore()) = AndroidCollectorsGraph(
        ApplicationProvider.getApplicationContext(),
        CollectorPorts(clock = TestAgentleClock(), writer = FakeWriter(), settings = MemorySettingsStore(), permissionRequests = store),
        scope = backgroundScope,
    )

    private fun grant(vararg permissions: String) {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(*permissions)
    }

    @Test
    fun `every registry capability resolves to a status and capabilities not in this build are restricted`() = runTest {
        val statuses = graph().permissionCenter.refresh()

        assertThat(statuses.keys).containsExactlyElementsIn(registry.all.map { it.id })
        registry.all.filter { it.plannedStatus == PlannedStatus.DEFER || it.plannedStatus == PlannedStatus.DOCUMENT_UNAVAILABLE }
            .forEach { capability ->
                val status = statuses.getValue(capability.id)
                assertThat(status.state.canCollect).isFalse()
                assertThat(status.blockers).contains(Blocker.NOT_IN_THIS_BUILD)
            }
    }

    @Test
    fun `a runtime permission capability follows the grant`() = runTest {
        val center = graph().permissionCenter
        assertThat(center.refresh(listOf(CapabilityIds.CALENDAR_EVENTS)).getValue(CapabilityIds.CALENDAR_EVENTS).state)
            .isEqualTo(PermissionState.DENIED)

        grant(Manifest.permission.READ_CALENDAR)

        assertThat(center.refresh(listOf(CapabilityIds.CALENDAR_EVENTS)).getValue(CapabilityIds.CALENDAR_EVENTS).state.canCollect)
            .isTrue()
    }

    @Test
    fun `a background pass never decides DENIED_PERMANENTLY itself`() = runTest {
        val store = MemoryPermissionRequestStore().apply { requested += Manifest.permission.READ_CALENDAR }
        val center = graph(store).permissionCenter

        assertThat(center.refresh(listOf(CapabilityIds.CALENDAR_EVENTS)).getValue(CapabilityIds.CALENDAR_EVENTS).state)
            .isNotEqualTo(PermissionState.DENIED_PERMANENTLY)

        store.permanent += Manifest.permission.READ_CALENDAR
        assertThat(center.refresh(listOf(CapabilityIds.CALENDAR_EVENTS)).getValue(CapabilityIds.CALENDAR_EVENTS).state)
            .isEqualTo(PermissionState.DENIED_PERMANENTLY)
    }

    @Test
    fun `requestable permissions are empty for capabilities not in this build`() = runTest {
        val center = graph().permissionCenter
        registry.all.filter { it.plannedStatus == PlannedStatus.DEFER }.forEach {
            assertThat(center.requestablePermissions(it.id)).isEmpty()
        }
        assertThat(center.requestablePermissions(CapabilityIds.CALENDAR_EVENTS)).contains(Manifest.permission.READ_CALENDAR)
    }
}
