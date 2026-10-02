package dev.agentle.fakes.chatgpt

/**
 * The scenario catalog of docs/research/08 §5.4 (28 rows, mapped to docs/research/06 §9.3-9.4), plus the variants
 * R06 §9.2-9.4 and R08 §5.5/§5.8 ask for that the catalog does not name yet (marked "addition").
 *
 * @property id the URL segment of the scenario-prefix mode (`/fake-chatgpt/scenario/<id>/...`).
 * @property row the R06/R08 row the scenario reproduces.
 */
public enum class ChatGptScenario(public val id: String, public val row: String) {
    HAPPY("happy", "R06 9.2/9.3 success payloads"),

    // Sign-in (R06 9.4 "Sign-in and token scenarios").
    CONSENT_DENIED("consent-denied", "R06 9.4 Login cancelled (consent denied)"),
    INVALID_STATE("invalid-state", "R06 9.4 Invalid state"),
    REGISTRATION_INCOMPLETE("registration-incomplete", "R06 9.4 Registration incomplete"),
    PLAN_SCOPE_DECLINED("plan-scope-declined", "R06 9.4 Plan usage not granted"),
    EXCHANGE_INVALID_GRANT("exchange-invalid-grant", "R06 9.4 Code reused or expired (every exchange; addition)"),
    EXCHANGE_INVALID_GRANT_ONCE("exchange-invalid-grant-once", "R06 9.4 Code reused or expired (first exchange; addition)"),
    EXCHANGE_EARLIEST_ISO("exchange-earliest-iso", "R06 9.3 earliest_refresh_at as ISO-8601 (addition)"),
    EXCHANGE_NO_EARLIEST("exchange-no-earliest", "R06 9.3 earliest_refresh_at absent (addition)"),
    OTHER_ACCOUNT("other-account", "R06 8.1 ID token sub differs from the saved account (addition)"),

    // Discovery and JWKS (R06 9.2 variants).
    DISCOVERY_ISSUER_MISMATCH("discovery-issuer-mismatch", "R06 9.2 Discovery issuer mismatch"),
    DISCOVERY_FOREIGN_ENDPOINT("discovery-foreign-endpoint", "R06 9.2 Discovery endpoint on a foreign origin (addition)"),
    DISCOVERY_NO_REVOCATION("discovery-no-revocation", "R06 9.2 Discovery without revocation_endpoint (addition)"),
    DISCOVERY_UNAVAILABLE("discovery-unavailable", "R06 8.1 Discovery unavailable (addition)"),
    JWKS_UNAVAILABLE("jwks-unavailable", "R06 9.2 JWKS outage: 503"),
    JWKS_EMPTY("jwks-empty", "R06 9.2 JWKS outage: no keys (addition)"),
    JWKS_UNKNOWN_KID("jwks-unknown-kid", "R06 9.2 JWKS outage: unknown kid (addition)"),
    JWKS_MALFORMED("jwks-malformed", "R06 9.2 JWKS outage: malformed JSON (addition)"),

    // Refresh (R06 9.3 variants and 9.4 rows).
    REFRESH_INVALID_GRANT("refresh-invalid-grant", "R06 9.4 Refresh failure (terminal): invalid_grant"),
    REFRESH_INVALID_CLIENT("refresh-invalid-client", "R06 8.1 invalid_client on refresh (addition)"),
    REFRESH_TRANSIENT("refresh-transient", "R06 9.4 Refresh failure (transient): 503 once"),
    REFRESH_BAD_GATEWAY("refresh-bad-gateway", "R06 9.4 Refresh failure (transient): HTML 502 once (addition)"),
    REFRESH_SOCKET_CLOSE("refresh-socket-close", "R06 9.4 Refresh failure (transient): socket closed once (addition)"),
    REFRESH_WITH_SCOPE("refresh-with-scope", "R06 9.3 refresh variant with scope (addition)"),
    REFRESH_WITH_ID_TOKEN("refresh-with-id-token", "R06 9.3 refresh variant with a new id_token, same sub (addition)"),
    REFRESH_ACCOUNT_MISMATCH("refresh-account-mismatch", "R06 9.3 refresh variant: id_token sub differs (addition)"),
    REFRESH_NOT_READY("refresh-not-ready", "R06 8.1 earliest_refresh_at after expiry (addition)"),

    // Inference (R06 9.4 "Inference scenarios").
    EXPIRED_ACCESS_TOKEN("expired-access-token", "R06 9.4 Expired access token"),
    USAGE_LIMIT("usage-limit", "R06 9.4 Rate limited (plan limit)"),
    RATE_LIMITED_GENERIC("rate-limited-generic", "R06 9.4 Rate limited, generic variant with Retry-After (addition)"),
    NOT_ELIGIBLE("not-eligible", "R06 9.4 Not eligible"),
    UNAVAILABLE("unavailable", "R06 9.4 Plan usage unavailable"),
    USER_UNAVAILABLE("user-unavailable", "R06 5.5 subscription_sharing_user_unavailable (addition)"),
    INVALID_USER("invalid-user", "R06 9.4 Account disconnected"),
    GRANT_NOT_AUTHORIZED("grant-not-authorized", "R06 9.4 Grant not authorized"),
    INVALID_AUTHORIZATION_CONTEXT("invalid-authorization-context", "R06 5.5 chatpass_v2_invalid_authorization_context (addition)"),
    CLIENT_NOT_ENABLED("client-not-enabled", "R06 8.1 legacy subscription_sharing_v2_client_not_enabled (addition)"),
    UNSUPPORTED_CAPABILITY("unsupported-capability", "R06 9.4 Unsupported capability"),
    MODEL_NOT_FOUND("model-not-found", "R06 5.5 model_not_found (addition)"),
    ADMISSION_401("admission-401", "R06 9.4 Admission errors: 401 detail"),
    ADMISSION_403("admission-403", "R06 9.4 Admission errors: 403 detail"),
    ADMISSION_503("admission-503", "R06 9.4 Admission errors: 503 detail"),
    SERVER_ERROR("server-error", "R06 9.4 Server error: 500"),
    BAD_GATEWAY_HTML("bad-gateway-html", "R06 9.4 Server error: 502 text/html (addition)"),
    GATEWAY_TIMEOUT_EMPTY("gateway-timeout-empty", "R06 9.4 Server error: 504 empty (addition)"),
    MID_STREAM_USAGE_LIMIT("mid-stream-usage-limit", "R06 9.4 Rate limited, mid-stream response.failed"),
    MID_STREAM_UNAVAILABLE("mid-stream-unavailable", "R06 9.4 Plan usage unavailable, mid-stream (addition)"),
    INCOMPLETE("incomplete", "R06 9.4 Stream incomplete"),
    ERROR_EVENT("error-event", "R06 9.4 error event"),
    STREAM_CUT("stream-cut", "R06 9.4 Stream interrupted; R08 5.5 disconnect mid-body"),
    SLOW_STREAM("slow-stream", "R08 5.5 slow body (64 B / 50 ms)"),
    STALL("stall", "R08 5.5 headers never arrive"),
    NO_CONTENT_TYPE("no-content-type", "R06 4.3 valid SSE without Content-Type"),
    WRONG_CONTENT_TYPE("wrong-content-type", "R06 4.3 a Content-Type other than text/event-stream (addition)"),

    // Revocation (R06 9.4 "Revocation scenarios").
    REVOCATION_FAILURE("revocation-failure", "R06 9.4 Revocation failure: 503"),
    ;

    public companion object {
        public fun fromId(id: String): ChatGptScenario? = entries.firstOrNull { it.id == id }
    }
}
