package com.openminis.app.data.repository

import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM proof for the pure half of ProviderRepository.removeInstance:
 * dispatch the real OpenAI key mapping into in-memory stores and verify that
 * only the requested instance disappears.
 */
class ProviderCredentialCleanupTest {

    @Test
    fun `deleting OpenAI OAuth provider clears both stores for target only`() {
        val target = instance("target", ProviderType.openAI, ProviderCredential.oauth)
        val targetOAuthKeys = OpenAIOAuthManager.credentialPreferenceKeys(target.id)
        val otherOAuthKeys = OpenAIOAuthManager.credentialPreferenceKeys("other")
        val oauthStore = (targetOAuthKeys + otherOAuthKeys).toMutableSet()
        val apiKeyStore = mutableSetOf("target", "other")

        clearRemovedProviderCredentials(
            instanceId = target.id,
            removedInstance = target,
            clearOAuthCredentials = { removed ->
                OpenAIOAuthManager.credentialPreferenceKeys(removed.id)
                    .forEach(oauthStore::remove)
            },
            clearApiKey = apiKeyStore::remove,
        )

        assertTrue(oauthStore.containsAll(otherOAuthKeys))
        assertFalse(oauthStore.any { it in targetOAuthKeys })
        assertEquals(setOf("other"), apiKeyStore)
    }

    @Test
    fun `deleting non OAuth provider never clears OAuth namespace`() {
        val target = instance("target", ProviderType.openAI, ProviderCredential.apiKey)
        val oauthStore = OpenAIOAuthManager.credentialPreferenceKeys(target.id).toMutableSet()
        var oauthCleanupCalls = 0
        val apiKeyStore = mutableSetOf("target")

        clearRemovedProviderCredentials(
            instanceId = target.id,
            removedInstance = target,
            clearOAuthCredentials = {
                oauthCleanupCalls += 1
                oauthStore.clear()
            },
            clearApiKey = apiKeyStore::remove,
        )

        assertEquals(0, oauthCleanupCalls)
        assertTrue(oauthStore.isNotEmpty())
        assertTrue(apiKeyStore.isEmpty())
    }

    @Test
    fun `stale provider id still clears API key mirror`() {
        val apiKeyStore = mutableSetOf("stale", "other")

        clearRemovedProviderCredentials(
            instanceId = "stale",
            removedInstance = null,
            clearOAuthCredentials = { error("must not be called") },
            clearApiKey = apiKeyStore::remove,
        )

        assertEquals(setOf("other"), apiKeyStore)
    }

    private fun instance(
        id: String,
        providerType: ProviderType,
        credential: ProviderCredential,
    ) = ProviderInstance(
        id = id,
        label = id,
        providerType = providerType,
        credentialType = credential,
    )
}
