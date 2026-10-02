package dev.agentle.ai.chatgpt

import dev.agentle.core.common.Logger
import dev.agentle.core.network.HttpClientConfig
import dev.agentle.core.network.HttpClientFactory
import dev.agentle.core.oauth.OAuthHttpClients
import dev.agentle.core.time.AgentleClock
import okhttp3.OkHttpClient
import kotlin.time.toJavaDuration

/**
 * The three HTTP profiles of docs/research/06 §8.3 (red team oauth-security-09). Each one has an egress allow-list,
 * so none of them follows redirects:
 * - [auth]: token, revocation, discovery and JWKS only; no silent retries, connect 15 s, call 30 s. Never wrapped in a
 *   retry policy (a replayed refresh trips `refresh_token_reused`).
 * - [api]: `GET /v1/models`; no silent retries.
 * - [stream]: `POST /v1/responses`; call timeout 180 s, read timeout 60 s between events.
 */
public class SiwcHttpClients(public val auth: OkHttpClient, public val api: OkHttpClient, public val stream: OkHttpClient) {
    public companion object {
        public fun create(config: SiwcConfig, clock: AgentleClock, logger: Logger = Logger.NONE): SiwcHttpClients {
            val auth = OAuthHttpClients.authClient(config.userAgent, config.authHosts, config.allowCleartextLoopback, logger)
            val api = HttpClientFactory.create(
                HttpClientConfig(
                    userAgent = config.userAgent,
                    allowCleartextLoopback = config.allowCleartextLoopback,
                    allowedHosts = config.apiHosts,
                ),
                logger,
                nowMs = { clock.elapsed().inWholeMilliseconds },
            ).newBuilder().retryOnConnectionFailure(false).build()
            val stream = api.newBuilder()
                .callTimeout(config.streamCallTimeout.toJavaDuration())
                .readTimeout(config.streamReadTimeout.toJavaDuration())
                .build()
            return SiwcHttpClients(auth, api, stream)
        }
    }
}
