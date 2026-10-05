package dev.agentle.app.wiring

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.ai.api.screen.ScreenDescription
import dev.agentle.ai.chatgpt.ChatGptAiProvider
import dev.agentle.ai.chatgpt.CredentialStore
import dev.agentle.ai.chatgpt.DisconnectOutcome
import dev.agentle.ai.chatgpt.InstallIdProvider
import dev.agentle.ai.chatgpt.SignInOutcome
import dev.agentle.ai.chatgpt.SignInRequest
import dev.agentle.ai.chatgpt.SiwcConfig
import dev.agentle.ai.chatgpt.SiwcGraph
import dev.agentle.ai.chatgpt.SiwcVault
import dev.agentle.ai.chatgpt.SiwcVaultCodec
import dev.agentle.ai.chatgpt.VaultRead
import dev.agentle.app.BuildConfig
import dev.agentle.app.shell.ChatMessage
import dev.agentle.app.shell.ChatPort
import dev.agentle.app.shell.ChatReply
import dev.agentle.app.shell.DashboardSpec
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Secret
import dev.agentle.core.common.map
import dev.agentle.core.oauth.BrowserLauncher
import dev.agentle.core.security.SecretVault
import dev.agentle.core.security.VaultEntry
import dev.agentle.core.security.VaultWrite
import dev.agentle.core.time.AgentleClock
import dev.agentle.feature.connections.port.ChatGptConnectRequest
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptConnectionPort
import dev.agentle.feature.connections.port.ChatGptConnectionState
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dev.agentle.core.security.VaultRead as SecureRead

/** The SIWC credentials sealed in the app's Keystore-backed [SecretVault]. */
internal class SecretVaultCredentialStore(private val vault: SecretVault) : CredentialStore {
    override suspend fun read(): VaultRead = when (val read = vault.read(VaultEntry.SIWC_CREDENTIALS)) {
        is SecureRead.Present -> SiwcVaultCodec.decode(read.secret.value.toByteArray(Charsets.UTF_8))?.let { VaultRead.Present(it) }
            ?: VaultRead.Unreadable

        SecureRead.Absent -> VaultRead.Absent

        is SecureRead.Unreadable, is SecureRead.Unavailable -> VaultRead.Unreadable
    }

    override suspend fun write(vault: SiwcVault): Outcome<Unit> {
        val text = SiwcVaultCodec.encode(vault).toString(Charsets.UTF_8)
        return when (this.vault.write(VaultEntry.SIWC_CREDENTIALS, Secret(text))) {
            VaultWrite.Written -> Outcome.success(Unit)
            is VaultWrite.Failed -> Outcome.failure(AppError.DatabaseError("credential_write_failed"))
        }
    }

    override suspend fun wipe(): Outcome<Unit> {
        vault.delete(VaultEntry.SIWC_CREDENTIALS)
        return Outcome.success(Unit)
    }
}

/** A per-install `urn:uuid:` host id, created once and kept in app-private preferences. */
internal class PrefsInstallIdProvider(context: Context) : InstallIdProvider {
    private val prefs = context.getSharedPreferences("siwc_install", Context.MODE_PRIVATE)

    override suspend fun hostId(): String = synchronized(this) {
        prefs.getString(KEY, null) ?: "urn:uuid:${UUID.randomUUID()}".also { prefs.edit().putString(KEY, it).commit() }
    }

    private companion object {
        const val KEY = "host_id"
    }
}

/** Opens the sign-in page in the user's browser. */
internal class AndroidBrowserLauncher(private val context: Context) : BrowserLauncher {
    override suspend fun launch(url: HttpUrl): Outcome<Unit> = withContext(Dispatchers.Main) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome.success(Unit)
        } catch (_: ActivityNotFoundException) {
            Outcome.failure(BrowserLauncher.NO_BROWSER_ERROR)
        }
    }
}

/** The app's one SIWC graph. AI requests stay refused until the consent pipeline (EgressGuard) is wired. */
@Singleton
internal class AppSiwc @Inject constructor(@ApplicationContext context: Context, vault: SecretVault, clock: AgentleClock) {
    private val store = SecretVaultCredentialStore(vault)

    @Suppress("InjectDispatcher") // The app-lifetime scope of the SIWC session, like AgentleApplication's.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val graph: SiwcGraph = SiwcGraph(
        config = SiwcConfig(applicationId = BuildConfig.APPLICATION_ID),
        store = store,
        installIds = PrefsInstallIdProvider(context),
        browser = AndroidBrowserLauncher(context),
        clock = clock,
        scope = scope,
        sendVerifier = AiSendVerifier { envelope, digest ->
            verifier?.verifyBeforeSend(envelope, digest) ?: Outcome.failure(AppError.UnsupportedFeature("ai_egress"))
        },
    )

    /** The EgressGuard, set once by [AppAi]; until then every send is refused. */
    @Volatile
    var verifier: AiSendVerifier? = null

    /** The signed-in account's `sub`, read from the sealed credentials. */
    suspend fun accountSub(): String? = (store.read() as? VaultRead.Present)?.vault?.registration?.sub

    init {
        scope.launch { graph.session.start() }
    }
}

/** The ChatGPT screen over the real Sign in with ChatGPT flow. */
@Singleton
internal class SiwcConnectionPort @Inject constructor(private val siwc: AppSiwc) : ChatGptConnectionPort {
    private val noticeAcknowledged = MutableStateFlow(false)

    override val state: Flow<ChatGptConnectionState> = combine(siwc.graph.signIn.snapshot, noticeAcknowledged) { snapshot, notice ->
        val provider = snapshot.toProviderState()
        ChatGptConnectionState(
            available = true,
            provider = provider,
            capabilities = if (provider is AiProviderState.Connected) ChatGptAiProvider.CAPABILITIES else null,
            signInInProgress = snapshot.connecting,
            planNoticeAcknowledged = notice,
        )
    }

    override suspend fun connect(request: ChatGptConnectRequest): ChatGptConnectResult =
        siwc.graph.signIn.signIn(SignInRequest(request.enablePlanUsage, request.addAccount)).toResult()

    override fun cancelConnect() = siwc.graph.signIn.cancelSignIn()

    override suspend fun takeInterruptedConnect(): ChatGptConnectResult.Interrupted? =
        siwc.graph.signIn.recoverInterruptedSignIn()?.toResult() as? ChatGptConnectResult.Interrupted

    override suspend fun acknowledgePlanNotice(): Outcome<Unit> {
        noticeAcknowledged.value = true
        return Outcome.success(Unit)
    }

    override suspend fun disconnect(forgetRegistration: Boolean): ChatGptDisconnectResult =
        when (siwc.graph.signIn.disconnect(forgetRegistration)) {
            DisconnectOutcome.Disconnected -> ChatGptDisconnectResult.Disconnected
            DisconnectOutcome.RevocationUnconfirmed -> ChatGptDisconnectResult.RevocationUnconfirmed
            DisconnectOutcome.LocalClearFailed -> ChatGptDisconnectResult.Failed(AppError.DatabaseError("credential_clear_failed"))
        }

    private fun SignInOutcome.toResult(): ChatGptConnectResult = when (this) {
        is SignInOutcome.Connected -> ChatGptConnectResult.Connected(accountLabel)

        SignInOutcome.PlanUsageNotGranted -> ChatGptConnectResult.PlanUsageNotGranted

        SignInOutcome.NotCompleted -> ChatGptConnectResult.NotCompleted

        SignInOutcome.NoBrowser -> ChatGptConnectResult.NoBrowser

        SignInOutcome.RegistrationIncomplete, SignInOutcome.ConnectionChanged, SignInOutcome.InvalidIdToken ->
            ChatGptConnectResult.AttemptRejected

        SignInOutcome.AccountMismatch -> ChatGptConnectResult.AccountMismatch

        SignInOutcome.IdentityVerificationUnavailable -> ChatGptConnectResult.ServiceUnavailable

        SignInOutcome.DeviceClockWrong -> ChatGptConnectResult.DeviceClockWrong

        is SignInOutcome.Interrupted -> ChatGptConnectResult.Interrupted(firstRegistration)

        is SignInOutcome.Failed -> ChatGptConnectResult.Failed(error)
    }
}

/** The chat tab: questions go through the consent-checked AI pipeline ([AppAi]) to ChatGPT. */
internal class SiwcChatPort @Inject constructor(siwc: AppSiwc, private val ai: AppAi) : ChatPort {
    override val connected: Flow<Boolean> = siwc.graph.signIn.snapshot.map { snapshot ->
        when (snapshot.toProviderState()) {
            is AiProviderState.Connected, is AiProviderState.UsageLimited, is AiProviderState.Unavailable -> true
            else -> false
        }
    }

    override val sharingAllowed: Flow<Boolean> = ai.phoneUsageShared

    override suspend fun allowSharing(): Outcome<Unit> = ai.sharePhoneUsage()

    override suspend fun send(history: List<ChatMessage>, editing: DashboardSpec?): Outcome<ChatReply> {
        val question = history.lastOrNull { it.fromUser }?.text ?: return Outcome.failure(AppError.ValidationError(listOf("empty")))
        // A change request carries the saved screen in words (the request keeps the user's text first, so a cut keeps it).
        val asked = editing?.let { "${sentence(question)} ${ScreenDescription.describe(it.layout())}" } ?: question
        return ai.ask(asked).map { answer ->
            val dashboard = answer.screen?.let { DashboardSpec.fromScreen(editing?.id ?: UUID.randomUUID().toString(), it) }
            val unsaved = answer.screen != null && dashboard == null
            ChatReply(if (unsaved) "${answer.reply} (The screen couldn't be saved.)" else answer.reply, dashboard)
        }
    }

    private fun sentence(text: String): String = text.trimEnd().let { if (it.last() in ".?!") it else "$it." }
}
