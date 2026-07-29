package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the two browser-OAuth log boundaries shared by the
 * existing OpenAI flow and future device-login fallback paths.
 */
class OAuthCallbackLogRedactionTest {

    @Test
    fun `callback request summary removes authorization code and state`() {
        val requestLine =
            "GET /auth/callback?code=AUTHORIZATION-SECRET&state=STATE-SECRET HTTP/1.1"

        val summary = OAuthCallbackServer.sanitizeRequestLine(requestLine)

        assertEquals("GET /auth/callback HTTP/1.1", summary)
        assertFalse(summary.contains("AUTHORIZATION-SECRET"))
        assertFalse(summary.contains("STATE-SECRET"))
    }

    @Test
    fun `malformed callback request cannot inject control characters into log`() {
        val summary = OAuthCallbackServer.sanitizeRequestLine(
            "GET /auth/callback?code=SECRET\r\nInjected:true HTTP/1.1",
        )

        assertFalse(summary.contains("SECRET"))
        assertFalse(summary.contains("\r"))
        assertFalse(summary.contains("\n"))
    }

    @Test
    fun `OpenAI authorization URL redacts state and PKCE challenge only`() {
        val url =
            "https://auth.openai.com/oauth/authorize?client_id=client" +
                "&state=STATE-SECRET&code_challenge=CHALLENGE-SECRET&scope=openid"

        val sanitized = OpenAIOAuthManager.sanitizeAuthorizationUrl(url)

        assertFalse(sanitized.contains("STATE-SECRET"))
        assertFalse(sanitized.contains("CHALLENGE-SECRET"))
        assertTrue(sanitized.contains("client_id=client"))
        assertTrue(sanitized.contains("scope=openid"))
    }
}
