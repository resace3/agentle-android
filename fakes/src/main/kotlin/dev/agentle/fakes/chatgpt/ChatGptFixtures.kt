package dev.agentle.fakes.chatgpt

/**
 * The bodies of docs/research/06 §9.2-9.4 as literal JSON and SSE text. The fake serves exactly these strings, and the
 * client's golden tests feed the same literals to its parsers (red team oauth-security-14), so a parser bug cannot be
 * mirrored by a shared DTO. Messages inside the bodies are fixture text; clients must never surface or log them.
 *
 * Names follow the R06 rows; `UNVERIFIED` marks bodies whose exact shape R06 §10 item 4 lists as unconfirmed.
 */
public object ChatGptFixtures {
    // Constants of the fake topology (R06 §9.1). They are the fake's own literals, not the client's constants.
    public const val CLIENT_ID: String = "oaiapp_fakeA1"
    public const val BOOTSTRAP_CLIENT_ID: String = "dynamic_agent_client"
    public const val SUB: String = "user_fake_sub_1"
    public const val OTHER_SUB: String = "user_fake_sub_2"
    public const val EMAIL: String = "fake@example.invalid"
    public const val NAME: String = "Fake User"
    public const val KEY_ID: String = "fake-key-1"
    public const val UNKNOWN_KEY_ID: String = "fake-key-2"
    public const val MODEL: String = "gpt-6.1-sol"
    public const val HIDDEN_MODEL: String = "fake-hidden"
    public const val RESOURCE: String = "https://api.openai.com/v1"
    public const val PRODUCTION_ISSUER: String = "https://auth.openai.com"
    public const val ALL_SCOPES: String = "chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"
    public const val CALLBACK_SCOPES: String = "chatgpt.tokens.use.direct+email+offline_access+openid+profile+resource.invoke"

    /** R06 §9.4 "Plan usage not granted" (exact declined scope string UNVERIFIED). */
    public const val DECLINED_SCOPES: String = "openid profile email offline_access"

    /** R06 §9.2 discovery; `{auth}` is replaced by the auth base URL. */
    public const val DISCOVERY_TEMPLATE: String =
        """{"issuer":"{issuer}","authorization_endpoint":"{auth}/api/accounts/authorize",""" +
            """"token_endpoint":"{auth}/api/accounts/oauth/token",""" +
            """"jwks_uri":"{auth}/.well-known/jwks.json","revocation_endpoint":"{auth}/api/accounts/oauth/revoke",""" +
            """"response_types_supported":["code"],"code_challenge_methods_supported":["S256"],""" +
            """"token_endpoint_auth_methods_supported":["none"],"id_token_signing_alg_values_supported":["RS256"]}"""

    /** Discovery without `revocation_endpoint` (R06 §9.2 variant: disconnect reports "revocation unconfirmed"). */
    public const val DISCOVERY_NO_REVOCATION_TEMPLATE: String =
        """{"issuer":"{issuer}","authorization_endpoint":"{auth}/api/accounts/authorize",""" +
            """"token_endpoint":"{auth}/api/accounts/oauth/token",""" +
            """"jwks_uri":"{auth}/.well-known/jwks.json","response_types_supported":["code"],""" +
            """"code_challenge_methods_supported":["S256"],""" +
            """"token_endpoint_auth_methods_supported":["none"],"id_token_signing_alg_values_supported":["RS256"]}"""

    /** R06 §9.2 JWKS; `{n}` is the base64url modulus of the fake key. */
    public const val JWKS_TEMPLATE: String =
        """{"keys":[{"kty":"RSA","kid":"fake-key-1","use":"sig","alg":"RS256","n":"{n}","e":"AQAB"}]}"""
    public const val JWKS_EMPTY: String = """{"keys":[]}"""
    public const val JWKS_MALFORMED: String = """{"keys":[{"kty":"RSA","kid":"fake-key-1","""

    /** R06 §9.2 models. `hide` is a fake value that proves the client filters on `visibility == "list"`. */
    public const val MODELS: String =
        """{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},""" +
            """{"slug":"fake-hidden","display_name":"Hidden","visibility":"hide"}]}"""

    /** R06 §9.3 code exchange, as printed (`<JWT>` stands for the signed ID token). */
    public const val EXCHANGE_EXAMPLE: String =
        """{"access_token":"at_1","refresh_token":"rt_1","id_token":"<JWT>","token_type":"Bearer","expires_in":3600,""" +
            """"scope":"chatgpt.tokens.use.direct email offline_access openid profile resource.invoke","earliest_refresh_at":1790003000}"""

    /** R06 §9.3 refresh. */
    public const val REFRESH: String = """{"access_token":"at_2","refresh_token":"rt_2","token_type":"Bearer","expires_in":3600}"""

    /** R06 §9.3 stream: two deltas, then `response.completed`. */
    public const val STREAM_SUCCESS: String =
        "event: response.created\n" +
            """data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_f1","object":"response",""" +
            """"status":"in_progress","model":"gpt-6.1-sol","output":[]}}""" + "\n\n" +
            "event: response.output_text.delta\n" +
            """data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_f1","output_index":0,"content_index":0,""" +
            """"delta":"Take a 2-minute ","logprobs":[]}""" + "\n\n" +
            "event: response.output_text.delta\n" +
            """data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_f1","output_index":0,"content_index":0,""" +
            """"delta":"walk outside.","logprobs":[]}""" + "\n\n" +
            "event: response.completed\n" +
            """data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_f1","object":"response",""" +
            """"status":"completed",""" +
            """"model":"gpt-6.1-sol","store":false,"output":[{"type":"message","id":"msg_f1","role":"assistant","status":"completed",""" +
            """"content":[{"type":"output_text","text":"Take a 2-minute walk outside.","annotations":[]}]}]}}""" + "\n\n"

    public const val STREAM_TEXT: String = "Take a 2-minute walk outside."

    private const val STREAM_PREFIX: String =
        "event: response.created\n" +
            """data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_f2","object":"response",""" +
            """"status":"in_progress","model":"gpt-6.1-sol","output":[]}}""" + "\n\n" +
            "event: response.output_text.delta\n" +
            """data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_f2","output_index":0,"content_index":0,""" +
            """"delta":"Take a 2-minute ","logprobs":[]}""" + "\n\n"

    /** R06 §9.4 "Plan usage unavailable", mid-stream variant. */
    public const val STREAM_FAILED_USAGE_UNAVAILABLE: String = STREAM_PREFIX +
        "event: response.failed\n" +
        """data: {"type":"response.failed","sequence_number":2,"response":{"id":"resp_f2","status":"failed",""" +
        """"error":{"code":"subscription_sharing_usage_unavailable","message":"..."}}}""" + "\n\n"

    /** R06 §9.4 "Rate limited", mid-stream `response.failed` variant (same shape as the unavailable row). */
    public const val STREAM_FAILED_USAGE_LIMIT: String = STREAM_PREFIX +
        "event: response.failed\n" +
        """data: {"type":"response.failed","sequence_number":2,"response":{"id":"resp_f2","status":"failed",""" +
        """"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"..."}}}""" + "\n\n"

    /** R06 §9.4 "Stream incomplete". */
    public const val STREAM_INCOMPLETE: String = STREAM_PREFIX +
        "event: response.incomplete\n" +
        """data: {"type":"response.incomplete","sequence_number":2,"response":{"id":"resp_f3","status":"incomplete",""" +
        """"incomplete_details":{"reason":"max_output_tokens"}}}""" + "\n\n"

    /** R06 §9.4 "`error` event". */
    public const val STREAM_ERROR_EVENT: String =
        "event: response.created\n" +
            """data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_f4","object":"response",""" +
            """"status":"in_progress","model":"gpt-6.1-sol","output":[]}}""" + "\n\n" +
            "event: error\n" +
            """data: {"type":"error","code":"server_error","message":"...","param":null,"sequence_number":1}""" + "\n\n"

    // Authorization and token endpoint errors (R06 §9.4; OAuth bodies modelled on RFC 6749, UNVERIFIED).
    public const val AUTHORIZE_INVALID_REDIRECT: String =
        """{"error":"invalid_request","error_description":"redirect_uri is not allowed for this client"}"""
    public const val AUTHORIZE_UNKNOWN_CLIENT: String = """{"error":"invalid_request","error_description":"unknown client"}"""
    public const val TOKEN_PKCE_MISMATCH: String =
        """{"error":"invalid_grant","error_description":"code_verifier does not match code_challenge"}"""
    public const val TOKEN_CODE_INVALID: String =
        """{"error":"invalid_grant","error_description":"authorization code is invalid or expired"}"""
    public const val TOKEN_REDIRECT_MISMATCH: String = """{"error":"invalid_grant","error_description":"redirect_uri mismatch"}"""
    public const val TOKEN_INVALID_CLIENT: String = """{"error":"invalid_client"}"""
    public const val TOKEN_INVALID_REQUEST: String = """{"error":"invalid_request"}"""
    public const val TOKEN_UNSUPPORTED_GRANT: String = """{"error":"unsupported_grant_type"}"""
    public const val REFRESH_INVALID_GRANT: String = """{"error":"invalid_grant"}"""
    public const val REFRESH_REUSED: String = """{"error":"refresh_token_reused"}"""
    public const val REFRESH_REVOKED: String = """{"error":"invalid_grant","error_description":"refresh token revoked"}"""
    public const val REFRESH_TEMPORARILY_UNAVAILABLE: String = """{"error":"temporarily_unavailable"}"""

    /** The six terminal refresh codes of R06 §2.11 / §5.5. */
    public val TERMINAL_REFRESH_CODES: List<String> = listOf(
        "invalid_grant",
        "invalid_refresh_token",
        "token_expired",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused",
    )

    /** `{"error":"<code>"}` (OAuth string form). */
    public fun oauthError(code: String): String = """{"error":"$code"}"""

    /** `{"error":{"code":"<code>","message":"..."}}` (API object form, R06 §9.4 refresh-failure row). */
    public fun objectError(code: String): String = """{"error":{"code":"$code","message":"..."}}"""

    // Inference errors (R06 §9.4).
    public const val API_TOKEN_EXPIRED: String =
        """{"error":{"message":"Access token expired.","type":"invalid_request_error","param":null,"code":"token_expired"}}"""
    public const val DETAIL_UNAUTHORIZED: String = """{"detail":"Unauthorized"}"""
    public const val DETAIL_FORBIDDEN: String = """{"detail":"Forbidden"}"""
    public const val DETAIL_UNAVAILABLE: String = """{"detail":"Service Unavailable"}"""
    public const val NOT_ELIGIBLE: String =
        """{"error":{"message":"ChatGPT plan usage is unavailable for this user.","type":"invalid_request_error","param":null,""" +
            """"code":"subscription_sharing_user_not_eligible"}}"""
    public const val USAGE_UNAVAILABLE: String =
        """{"error":{"message":"Usage availability could not be checked.","type":"server_error","param":null,""" +
            """"code":"subscription_sharing_usage_unavailable"}}"""
    public const val USER_UNAVAILABLE: String =
        """{"error":{"message":"...","type":"server_error","param":null,"code":"subscription_sharing_user_unavailable"}}"""
    public const val USAGE_LIMIT: String =
        """{"error":{"message":"Usage limit reached.","type":"rate_limit_error","param":null,""" +
            """"code":"subscription_sharing_usage_limit_exceeded"}}"""
    public const val RATE_LIMIT_GENERIC: String =
        """{"error":{"message":"Rate limit reached.","type":"rate_limit_error","param":null,"code":"rate_limit_exceeded"}}"""
    public const val INVALID_USER: String =
        """{"error":{"message":"The subscriber context could not be validated.","type":"invalid_request_error","param":null,""" +
            """"code":"subscription_sharing_invalid_user"}}"""
    public const val GRANT_NOT_AUTHORIZED: String =
        """{"error":{"code":"chatpass_v2_scope_not_authorized","message":"...","param":null,"type":"invalid_request_error"}}"""
    public const val INVALID_AUTHORIZATION_CONTEXT: String =
        """{"error":{"code":"chatpass_v2_invalid_authorization_context","message":"...","param":null,"type":"invalid_request_error"}}"""
    public const val ROUTE_NOT_SUPPORTED: String =
        """{"error":{"code":"subscription_sharing_route_not_supported","message":"...","param":null,"type":"invalid_request_error"}}"""
    public const val MODEL_NOT_FOUND: String =
        """{"error":{"message":"...","type":"invalid_request_error","param":"model","code":"model_not_found"}}"""
    public const val SERVER_ERROR: String =
        """{"error":{"message":"The server had an error while processing your request.","type":"server_error","param":null,"code":null}}"""
    public const val BAD_GATEWAY_HTML: String = "<html><head><title>502 Bad Gateway</title></head><body>502 Bad Gateway</body></html>"
    public const val INVALID_JSON: String =
        """{"error":{"message":"...","type":"invalid_request_error","param":null,"code":"invalid_json"}}"""

    /** Legacy DevKit codes (R06 §5.5, not in the docs). */
    public fun legacyError(code: String): String =
        """{"error":{"code":"$code","message":"...","param":null,"type":"invalid_request_error"}}"""

    /** R06 §9.2 rejection body for an unsupported request field. */
    public fun unsupportedCapability(param: String): String =
        """{"error":{"code":"subscription_sharing_unsupported_capability","param":"$param","message":"...",""" +
            """"type":"invalid_request_error"}}"""
}
