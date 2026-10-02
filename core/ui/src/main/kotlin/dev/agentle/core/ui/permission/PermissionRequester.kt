package dev.agentle.core.ui.permission

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.ActivityCompat

/**
 * What one system permission dialog answered.
 *
 * @property granted each requested permission and whether it is held now. An empty map means the request was
 *   cancelled (for example by a configuration change): nothing was answered, and the "requested once" flag of
 *   docs/research/01 §5.2 must not be set.
 * @property showRationale `shouldShowRequestPermissionRationale` of each permission that is not granted, read right
 *   after the dialog. True: Android will show the dialog again (DENIED). False after a denial: the user chose "Don't
 *   ask again" or denied twice, so only the Settings app can grant it (DENIED_PERMANENTLY, docs/research/01 §5.3 A).
 */
@Immutable
public data class PermissionDialogResult(val granted: Map<String, Boolean>, val showRationale: Map<String, Boolean>) {
    val cancelled: Boolean get() = granted.isEmpty()

    val allGranted: Boolean get() = granted.isNotEmpty() && granted.values.all { it }
}

/** Launches the runtime-permission dialog and reads the rationale flag; created by [rememberPermissionRequester]. */
@Stable
public interface PermissionRequester {
    /** Shows the system dialog for [permissions]; permissions that are already held are answered at once. */
    public fun request(permissions: List<String>)

    /** `shouldShowRequestPermissionRationale` of each of [permissions]; all false when no activity is available. */
    public fun showRationale(permissions: List<String>): Map<String, Boolean>
}

/** Reads `shouldShowRequestPermissionRationale` for one permission. */
public fun interface PermissionRationale {
    public fun shouldShow(permission: String): Boolean
}

/**
 * Replaces how [rememberPermissionRequester] reads the rationale flag. Null (the default) asks the current activity.
 * UI tests set it: Robolectric does not model the platform's "denied twice" rule.
 */
public val LocalPermissionRationale: ProvidableCompositionLocal<PermissionRationale?> = staticCompositionLocalOf { null }

/**
 * A [PermissionRequester] backed by the Activity Result API (`RequestMultiplePermissions`). [onResult] receives every
 * answer with the rationale flags read right after the dialog, so the caller can report DENIED versus
 * DENIED_PERMANENTLY through its port: background code cannot tell them apart (it has no activity).
 */
@Composable
public fun rememberPermissionRequester(onResult: (PermissionDialogResult) -> Unit): PermissionRequester {
    val activity = LocalActivity.current
    val override = LocalPermissionRationale.current
    val rationale = remember(activity, override) {
        override ?: PermissionRationale { permission ->
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val denied = granted.filterValues { held -> !held }.keys
        onResult(PermissionDialogResult(granted = granted, showRationale = denied.associateWith { rationale.shouldShow(it) }))
    }
    return remember(launcher, rationale) {
        object : PermissionRequester {
            override fun request(permissions: List<String>) {
                if (permissions.isNotEmpty()) launcher.launch(permissions.toTypedArray())
            }

            override fun showRationale(permissions: List<String>): Map<String, Boolean> =
                permissions.associateWith { rationale.shouldShow(it) }
        }
    }
}
