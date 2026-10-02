package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * @property callbackPath the only path that can complete the attempt; compared byte for byte (no normalization).
 * @property timeout how long the attempt waits for the browser, measured on the monotonic clock
 *   ([AgentleClock.elapsed]) so a wall-clock change cannot shorten or extend it (docs/research/06 §3.3 #5: 10 minutes).
 * @property connectionReadTimeout per-read timeout of each connection, so a browser's idle spare connection cannot
 *   block the listener (docs/research/06 §3.3 #7); the whole request head must also arrive within twice this time.
 * @property pollInterval how often the accept loop wakes up to check [timeout]; small values make tests fast.
 * @property expectedIssuer if set and the callback carries an `iss` parameter (RFC 9207), it must equal this.
 * @property returnLink the "Return to ..." link of the result page, for example
 *   `intent://siwc-done#Intent;scheme=agentle;package=dev.agentle.app;end`; it must carry no secret.
 */
public data class LoopbackServerConfig(
    val callbackPath: String = DEFAULT_CALLBACK_PATH,
    val timeout: Duration = 10.minutes,
    val connectionReadTimeout: Duration = 10.seconds,
    val pollInterval: Duration = 250.milliseconds,
    val maxRequestLineBytes: Int = 8 * 1024,
    val maxHeadBytes: Int = 16 * 1024,
    val maxConcurrentConnections: Int = 8,
    val expectedIssuer: String? = null,
    val appName: String = "Agentle",
    val returnLink: String? = null,
) {
    init {
        require(
            callbackPath.startsWith("/") && callbackPath.none {
                it == '?' || it == '#' || it.isWhitespace()
            },
        ) { "invalid callback path" }
        require(timeout.isPositive() && connectionReadTimeout.isPositive() && pollInterval.isPositive()) { "durations must be positive" }
        require(maxRequestLineBytes in 256..maxHeadBytes) { "maxRequestLineBytes must be in 256..maxHeadBytes" }
        require(maxConcurrentConnections > 0) { "maxConcurrentConnections must be > 0" }
    }

    public companion object {
        public const val DEFAULT_CALLBACK_PATH: String = "/auth/callback"
    }
}

/** Query parameters of an accepted callback other than `code` and `state`. Values are never printed. */
public class CallbackParameters(private val values: Map<String, List<String>>) {
    public val names: Set<String> get() = values.keys

    public fun all(name: String): List<String> = values[name].orEmpty()

    /** The value if [name] occurs exactly once, otherwise null. */
    public fun single(name: String): String? = values[name]?.singleOrNull()

    override fun toString(): String = "CallbackParameters(names=${values.keys.sorted()})"
}

/** Why a callback that carried the right `state` cannot complete the attempt. [code] is a stable, loggable code. */
public data class CallbackRejection(val code: String) {
    public companion object {
        public val MISSING_CODE: CallbackRejection = CallbackRejection("missing_code")
        public val ISSUER_MISMATCH: CallbackRejection = CallbackRejection("issuer_mismatch")
    }
}

/** Provider rules for an accepted callback (for example the issued `client_id` of Sign in with ChatGPT). */
public fun interface CallbackValidator {
    /** Null to accept; runs on the listener thread and must not block. */
    public fun validate(parameters: CallbackParameters): CallbackRejection?
}

/** How an authorization attempt ended. */
public sealed interface CallbackOutcome {
    /** The authorization server redirected back with a code and the right `state`. */
    public data class Authorized(val code: Secret, val parameters: CallbackParameters) : CallbackOutcome

    /** The authorization server redirected back with an `error` (RFC 6749 §4.1.2.1), e.g. `access_denied`. */
    public data class Denied(val error: String) : CallbackOutcome

    /** The right `state`, but the response cannot be used. */
    public data class Rejected(val rejection: CallbackRejection) : CallbackOutcome

    /** No usable callback before the timeout (tab closed, or a cross-profile browser that cannot reach the port). */
    public data object TimedOut : CallbackOutcome

    /** The listener was closed before a callback arrived. */
    public data object Cancelled : CallbackOutcome
}

/** Maps a non-success outcome to the application error model; [provider] names the remote service. */
public fun CallbackOutcome.toAppError(provider: String): AppError? = when (this) {
    is CallbackOutcome.Authorized -> null

    is CallbackOutcome.Denied -> when (error) {
        "access_denied" -> AppError.Cancelled("authorization_denied")
        "temporarily_unavailable" -> AppError.RemoteServerError(SERVICE_UNAVAILABLE, "authorization_error:$error")
        "server_error" -> AppError.RemoteServerError(INTERNAL_ERROR, "authorization_error:$error")
        else -> AppError.Unexpected("authorization_error:$error")
    }

    is CallbackOutcome.Rejected -> AppError.AuthenticationRequired(provider, "callback_rejected:${rejection.code}")

    CallbackOutcome.TimedOut -> AppError.Cancelled("authorization_timeout")

    CallbackOutcome.Cancelled -> AppError.Cancelled("authorization_cancelled")
}

private const val SERVICE_UNAVAILABLE = 503
private const val INTERNAL_ERROR = 500

/**
 * The redirect receiver for native-app OAuth on the loopback interface (RFC 8252 §7.3, §8.3), as specified in
 * docs/ARCHITECTURE.md §8 and docs/research/06-openai-sign-in-with-chatgpt.md §2.5, §3.3, §8.2 and §9.5:
 *
 * - binds `127.0.0.1` on an ephemeral port, never a wildcard or LAN address;
 * - completes the attempt only for `GET <callbackPath>` with `Host: 127.0.0.1:<port>`, exactly;
 * - compares `state` in constant time; a wrong, duplicate or missing `state` gets 400 and the attempt keeps waiting;
 * - settles exactly once; every later request, and every request with the wrong method, path or Host, gets 404;
 * - times out after [LoopbackServerConfig.timeout] on the monotonic clock;
 * - answers with `Cache-Control: no-store`, `Referrer-Policy: no-referrer` and a hash-pinned CSP, and never echoes
 *   anything from the request.
 *
 * Each connection is served on its own thread with a read timeout, so idle connections cannot block the callback.
 * Callers [await] the outcome and then [close] the server (it also closes itself on timeout or cancellation).
 */
public class LoopbackCallbackServer private constructor(
    private val serverSocket: ServerSocket,
    private val expectedState: Secret,
    private val config: LoopbackServerConfig,
    private val clock: AgentleClock,
    private val logger: Logger,
    private val validator: CallbackValidator?,
) : Closeable {
    public val port: Int = serverSocket.localPort

    /** `http://127.0.0.1:<port><callbackPath>`; identical in the authorization and token requests. */
    public val redirectUri: HttpUrl = HttpUrl.Builder().scheme(
        "http",
    ).host(LOOPBACK_HOST).port(port).encodedPath(config.callbackPath).build()

    /** The address the listener is bound to; always the IPv4 loopback address. */
    public val bindAddress: InetAddress get() = serverSocket.inetAddress

    public val isSettled: Boolean get() = result.isCompleted

    private val expectedHost = "$LOOPBACK_HOST:$port"
    private val deadline = clock.elapsed() + config.timeout
    private val pages = CallbackPages(config.appName, config.returnLink)
    private val result = CompletableDeferred<CallbackOutcome>()
    private val lock = Any()
    private val permits = Semaphore(config.maxConcurrentConnections)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile private var closed = false

    private val acceptThread = Thread(::acceptLoop, "agentle-oauth-loopback").apply { isDaemon = true }

    private fun startAccepting() {
        acceptThread.start()
    }

    /** Suspends until the attempt settles. Cancelling the caller closes the listener and releases the port. */
    public suspend fun await(): CallbackOutcome = try {
        result.await()
    } catch (e: CancellationException) {
        close()
        throw e
    }

    /** Stops listening, releases the port and drops open connections. Settles as [CallbackOutcome.Cancelled] if pending. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            result.complete(CallbackOutcome.Cancelled)
        }
        runCatching { serverSocket.close() }
        sockets.forEach { socket -> runCatching { socket.close() } }
        // The accept thread can still be inside poll(), which keeps the listening socket alive until it returns;
        // wait (bounded) for it to let go, so the port is free once close() returns.
        if (Thread.currentThread() !== acceptThread) {
            try {
                acceptThread.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        logger.d(COMPONENT, "listener closed")
    }

    private fun acceptLoop() {
        while (!closed) {
            if (clock.elapsed() >= deadline) {
                settle(CallbackOutcome.TimedOut)
                close()
            } else {
                acceptOne()
            }
        }
    }

    /** Waits up to one poll interval for a connection and hands it to its own thread. */
    private fun acceptOne() {
        val socket = try {
            serverSocket.accept()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            close()
            null
        }
        when {
            socket == null -> Unit

            !permits.tryAcquire() -> {
                runCatching { socket.close() }
                logger.d(COMPONENT, "connection refused: too many connections")
            }

            else -> {
                sockets += socket
                Thread({ serve(socket) }, "agentle-oauth-loopback-conn").apply { isDaemon = true }.start()
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = config.connectionReadTimeout.inWholeMilliseconds.toInt().coerceAtLeast(1)
            val headDeadline = clock.elapsed() + config.connectionReadTimeout * 2
            val input = BufferedInputStream(socket.getInputStream())
            val read = RequestHeadReader.read(input, config.maxRequestLineBytes, config.maxHeadBytes) { clock.elapsed() >= headDeadline }
            val response = when (read) {
                is HeadRead.Ok -> respond(read.head)
                HeadRead.TooLarge -> pages.tooLarge().also { reject("too_large") }
                HeadRead.Malformed -> pages.badRequest().also { reject("malformed") }
                HeadRead.NoRequest -> null
            }
            if (response != null) {
                response.writeTo(socket.getOutputStream())
                // Lingering close (RFC 9112 §9.6): send FIN first and discard what the client still sends, so unread
                // request bytes do not turn the close into a reset that destroys the response in flight.
                socket.shutdownOutput()
                drain(input) { clock.elapsed() >= headDeadline }
            }
        } catch (_: IOException) {
            // The browser went away or the listener was closed; nothing to answer.
        } finally {
            runCatching { socket.close() }
            sockets -= socket
            permits.release()
        }
    }

    private fun drain(input: java.io.InputStream, expired: () -> Boolean) {
        val buffer = ByteArray(DRAIN_CHUNK)
        var drained = 0
        while (drained < MAX_DRAIN_BYTES && !expired()) {
            val n = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                -1
            }
            if (n < 0) return
            drained += n
        }
    }

    private fun respond(head: RequestHead): LoopbackResponse {
        val problem = routeProblem(head)
        val parameters = if (problem == null) CallbackTarget.split(head.target)?.let { CallbackTarget.parseQuery(it.second) } else null
        return when {
            problem != null -> pages.notFound().also { reject(problem) }
            parameters == null -> pages.invalid().also { reject("bad_encoding") }
            else -> answer(parameters)
        }
    }

    /** Method, Host and path checks of docs/ARCHITECTURE.md §8; null when the request is a callback. */
    private fun routeProblem(head: RequestHead): String? {
        val hosts = head.headerValues("Host")
        val target = CallbackTarget.split(head.target)
        return when {
            head.method != "GET" -> "wrong_method"
            hosts.size != 1 || hosts.single() != expectedHost -> "wrong_host"
            target == null || target.first != config.callbackPath -> "wrong_path"
            else -> null
        }
    }

    private fun answer(parameters: Map<String, List<String>>): LoopbackResponse = synchronized(lock) {
        val states = parameters["state"].orEmpty()
        when {
            closed || result.isCompleted -> pages.notFound().also { reject("after_settle") }

            states.size != 1 || !expectedState.matches(states.single()) -> pages.invalid().also { reject("bad_state") }

            else -> when (classify(parameters).also(::settle)) {
                is CallbackOutcome.Authorized -> pages.success()
                is CallbackOutcome.Denied -> pages.notCompleted()
                else -> pages.rejected()
            }
        }
    }

    /** Order of docs/research/06 §2.5 after the state check: issuer, error, code, provider rules. */
    private fun classify(parameters: Map<String, List<String>>): CallbackOutcome {
        val issuers = parameters["iss"]
        val errors = parameters["error"]
        val code = parameters["code"]?.singleOrNull()?.takeIf { it.isNotEmpty() }
        val rest = CallbackParameters(parameters.filterKeys { it != "code" && it != "state" })
        return when {
            issuers != null && config.expectedIssuer != null && issuers != listOf(config.expectedIssuer) ->
                CallbackOutcome.Rejected(CallbackRejection.ISSUER_MISMATCH)

            errors != null -> CallbackOutcome.Denied(errors.singleOrNull()?.takeIf { ERROR_CODE.matches(it) } ?: "invalid_error")

            code == null -> CallbackOutcome.Rejected(CallbackRejection.MISSING_CODE)

            else -> validator?.validate(rest)?.let { CallbackOutcome.Rejected(it) } ?: CallbackOutcome.Authorized(Secret(code), rest)
        }
    }

    private fun settle(outcome: CallbackOutcome) {
        val settled = synchronized(lock) { !closed && result.complete(outcome) }
        if (settled) {
            val detail = when (outcome) {
                is CallbackOutcome.Authorized -> "authorized"
                is CallbackOutcome.Denied -> "denied:${outcome.error}"
                is CallbackOutcome.Rejected -> "rejected:${outcome.rejection.code}"
                CallbackOutcome.TimedOut -> "timeout"
                CallbackOutcome.Cancelled -> "cancelled"
            }
            logger.i(COMPONENT, "authorization attempt settled", fields = mapOf("outcome" to detail))
        }
    }

    private fun reject(reason: String) {
        logger.d(COMPONENT, "request rejected", fields = mapOf("reason" to reason))
    }

    public companion object {
        public const val LOOPBACK_HOST: String = "127.0.0.1"
        private const val COMPONENT = "oauth.loopback"
        private const val BACKLOG = 16
        private const val DRAIN_CHUNK = 4096
        private const val MAX_DRAIN_BYTES = 64 * 1024
        private const val CLOSE_JOIN_MILLIS = 2_000L
        private val ERROR_CODE = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /**
         * Binds `127.0.0.1:0` and starts waiting for the callback that carries [expectedState]. The listener must be
         * running before the browser opens (docs/research/06 §3.1). Fails only if the port cannot be bound.
         */
        public fun start(
            expectedState: Secret,
            clock: AgentleClock,
            config: LoopbackServerConfig = LoopbackServerConfig(),
            logger: Logger = Logger.NONE,
            validator: CallbackValidator? = null,
        ): Outcome<LoopbackCallbackServer> {
            val socket = try {
                ServerSocket(0, BACKLOG, LOOPBACK_ADDRESS).apply {
                    soTimeout = config.pollInterval.inWholeMilliseconds.toInt().coerceAtLeast(1)
                }
            } catch (e: IOException) {
                logger.w(COMPONENT, "cannot bind the loopback listener", fields = mapOf("exception" to e::class.simpleName))
                return Outcome.Failure(AppError.NetworkUnavailable("loopback_bind"))
            }
            val server = LoopbackCallbackServer(socket, expectedState, config, clock, logger, validator)
            server.startAccepting()
            logger.d(COMPONENT, "listening")
            return Outcome.Success(server)
        }
    }
}
