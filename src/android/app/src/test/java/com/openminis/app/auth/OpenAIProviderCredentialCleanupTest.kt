package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android keystore itself is device-only, but the deletion boundary is a
 * deterministic per-instance key mapping. These tests pin every OpenAI-owned
 * field and prove that clearing one provider cannot select another provider's
 * namespace.
 */
class OpenAIProviderCredentialCleanupTest {

    @Test
    fun `OpenAI cleanup includes tokens bearer PKCE and account metadata`() {
        val keys = OpenAIOAuthManager.credentialPreferenceKeys("target")

        assertEquals(
            setOf(
                "oauth_tokens_target",
                "oauth_manual_bearer_token_target",
                "oauth_verifier_target",
                "oauth_state_target",
                "oauth_account_id_target",
                "oauth_plan_type_target",
            ),
            keys,
        )
    }

    @Test
    fun `OpenAI cleanup keys remain isolated to the requested provider`() {
        val targetKeys = OpenAIOAuthManager.credentialPreferenceKeys("target")
        val otherKeys = OpenAIOAuthManager.credentialPreferenceKeys("other")

        assertTrue(targetKeys.intersect(otherKeys).isEmpty())
        assertTrue(targetKeys.all { it.endsWith("_target") })
        assertFalse(targetKeys.any { it.endsWith("_other") })
    }
}
