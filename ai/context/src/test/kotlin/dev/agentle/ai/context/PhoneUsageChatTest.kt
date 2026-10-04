package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.fakes.ai.FakeAiProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** "Tell me about my phone usage recently" from the chat tab, through the real engine and guard, to the provider. */
class PhoneUsageChatTest {
    private val clock = TestAgentleClock(START, KATHMANDU)

    private val loader = object : PhoneUsageLoader {
        override suspend fun screenSessions(range: ClosedOpenRange) = listOf(
            ScreenSession(START - 30.hours, 95 * 60_000L),
            ScreenSession(START - 2.hours, 40 * 60_000L),
        )

        override suspend fun unlocks(range: ClosedOpenRange): List<Instant> = listOf(START - 30.hours, START - 29.hours, START - 1.hours)

        override suspend fun appSessions(range: ClosedOpenRange) = listOf(
            AppSession("YouTube", 50 * 60_000L),
            AppSession("Chrome", 20 * 60_000L),
            AppSession("YouTube", 10 * 60_000L),
        )
    }

    private var fake: FakeAiProvider? = null

    private val context = AiContext(
        dataSource = PhoneUsageDataSource(loader) { clock.now() },
        consentStore = InMemoryConsentStore(),
        account = FakeAccount(),
        auditLog = InMemoryAuditLog(),
        clock = clock,
        providerFactory = { verifier -> FakeAiProvider(verifier).also { fake = it } },
        options = AiContextOptions(accountSalt = "install-salt", random = Random(7)),
    )

    @Test
    fun `a phone usage question sends screen time and top apps and returns the reply`() = runTest {
        context.consent.grant(PHONE_USAGE, AiPurpose.GENERAL_QUESTION).getOrThrow()
        fake!!.respondWith(AiPurpose.GENERAL_QUESTION, null, "You averaged about 68 minutes of screen time a day.")

        val envelope = context.engine.build(AiPurpose.GENERAL_QUESTION, "Tell me about my phone usage recently").getOrThrow()
        val reply = context.guard.analyze(envelope).getOrThrow()

        assertThat(reply.text).isEqualTo("You averaged about 68 minutes of screen time a day.")
        val call = fake!!.journal.single()
        assertThat(call.sent).isTrue()
        assertThat(call.categories).containsExactly(AiDataCategory.SCREEN_TIME_TOTALS, AiDataCategory.APP_IDENTITY)
        assertThat(call.sentDataInput).contains("screen.minutes_daily_avg")
        assertThat(call.sentDataInput).contains("screen.unlocks_daily_avg")
        assertThat(call.sentDataInput).contains("YouTube")
        assertThat(call.sentUserInput).contains("phone usage")
    }

    @Test
    fun `without the user's consent nothing about phone usage is sent`() = runTest {
        val built = context.engine.build(AiPurpose.GENERAL_QUESTION, "Tell me about my phone usage recently")

        val sentData = built.errorOrNull()?.let { emptyList<String>() } ?: run {
            context.guard.analyze(built.getOrThrow())
            fake!!.journal.map { it.sentDataInput }
        }
        assertThat(sentData.none { "screen." in it || "YouTube" in it }).isTrue()
        assertThat(built.errorOrNull()).isNotInstanceOf(AppError.Unexpected::class.java)
    }

    private companion object {
        val PHONE_USAGE = setOf(AiDataCategory.SCREEN_TIME_TOTALS, AiDataCategory.APP_IDENTITY)
    }
}
