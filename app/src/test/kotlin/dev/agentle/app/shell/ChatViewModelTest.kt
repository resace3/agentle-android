package dev.agentle.app.shell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [37])
class ChatViewModelTest {
    @Before
    fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a reply check that cannot load says so in the chat instead of stopping the app`() {
        val chat = chatWith { throw ExceptionInInitializerError(PatternSyntaxException("Syntax error", "}}", 0)) }

        chat.send("how many sensors does this phone have?")

        assertThat(chat.messages.value.map { it.text }).containsExactly(
            "how many sensors does this phone have?",
            "ChatGPT couldn't answer (unexpected_ExceptionInInitializerError).",
        ).inOrder()
        assertThat(chat.sending.value).isFalse()
    }

    @Test
    fun `a sign-in that cannot refresh yet says when to try again`() {
        val chat = chatWith { Outcome.failure(AppError.RateLimited(90.seconds, "refresh_not_ready")) }

        chat.send("hello")

        assertThat(chat.messages.value.last().text).isEqualTo("ChatGPT can't take a question right now. Try again in 2 min (rate_limited).")
    }

    @Test
    fun `an answer is added to the chat`() {
        val chat = chatWith { Outcome.success(ChatReply("This phone has 23 sensors.")) }

        chat.send("how many sensors?")

        assertThat(chat.messages.value.last()).isEqualTo(ChatMessage(fromUser = false, text = "This phone has 23 sensors."))
    }

    @Test
    fun `an answer that fails Agentle's checks says nothing was saved`() {
        val chat = chatWith { Outcome.failure(AppError.ValidationError(listOf("E116", "E117"))) }

        chat.send("make me a sleep screen")

        assertThat(chat.messages.value.last().text).isEqualTo(
            "ChatGPT's answer didn't pass Agentle's checks, so nothing was saved. Try asking in other words (E116, E117).",
        )
    }

    @Test
    fun `a change request sends the dashboard and its new screen replaces it in place`() {
        val store = DashboardStore(ApplicationProvider.getApplicationContext())
        val target = store.dashboards.value.first()
        val changed = target.copy(title = "Sleep this month", days = 30)
        val asked = mutableListOf<DashboardSpec?>()
        val chat = chatWith(store) { editing ->
            asked += editing
            Outcome.success(ChatReply("Done.", editing?.let { changed }))
        }

        chat.change(target.id)
        chat.send("make it 30 days")

        assertThat(asked).containsExactly(target)
        assertThat(store.dashboards.value.first()).isEqualTo(changed)
        assertThat(store.dashboards.value.count { it.id == target.id }).isEqualTo(1)
        assertThat(chat.messages.value.last().dashboard).isEqualTo(changed)

        chat.stopChanging()
        chat.send("hello")

        assertThat(asked.last()).isNull()
    }

    private fun chatWith(
        store: DashboardStore = DashboardStore(ApplicationProvider.getApplicationContext()),
        answer: suspend (editing: DashboardSpec?) -> Outcome<ChatReply>,
    ) = ChatViewModel(
        object : ChatPort {
            override val connected: Flow<Boolean> = flowOf(true)

            override val sharingAllowed: Flow<Boolean> = flowOf(true)

            override suspend fun allowSharing(): Outcome<Unit> = Outcome.success(Unit)

            override suspend fun send(history: List<ChatMessage>, editing: DashboardSpec?): Outcome<ChatReply> = answer(editing)
        },
        store,
    )
}
