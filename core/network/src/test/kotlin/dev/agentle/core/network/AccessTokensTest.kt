package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AccessTokensTest {
    private class FakeSource(var tokens: ArrayDeque<Outcome<String>>) : AccessTokenSource {
        val calls = mutableListOf<Pair<Boolean, String?>>()

        override suspend fun accessToken(forceRefresh: Boolean, rejected: String?): Outcome<String> {
            calls += forceRefresh to rejected
            return tokens.removeFirst()
        }
    }

    @Test
    fun `a 401 refreshes once and retries with the new token`() = runTest {
        val source = FakeSource(ArrayDeque(listOf(Outcome.Success("old"), Outcome.Success("new"))))
        val used = mutableListOf<String>()
        val result = source.withAccessToken("chatgpt") { token ->
            used += token
            if (token == "old") Outcome.Failure(AppError.TokenExpired("chatgpt")) else Outcome.Success(42)
        }
        assertThat(result).isEqualTo(Outcome.Success(42))
        assertThat(used).containsExactly("old", "new").inOrder()
        assertThat(source.calls).containsExactly(false to null, true to "old").inOrder()
    }

    @Test
    fun `a second 401 means the user must sign in again`() = runTest {
        val source = FakeSource(ArrayDeque(listOf(Outcome.Success("a"), Outcome.Success("b"))))
        val result = source.withAccessToken<Unit>("chatgpt") { Outcome.Failure(AppError.TokenExpired("chatgpt")) }
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.AuthenticationRequired::class.java)
    }

    @Test
    fun `no token means no request`() = runTest {
        val source = FakeSource(ArrayDeque(listOf(Outcome.Failure(AppError.AuthenticationRequired("googlehealth")))))
        var called = false
        val result = source.withAccessToken("googlehealth") {
            called = true
            Outcome.Success(Unit)
        }
        assertThat(called).isFalse()
        assertThat(result).isEqualTo(Outcome.Failure(AppError.AuthenticationRequired("googlehealth")))
    }

    @Test
    fun `other failures are returned without a refresh`() = runTest {
        val source = FakeSource(ArrayDeque(listOf(Outcome.Success("t"))))
        val result = source.withAccessToken<Unit>("p") { Outcome.Failure(AppError.RemoteServerError(500)) }
        assertThat(result).isEqualTo(Outcome.Failure(AppError.RemoteServerError(500)))
        assertThat(source.calls).hasSize(1)
    }
}
