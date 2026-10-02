package dev.agentle.connectors.android.permissions

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.LocalRecordingClient

/**
 * Google Play services availability through `GoogleApiAvailability` (docs/research/03: anything but SUCCESS is
 * UNAVAILABLE with PLAY_SERVICES_MISSING). The Recording API needs at least
 * `LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE`.
 */
public class GmsPlayServicesProbe(private val context: Context) : PlayServicesProbe {
    @Suppress("TooGenericExceptionCaught")
    override fun isAvailable(requirement: PlayServicesRequirement): Boolean = try {
        val api = GoogleApiAvailability.getInstance()
        val code = when (requirement) {
            PlayServicesRequirement.ANY -> api.isGooglePlayServicesAvailable(context)

            PlayServicesRequirement.RECORDING_API ->
                api.isGooglePlayServicesAvailable(context, LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE)
        }
        code == ConnectionResult.SUCCESS
    } catch (ignored: RuntimeException) {
        false
    }
}
