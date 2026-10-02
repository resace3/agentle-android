package dev.agentle.feature.settings.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Performs a settings ViewModel's one-off effects while the screen is shown. */
@Composable
internal fun HandleSettingsEffects(effects: Flow<SettingsEffect>, navigator: AppNavigator, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val shareTitle = stringResource(R.string.settings_share_report_title)
    val cannotOpen = stringResource(R.string.settings_error_cannot_open)
    LaunchedEffect(effects, navigator) {
        effects.collect { effect ->
            when (effect) {
                is SettingsEffect.Message -> {
                    val text = resources.getString(effect.text, *effect.args.toTypedArray())
                    launch { snackbarHostState.showSnackbar(text) }
                }

                is SettingsEffect.OpenIntent ->
                    if (!context.startActivitySafely(effect.intent)) launch { snackbarHostState.showSnackbar(cannotOpen) }

                is SettingsEffect.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = effect.mimeType
                        putExtra(Intent.EXTRA_STREAM, effect.uri)
                        clipData = ClipData.newRawUri(shareTitle, effect.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (!context.startActivitySafely(Intent.createChooser(send, shareTitle))) {
                        launch { snackbarHostState.showSnackbar(cannotOpen) }
                    }
                }

                is SettingsEffect.Navigate -> navigator.navigate(effect.route)

                is SettingsEffect.ResetTo -> navigator.resetTo(effect.route)
            }
        }
    }
}

/** Starts [intent]; false when no activity handles it or the system refuses (never a crash). */
internal fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (ignored: ActivityNotFoundException) {
    false
} catch (ignored: SecurityException) {
    false
}
