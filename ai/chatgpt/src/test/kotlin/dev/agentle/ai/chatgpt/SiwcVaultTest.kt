package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.oauth.Secret
import dev.agentle.core.oauth.TokenResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The persisted SIWC state (docs/research/06 §8.2 `SiwcCredentialStore`), its codec, token timing and host ids. */
class SiwcVaultTest {
    private val tokens = StoredTokens(
        accessToken = Secret("vault-access-token"),
        refreshToken = Secret("vault-refresh-token"),
        scopes = SiwcConstants.SCOPES.toSet(),
        receivedAtElapsedMs = 1_000,
        receivedAtEpochMs = 1_790_000_000_000,
        expiresInMs = 3_600_000,
        earliestRefreshAtEpochMs = 1_790_003_000_000,
    )
    private val vault = SiwcVault(
        generation = 7,
        registration = SiwcRegistration(
            clientId = "oaiapp_vault1",
            sub = "sub-vault",
            accountLabel = "label@example.invalid",
            loginHint = "label@example.invalid",
            tokens = tokens,
            pendingRotation = PendingRotation(Secret("{\"access_token\":\"pending-body\"}"), 5_000, 1_790_000_005_000),
        ),
        pendingRegistration = SiwcRegistration(clientId = "oaiapp_vault2"),
        signInMarker = SignInMarker(createdAtEpochMs = 1_790_000_000_000, firstRegistration = false),
        status = SiwcStatus(SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT, "req_1"),
    )

    @Test
    fun `a vault round-trips through the codec and never prints a token`() {
        val decoded = SiwcVaultCodec.decode(SiwcVaultCodec.encode(vault))

        assertThat(decoded).isEqualTo(vault)
        listOf("vault-access-token", "vault-refresh-token", "pending-body").forEach { assertThat(vault.toString()).doesNotContain(it) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "",
            "not json",
            "[]",
            "{\"version\":2}",
            "{\"version\":1,\"generation\":\"many\"}",
            "{\"version\":1,\"registration\":{\"clientId\":\"dynamic_agent_client\"}}",
        ],
    )
    fun `bytes that are not a vault of this version decode to null`(text: String) {
        assertThat(SiwcVaultCodec.decode(text.toByteArray(Charsets.UTF_8))).isNull()
    }

    @Test
    fun `unknown fields of a newer writer are ignored`() {
        val text = String(SiwcVaultCodec.encode(SiwcVault()), Charsets.UTF_8).removeSuffix("}") + ",\"future\":true}"

        assertThat(SiwcVaultCodec.decode(text.toByteArray(Charsets.UTF_8))).isEqualTo(SiwcVault())
    }

    @Test
    fun `the remaining lifetime is measured on the monotonic clock and is zero after a reboot`() {
        assertThat(tokens.remaining(1_000.milliseconds + 600.seconds)).isEqualTo(3000.seconds)
        assertThat(tokens.remaining(1_000.milliseconds + 2.seconds * 3600)).isEqualTo(Duration.ZERO)
        assertThat(tokens.remaining(500.milliseconds)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `plan usage needs both plan scopes`() {
        assertThat(tokens.planUsageGranted).isTrue()
        assertThat(tokens.copy(scopes = setOf("openid", SiwcConstants.SCOPE_RESOURCE_INVOKE)).planUsageGranted).isFalse()
    }

    @Test
    fun `a code exchange stores expires_in and earliest_refresh_at as received`() {
        val response = TokenResponse(
            accessToken = Secret("a1"),
            tokenType = "Bearer",
            expiresIn = 3600.seconds,
            refreshToken = Secret("r1"),
            scope = "openid resource.invoke",
            earliestRefreshAt = Instant.fromEpochSeconds(1_790_003_000),
        )

        val stored = StoredTokens.from(response, elapsedNow = 42.seconds, epochNowMs = 1_790_000_000_000)

        assertThat(stored).isEqualTo(
            StoredTokens(
                Secret("a1"),
                Secret("r1"),
                setOf("openid", "resource.invoke"),
                42_000,
                1_790_000_000_000,
                3_600_000,
                1_790_003_000_000,
            ),
        )
    }

    @Test
    fun `a rotation keeps the previous refresh token and scopes when the answer omits them`() {
        val answer = TokenResponse(accessToken = Secret("a2"), tokenType = "Bearer", expiresIn = 1800.seconds)

        val rotated = StoredTokens.rotate(tokens, answer, receivedAtElapsedMs = 9_000, receivedAtEpochMs = 1_790_000_009_000)

        assertThat(rotated.accessToken).isEqualTo(Secret("a2"))
        assertThat(rotated.refreshToken).isEqualTo(tokens.refreshToken)
        assertThat(rotated.scopes).isEqualTo(tokens.scopes)
        assertThat(rotated.expiresInMs).isEqualTo(1_800_000)
        assertThat(rotated.earliestRefreshAtEpochMs).isNull()
        val replaced = StoredTokens.rotate(null, answer.copy(refreshToken = Secret("r2"), scope = "openid"), 9_000, 0)
        assertThat(replaced.refreshToken).isEqualTo(Secret("r2"))
        assertThat(replaced.scopes).containsExactly("openid")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["dynamic_agent_client", "", "oaiapp bad", "oaiapp_ü"])
    fun `only an issued client id can be stored`(clientId: String) {
        assertThrows<IllegalArgumentException> { SiwcRegistration(clientId = clientId) }
    }

    @Test
    fun `host ids are RFC 9562 version-4 URNs with the version and variant bits set`() {
        assertThat(HostIds.uuidV4(ByteArray(16) { -1 })).isEqualTo("urn:uuid:ffffffff-ffff-4fff-bfff-ffffffffffff")
        assertThat(HostIds.uuidV4(ByteArray(16))).isEqualTo("urn:uuid:00000000-0000-4000-8000-000000000000")
        assertThat(HostIds.isValid(HostIds.uuidV4(ByteArray(16) { it.toByte() }))).isTrue()
        assertThat(HostIds.isValid("host-1")).isFalse()
        assertThrows<IllegalArgumentException> { HostIds.uuidV4(ByteArray(15)) }
    }

    @Test
    fun `an install id is created once and is reproducible from its seed`() = runTest {
        val provider = InMemoryInstallIdProvider(Random(SEED))

        val first = provider.hostId()

        assertThat(provider.hostId()).isEqualTo(first)
        assertThat(InMemoryInstallIdProvider(Random(SEED)).hostId()).isEqualTo(first)
        assertThat(HostIds.isValid(first)).isTrue()
    }

    @Test
    fun `the in-memory store round-trips, fails writes on demand, reports unreadable blobs and wipes`() = runTest {
        val store = InMemoryCredentialStore()
        assertThat(store.read()).isEqualTo(VaultRead.Absent)

        assertThat(store.write(vault)).isEqualTo(Outcome.Success(Unit))
        assertThat(store.read()).isEqualTo(VaultRead.Present(vault))
        assertThat(store.writes).isEqualTo(1)
        store.failWrites = true
        assertThat(store.write(SiwcVault())).isEqualTo(Outcome.Failure(AppError.DatabaseError("credential_write_failed")))
        assertThat(store.snapshot()).isEqualTo(vault)
        store.unreadable = true
        assertThat(store.read()).isEqualTo(VaultRead.Unreadable)
        store.wipe()
        assertThat(store.read()).isEqualTo(VaultRead.Absent)
        assertThat(store.unreadable).isFalse()
        assertThat(InMemoryCredentialStore(vault).snapshot()).isEqualTo(vault)
    }

    private companion object {
        const val SEED = 20261002L
    }
}
