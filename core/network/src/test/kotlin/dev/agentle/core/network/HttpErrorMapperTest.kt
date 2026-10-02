package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HttpErrorMapperTest {
    private val now = Instant.parse("2026-10-02T08:00:00Z")

    @Test
    fun `401 asks for a token refresh and 403 is a remote permission problem`() {
        assertThat(HttpErrorMapper.fromStatus(401, "googlehealth")).isEqualTo(AppError.TokenExpired("googlehealth"))
        assertThat(HttpErrorMapper.fromStatus(403, "googlehealth")).isEqualTo(AppError.PermissionDenied("googlehealth.remote"))
    }

    @Test
    fun `429 carries Retry-After in seconds or as an HTTP date`() {
        assertThat(HttpErrorMapper.fromStatus(429, "chatgpt", "17", now)).isEqualTo(AppError.RateLimited(17.seconds))
        val dated = HttpErrorMapper.fromStatus(429, "chatgpt", "Fri, 02 Oct 2026 08:01:30 GMT", now)
        assertThat(dated).isEqualTo(AppError.RateLimited(90.seconds))
        assertThat(HttpErrorMapper.fromStatus(429, "chatgpt", null, now)).isEqualTo(AppError.RateLimited(null))
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `transient statuses are retryable`(status: Int) {
        assertThat(HttpErrorMapper.isTransient(HttpErrorMapper.fromStatus(status, "p"))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409, 422, 501])
    fun `client errors and 501 are not retryable`(status: Int) {
        assertThat(HttpErrorMapper.isTransient(HttpErrorMapper.fromStatus(status, "p"))).isFalse()
    }

    @Test
    fun `transport failures map to network unavailable`() {
        assertThat(HttpErrorMapper.fromThrowable(UnknownHostException("x"), "p")).isEqualTo(AppError.NetworkUnavailable("dns"))
        assertThat(HttpErrorMapper.fromThrowable(SocketTimeoutException(), "p")).isEqualTo(AppError.NetworkUnavailable("timeout"))
        assertThat(HttpErrorMapper.fromThrowable(SSLHandshakeException("bad cert"), "p")).isEqualTo(AppError.NetworkUnavailable("tls"))
        assertThat(HttpErrorMapper.fromThrowable(IOException("reset"), "p")).isInstanceOf(AppError.NetworkUnavailable::class.java)
    }

    @Test
    fun `malformed bodies are parsing errors and unknown throwables are unexpected`() {
        assertThat(HttpErrorMapper.fromThrowable(SerializationException("bad"), "p")).isInstanceOf(AppError.ParsingError::class.java)
        assertThat(HttpErrorMapper.fromThrowable(IllegalStateException(), "p")).isInstanceOf(AppError.Unexpected::class.java)
    }

    @Test
    fun `retrofit http exceptions keep status and Retry-After`() {
        val response = Response.error<Unit>(
            "slow down".toResponseBody(),
            okhttp3.Response.Builder()
                .code(429)
                .message("Too Many Requests")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .header("Retry-After", "3")
                .request(okhttp3.Request.Builder().url("https://health.googleapis.com/v4/x").build())
                .build(),
        )
        assertThat(HttpErrorMapper.fromThrowable(HttpException(response), "googlehealth")).isEqualTo(AppError.RateLimited(3.seconds))
    }

    @Test
    fun `cancellation is rethrown, never mapped`() {
        assertThrows<CancellationException> { HttpErrorMapper.fromThrowable(CancellationException("stop"), "p") }
    }

    @Test
    fun `Retry-After rejects garbage and clamps past dates to zero`() {
        assertThat(RetryAfter.parse("soon", now)).isNull()
        assertThat(RetryAfter.parse("-5", now)).isNull()
        assertThat(RetryAfter.parse("Thu, 01 Oct 2026 08:00:00 GMT", now)).isEqualTo(Duration.ZERO)
        assertThat(RetryAfter.parse("Fri, 02 Oct 2026 08:01:30 GMT", null)).isNull()
    }
}
