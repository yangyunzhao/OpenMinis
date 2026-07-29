package com.openminis.app.auth

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIDeviceProviderCommitterTest {

    @Test
    fun `successful commit preserves strict order and loads models last`() = runTest {
        var valid = true
        val lease = lease { valid }
        val store = FakeStore()

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease,
            store,
        ) {
            store.events.add("complete")
            valid = false
            true
        }

        assertEquals(OpenAIDeviceProviderCommitResult.Saved, result)
        assertEquals(
            listOf(
                "transaction:start",
                "marker:PREPARED",
                "provider",
                "marker:PROVIDER_SAVED",
                "credentials",
                "verify-provider",
                "complete",
                "marker:COMMIT_CONFIRMED",
                "clear-marker",
                "transaction:end",
                "models",
            ),
            store.events,
        )
        assertFalse(store.rolledBack)
    }

    @Test
    fun `each pre-confirmation storage failure rolls back both sides`() = runTest {
        val failurePoints = listOf(
            "marker:PREPARED",
            "provider",
            "marker:PROVIDER_SAVED",
            "credentials",
            "verify-provider",
            "marker:COMMIT_CONFIRMED",
        )

        failurePoints.forEach { failurePoint ->
            val store = FakeStore(failAt = failurePoint)
            val result = commitOpenAIDeviceProvider(
                instance(),
                lease { true },
                store,
            ) { true }

            assertTrue("$failurePoint must fail safely", result is OpenAIDeviceProviderCommitResult.Failed)
            if (failurePoint == "marker:PREPARED") {
                assertFalse(store.rolledBack)
            } else {
                assertTrue("$failurePoint must compensate", store.rolledBack)
                assertTrue(store.events.contains("clear-marker"))
            }
        }
    }

    @Test
    fun `lease invalidation after provider save rolls back without credentials`() = runTest {
        var valid = true
        val store = FakeStore(
            afterEvent = { event ->
                if (event == "marker:PROVIDER_SAVED") valid = false
            },
        )

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { valid },
            store,
        ) { true }

        assertEquals(OpenAIDeviceProviderCommitResult.StaleAttempt, result)
        assertFalse(store.events.contains("credentials"))
        assertTrue(store.rolledBack)
    }

    @Test
    fun `final generation handshake failure rolls back written credentials`() = runTest {
        val store = FakeStore()

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            store,
        ) { false }

        assertEquals(OpenAIDeviceProviderCommitResult.StaleAttempt, result)
        assertTrue(store.events.contains("credentials"))
        assertTrue(store.rolledBack)
        assertFalse(store.events.contains("marker:COMMIT_CONFIRMED"))
    }

    @Test
    fun `cancellation before confirmation compensates in non cancellable context`() = runTest {
        val store = FakeStore(cancelAtProvider = true)

        try {
            commitOpenAIDeviceProvider(
                instance(),
                lease { true },
                store,
            ) { true }
            throw AssertionError("CancellationException expected")
        } catch (_: CancellationException) {
            assertTrue(store.rolledBack)
            assertTrue(store.events.contains("clear-marker"))
        }
    }

    @Test
    fun `confirmed marker clear failure remains saved for startup finalization`() = runTest {
        val store = FakeStore(failAt = "clear-marker")

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            store,
        ) { true }

        assertEquals(OpenAIDeviceProviderCommitResult.Saved, result)
        assertFalse(store.rolledBack)
        assertTrue(store.events.contains("marker:COMMIT_CONFIRMED"))
    }

    @Test
    fun `model failure preserves committed provider and credentials`() = runTest {
        val store = FakeStore(failAt = "models")

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            store,
        ) { true }

        assertEquals(
            OpenAIDeviceProviderCommitResult.SavedWithModelLoadFailure,
            result,
        )
        assertFalse(store.rolledBack)
    }

    @Test
    fun `model cancellation propagates after committed provider is preserved`() = runTest {
        val store = FakeStore(cancelAtModels = true)

        try {
            commitOpenAIDeviceProvider(
                instance(),
                lease { true },
                store,
            ) { true }
            throw AssertionError("CancellationException expected")
        } catch (_: CancellationException) {
            assertFalse(store.rolledBack)
            assertTrue(store.events.contains("marker:COMMIT_CONFIRMED"))
        }
    }

    @Test
    fun `stale lease before prepare performs no storage write`() = runTest {
        val store = FakeStore()

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { false },
            store,
        ) { true }

        assertEquals(OpenAIDeviceProviderCommitResult.StaleAttempt, result)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `commit diagnostics never include tokens`() = runTest {
        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            FakeStore(failAt = "credentials"),
        ) { true }
        val diagnostic = result.toString()

        assertFalse(diagnostic.contains("access-secret"))
        assertFalse(diagnostic.contains("refresh-secret"))
        assertFalse(diagnostic.contains("id-secret"))
    }

    @Test
    fun `failed rollback keeps marker for startup recovery`() = runTest {
        val store = FakeStore(
            failAt = "credentials",
            failRollback = true,
        )

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            store,
        ) { true }

        assertTrue(result is OpenAIDeviceProviderCommitResult.Failed)
        assertTrue(store.events.contains("rollback"))
        assertFalse(store.events.contains("clear-marker"))
    }

    @Test
    fun `cross storage writes stay locked while model load runs after transaction`() = runTest {
        val store = FakeStore()

        val result = commitOpenAIDeviceProvider(
            instance(),
            lease { true },
            store,
        ) {
            assertTrue(store.transactionOpen)
            true
        }

        assertEquals(OpenAIDeviceProviderCommitResult.Saved, result)
        assertFalse(store.transactionOpen)
        assertTrue(
            store.events.indexOf("transaction:end") <
                store.events.indexOf("models"),
        )
    }

    @Test
    fun `startup recovery only finalizes a complete confirmed target`() {
        assertEquals(
            OpenAIDeviceRecoveryAction.FINALIZE,
            decideOpenAIDeviceRecoveryAction(
                OpenAIDevicePendingCommitStage.COMMIT_CONFIRMED,
                OpenAIDeviceRecoveryProviderKind.TARGET_OPENAI_OAUTH,
                credentialsCompleteAndMatching = true,
            ),
        )
        assertEquals(
            OpenAIDeviceRecoveryAction.ROLLBACK,
            decideOpenAIDeviceRecoveryAction(
                OpenAIDevicePendingCommitStage.COMMIT_CONFIRMED,
                OpenAIDeviceRecoveryProviderKind.TARGET_OPENAI_OAUTH,
                credentialsCompleteAndMatching = false,
            ),
        )
        assertEquals(
            OpenAIDeviceRecoveryAction.ROLLBACK,
            decideOpenAIDeviceRecoveryAction(
                OpenAIDevicePendingCommitStage.PROVIDER_SAVED,
                OpenAIDeviceRecoveryProviderKind.TARGET_OPENAI_OAUTH,
                credentialsCompleteAndMatching = true,
            ),
        )
    }

    @Test
    fun `startup recovery never deletes unrelated provider`() {
        OpenAIDevicePendingCommitStage.entries.forEach { stage ->
            assertEquals(
                OpenAIDeviceRecoveryAction.CLEAR_MARKER_ONLY,
                decideOpenAIDeviceRecoveryAction(
                    stage,
                    OpenAIDeviceRecoveryProviderKind.UNRELATED,
                    credentialsCompleteAndMatching = false,
                ),
            )
        }
    }

    @Test
    fun `device snapshot replaces legacy manual bearer key`() {
        assertTrue(
            OpenAIOAuthManager.deviceSnapshotKeyNames()
                .contains("manual_bearer_token"),
        )
        assertFalse(
            OpenAIOAuthManager.deviceSnapshotKeyNames()
                .contains("manual_bearer"),
        )
    }

    private class FakeStore(
        private val failAt: String? = null,
        private val cancelAtProvider: Boolean = false,
        private val cancelAtModels: Boolean = false,
        private val failRollback: Boolean = false,
        private val afterEvent: (String) -> Unit = {},
    ) : OpenAIDeviceProviderCommitStore {
        val events = mutableListOf<String>()
        var rolledBack = false
        var transactionOpen = false
            private set

        override suspend fun <T> withProviderTransaction(
            instanceId: String,
            block: suspend () -> T,
        ): T {
            check(!transactionOpen)
            events.add("transaction:start")
            transactionOpen = true
            return try {
                block()
            } finally {
                transactionOpen = false
                events.add("transaction:end")
            }
        }

        override suspend fun writePendingMarker(
            instanceId: String,
            stage: OpenAIDevicePendingCommitStage,
        ) {
            check(transactionOpen)
            event("marker:$stage")
        }

        override suspend fun saveProviderStrict(instance: ProviderInstance) {
            check(transactionOpen)
            event("provider")
            if (cancelAtProvider) {
                throw CancellationException("synthetic cancellation")
            }
        }

        override suspend fun saveCredentialsStrict(
            instanceId: String,
            tokens: OpenAIDeviceTokens,
        ) {
            check(transactionOpen)
            event("credentials")
        }

        override suspend fun verifyProviderStrict(instance: ProviderInstance) {
            check(transactionOpen)
            event("verify-provider")
        }

        override suspend fun clearPendingMarker(instanceId: String) {
            check(transactionOpen)
            event("clear-marker")
        }

        override suspend fun rollbackStrict(instanceId: String) {
            check(transactionOpen)
            events.add("rollback")
            if (failRollback) throw IllegalStateException("synthetic rollback failure")
            rolledBack = true
        }

        override suspend fun applyOpenAIOAuthModels(instance: ProviderInstance) {
            check(!transactionOpen)
            event("models")
            if (cancelAtModels) {
                throw CancellationException("synthetic model cancellation")
            }
        }

        private fun event(name: String) {
            events.add(name)
            afterEvent(name)
            if (failAt == name) throw IllegalStateException("synthetic failure")
        }
    }

    companion object {
        private fun instance() = ProviderInstance(
            id = "provider-instance",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.oauth,
            customBaseURL = null,
        )

        private fun lease(validity: () -> Boolean) =
            OpenAIDeviceTokenLease(
                attemptId = 7L,
                tokens = OpenAIDeviceTokens(
                    accessToken = "access-secret",
                    refreshToken = "refresh-secret",
                    idToken = "id-secret",
                    expiresInSeconds = 3_600L,
                    expiresAtEpochMillis = 3_601_000L,
                ),
                validityCheck = validity,
            )
    }
}
