package dev.agentle.core.ui.component

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.AppError
import dev.agentle.core.ui.R
import org.junit.Test

/** Every stable `AppError` code (docs/ARCHITECTURE.md §15) has its own user message; unknown codes fall back. */
class ErrorMessagesTest {
    private val everyError: List<AppError> = listOf(
        AppError.PermissionDenied("activity_recognition"),
        AppError.PermissionPermanentlyDenied("activity_recognition"),
        AppError.AuthenticationRequired("chatgpt"),
        AppError.TokenExpired("chatgpt"),
        AppError.RateLimited(),
        AppError.NetworkUnavailable(),
        AppError.RemoteServerError(status = 503),
        AppError.ParsingError(),
        AppError.DatabaseError(),
        AppError.UnsupportedFeature("timeline"),
        AppError.ValidationError(listOf("name_empty")),
        AppError.ConsentViolation(setOf("location")),
        AppError.NotEligible("plan"),
        AppError.Cancelled(),
        AppError.Unexpected(),
    )

    @Test
    fun `every AppError code has its own message`() {
        for (error in everyError) {
            assertWithMessage(error.code).that(hasErrorMessage(error.code)).isTrue()
        }
        val resources = everyError.map { errorMessageRes(it.code) }
        assertThat(resources.toSet()).hasSize(everyError.size)
    }

    @Test
    fun `an unknown code shows the generic message`() {
        assertThat(hasErrorMessage("added_in_a_later_version")).isFalse()
        assertThat(errorMessageRes("added_in_a_later_version")).isEqualTo(R.string.ui_error_unexpected)
    }

    @Test
    fun `the message never depends on the developer detail`() {
        val withDetail = AppError.DatabaseError(detail = "SQLiteException in events_v1")
        assertThat(errorMessageRes(withDetail.code)).isEqualTo(errorMessageRes(AppError.DatabaseError().code))
    }
}
