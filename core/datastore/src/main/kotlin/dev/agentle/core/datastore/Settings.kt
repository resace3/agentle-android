package dev.agentle.core.datastore

import kotlinx.serialization.Serializable

/**
 * Every setting that is not a record (docs/ARCHITECTURE.md §5.2), stored as one JSON document
 * (`settings.v1.json`). Every field has a default, so a missing key decodes to its default, and every default is the
 * privacy-preserving choice. Values read from the file are clamped by [sanitized], so a corrupted or hostile file can
 * never raise a budget or enable a collection.
 */
@Serializable
data class AppSettings(
    val version: Int = CURRENT_VERSION,
    val retention: RetentionSettings = RetentionSettings(),
    val quietHours: QuietHours = QuietHours(),
    val jitai: JitaiSettings = JitaiSettings(),
    val collectionProfile: CollectionProfile = CollectionProfile.BALANCED,
    val onboarding: OnboardingState = OnboardingState(),
    val permissions: PermissionFlags = PermissionFlags(),
    val collection: CollectionChoices = CollectionChoices(),
    val notices: NoticeAcceptances = NoticeAcceptances(),
    /** Developer options; read and written only in the fake flavor (see [DataStoreSettingsStore]). */
    val debug: DebugOptions = DebugOptions(),
) {
    /** These settings with every value inside its allowed range. */
    fun sanitized(): AppSettings = copy(version = CURRENT_VERSION, quietHours = quietHours.sanitized(), jitai = jitai.sanitized())

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/** How long raw data is kept (docs/ARCHITECTURE.md §5.5): indefinitely (default), 30 days, 90 days or one year. */
@Serializable
enum class RetentionPeriod(val days: Int?) {
    FOREVER(null),
    DAYS_30(30),
    DAYS_90(90),
    YEAR_1(365),
}

/**
 * Retention per family of data, applied by the daily retention worker. JITAI definitions, the decision ledger
 * (400 days), user goals and the import floors are exempt (round 2 correction 4).
 */
@Serializable
data class RetentionSettings(
    /** Google Health API and Health Connect data. */
    val wearable: RetentionPeriod = RetentionPeriod.FOREVER,
    /** On-device Android collectors. */
    val android: RetentionPeriod = RetentionPeriod.FOREVER,
    /** The user's own logs (mood, energy, notes). */
    val userLogs: RetentionPeriod = RetentionPeriod.FOREVER,
    /** Agentle's records: insights, intervention content, evaluation logs and generated media. */
    val agentle: RetentionPeriod = RetentionPeriod.FOREVER,
)

/**
 * Quiet hours (R10 §9.3): `HH:mm`, half-open, crossing midnight when [end] is before [start]. Default 22:00-07:00, on.
 * The values are civil times in the clock's zone (AgentleClock), never the JVM default zone.
 */
@Serializable
data class QuietHours(val start: String = DEFAULT_START, val end: String = DEFAULT_END, val enabled: Boolean = true) {
    fun sanitized(): QuietHours = if (isTime(start) && isTime(end)) this else QuietHours(enabled = enabled)

    companion object {
        const val DEFAULT_START: String = "22:00"
        const val DEFAULT_END: String = "07:00"
        private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")

        fun isTime(text: String): Boolean = TIME.matches(text)
    }
}

/**
 * JITAI budgets (R10 §3.5, §9.2): 6 deliveries a day, 30 a week, at least 30 minutes apart. Channel and event-type keys
 * are the engine's enum names (`VOICE`, `SCREEN_ON`, ...); the engine maps them and clamps again.
 */
@Serializable
data class JitaiSettings(
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
    val maxPerWeek: Int = DEFAULT_MAX_PER_WEEK,
    val minGapMinutes: Int = DEFAULT_MIN_GAP_MINUTES,
    val channelCaps: Map<String, Int> = DEFAULT_CHANNEL_CAPS,
    /** Minutes after local midnight at which the engine day rolls over (default 04:00). */
    val rolloverMinute: Int = DEFAULT_ROLLOVER_MINUTE,
    /** Every JITAI is paused until this instant (epoch ms), if set. */
    val pauseUntilMs: Long? = null,
    val maxEventAgeMinutes: Map<String, Int> = emptyMap(),
    /** ISO day numbers (1 = Monday) that count as weekend days. */
    val weekendDays: Set<Int> = DEFAULT_WEEKEND_DAYS,
) {
    /** Every budget inside the hard ceilings of R10 §9.2. */
    fun sanitized(): JitaiSettings {
        val day = maxPerDay.coerceIn(0, HARD_MAX_PER_DAY)
        return copy(
            maxPerDay = day,
            maxPerWeek = maxPerWeek.coerceIn(0, HARD_MAX_PER_WEEK),
            minGapMinutes = minGapMinutes.coerceIn(HARD_MIN_GAP_MINUTES, MAX_GAP_MINUTES),
            channelCaps = channelCaps.mapValues { (_, cap) -> cap.coerceIn(0, day) },
            rolloverMinute = rolloverMinute.coerceIn(0, MAX_ROLLOVER_MINUTE),
            maxEventAgeMinutes = maxEventAgeMinutes.mapValues { (_, age) -> age.coerceIn(1, MAX_EVENT_AGE_MINUTES) },
            weekendDays = weekendDays.filterTo(sortedSetOf()) { it in 1..7 },
        )
    }

    companion object {
        const val DEFAULT_MAX_PER_DAY: Int = 6
        const val DEFAULT_MAX_PER_WEEK: Int = 30
        const val DEFAULT_MIN_GAP_MINUTES: Int = 30
        const val DEFAULT_ROLLOVER_MINUTE: Int = 240
        const val HARD_MAX_PER_DAY: Int = 12
        const val HARD_MAX_PER_WEEK: Int = 60
        const val HARD_MIN_GAP_MINUTES: Int = 15
        const val MAX_GAP_MINUTES: Int = 240
        const val MAX_ROLLOVER_MINUTE: Int = 360
        const val MAX_EVENT_AGE_MINUTES: Int = 120
        val DEFAULT_CHANNEL_CAPS: Map<String, Int> = mapOf("VOICE" to 2, "VIDEO" to 1, "IMAGE" to 3)
        val DEFAULT_WEEKEND_DAYS: Set<Int> = setOf(6, 7)
    }
}

/** Collection profiles (R02 §3.2): the `jitai-tick` and sweep period, 60 / 30 / 15 minutes. Default BALANCED. */
@Serializable
enum class CollectionProfile(val tickMinutes: Int) {
    LOW(60),
    BALANCED(30),
    HIGH(15),
}

/** Onboarding progress: the version of the onboarding flow the user completed (0 = never). */
@Serializable
data class OnboardingState(val completedVersion: Int = 0, val completedAtMs: Long? = null)

/**
 * Flags the Permission Center needs (docs/research/01 §5.2, §5.3): runtime permissions requested at least once and
 * special-access Settings screens the user opened and came back from. Kept in `noBackupFilesDir`, so a restore on a
 * new device never claims a permission was already requested there.
 */
@Serializable
data class PermissionFlags(val requested: Set<String> = emptySet(), val settingsVisited: Set<String> = emptySet())

/** The user's collection choices that on-device collectors read; defaults are the privacy-preserving choices. */
@Serializable
data class CollectionChoices(
    /** Connector ids the user turned off. */
    val disabledConnectors: Set<String> = emptySet(),
    /** Packages whose notification titles and text may be stored (opt-in per app). */
    val notificationContentPackages: Set<String> = emptySet(),
    /** Whether content capture may include the default SMS app and dialer (docs/research/01 §3.34). Off by default. */
    val notificationContentFromSmsAndDialer: Boolean = false,
    /** Whether foreground location may use precise fixes; approximate by default. */
    val preciseLocation: Boolean = false,
)

/**
 * Versions of the notices and prominent disclosures the user accepted, by notice id (`privacy`, `health_data`,
 * `notification_content`, ...). A notice counts as accepted only at the version the app currently requires.
 */
@Serializable
data class NoticeAcceptances(val accepted: Map<String, Int> = emptyMap()) {
    fun isAccepted(notice: String, requiredVersion: Int): Boolean = (accepted[notice] ?: 0) >= requiredVersion
}

/** Developer options of the fake flavor. */
@Serializable
data class DebugOptions(
    /** The scripted scenario of the fake connectors and AI provider, if any. */
    val fakeScenario: String? = null,
    /** Shows the diagnostics screen entries that are hidden in normal builds. */
    val showDiagnostics: Boolean = false,
    /** Shortens periodic schedules for manual testing. */
    val fastSchedules: Boolean = false,
)
