package dev.agentle.feature.onboarding

import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Onboarding steps, the permission primer and the capability summary: light/dark, font 1.0/2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = RobolectricDeviceQualifiers.MediumPhone)
class OnboardingScreenshotTest {
    private val primers = listOf(PrimerUi(Primer.NOTIFICATIONS, null), PrimerUi(Primer.ACTIVITY, null))

    private fun state(step: OnboardingStep) =
        OnboardingUiState(loading = false, step = step, sources = setOf(OnboardingSource.PHONE), primers = primers)

    private val denied = state(OnboardingStep.PERMISSIONS).copy(
        primers = listOf(PrimerUi(Primer.NOTIFICATIONS, PermissionState.DENIED_PERMANENTLY), PrimerUi(Primer.ACTIVITY, null)),
    )
    private val summary = state(OnboardingStep.SUMMARY).copy(
        sources = setOf(OnboardingSource.PHONE, OnboardingSource.WEARABLE),
        primers = listOf(PrimerUi(Primer.NOTIFICATIONS, PermissionState.ALLOWED), PrimerUi(Primer.ACTIVITY, PermissionState.DENIED)),
    )

    private fun capture(state: OnboardingUiState, dark: Boolean, fontScale: Float) {
        captureRoboImage { AgentleTestFrame(darkTheme = dark, fontScale = fontScale) { OnboardingScreen(state, OnboardingActions()) } }
    }

    @Test fun welcome_light() = capture(state(OnboardingStep.WELCOME), false, 1f)

    @Test fun welcome_dark_fontScale2() = capture(state(OnboardingStep.WELCOME), true, 2f)

    @Test fun promises_light() = capture(state(OnboardingStep.PROMISES), false, 1f)

    @Test fun promises_dark() = capture(state(OnboardingStep.PROMISES), true, 1f)

    @Test fun promises_light_fontScale2() = capture(state(OnboardingStep.PROMISES), false, 2f)

    @Test fun sources_light() = capture(state(OnboardingStep.SOURCES), false, 1f)

    @Test fun sources_dark_fontScale2() = capture(state(OnboardingStep.SOURCES), true, 2f)

    @Test fun primer_light() = capture(state(OnboardingStep.PERMISSIONS), false, 1f)

    @Test fun primer_dark() = capture(state(OnboardingStep.PERMISSIONS), true, 1f)

    @Test fun primer_light_fontScale2() = capture(state(OnboardingStep.PERMISSIONS), false, 2f)

    @Test fun primerDeniedPermanently_light() = capture(denied, false, 1f)

    @Test fun primerDeniedPermanently_dark_fontScale2() = capture(denied, true, 2f)

    @Test fun summary_light() = capture(summary, false, 1f)

    @Test fun summary_dark() = capture(summary, true, 1f)

    @Test fun summary_light_fontScale2() = capture(summary, false, 2f)

    @Test fun summary_dark_fontScale2() = capture(summary, true, 2f)

    @Test fun error_light() = capture(
        state(OnboardingStep.SOURCES).copy(error = dev.agentle.core.common.AppError.DatabaseError()),
        false,
        1f,
    )
}
