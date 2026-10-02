package dev.agentle.feature.onboarding.port

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PermissionState
import kotlinx.coroutines.flow.Flow

/** The onboarding pages, in order. */
public enum class OnboardingStep { WELCOME, PROMISES, SOURCES, PERMISSIONS, SUMMARY }

/** Optional sources the user can pick on the source page; picking one only records interest, it connects nothing. */
public enum class OnboardingSource { PHONE, WEARABLE, CHATGPT }

/**
 * Persisted onboarding progress.
 *
 * @property permissionStates the last known state of each capability id the primers asked for (absent: never asked).
 */
public data class OnboardingProgress(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedSources: Set<OnboardingSource> = setOf(OnboardingSource.PHONE),
    val permissionStates: Map<String, PermissionState> = emptyMap(),
    val completed: Boolean = false,
)

/**
 * Onboarding state and the results of the permission primers (Journey 1). All functions are main-safe.
 */
public interface OnboardingPort {
    /**
     * The saved progress. Emits [OnboardingProgress] defaults on a fresh install and the stored value after a process
     * restart. Never fails: an unreadable store emits the defaults.
     */
    public val progress: Flow<OnboardingProgress>

    /** Saves the page the user is on. Errors: `database_error`. */
    public suspend fun saveStep(step: OnboardingStep): Outcome<Unit>

    /** Records whether [source] is picked. Errors: `database_error`. */
    public suspend fun setSourceSelected(source: OnboardingSource, selected: Boolean): Outcome<Unit>

    /**
     * Reports what one system permission dialog answered for [capabilityId]: [granted], and after a denial
     * `shouldShowRequestPermissionRationale` ([showRationale]). The wiring sets the "requested once" flag and stores
     * DENIED (rationale true) or DENIED_PERMANENTLY (rationale false), which background re-evaluation keeps
     * (ARCHITECTURE §6.3). The new state is emitted through [progress]. Errors: `database_error`.
     */
    public suspend fun reportPermissionResult(
        capabilityId: String,
        permission: String,
        granted: Boolean,
        showRationale: Boolean,
    ): Outcome<Unit>

    /** Marks onboarding finished; the app then starts on the Dashboard. Errors: `database_error`. */
    public suspend fun complete(): Outcome<Unit>
}
