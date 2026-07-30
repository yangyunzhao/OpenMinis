package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The user-visible token-exchange exception must never retain server bodies. */
class OpenAIOAuthErrorTest {

    @Test
    fun `token exchange error exposes status without response body`() {
        val secretBody =
            """{"error":"invalid_grant","authorization_code":"AUTHORIZATION-SECRET"}"""

        val error = try {
            OpenAIOAuthManager.requireSuccessfulTokenResponse(401)
            error("expected OAuthTokenExchangeException")
        } catch (caught: OAuthTokenExchangeException) {
            caught
        }

        assertEquals(401, error.statusCode)
        assertEquals("Token exchange failed (HTTP 401)", error.message)
        assertFalse(error.message.orEmpty().contains(secretBody))
        assertFalse(error.message.orEmpty().contains("AUTHORIZATION-SECRET"))
    }

    @Test
    fun `malformed token response error cannot retain parser input`() {
        val secretBody = "<html>AUTHORIZATION-SECRET</html>"

        val error = try {
            OpenAIOAuthManager.parseTokenResponse(secretBody)
            error("expected OAuthTokenResponseException")
        } catch (caught: OAuthTokenResponseException) {
            caught
        }

        assertEquals("Token endpoint returned an invalid response", error.message)
        assertFalse(error.message.orEmpty().contains(secretBody))
        assertFalse(error.message.orEmpty().contains("AUTHORIZATION-SECRET"))
    }
}
