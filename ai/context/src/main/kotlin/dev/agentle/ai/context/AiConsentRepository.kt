package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What a background send of [purpose] would hold. The user sees it before accepting it as a [StandingConsent]
 * (privacy-ai-01). [restrictedTo] narrows it to the categories the user keeps.
 */
public data class StandingConsentTemplate(
    val purpose: AiPurpose,
    val fields: Set<String>,
    val categories: Set<AiDataCategory>,
    val sourceFamilies: Set<SourceFamily>,
    val lookbackDays: Int,
    val cadence: Duration,
    val dailyBudget: Int,
) {
    /** The template without the fields of categories outside [keep]. */
    public fun restrictedTo(keep: Set<AiDataCategory>): StandingConsentTemplate {
        val kept = categories.intersect(keep)
        val keptFields = fields.filterTo(sortedSetOf()) { code -> AiFieldRegistry[code]?.categories?.let(kept::containsAll) ?: false }
        return copy(fields = keptFields, categories = kept)
    }
}

/**
 * Reads and changes the AI consent state (privacy-ai-02) and owns its stored format.
 *
 * Reading fails closed. A store that was never written grants nothing. A store that throws (I/O failure, corruption)
 * or holds anything this version cannot decode exactly (a missing key, an unknown key, category or purpose, an
 * implausible value) is [ConsentState.Unreadable], and every category is then denied. Grants count only for
 * [currentVersion] and for the active ChatGPT account. The policies check this; the repository stores what it is given.
 *
 * Writes are serialized. If a write finds an unreadable document, it starts again from [ConsentSnapshot.EMPTY], so
 * corrupt state is dropped and never repaired into grants.
 */
public class AiConsentRepository(
    private val store: AiConsentStore,
    private val account: AiAccountSource,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
    public val currentVersion: Int = AiConsentDisclosure.VERSION,
) {
    private val mutex = Mutex()

    init {
        require(currentVersion > 0) { "currentVersion must be positive" }
    }

    /** Emits after every stored change (hot, no replay). */
    public val changes: Flow<Unit> get() = store.changes

    /** The current state, read fresh from the store on every call. Never throws except for cancellation. */
    public suspend fun read(): ConsentState {
        val text = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            return unreadable(CODE_UNREADABLE, expected::class.simpleName)
        }
        val snapshot = if (text == null) ConsentSnapshot.EMPTY else ConsentDocuments.decode(text)
        return if (snapshot == null) unreadable(CODE_CORRUPT, null) else ConsentState.Readable(snapshot)
    }

    /** Allows [categories] for [purpose] under [currentVersion] for the active account. Third-party text cannot be granted in v1. */
    public suspend fun grant(categories: Set<AiDataCategory>, purpose: AiPurpose): Outcome<Unit> {
        val thirdParty = categories.filter { it.thirdPartyText }
        if (thirdParty.isNotEmpty()) {
            return Outcome.Failure(AppError.ConsentViolation(thirdParty.namesSorted(), CODE_THIRD_PARTY))
        }
        val sub = activeSub() ?: return Outcome.Failure(AppError.AuthenticationRequired(ACCOUNT_PROVIDER))
        val now = clock.now()
        return mutate(adds = true) { snapshot ->
            val kept = snapshot.grants.filterNot { it.purpose == purpose && it.category in categories }
            snapshot.copy(grants = kept + categories.sorted().map { ConsentGrant(it, purpose, currentVersion, now, sub) })
        }
    }

    /** Removes every grant of [categories] (all purposes) and every standing consent that includes one of them. */
    public suspend fun revoke(categories: Set<AiDataCategory>): Outcome<Unit> = mutate { snapshot ->
        ConsentSnapshot(
            grants = snapshot.grants.filterNot { it.category in categories },
            standing = snapshot.standing.filterNot { standing -> standing.categories.any { it in categories } },
        )
    }

    /**
     * The consent half of deleting stored data (database-sync-08). The deletion flow calls this in the same flow
     * that deletes [category], so the grants of every AI category derived from it ([AiDataCategory.forStorageCategory])
     * are revoked. Requests built earlier then fail their send-time check.
     */
    public suspend fun revokeForDeletedData(category: DataCategory): Outcome<Unit> = revoke(AiDataCategory.forStorageCategory(category))

    /** Removes every grant and the standing consent of [purpose]. */
    public suspend fun revokePurpose(purpose: AiPurpose): Outcome<Unit> = mutate { snapshot ->
        ConsentSnapshot(snapshot.grants.filterNot { it.purpose == purpose }, snapshot.standing.filterNot { it.purpose == purpose })
    }

    public suspend fun revokeAll(): Outcome<Unit> = mutate { ConsentSnapshot.EMPTY }

    /**
     * Accepts [template] as the standing consent of its purpose. It replaces any earlier one and also grants the
     * template's categories for that purpose, because the user approved exactly those.
     */
    public suspend fun acceptStanding(template: StandingConsentTemplate): Outcome<StandingConsent> {
        if (!isAcceptable(template)) return Outcome.Failure(AppError.ValidationError(listOf(CODE_TEMPLATE_INVALID)))
        val sub = activeSub() ?: return Outcome.Failure(AppError.AuthenticationRequired(ACCOUNT_PROVIDER))
        val now = clock.now()
        val standing = StandingConsent(
            purpose = template.purpose,
            fields = template.fields.toSortedSet(),
            categories = template.categories.toSortedSet(),
            sourceFamilies = template.sourceFamilies.toSortedSet(),
            lookbackDays = template.lookbackDays,
            cadence = template.cadence,
            dailyBudget = template.dailyBudget,
            consentVersion = currentVersion,
            grantedAt = now,
            accountSub = sub,
        )
        val written = mutate(adds = true) { snapshot ->
            val grants = snapshot.grants.filterNot { it.purpose == template.purpose && it.category in template.categories } +
                template.categories.sorted().map { ConsentGrant(it, template.purpose, currentVersion, now, sub) }
            ConsentSnapshot(grants, snapshot.standing.filterNot { it.purpose == template.purpose } + standing)
        }
        return when (written) {
            is Outcome.Success -> Outcome.Success(standing)
            is Outcome.Failure -> written
        }
    }

    public suspend fun revokeStanding(purpose: AiPurpose): Outcome<Unit> = mutate { snapshot ->
        snapshot.copy(standing = snapshot.standing.filterNot { it.purpose == purpose })
    }

    private fun isAcceptable(template: StandingConsentTemplate): Boolean {
        val spec = PurposePolicy.spec(template.purpose)
        return spec.background != null &&
            template.fields.isNotEmpty() &&
            template.fields.all { AiFieldRegistry[it]?.kind in AGGREGATE_KINDS } &&
            template.categories.isNotEmpty() &&
            spec.categories.containsAll(template.categories) &&
            template.sourceFamilies.isNotEmpty() &&
            template.lookbackDays in 1..spec.lookbackDays &&
            template.cadence >= MIN_CADENCE &&
            template.dailyBudget in 1..MAX_DAILY_BUDGET
    }

    private suspend fun activeSub(): String? = account.activeAccountSub()?.takeIf { it.isNotBlank() }

    /**
     * [adds] is true for grants: they start again from EMPTY only when the document is corrupt, never when a load failed
     * (that would wipe the other grants). Revocations may always write EMPTY.
     */
    private suspend fun mutate(adds: Boolean = false, change: (ConsentSnapshot) -> ConsentSnapshot): Outcome<Unit> = mutex.withLock {
        val current = when (val state = read()) {
            is ConsentState.Readable -> state.snapshot

            is ConsentState.Unreadable ->
                if (adds &&
                    state.code != CODE_CORRUPT
                ) {
                    return@withLock Outcome.Failure(AppError.DatabaseError(state.code))
                } else {
                    ConsentSnapshot.EMPTY
                }
        }
        try {
            store.save(ConsentDocuments.encode(change(current)))
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            logger.w(COMPONENT, "consent write failed", fields = mapOf("reason" to CODE_WRITE_FAILED, "type" to expected::class.simpleName))
            Outcome.Failure(AppError.DatabaseError(CODE_WRITE_FAILED))
        }
    }

    private fun unreadable(code: String, type: String?): ConsentState.Unreadable {
        logger.w(COMPONENT, "consent state unreadable, denying every category", fields = mapOf("reason" to code, "type" to type))
        return ConsentState.Unreadable(code)
    }

    public companion object {
        public const val CODE_UNREADABLE: String = "consent_unreadable"
        public const val CODE_CORRUPT: String = "consent_corrupt"
        public const val CODE_WRITE_FAILED: String = "consent_write_failed"
        public const val CODE_THIRD_PARTY: String = "third_party_text"
        public const val CODE_TEMPLATE_INVALID: String = "standing_template_invalid"

        /** The provider named by `AppError.AuthenticationRequired` when nobody is signed in. */
        public const val ACCOUNT_PROVIDER: String = "chatgpt"

        public val MIN_CADENCE: Duration = 60.minutes
        public const val MAX_DAILY_BUDGET: Int = 24

        private const val COMPONENT = "ai.consent"
        private val AGGREGATE_KINDS = setOf(ItemKind.QUANTITY, ItemKind.TIME_OF_DAY, ItemKind.CODE)
    }
}

internal fun Collection<AiDataCategory>.namesSorted(): Set<String> = mapTo(sortedSetOf()) { it.name }

/**
 * The stored consent document, format 1. Decoding is exact: every key is required, unknown keys and enum values are
 * errors, and implausible values make the whole document untrusted. The caller then denies everything.
 */
internal object ConsentDocuments {
    const val FORMAT: Int = 1
    private const val MAX_SUB_LENGTH = 256
    private const val MAX_LOOKBACK_DAYS = 366
    private val FIELD = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = false
    }

    @Serializable
    private data class Document(@SerialName("format") val version: Int, val grants: List<Grant>, val standing: List<Standing>)

    @Serializable
    private data class Grant(
        val category: AiDataCategory,
        val purpose: AiPurpose,
        val consentVersion: Int,
        val grantedAt: Instant,
        val accountSub: String,
    )

    @Serializable
    private data class Standing(
        val purpose: AiPurpose,
        val fields: List<String>,
        val categories: List<AiDataCategory>,
        val sourceFamilies: List<SourceFamily>,
        val lookbackDays: Int,
        val cadenceMinutes: Long,
        val dailyBudget: Int,
        val consentVersion: Int,
        val grantedAt: Instant,
        val accountSub: String,
    )

    /** The snapshot of [text], or null if it is not exactly a plausible format-1 document. */
    fun decode(text: String): ConsentSnapshot? {
        val document = try {
            json.decodeFromString(Document.serializer(), text)
        } catch (expected: Exception) {
            null
        }
        if (document == null || document.version != FORMAT) return null
        val plausible = document.grants.all { it.consentVersion > 0 && isSub(it.accountSub) } && document.standing.all(::isPlausible)
        return if (plausible) toSnapshot(document) else null
    }

    fun encode(snapshot: ConsentSnapshot): String = json.encodeToString(
        Document.serializer(),
        Document(
            version = FORMAT,
            grants = snapshot.grants.map { Grant(it.category, it.purpose, it.consentVersion, it.grantedAt, it.accountSub) },
            standing = snapshot.standing.map {
                Standing(
                    purpose = it.purpose,
                    fields = it.fields.sorted(),
                    categories = it.categories.sorted(),
                    sourceFamilies = it.sourceFamilies.sorted(),
                    lookbackDays = it.lookbackDays,
                    cadenceMinutes = it.cadence.inWholeMinutes,
                    dailyBudget = it.dailyBudget,
                    consentVersion = it.consentVersion,
                    grantedAt = it.grantedAt,
                    accountSub = it.accountSub,
                )
            },
        ),
    )

    private fun isSub(sub: String): Boolean = sub.isNotBlank() && sub.length <= MAX_SUB_LENGTH

    private fun isPlausible(standing: Standing): Boolean = standing.fields.isNotEmpty() &&
        standing.fields.all { FIELD.matches(it) } &&
        standing.categories.isNotEmpty() &&
        standing.sourceFamilies.isNotEmpty() &&
        standing.lookbackDays in 1..MAX_LOOKBACK_DAYS &&
        standing.cadenceMinutes > 0 &&
        standing.dailyBudget > 0 &&
        standing.consentVersion > 0 &&
        isSub(standing.accountSub)

    private fun toSnapshot(document: Document): ConsentSnapshot = ConsentSnapshot(
        grants = document.grants.map { ConsentGrant(it.category, it.purpose, it.consentVersion, it.grantedAt, it.accountSub) },
        standing = document.standing.map {
            StandingConsent(
                purpose = it.purpose,
                fields = it.fields.toSortedSet(),
                categories = it.categories.toSortedSet(),
                sourceFamilies = it.sourceFamilies.toSortedSet(),
                lookbackDays = it.lookbackDays,
                cadence = it.cadenceMinutes.minutes,
                dailyBudget = it.dailyBudget,
                consentVersion = it.consentVersion,
                grantedAt = it.grantedAt,
                accountSub = it.accountSub,
            )
        },
    )
}
