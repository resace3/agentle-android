package dev.agentle.background

import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import dev.agentle.background.port.CollectionProfile
import dev.agentle.background.worker.AgentleWorker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The unique work names of docs/ARCHITECTURE.md §13 (plus `reconcile`, lifecycle-battery-10). */
public object WorkNames {
    public const val COLLECT_USAGE: String = "collect-usage"
    public const val COLLECT_DEVICE: String = "collect-device"
    public const val SYNC_GOOGLEHEALTH: String = "sync-googlehealth"
    public const val SYNC_GOOGLEHEALTH_NOW: String = "sync-googlehealth-now"
    public const val FEATURES_REFRESH: String = "features-refresh"
    public const val JITAI_TIMER: String = "jitai-timer"
    public const val JITAI_EVAL_EVENTS: String = "jitai-eval-events"
    public const val RETENTION: String = "retention"
    public const val MEDIA_CLEANUP: String = "media-cleanup"
    public const val INSIGHTS_WEEKLY: String = "insights-weekly"
    public const val RECONCILE: String = "reconcile"

    public val PERIODIC: List<String> =
        listOf(COLLECT_USAGE, COLLECT_DEVICE, SYNC_GOOGLEHEALTH, FEATURES_REFRESH, RETENTION, MEDIA_CLEANUP, INSIGHTS_WEEKLY)
    public val ONE_TIME: List<String> = listOf(SYNC_GOOGLEHEALTH_NOW, JITAI_TIMER, JITAI_EVAL_EVENTS, RECONCILE)
    public val ALL: List<String> = PERIODIC + ONE_TIME

    /** Tag on every request, so "delete everything" and diagnostics find all of Agentle's work. */
    public const val TAG: String = "agentle"
}

/** Why the reconciler runs (§13; R02 §5.2). */
public enum class ReconcileReason(public val debounced: Boolean) {
    PROCESS_START(debounced = false),
    BOOT(debounced = false),
    PACKAGE_REPLACED(debounced = false),
    CLOCK(debounced = true),
    TIMEZONE(debounced = true),
    OFFSET(debounced = true),
    LOCALE(debounced = true),
}

/** Cadence and constraints of a periodic work under a profile. */
public data class PeriodicSpec(
    val name: String,
    val interval: Duration,
    val network: NetworkType = NetworkType.NOT_REQUIRED,
    val batteryNotLow: Boolean = false,
    val charging: Boolean = false,
)

/**
 * Cadences (one table, so every number is traceable):
 * - collect-usage 6 h / 2 h / 1 h: §6.4 "every 1-6 h by profile"; R02 §2.3 sweep.local 6 h / 2 h (High floored at
 *   §6.4's 1 h). Low adds batteryNotLow (R02 §1.3 sweep.local).
 * - collect-device 60 / 30 / 15 min: §6.4 battery "periodic sample 15-60 min".
 * - sync-googlehealth 6 h UNMETERED / 1 h CONNECTED / 30 min CONNECTED: §13, R02 §2.3.
 * - features-refresh daily, batteryNotLow (Low: charging): R02 §1.3/§2.3 daily.features.
 * - retention, media-cleanup daily, batteryNotLow (Low: charging): §5.5 daily worker; R02 §2.1 rule 4.
 * - insights-weekly 7 d, charging + CONNECTED (Low: UNMETERED): R02 §2.3 daily.insights, lifecycle-battery-09.
 * The interval-rule cadence is the JITAI timer's (rules, >= 15 min), never the profile (lifecycle-battery-11).
 */
public object Cadences {
    public fun periodic(profile: CollectionProfile): List<PeriodicSpec> {
        val low = profile == CollectionProfile.LOW
        return listOf(
            PeriodicSpec(
                WorkNames.COLLECT_USAGE,
                byProfile(profile, 6.hours, 2.hours, 1.hours),
                batteryNotLow = low,
            ),
            PeriodicSpec(WorkNames.COLLECT_DEVICE, byProfile(profile, 60.minutes, 30.minutes, 15.minutes), batteryNotLow = low),
            PeriodicSpec(
                WorkNames.SYNC_GOOGLEHEALTH,
                byProfile(profile, 6.hours, 1.hours, 30.minutes),
                network = if (low) NetworkType.UNMETERED else NetworkType.CONNECTED,
            ),
            PeriodicSpec(WorkNames.FEATURES_REFRESH, 1.days, batteryNotLow = true, charging = low),
            PeriodicSpec(WorkNames.RETENTION, 1.days, batteryNotLow = true, charging = low),
            PeriodicSpec(WorkNames.MEDIA_CLEANUP, 1.days, batteryNotLow = true, charging = low),
            PeriodicSpec(
                WorkNames.INSIGHTS_WEEKLY,
                7.days,
                network = if (low) NetworkType.UNMETERED else NetworkType.CONNECTED,
                charging = true,
            ),
        )
    }

    private fun byProfile(profile: CollectionProfile, low: Duration, balanced: Duration, high: Duration): Duration =
        when (profile) {
            CollectionProfile.LOW -> low
            CollectionProfile.BALANCED -> balanced
            CollectionProfile.HIGH -> high
        }

    /** Delay that coalesces a burst of debounced broadcasts (several TIME_SETs) into one reconcile. */
    public val RECONCILE_DEBOUNCE: Duration = 30.seconds

    /** Exponential backoff of retried one-time work (R02 §1.3: 30 s default, 5 h WorkManager cap). */
    public val BACKOFF: Duration = 30.seconds
}

/** Builds the requests; the only place that knows the worker class. */
internal object Requests {
    const val KEY_WORK = "work"

    fun periodic(spec: PeriodicSpec): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(AgentleWorker::class.java, spec.interval.inWholeMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(spec.network)
                    .setRequiresBatteryNotLow(spec.batteryNotLow)
                    .setRequiresCharging(spec.charging)
                    .build(),
            )
            .setInputData(Data.Builder().putString(KEY_WORK, spec.name).build())
            .addTag(WorkNames.TAG)
            .build()

    /**
     * A one-time request for [name]. Expedited only on API 31+ (RUN_AS_NON_EXPEDITED_WORK_REQUEST when out of
     * quota); on 29-30 a plain one-time request, so no foreground service is ever needed (§13). Expedited work
     * cannot be delayed, so a [delay] makes it a plain request.
     */
    fun oneTime(
        name: String,
        delay: Duration = Duration.ZERO,
        expedited: Boolean,
        network: NetworkType = NetworkType.NOT_REQUIRED,
        id: UUID? = null,
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(AgentleWorker::class.java)
            .setInputData(Data.Builder().putString(KEY_WORK, name).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Cadences.BACKOFF.inWholeSeconds, TimeUnit.SECONDS)
            .addTag(WorkNames.TAG)
        if (id != null) builder.setId(id)
        if (delay.isPositive()) {
            builder.setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } else if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }
}
