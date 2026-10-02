package dev.agentle.fakes.googlehealth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FakeGoogleAuthorizerTest {
    @Test
    fun `issues fake-valid with every v1 scope as full scope URLs`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        val token = authorizer.token(interactive = false) as FakeAuthorization.Token
        assertThat(token.value).isEqualTo(FakeTokens.VALID)
        assertThat(token.grantedScopes).contains("https://www.googleapis.com/auth/googlehealth.sleep.readonly")
        assertThat(token.grantedScopes).hasSize(5)
        assertThat(token.toString()).doesNotContain(FakeTokens.VALID)
        assertThat(authorizer.grantedScopes()).isEqualTo(token.grantedScopes)
    }

    @Test
    fun `an expired token is replaced after the client invalidates it (R05 8 7 S21)`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.expireOnce()
        assertThat((authorizer.token(false) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.EXPIRED)
        assertThat((authorizer.token(false) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.EXPIRED)
        authorizer.invalidate(FakeTokens.EXPIRED)
        assertThat((authorizer.token(false) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.VALID)
        assertThat(authorizer.invalidations).isEqualTo(1)
        assertThat(authorizer.calls.first()).isEqualTo("token(interactive=false)")
    }

    @Test
    fun `a revoked token stays revoked after invalidation (R05 8 7 S22)`() = runTest {
        val authorizer = FakeGoogleAuthorizer(FakeTokens.REVOKED)
        authorizer.invalidate(FakeTokens.REVOKED)
        assertThat((authorizer.token(false) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.REVOKED)
    }

    @Test
    fun `revocation needs a resolution until an interactive call succeeds`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        assertThat(authorizer.revoke()).isTrue()
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.NeedsResolution)
        assertThat(authorizer.grantedScopes()).isEmpty()
        authorizer.interactiveOutcome = FakeAuthorization.Denied
        assertThat(authorizer.token(true)).isEqualTo(FakeAuthorization.Denied)
        authorizer.interactiveOutcome = null
        assertThat(authorizer.token(true)).isInstanceOf(FakeAuthorization.Token::class.java)
        assertThat(authorizer.token(false)).isInstanceOf(FakeAuthorization.Token::class.java)
        authorizer.requireConsent()
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.NeedsResolution)
    }

    @Test
    fun `scripted failures, cancellation and partial grants`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.enqueue(
            FakeAuthorization.Failure(FakeAuthorization.DEVELOPER_ERROR),
            FakeAuthorization.Failure(FakeAuthorization.CANCELED),
        )
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.Failure(10))
        assertThat(authorizer.token(true)).isEqualTo(FakeAuthorization.Failure(16))
        authorizer.issue(FakeTokens.scoped(listOf(GhScopes.SLEEP)))
        val partial = authorizer.token(false) as FakeAuthorization.Token
        assertThat(partial.value).isEqualTo("fake-scope-sleep.readonly")
        assertThat(partial.grantedScopes).containsExactly("https://www.googleapis.com/auth/googlehealth.sleep.readonly")
    }

    @Test
    fun `testing-build-01 partial scopes are reported as full scope URLs`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.grantPartial(listOf(GhScopes.SLEEP, GhScopes.full(GhScopes.SETTINGS)))
        val token = authorizer.token(false) as FakeAuthorization.Token
        assertThat(token.grantedScopes).containsExactly(GhScopes.full(GhScopes.SLEEP), GhScopes.full(GhScopes.SETTINGS))
        assertThat(authorizer.grantedScopes()).isEqualTo(token.grantedScopes)
    }

    @Test
    fun `testing-build-01 a background call needs a resolution while an interactive one succeeds`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.needResolutionInBackground()
        assertThat(authorizer.token(interactive = false)).isEqualTo(FakeAuthorization.NeedsResolution)
        assertThat(authorizer.token(interactive = false)).isEqualTo(FakeAuthorization.NeedsResolution)
        assertThat(authorizer.token(interactive = true)).isInstanceOf(FakeAuthorization.Token::class.java)
        assertThat(authorizer.token(interactive = false)).isInstanceOf(FakeAuthorization.Token::class.java)
    }

    @Test
    fun `testing-build-01 access revoked upstream is rejected, then needs a resolution`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.revokeAccessUpstream()
        val stale = authorizer.token(false) as FakeAuthorization.Token
        assertThat(stale.value).isEqualTo(FakeTokens.REVOKED)
        assertThat(FakeTokens.scopesOf(stale.value)).isNull()
        authorizer.invalidate("some-other-token")
        assertThat((authorizer.token(false) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.REVOKED)
        authorizer.invalidate(FakeTokens.REVOKED)
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.NeedsResolution)
        assertThat(authorizer.grantedScopes()).isEmpty()
        assertThat((authorizer.token(true) as FakeAuthorization.Token).value).isEqualTo(FakeTokens.VALID)
    }

    @Test
    fun `testing-build-01 network errors and missing Play services`() = runTest {
        val authorizer = FakeGoogleAuthorizer()
        authorizer.failWithNetworkError(times = 2)
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.Failure(FakeAuthorization.NETWORK_ERROR))
        assertThat(authorizer.token(true)).isEqualTo(FakeAuthorization.Failure(7))
        assertThat(authorizer.token(false)).isInstanceOf(FakeAuthorization.Token::class.java)
        authorizer.playServicesMissing()
        assertThat(authorizer.token(true)).isEqualTo(FakeAuthorization.Failure(FakeAuthorization.SERVICE_MISSING))
        assertThat(authorizer.grantedScopes()).isEmpty()
        assertThat(authorizer.revoke()).isFalse()
        authorizer.playServicesMissing(FakeAuthorization.SERVICE_VERSION_UPDATE_REQUIRED)
        assertThat(authorizer.token(false)).isEqualTo(FakeAuthorization.Failure(2))
        authorizer.playServicesAvailable()
        assertThat(authorizer.token(false)).isInstanceOf(FakeAuthorization.Token::class.java)
        assertThat(authorizer.calls.count { it == "token(interactive=false)" }).isEqualTo(4)
    }
}
