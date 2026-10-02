package dev.agentle.jitai.dsl.nl

import java.text.Normalizer
import java.util.Locale

/**
 * One launcher-visible app (R10 §13.4 step 1).
 *
 * @property usageMinutesLast7Days local usage, only for ordering candidates; never leaves the phone.
 */
public data class InstalledApp(val packageName: String, val label: String, val usageMinutesLast7Days: Long = 0)

/**
 * Port to the installed, launcher-visible apps (`ACTION_MAIN` + `CATEGORY_LAUNCHER` through a `<queries>` declaration,
 * without `QUERY_ALL_PACKAGES`). The list is used only on the device, after the model call (R10 §13.4 step 6).
 */
public fun interface AppLabelResolver {
    public fun launcherApps(): List<InstalledApp>

    /** [AppMatcher.resolve] against [launcherApps]. */
    public fun resolve(appLabel: String): AppResolution = AppMatcher.resolve(appLabel, launcherApps())

    /** True when [packageName] is installed and launcher-visible (R10 §13.4 step 5). */
    public fun isLauncherVisible(packageName: String): Boolean = launcherApps().any { it.packageName == packageName }

    /** The label of an installed app, for the rendered sentence. */
    public fun labelOf(packageName: String): String? = launcherApps().firstOrNull { it.packageName == packageName }?.label

    public companion object {
        /** No launcher-visible apps: every `appLabel` asks the user (C01 with the full picker). */
        public val NONE: AppLabelResolver = AppLabelResolver { emptyList() }

        /** A fixed app list: the fake for tests, previews and the rule editor's sample data (no package manager). */
        public fun of(apps: List<InstalledApp>): AppLabelResolver {
            val fixed = apps.toList()
            return AppLabelResolver { fixed }
        }
    }
}

/** Result of resolving an `appLabel` (R10 §13.4 steps 3-5). */
public sealed interface AppResolution {
    /** Exactly one exact match: resolved without a question. */
    public data class Resolved(val app: InstalledApp) : AppResolution

    /** Several exact matches, or only partial ones: confirm item C01 with these candidates (at most 8). */
    public data class Ambiguous(val candidates: List<InstalledApp>) : AppResolution

    /** No match: C01 with the full app picker. */
    public data object NotFound : AppResolution
}

/** The matching rules of R10 §13.4, as pure functions. */
public object AppMatcher {
    public const val MAX_CANDIDATES: Int = 8

    private val WHITESPACE = Regex("\\s+")
    private const val APP_SUFFIX = " app"

    /** NFKC, case fold, trim, collapse whitespace, drop a trailing " app" (R10 §13.4 step 2). */
    public fun normalize(label: String): String {
        val folded = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(WHITESPACE, " ")
        return if (folded.endsWith(APP_SUFFIX) && folded.length > APP_SUFFIX.length) folded.dropLast(APP_SUFFIX.length).trim() else folded
    }

    /**
     * Exactly one exact match resolves; several exact matches, or partial matches (one normalized label starts with or
     * contains the other), are ambiguous; candidates are ordered by usage minutes (descending), then label.
     */
    public fun resolve(appLabel: String, apps: List<InstalledApp>): AppResolution {
        val wanted = normalize(appLabel)
        if (wanted.isEmpty()) return AppResolution.NotFound
        val exact = apps.filter { normalize(it.label) == wanted }
        if (exact.size == 1) return AppResolution.Resolved(exact.single())
        val candidates = exact.ifEmpty {
            apps.filter {
                val label = normalize(it.label)
                label.isNotEmpty() && (label.contains(wanted) || wanted.contains(label))
            }
        }
        if (candidates.isEmpty()) return AppResolution.NotFound
        val ordered = candidates
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<InstalledApp> { it.usageMinutesLast7Days }.thenBy { it.label }.thenBy { it.packageName })
            .take(MAX_CANDIDATES)
        return AppResolution.Ambiguous(ordered)
    }
}
