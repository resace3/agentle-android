package dev.agentle.connectors.googlehealth

import dev.agentle.core.network.HttpClientConfig
import dev.agentle.core.network.TransportSecurityInterceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the Google Health API v4 connector (docs/research/05 §7). Only the Google Health API
 * (`health.googleapis.com`) is ever called; the legacy Fitbit Web API is not supported.
 *
 * @property baseUrl `https://health.googleapis.com/` in production; the fake flavor and tests use a loopback fake.
 * @property liveApiEnabled feature flag for the production API source. It stays off until the live spike of
 *   docs/research/05 §7.9 step 1 passes (Google is not onboarding new projects yet). A loopback [baseUrl] (the fake
 *   flavor and tests) is always allowed.
 * @property rawHeartRate opt-in: raw heart-rate samples through `list` (up to 86,400 a day) instead of the default
 *   60-second `:rollUp` windows.
 * @property streams the streams to sync (ids from [GoogleHealthStreams]).
 * @property pageSize page size of interval and sample `list` / `:reconcile` / `:rollUp` requests (max 10000).
 * @property sessionPageSize page size for sleep and exercise (max 25, hygiene H6).
 * @property devicePageSize page size of `pairedDevices.list` (max 100).
 * @property hotLoad first-sync window ("hot load").
 * @property backfillChunk span of one background backfill step ("cold load").
 * @property backfillHorizon how far back backfill goes; also bounded by the store's import floor (retention).
 * @property deepResyncWindow / [deepResyncEvery] the weekly deep re-sync of the last 30 days.
 * @property maxChunksPerRun forward windows fetched per stream and run; a long gap continues in the next run.
 * @property readTimeout / [callTimeout] HTTP timeouts of this connector's client.
 */
public data class GoogleHealthConfig(
    val baseUrl: String = PRODUCTION_BASE_URL,
    val liveApiEnabled: Boolean = false,
    val rawHeartRate: Boolean = false,
    val streams: Set<String> = GoogleHealthStreams.ALL.toSet(),
    val pageSize: Int = MAX_PAGE_SIZE,
    val sessionPageSize: Int = MAX_SESSION_PAGE_SIZE,
    val devicePageSize: Int = MAX_DEVICE_PAGE_SIZE,
    val hotLoad: Duration = 14.days,
    val backfillChunk: Duration = 30.days,
    val backfillHorizon: Duration = 90.days,
    val deepResyncWindow: Duration = 30.days,
    val deepResyncEvery: Duration = 7.days,
    val maxChunksPerRun: Int = 45,
    val userAgent: String = "Agentle",
    val readTimeout: Duration = 30.seconds,
    val callTimeout: Duration = 90.seconds,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE" }
        require(sessionPageSize in 1..MAX_SESSION_PAGE_SIZE) { "sessionPageSize must be in 1..$MAX_SESSION_PAGE_SIZE" }
        require(devicePageSize in 1..MAX_DEVICE_PAGE_SIZE) { "devicePageSize must be in 1..$MAX_DEVICE_PAGE_SIZE" }
        require(maxChunksPerRun >= 1) { "maxChunksPerRun must be >= 1" }
        require(streams.all { it in GoogleHealthStreams.ALL }) { "unknown stream in $streams" }
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/'" }
        require(readTimeout.isPositive() && callTimeout.isPositive()) { "timeouts must be > 0" }
    }

    /** The parsed [baseUrl]. */
    public val httpUrl: HttpUrl get() = baseUrl.toHttpUrl()

    /** True when [baseUrl] is a loopback fake (fake flavor, tests). */
    public val isLoopback: Boolean get() = TransportSecurityInterceptor.isLoopback(httpUrl.host)

    /** Whether the connector may call [baseUrl] at all: the live API only behind [liveApiEnabled]. */
    public val apiEnabled: Boolean get() = liveApiEnabled || isLoopback

    /**
     * The OkHttp settings for this connector: the egress allow-list holds only the API host (production:
     * `health.googleapis.com`; fake: loopback), so redirects are never followed, and cleartext is allowed only for a
     * loopback fake.
     */
    public fun httpClientConfig(): HttpClientConfig = HttpClientConfig(
        userAgent = userAgent,
        readTimeout = readTimeout,
        callTimeout = callTimeout,
        allowCleartextLoopback = isLoopback,
        allowedHosts = setOf(httpUrl.host),
    )

    public companion object {
        public const val PRODUCTION_BASE_URL: String = "https://health.googleapis.com/"
        public const val PRODUCTION_HOST: String = "health.googleapis.com"
        public const val MAX_PAGE_SIZE: Int = 10_000
        public const val MAX_SESSION_PAGE_SIZE: Int = 25
        public const val MAX_DEVICE_PAGE_SIZE: Int = 100
    }
}

/** Stream ids of the Google Health connector; each stream's events have the source `googlehealth.<id>`. */
public object GoogleHealthStreams {
    public const val DEVICES: String = "devices"
    public const val SLEEP: String = "sleep"
    public const val STEPS: String = "steps"
    public const val DISTANCE: String = "distance"
    public const val ACTIVE_ENERGY: String = "active_energy"
    public const val FLOORS: String = "floors"
    public const val HEART_RATE: String = "heart_rate"
    public const val EXERCISE: String = "exercise"
    public const val RESTING_HEART_RATE: String = "resting_heart_rate"
    public const val DAILY_STEPS: String = "daily_steps"
    public const val DAILY_DISTANCE: String = "daily_distance"
    public const val DAILY_FLOORS: String = "daily_floors"
    public const val DAILY_TOTAL_CALORIES: String = "daily_total_calories"
    public const val DAILY_ACTIVE_ENERGY: String = "daily_active_energy"
    public const val WEIGHT: String = "weight"
    public const val BODY_FAT: String = "body_fat"

    /** Every stream, in sync order (paired devices first: their last sync bounds the others' coverage). */
    public val ALL: List<String> = listOf(
        DEVICES, SLEEP, STEPS, DISTANCE, ACTIVE_ENERGY, FLOORS, HEART_RATE, EXERCISE, RESTING_HEART_RATE,
        DAILY_STEPS, DAILY_DISTANCE, DAILY_FLOORS, DAILY_TOTAL_CALORIES, DAILY_ACTIVE_ENERGY, WEIGHT, BODY_FAT,
    )
}

/** Google Health read scopes (docs/research/05 §2.2), as full scope URLs. */
public object GoogleHealthScopes {
    public const val PREFIX: String = "https://www.googleapis.com/auth/googlehealth."
    public const val ACTIVITY: String = PREFIX + "activity_and_fitness.readonly"
    public const val METRICS: String = PREFIX + "health_metrics_and_measurements.readonly"
    public const val SLEEP: String = PREFIX + "sleep.readonly"
    public const val SETTINGS: String = PREFIX + "settings.readonly"

    /** The minimal v1 request (§2.2): read-only, never a write scope. */
    public val REQUESTED: Set<String> = setOf(ACTIVITY, METRICS, SLEEP, SETTINGS)
}
