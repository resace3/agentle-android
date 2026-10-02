package dev.agentle.background

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.receiver.SystemEventReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class SystemEventReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = CopyOnWriteArrayList<Set<ReconcileReason>>()
    private val finished = CountDownLatch(1)
    private var asyncCalls = 0

    private val receiver = object : SystemEventReceiver() {
        override fun requester(context: Context): ReconcileRequester = ReconcileRequester { requests += it }

        override fun goAsyncFinisher(): () -> Unit {
            asyncCalls++
            return { finished.countDown() }
        }
    }

    @Test
    fun `boot requests one reconcile and finishes the pending result`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(requests).containsExactly(setOf(ReconcileReason.BOOT))
        assertThat(asyncCalls).isEqualTo(1)
    }

    @Test
    fun `unknown action does nothing`() {
        receiver.onReceive(context, Intent("dev.example.SOMETHING"))
        receiver.onReceive(context, Intent())
        assertThat(asyncCalls).isEqualTo(0)
        assertThat(requests).isEmpty()
    }
}
