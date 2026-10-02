package dev.agentle.core.ui.status

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import org.junit.Test

/** Status is never color only: every state has its own icon and label (spec §6, §45). */
class StatusMappersTest {
    @Test
    fun `all ten permission states have distinct icons and distinct labels`() {
        val specs = PermissionState.entries.map { it.toStatusSpec() }
        assertThat(specs).hasSize(10)
        assertWithMessage("labels").that(specs.map { it.label }.toSet()).hasSize(PermissionState.entries.size)
        assertWithMessage("icons").that(specs.map { it.icon.name }.toSet()).hasSize(PermissionState.entries.size)
    }

    @Test
    fun `permission tones follow how usable the capability is`() {
        val tones = PermissionState.entries.associateWith { it.toStatusSpec().tone }
        assertThat(tones[PermissionState.ALLOWED]).isEqualTo(StatusTone.POSITIVE)
        assertThat(tones[PermissionState.BACKGROUND_ALLOWED]).isEqualTo(StatusTone.POSITIVE)
        assertThat(tones[PermissionState.FOREGROUND_ONLY]).isEqualTo(StatusTone.CAUTION)
        assertThat(tones[PermissionState.PARTIALLY_ALLOWED]).isEqualTo(StatusTone.CAUTION)
        assertThat(tones[PermissionState.UNAVAILABLE]).isEqualTo(StatusTone.CAUTION)
        assertThat(tones[PermissionState.DENIED]).isEqualTo(StatusTone.NEGATIVE)
        assertThat(tones[PermissionState.DENIED_PERMANENTLY]).isEqualTo(StatusTone.NEGATIVE)
        assertThat(tones[PermissionState.REQUIRES_SETTINGS]).isEqualTo(StatusTone.NEGATIVE)
        assertThat(tones[PermissionState.RESTRICTED_BY_ANDROID]).isEqualTo(StatusTone.NEUTRAL)
        assertThat(tones[PermissionState.UNSUPPORTED_ON_DEVICE]).isEqualTo(StatusTone.NEUTRAL)
    }

    @Test
    fun `connection states have distinct labels`() {
        val specs = ConnectionStatus.entries.map { it.toStatusSpec() }
        assertThat(specs.map { it.label }.toSet()).hasSize(ConnectionStatus.entries.size)
        assertThat(ConnectionStatus.CONNECTED.toStatusSpec().tone).isEqualTo(StatusTone.POSITIVE)
        assertThat(ConnectionStatus.NEEDS_REAUTH.toStatusSpec().tone).isEqualTo(StatusTone.NEGATIVE)
        assertThat(ConnectionStatus.ERROR.toStatusSpec().tone).isEqualTo(StatusTone.NEGATIVE)
        assertThat(ConnectionStatus.CONNECTING.toStatusSpec().tone).isEqualTo(StatusTone.INFO)
    }

    @Test
    fun `sync states have distinct labels and icons`() {
        val specs = SyncStatus.entries.map { it.toStatusSpec() }
        assertThat(specs.map { it.label }.toSet()).hasSize(SyncStatus.entries.size)
        assertThat(specs.map { it.icon.name }.toSet()).hasSize(SyncStatus.entries.size)
        assertThat(SyncStatus.FAILED.toStatusSpec().tone).isEqualTo(StatusTone.NEGATIVE)
        assertThat(SyncStatus.PARTIAL.toStatusSpec().tone).isEqualTo(StatusTone.CAUTION)
    }
}
