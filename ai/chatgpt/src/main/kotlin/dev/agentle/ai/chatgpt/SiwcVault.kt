package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.oauth.Secret
import dev.agentle.core.oauth.TokenResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tokens of one registration. Expiry is measured on the monotonic clock from the moment the token response was
 * received (`expires_in`), never from the token's `exp` against wall time (red team oauth-security-10). After a
 * reboot the monotonic clock restarts, which makes the token look expired and costs one refresh.
 */
@Serializable
public data class StoredTokens(
    val accessToken: Secret,
    val refreshToken: Secret? = null,
    val scopes: Set<String>,
    val receivedAtElapsedMs: Long,
    val receivedAtEpochMs: Long,
    val expiresInMs: Long,
    val earliestRefreshAtEpochMs: Long? = null,
) {
    /** Lifetime left at monotonic time [elapsedNow]; zero if the clock went backwards (reboot). */
    public fun remaining(elapsedNow: Duration): Duration {
        val age = elapsedNow.inWholeMilliseconds - receivedAtElapsedMs
        return if (age < 0) Duration.ZERO else (expiresInMs - age).coerceAtLeast(0).milliseconds
    }

    /** R06 §2.8: both `resource.invoke` and `chatgpt.tokens.use.direct` were granted. */
    public val planUsageGranted: Boolean get() = scopes.containsAll(SiwcConstants.PLAN_SCOPES)

    public companion object {
        /** A first token set from a code exchange. */
        public fun from(response: TokenResponse, elapsedNow: Duration, epochNowMs: Long): StoredTokens = StoredTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            scopes = response.grantedScopes.orEmpty(),
            receivedAtElapsedMs = elapsedNow.inWholeMilliseconds,
            receivedAtEpochMs = epochNowMs,
            expiresInMs = response.expiresIn.inWholeMilliseconds,
            earliestRefreshAtEpochMs = response.earliestRefreshAt?.toEpochMilliseconds(),
        )

        /** A rotation (R06 §2.11): an omitted `scope` or refresh token keeps the previous one. */
        public fun rotate(
            previous: StoredTokens?,
            response: TokenResponse,
            receivedAtElapsedMs: Long,
            receivedAtEpochMs: Long,
        ): StoredTokens = StoredTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: previous?.refreshToken,
            scopes = response.grantedScopes ?: previous?.scopes.orEmpty(),
            receivedAtElapsedMs = receivedAtElapsedMs,
            receivedAtEpochMs = receivedAtEpochMs,
            expiresInMs = response.expiresIn.inWholeMilliseconds,
            earliestRefreshAtEpochMs = response.earliestRefreshAt?.toEpochMilliseconds(),
        )
    }
}

/**
 * The raw body of a refresh answer, checkpointed before anything else can fail (red team oauth-security-02): once the
 * server answered, the old refresh token is spent and this body holds the only valid one.
 */
@Serializable
public data class PendingRotation(val body: Secret, val receivedAtElapsedMs: Long, val receivedAtEpochMs: Long)

/**
 * One registration (R06 §2.2, §2.14): the issued client id and everything obtained with it. Tokens are always written
 * into the record of the client id that obtained them. [sub] is the account identity; [accountLabel] and [loginHint]
 * (the e-mail) are labels for the UI and `login_hint`, never identity.
 */
@Serializable
public data class SiwcRegistration(
    val clientId: String,
    val sub: String? = null,
    val accountLabel: String? = null,
    val loginHint: String? = null,
    val tokens: StoredTokens? = null,
    val pendingRotation: PendingRotation? = null,
    val unusable: Boolean = false,
) {
    init {
        require(SiwcConstants.ISSUED_CLIENT_ID.matches(clientId) && clientId != SiwcConstants.BOOTSTRAP_CLIENT_ID) {
            "only an issued client id can be stored"
        }
    }
}

/** The non-secret marker of a sign-in in progress (red team oauth-security-04); never holds attempt secrets. */
@Serializable
public data class SignInMarker(val createdAtEpochMs: Long, val firstRegistration: Boolean)

/**
 * Everything SIWC persists, sealed as one blob by the platform [CredentialStore] (Android: Tink AEAD with a Keystore
 * key, excluded from backup). [generation] increases with every credential change; sign-in completion, refresh and
 * disconnect apply their results only if it still has the value they started from.
 */
@Serializable
public data class SiwcVault(
    val version: Int = VERSION,
    val generation: Long = 0,
    val registration: SiwcRegistration? = null,
    val pendingRegistration: SiwcRegistration? = null,
    val signInMarker: SignInMarker? = null,
    val status: SiwcStatus = SiwcStatus.DISCONNECTED,
) {
    public companion object {
        public const val VERSION: Int = 1
    }
}

/** Serializes the vault for the platform store; decoding never throws and never echoes the input. */
public object SiwcVaultCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(vault: SiwcVault): ByteArray = json.encodeToString(SiwcVault.serializer(), vault).toByteArray(Charsets.UTF_8)

    /** Null if the bytes are not a vault of a known version (the caller treats that as unreadable). */
    public fun decode(bytes: ByteArray): SiwcVault? = try {
        json.decodeFromString(SiwcVault.serializer(), bytes.toString(Charsets.UTF_8)).takeIf { it.version == SiwcVault.VERSION }
    } catch (_: SerializationException) {
        // The exception message embeds the input (tokens); it is dropped (red team privacy-ai-11).
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Result of reading the credential blob. */
public sealed interface VaultRead {
    public data class Present(val vault: SiwcVault) : VaultRead

    public data object Absent : VaultRead

    /** The blob exists but cannot be decrypted or decoded (Keystore key lost, restore to a new device). */
    public data object Unreadable : VaultRead
}

/**
 * Durable storage of the [SiwcVault] (docs/research/06 §8.2 `SiwcCredentialStore`). The Android implementation seals
 * [SiwcVaultCodec.encode] with Tink AEAD under a non-exportable Keystore key without user-authentication requirements
 * (background refresh) and writes it atomically through DataStore; it is excluded from backup and device transfer.
 */
public interface CredentialStore {
    public suspend fun read(): VaultRead

    /** Atomic, durable replace; returns only after the bytes are persisted. */
    public suspend fun write(vault: SiwcVault): Outcome<Unit>

    /** Deletes the blob, also an unreadable one. */
    public suspend fun wipe(): Outcome<Unit>
}

/** A [CredentialStore] in memory (JVM tests, previews). It round-trips through [SiwcVaultCodec] like the real one. */
public class InMemoryCredentialStore(initial: SiwcVault? = null) : CredentialStore {
    @Volatile private var bytes: ByteArray? = initial?.let(SiwcVaultCodec::encode)

    /** When true, [read] reports [VaultRead.Unreadable] while a blob exists (simulates a lost Keystore key). */
    @Volatile public var unreadable: Boolean = false

    /** When true, [write] fails (simulates a full disk). */
    @Volatile public var failWrites: Boolean = false

    @Volatile public var writes: Int = 0
        private set

    public fun snapshot(): SiwcVault? = bytes?.let(SiwcVaultCodec::decode)

    override suspend fun read(): VaultRead {
        val current = bytes ?: return VaultRead.Absent
        if (unreadable) return VaultRead.Unreadable
        return SiwcVaultCodec.decode(current)?.let { VaultRead.Present(it) } ?: VaultRead.Unreadable
    }

    override suspend fun write(vault: SiwcVault): Outcome<Unit> {
        if (failWrites) return Outcome.Failure(AppError.DatabaseError("credential_write_failed"))
        bytes = SiwcVaultCodec.encode(vault)
        writes += 1
        return Outcome.Success(Unit)
    }

    override suspend fun wipe(): Outcome<Unit> {
        bytes = null
        unreadable = false
        return Outcome.Success(Unit)
    }
}

/**
 * The per-installation `ext_agent_host_id` (R06 §2.13): `urn:uuid:<v4>`, created once before the first sign-in,
 * opaque, excluded from backup so a restored phone never shares it. Not a credential.
 */
public interface InstallIdProvider {
    public suspend fun hostId(): String
}

/** An [InstallIdProvider] in memory; [random] makes the id reproducible in tests. */
public class InMemoryInstallIdProvider(private val random: java.util.Random) : InstallIdProvider {
    @Volatile private var id: String? = null

    override suspend fun hostId(): String = id ?: synchronized(this) {
        id ?: HostIds.uuidV4(ByteArray(UUID_BYTES).also(random::nextBytes)).also { id = it }
    }

    private companion object {
        const val UUID_BYTES = 16
    }
}

public object HostIds {
    private const val VERSION_BYTE = 6
    private const val VARIANT_BYTE = 8
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0x0f
    private const val HEX = "0123456789abcdef"

    /** `urn:uuid:` + an RFC 9562 version-4 UUID built from 16 random bytes. */
    public fun uuidV4(random: ByteArray): String {
        require(random.size == 16) { "a UUID needs 16 bytes" }
        val b = random.copyOf()
        b[VERSION_BYTE] = ((b[VERSION_BYTE].toInt() and 0x0f) or 0x40).toByte()
        b[VARIANT_BYTE] = ((b[VARIANT_BYTE].toInt() and 0x3f) or 0x80).toByte()
        val hex = buildString {
            b.forEach { byte ->
                append(HEX[(byte.toInt() shr NIBBLE) and LOW_NIBBLE])
                append(HEX[byte.toInt() and LOW_NIBBLE])
            }
        }
        val groups = listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
        return "urn:uuid:" + groups.joinToString("-")
    }

    public fun isValid(hostId: String): Boolean = SiwcConstants.HOST_ID.matches(hostId)
}
