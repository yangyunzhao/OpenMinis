package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-android-oauth-log-redaction] sanitizeBody masks credential values and truncates. */
class OAuthLogRedactionTest {

    @Test
    fun `credential field values are masked`() {
        val body = """{"access_token":"sk-live-SECRET","refresh_token":"rt-SECRET2","token_type":"bearer"}"""
        val out = OAuthManager.sanitizeBody(body)
        assertFalse(out.contains("sk-live-SECRET"))
        assertFalse(out.contains("rt-SECRET2"))
        assertTrue(out.contains("\"access_token\":\"***\""))
        assertTrue(out.contains("\"refresh_token\":\"***\""))
        // Non-sensitive fields survive for diagnostics.
        assertTrue(out.contains("token_type"))
    }

    @Test
    fun `device_code and key variants are masked`() {
        val body = """{"device_code":"dc-SECRET","key":"or-KEY","client_secret":"cs-SECRET"}"""
        val out = OAuthManager.sanitizeBody(body)
        assertFalse(out.contains("dc-SECRET"))
        assertFalse(out.contains("or-KEY"))
        assertFalse(out.contains("cs-SECRET"))
    }

    @Test
    fun `all OpenAI device authorization fields are masked at any nesting level`() {
        val body = """
            {
              "user_code":"ABCD-EFGH",
              "payload":{
                "device_auth_id":"device-auth-SECRET",
                "authorization_code":"authorization-SECRET",
                "code_verifier":"verifier-SECRET",
                "code_challenge":"challenge-SECRET"
              },
              "safe":"diagnostic-value"
            }
        """.trimIndent()

        val out = OAuthManager.sanitizeBody(body, maxLen = 2_000)

        listOf(
            "ABCD-EFGH",
            "device-auth-SECRET",
            "authorization-SECRET",
            "verifier-SECRET",
            "challenge-SECRET",
        ).forEach { secret ->
            assertFalse("$secret must not survive redaction", out.contains(secret))
        }
        assertTrue(out.contains("diagnostic-value"))
    }

    @Test
    fun `redaction is case insensitive and handles escaped or primitive values`() {
        val body = """{"USER_CODE":"A\"B","device_auth_id":12345,"safe":true}"""
        val out = OAuthManager.sanitizeBody(body)

        assertFalse(out.contains("""A\"B"""))
        assertFalse(out.contains("12345"))
        assertTrue(out.contains("\"USER_CODE\":\"***\""))
        assertTrue(out.contains("\"device_auth_id\":\"***\""))
        assertTrue(out.contains("\"safe\":true"))
    }

    @Test
    fun `sensitive object and array values are replaced as one unit`() {
        val body = """
            {
              "access_token":["FIRST-SECRET","SECOND-SECRET"],
              "nested":{"code_verifier":{"raw":"THIRD-SECRET"}},
              "safe":["visible"]
            }
        """.trimIndent()

        val out = OAuthManager.sanitizeBody(body, maxLen = 2_000)

        assertFalse(out.contains("FIRST-SECRET"))
        assertFalse(out.contains("SECOND-SECRET"))
        assertFalse(out.contains("THIRD-SECRET"))
        assertTrue(out.contains("\"access_token\":\"***\""))
        assertTrue(out.contains("\"code_verifier\":\"***\""))
        assertTrue(out.contains("visible"))
    }

    @Test
    fun `malformed JSON with a sensitive key is withheld conservatively`() {
        val body = """prefix {"access_token":["FIRST-SECRET","SECOND-SECRET"]"""

        val out = OAuthManager.sanitizeBody(body)

        assertEquals("<redacted OAuth body: ${body.length} chars>", out)
        assertFalse(out.contains("FIRST-SECRET"))
        assertFalse(out.contains("SECOND-SECRET"))
    }

    @Test
    fun `form encoded credential fields are masked`() {
        val body =
            "grant_type=authorization_code&code_verifier=VERIFIER-SECRET" +
                "&authorization_code=AUTHORIZATION-SECRET&safe=value"

        val out = OAuthManager.sanitizeBody(body)

        assertFalse(out.contains("VERIFIER-SECRET"))
        assertFalse(out.contains("AUTHORIZATION-SECRET"))
        assertTrue(out.contains("code_verifier=***"))
        assertTrue(out.contains("authorization_code=***"))
        assertTrue(out.contains("safe=value"))
    }

    @Test
    fun `plain error body passes through and long body truncates`() {
        assertEquals("""{"error":"invalid_grant"}""", OAuthManager.sanitizeBody("""{"error":"invalid_grant"}"""))
        val long = "x".repeat(1000)
        val out = OAuthManager.sanitizeBody(long)
        assertTrue(out.length < 340)
        assertTrue(out.endsWith("…(1000 chars)"))
    }
}
