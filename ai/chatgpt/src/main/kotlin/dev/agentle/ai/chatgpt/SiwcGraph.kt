package dev.agentle.ai.chatgpt

import dev.agentle.core.common.Logger
import dev.agentle.core.oauth.BrowserLauncher
import dev.agentle.core.oauth.OAuthRandom
import dev.agentle.core.oauth.TokenClient
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import java.security.SecureRandom

/**
 * Wires the SIWC components (docs/research/06 §8.2) for the app's DI module and for journey tests. Everything is a
 * singleton of the app scope [scope] (`SupervisorJob() + io`), never of a screen. Prod passes the platform store,
 * install id and Custom Tab launcher; the fake flavor passes `FakeBrowserLauncher` and a config pointing at
 * `FakeChatGptServer`, so the real authorizer and session manager run in journeys (red team oauth-security-14).
 *
 * @param http the three HTTP profiles; tests may wrap them (for example to simulate TLS interception).
 * @param egressCheck the AI-CONTEXT consent check on the exact request bytes.
 */
public class SiwcGraph(
    public val config: SiwcConfig,
    store: CredentialStore,
    installIds: InstallIdProvider,
    browser: BrowserLauncher,
    clock: AgentleClock,
    scope: CoroutineScope,
    egressCheck: EgressCheck,
    accountChanges: AccountChangeListener = AccountChangeListener.NONE,
    logger: Logger = Logger.NONE,
    http: SiwcHttpClients = SiwcHttpClients.create(config, clock, logger),
    random: OAuthRandom = OAuthRandom(SecureRandom()),
) {
    public val tokenClient: TokenClient = TokenClient(http.auth, clock, logger)
    public val discovery: SiwcDiscovery = SiwcDiscovery(config, tokenClient, logger)
    public val idTokens: IdTokenVerifier = IdTokenVerifier(config, discovery, tokenClient, clock, logger)
    public val session: SiwcSessionManager = SiwcSessionManager(config, store, discovery, tokenClient, idTokens, clock, scope, logger)
    public val authorizer: SiwcAuthorizer =
        SiwcAuthorizer(config, session, discovery, tokenClient, idTokens, installIds, browser, random, clock, accountChanges, logger)
    public val signIn: SignInCoordinator = SignInCoordinator(authorizer, session, clock, scope, logger)
    public val responses: ResponsesClient = ResponsesClient(config, http.stream, clock, logger)
    public val models: ModelCatalog = ModelCatalog(config, http.api, session, clock, logger)
    public val provider: ChatGptAiProvider = ChatGptAiProvider(session, responses, models, egressCheck, scope, logger = logger)
}
