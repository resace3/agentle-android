package dev.agentle.connectors.android.collectors.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.permissions.Permissions
import dev.agentle.connectors.android.permissions.PlatformState
import dev.agentle.connectors.android.permissions.PlayServicesProbe
import dev.agentle.connectors.android.permissions.PlayServicesRequirement
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CurrentPlaceProvider
import dev.agentle.connectors.api.KnownPlace
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.PlaceClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/** One location fix; it only lives in memory (it is never stored, logged or sent anywhere). */
public class LocationFix(public val latitude: Double, public val longitude: Double, public val accuracyMeters: Float?) {
    override fun toString(): String = "LocationFix(accuracy=$accuracyMeters)"
}

/** One current fix behind a seam. Throws `SecurityException` without a location permission; null when none. */
public fun interface LocationSource {
    public suspend fun currentFix(precise: Boolean): LocationFix?
}

/**
 * The fused provider's current location (balanced power, approximate by default; high accuracy only when the user chose
 * precise location and granted it), or the freshest last known platform fix without Play services.
 */
public class FusedLocationSource(private val context: Context, private val playServices: PlayServicesProbe) : LocationSource {
    @SuppressLint("MissingPermission") // The connector runs only with a location permission; SecurityException is handled there.
    override suspend fun currentFix(precise: Boolean): LocationFix? {
        val location = if (playServices.isAvailable(PlayServicesRequirement.ANY)) fused(precise) else lastKnown(precise)
        return location?.let { LocationFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fused(precise: Boolean): Location? {
        val cancellation = CancellationTokenSource()
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        return try {
            withTimeoutOrNull(FIX_TIMEOUT) {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(priority, cancellation.token).await()
            }
        } finally {
            cancellation.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught")
    private fun lastKnown(precise: Boolean): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = if (precise) {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        return providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                throw e
            } catch (ignored: RuntimeException) {
                null
            }
        }.maxByOrNull { it.elapsedRealtimeNanos }
    }

    private companion object {
        val FIX_TIMEOUT = 20.seconds
    }
}

/** Classifies a fix against the user's known places, locally. */
public object PlaceClassifier {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** A coarse fix's uncertainty widens a place by at most this much. */
    private const val MAX_SLACK_METERS = 500f

    public fun classify(fix: LocationFix?, places: List<KnownPlace>): PlaceClass {
        if (fix == null) return PlaceClass.UNKNOWN
        val slack = (fix.accuracyMeters ?: 0f).coerceIn(0f, MAX_SLACK_METERS)
        val match = places
            .map { place -> place to distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude) }
            .filter { (place, distance) -> distance <= place.radiusMeters + slack }
            .minByOrNull { it.second }
        return match?.first?.placeClass ?: PlaceClass.OTHER
    }

    /** Great-circle distance (haversine). */
    public fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * Foreground location (§6.4 "Location", red team lifecycle-battery-06): one fix while an Agentle screen is visible,
 * approximate by default, classified locally against the user's known places. It only updates [currentPlace] for the
 * UI: no event, no dwell time, no trigger, and nothing is stored (background location is DEFER).
 */
public class LocationConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val source: LocationSource,
    private val platform: PlatformState,
    private val foreground: () -> Boolean,
) : AndroidConnector(
    id = AndroidConnectorIds.LOCATION,
    name = "Location (while open)",
    supportedEventTypes = emptySet(),
    capabilityIds = listOf(CapabilityIds.LOCATION_FOREGROUND),
    runtime = runtime,
    permissions = permissions,
),
    CurrentPlaceProvider {
    private val place = MutableStateFlow<PlaceClass?>(null)

    /** Nothing is stored, so there is no coverage to record. */
    override val currentPlace: StateFlow<PlaceClass?> = place.asStateFlow()

    override suspend fun refresh() {
        if (foreground()) sync(SyncTrigger.MANUAL)
    }

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        if (!foreground()) return CollectOutcome.EMPTY
        val settings = runtime.settings.current()
        val precise = settings.preciseLocation && platform.isGranted(Permissions.ACCESS_FINE_LOCATION)
        val fix = source.currentFix(precise)
        place.value = PlaceClassifier.classify(fix, settings.knownPlaces)
        return CollectOutcome(fetched = if (fix == null) 0 else 1)
    }

    override suspend fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) place.value = null
    }
}
