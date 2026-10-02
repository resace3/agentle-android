package dev.agentle.core.datastore

import androidx.datastore.core.DataStore
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * One AI-sharing permission the user gave (round 1 correction 4): data of [category] may be sent for [purpose]. The
 * consent store is an allow-list: absent, unknown or unreadable state means deny, and every category is off by default.
 *
 * @property consentVersion the version of the consent terms the user accepted when granting; grants under other terms
 *   are not in force.
 * @property accountSub the ChatGPT account (`sub`) the grant was given for; it applies to that account only.
 */
@Serializable
data class ConsentGrant(
    val category: String,
    val purpose: String,
    val consentVersion: Int,
    val grantedAtMs: Long,
    val accountSub: String? = null,
)

/**
 * The stored consent document (`ai-consent.v1.json` in `noBackupFilesDir`).
 *
 * @property revision changes on every grant, revocation and reset; a request carries the revision it was approved at
 *   and is revalidated against it immediately before sending.
 * @property installId the install the grants belong to; the grants of a file copied from another install are void.
 */
@Serializable
data class AiConsentRecord(val revision: Int = 0, val installId: String? = null, val grants: List<ConsentGrant> = emptyList())

/** The categories and purposes this app version knows; grants and requests outside them are denied. */
data class ConsentVocabulary(val categories: Set<String>, val purposes: Set<String>, val termsVersion: Int)

/**
 * The grants in force at one [revision]. [readable] is false when the store could not be read, and then nothing is
 * allowed.
 */
data class AiConsentSnapshot(val revision: Int, val grants: Set<ConsentGrant>, val readable: Boolean) {
    fun allows(category: String, purpose: String, accountSub: String?): Boolean =
        readable && grants.any { it.category == category && it.purpose == purpose && it.accountSub == accountSub }

    fun allowedCategories(purpose: String, accountSub: String?): Set<String> = if (readable) {
        grants.filter {
            it.purpose == purpose && it.accountSub == accountSub
        }.mapTo(sortedSetOf()) { it.category }
    } else {
        emptySet()
    }

    companion object {
        val DENY_ALL: AiConsentSnapshot = AiConsentSnapshot(revision = 0, grants = emptySet(), readable = false)
    }
}

/** The approval of one AI request: valid while the consent store is still at [revision] (see [AiConsentStore.revalidate]). */
data class ConsentTicket(val revision: Int, val purpose: String, val categories: Set<String>, val accountSub: String?)

/**
 * The AI-sharing allow-list (round 1 correction 4; round 4 correction 6). Every failure denies: an unreadable file, a
 * corrupted file (reset to no grants), a missing key, an unknown category or purpose, grants of another install or
 * other terms, and any change between a request's approval and its send.
 *
 * Only [grant] adds a grant, and only the user's own action in the UI calls it: no corruption handler, deletion or
 * restore can create one.
 */
interface AiConsentStore {
    val snapshots: Flow<AiConsentSnapshot>

    suspend fun snapshot(): AiConsentSnapshot

    /** Approves a request for [categories] and [purpose], or null when any of them is not allowed. */
    suspend fun ticket(purpose: String, categories: Set<String>, accountSub: String?): ConsentTicket?

    /**
     * True only when the store is readable, still at the ticket's revision (nothing was granted, revoked or reset
     * since) and still allows every category of the ticket. Called immediately before each send.
     */
    suspend fun revalidate(ticket: ConsentTicket): Boolean

    /** Records the user's grant. False when the category or purpose is unknown or the file could not be written. */
    suspend fun grant(category: String, purpose: String, accountSub: String?): Boolean

    /** Withdraws [category] for [purpose], or for every purpose when [purpose] is null. */
    suspend fun revoke(category: String, purpose: String? = null): Boolean

    /** Withdraws every grant of [categories] (a category deletion); every grant when [categories] is null. */
    suspend fun revokeCategories(categories: Set<String>?): Boolean
}

/** [AiConsentStore] on a typed DataStore. */
class DataStoreAiConsentStore internal constructor(
    private val store: DataStore<AiConsentRecord>,
    private val vocabulary: ConsentVocabulary,
    private val installId: () -> String,
    private val clock: AgentleClock,
    private val diagnostics: StoreDiagnostics,
) : AiConsentStore {
    override val snapshots: Flow<AiConsentSnapshot> = store.data
        .map { snapshotOf(it) }
        .catch { error ->
            if (error !is IOException) throw error
            diagnostics.onIoFailure(NAME, "read", error.javaClass.simpleName)
            emit(AiConsentSnapshot.DENY_ALL)
        }

    override suspend fun snapshot(): AiConsentSnapshot = snapshots.first()

    override suspend fun ticket(purpose: String, categories: Set<String>, accountSub: String?): ConsentTicket? {
        val snapshot = snapshot()
        val allowed = snapshot.readable && categories.all { snapshot.allows(it, purpose, accountSub) }
        return if (allowed) ConsentTicket(snapshot.revision, purpose, categories.toSortedSet(), accountSub) else null
    }

    override suspend fun revalidate(ticket: ConsentTicket): Boolean {
        val snapshot = snapshot()
        return snapshot.readable &&
            snapshot.revision == ticket.revision &&
            ticket.categories.all { snapshot.allows(it, ticket.purpose, ticket.accountSub) }
    }

    override suspend fun grant(category: String, purpose: String, accountSub: String?): Boolean {
        if (category !in vocabulary.categories || purpose !in vocabulary.purposes) return false
        val grant = ConsentGrant(category, purpose, vocabulary.termsVersion, clock.now().toEpochMilliseconds(), accountSub)
        return change { grants ->
            val kept = grants.filterNot { it.category == category && it.purpose == purpose && it.accountSub == accountSub }
            kept + grant
        }
    }

    override suspend fun revoke(category: String, purpose: String?): Boolean =
        change { grants -> grants.filterNot { it.category == category && (purpose == null || it.purpose == purpose) } }

    override suspend fun revokeCategories(categories: Set<String>?): Boolean =
        change { grants -> if (categories == null) emptyList() else grants.filterNot { it.category in categories } }

    /**
     * Applies [transform] to the grants in force (grants of another install or other terms are dropped) and bumps the
     * revision whenever the stored document changes.
     */
    private suspend fun change(transform: (List<ConsentGrant>) -> List<ConsentGrant>): Boolean = try {
        val install = installId()
        store.updateData { stored ->
            val current = if (stored.installId == install) stored.grants.filter { inForce(it) } else emptyList()
            val next = AiConsentRecord(revision = stored.revision, installId = install, grants = transform(current))
            if (next == stored) stored else next.copy(revision = nextRevision(stored.revision))
        }
        true
    } catch (expected: IOException) {
        diagnostics.onIoFailure(NAME, "write", expected.javaClass.simpleName)
        false
    }

    private fun snapshotOf(record: AiConsentRecord): AiConsentSnapshot {
        val grants = if (record.installId == installId()) record.grants.filterTo(LinkedHashSet()) { inForce(it) } else emptySet()
        return AiConsentSnapshot(record.revision, grants, readable = true)
    }

    private fun inForce(grant: ConsentGrant): Boolean =
        grant.category in vocabulary.categories && grant.purpose in vocabulary.purposes && grant.consentVersion == vocabulary.termsVersion

    companion object {
        const val NAME: String = "ai-consent"

        private fun nextRevision(revision: Int): Int = if (revision >= Int.MAX_VALUE - 1) 1 else revision + 1

        /**
         * The revision after a reset: a random value in the upper half of the positive range, so a request approved
         * before the reset can never match the revisions that follow it.
         */
        internal fun resetRevision(random: SecureRandom = SecureRandom()): Int = (1 shl 30) + random.nextInt(1 shl 29)

        fun create(
            file: () -> File,
            scope: CoroutineScope,
            vocabulary: ConsentVocabulary,
            installId: () -> String,
            clock: AgentleClock,
            diagnostics: StoreDiagnostics = StoreDiagnostics.NONE,
        ): DataStoreAiConsentStore {
            val store = jsonDataStore(NAME, file, AiConsentRecord.serializer(), AiConsentRecord(), scope, diagnostics) {
                // A corrupted file never yields a grant: the reset document has none.
                AiConsentRecord(revision = resetRevision(), installId = null, grants = emptyList())
            }
            return DataStoreAiConsentStore(store, vocabulary, installId, clock, diagnostics)
        }

        /** For tests: a store over any [DataStore] (a fault-injecting one, for example). */
        internal fun over(
            store: DataStore<AiConsentRecord>,
            vocabulary: ConsentVocabulary,
            installId: () -> String,
            clock: AgentleClock,
            diagnostics: StoreDiagnostics,
        ) = DataStoreAiConsentStore(store, vocabulary, installId, clock, diagnostics)
    }
}
