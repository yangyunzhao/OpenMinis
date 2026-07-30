package com.openminis.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 协调器测试。全部时间由 TestCoroutineScheduler 推进，不访问网络、不等待真实
 * 15 分钟。部分 fake 故意在 NonCancellable 中迟到返回，用来证明 generation 防护不是
 * 仅依赖 Job.cancel() 的表面效果。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAIDeviceLoginCoordinatorTest {

    @Test
    fun `first poll is immediate and pending waits server interval`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Pending)
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
        }
        val coordinator = coordinator(protocol)

        assertTrue(coordinator.start())
        runCurrent()

        val waiting = coordinator.state.value as OpenAIDeviceLoginState.WaitingForUser
        assertEquals("raw-user-code", waiting.userCode)
        assertEquals(1, protocol.requestCalls)
        assertEquals(1, protocol.pollCalls)
        assertEquals(0, protocol.exchangeCalls)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, protocol.pollCalls)
        assertEquals(1, protocol.exchangeCalls)
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.Authenticated)
    }

    @Test
    fun `poll timeout expires at exact deadline and permits a new attempt`() = runTest {
        val protocol = FakeProtocol().apply {
            authorizationIntervalSeconds = 900L
        }
        val coordinator = coordinator(protocol)

        assertTrue(coordinator.start())
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        advanceTimeBy(899_999L)
        runCurrent()
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.WaitingForUser)
        assertEquals(1, protocol.pollCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(OpenAIDeviceLoginState.Expired, coordinator.state.value)
        assertEquals(1, protocol.pollCalls)

        assertTrue(coordinator.start())
        runCurrent()
        assertEquals(2, protocol.requestCalls)
    }

    @Test
    fun `huge poll interval cannot overflow or escape total timeout`() = runTest {
        val protocol = FakeProtocol().apply {
            authorizationIntervalSeconds = Long.MAX_VALUE
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        advanceTimeBy(900_000L)
        runCurrent()
        assertEquals(OpenAIDeviceLoginState.Expired, coordinator.state.value)
        assertEquals(1, protocol.pollCalls)
    }

    @Test
    fun `non cancellable authorized result arriving at timeout remains expired`() = runTest {
        val releaseLateResult = CompletableDeferred<Unit>()
        val protocol = FakeProtocol().apply {
            pollHandler = { _, _ ->
                withContext(NonCancellable) {
                    releaseLateResult.await()
                }
                OpenAIDevicePollResult.Authorized(authorizationCode())
            }
        }
        val coordinator = coordinator(
            protocol,
            OpenAIDeviceLoginPolicy(maxPollDurationMillis = 1_000L),
        )

        coordinator.start()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.WaitingForUser)

        releaseLateResult.complete(Unit)
        runCurrent()
        assertEquals(OpenAIDeviceLoginState.Expired, coordinator.state.value)
        assertEquals(0, protocol.exchangeCalls)
    }

    @Test
    fun `request network failures retry at one and two seconds then recover`() = runTest {
        val protocol = FakeProtocol().apply {
            requestResults.add(networkAuthorizationFailure())
            requestResults.add(networkAuthorizationFailure())
            requestResults.add(readyAuthorization())
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.requestCalls)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, protocol.requestCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, protocol.requestCalls)

        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(2, protocol.requestCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, protocol.requestCalls)
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.WaitingForUser)
    }

    @Test
    fun `third request network failure terminates without infinite retry`() = runTest {
        val protocol = FakeProtocol().apply {
            repeat(3) { requestResults.add(networkAuthorizationFailure()) }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(3, protocol.requestCalls)
        assertEquals(
            OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.REQUEST_CODE),
            coordinator.state.value,
        )

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(3, protocol.requestCalls)
    }

    @Test
    fun `pending resets consecutive poll network failure counter`() = runTest {
        val protocol = FakeProtocol().apply {
            authorizationIntervalSeconds = 2L
            pollResults.add(networkPollFailure())
            pollResults.add(networkPollFailure())
            pollResults.add(OpenAIDevicePollResult.Pending)
            pollResults.add(networkPollFailure())
            pollResults.add(networkPollFailure())
            pollResults.add(networkPollFailure())
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        repeat(5) {
            advanceTimeBy(2_000L)
            runCurrent()
        }

        assertEquals(6, protocol.pollCalls)
        assertEquals(
            OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.POLL),
            coordinator.state.value,
        )
    }

    @Test
    fun `poll network backoff grows one two four and caps at four seconds`() = runTest {
        val protocol = FakeProtocol().apply {
            authorizationIntervalSeconds = 1L
            pollHandler = { _, _ -> networkPollFailure() }
        }
        val coordinator = coordinator(
            protocol,
            OpenAIDeviceLoginPolicy(
                maxConsecutivePollNetworkFailures = 6,
                retryBaseMillis = 1_000L,
                retryMaxMillis = 4_000L,
            ),
        )

        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, protocol.pollCalls)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(3, protocol.pollCalls)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(4, protocol.pollCalls)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(5, protocol.pollCalls)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(6, protocol.pollCalls)
        assertEquals(
            OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.POLL),
            coordinator.state.value,
        )
    }

    @Test
    fun `token exchange network failure is not retried`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
            tokenResults.add(
                OpenAIDeviceTokenResult.Failure(OpenAIDeviceAuthError.Network),
            )
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()

        assertEquals(1, protocol.exchangeCalls)
        assertEquals(
            OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.EXCHANGE_TOKEN),
            coordinator.state.value,
        )
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, protocol.exchangeCalls)
    }

    @Test
    fun `active repeated start is idempotent and produces one request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val protocol = FakeProtocol().apply {
            requestHandler = {
                gate.await()
                readyAuthorization()
            }
        }
        val coordinator = coordinator(protocol)

        assertTrue(coordinator.start())
        assertFalse(coordinator.start())
        assertFalse(coordinator.start())
        runCurrent()
        assertEquals(1, protocol.requestCalls)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, protocol.requestCalls)
    }

    @Test
    fun `cancelled request late result cannot start polling`() = runTest {
        val lateResult = CompletableDeferred<Unit>()
        val protocol = FakeProtocol().apply {
            requestHandler = {
                withContext(NonCancellable) {
                    lateResult.await()
                }
                readyAuthorization()
            }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertTrue(coordinator.cancel())
        assertEquals(OpenAIDeviceLoginState.Cancelled, coordinator.state.value)

        lateResult.complete(Unit)
        runCurrent()
        assertEquals(OpenAIDeviceLoginState.Cancelled, coordinator.state.value)
        assertEquals(0, protocol.pollCalls)
    }

    @Test
    fun `old poll late result cannot exchange or overwrite new attempt`() = runTest {
        val oldPoll = CompletableDeferred<Unit>()
        val protocol = FakeProtocol().apply {
            requestHandler = { call ->
                OpenAIDeviceAuthorizationResult.Ready(
                    authorization(userCode = "code-$call"),
                )
            }
            pollHandler = { call, _ ->
                if (call == 1) {
                    withContext(NonCancellable) {
                        oldPoll.await()
                    }
                    OpenAIDevicePollResult.Authorized(authorizationCode())
                } else {
                    OpenAIDevicePollResult.Pending
                }
            }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.pollCalls)

        coordinator.cancel()
        assertTrue(coordinator.start())
        runCurrent()
        val newWaiting = coordinator.state.value as OpenAIDeviceLoginState.WaitingForUser
        assertEquals("code-2", newWaiting.userCode)
        assertEquals(2, protocol.pollCalls)

        oldPoll.complete(Unit)
        runCurrent()
        assertEquals(0, protocol.exchangeCalls)
        assertEquals("code-2", (coordinator.state.value as OpenAIDeviceLoginState.WaitingForUser).userCode)
    }

    @Test
    fun `old exchange late tokens cannot authenticate new attempt`() = runTest {
        val oldExchange = CompletableDeferred<Unit>()
        val protocol = FakeProtocol().apply {
            requestHandler = { call ->
                OpenAIDeviceAuthorizationResult.Ready(
                    authorization(userCode = "code-$call"),
                )
            }
            pollHandler = { call, _ ->
                if (call == 1) {
                    OpenAIDevicePollResult.Authorized(authorizationCode())
                } else {
                    OpenAIDevicePollResult.Pending
                }
            }
            tokenHandler = { _, _ ->
                withContext(NonCancellable) {
                    oldExchange.await()
                }
                tokenSuccess()
            }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.ExchangingToken)

        coordinator.cancel()
        coordinator.start()
        runCurrent()
        val newState = coordinator.state.value as OpenAIDeviceLoginState.WaitingForUser
        assertEquals("code-2", newState.userCode)

        oldExchange.complete(Unit)
        runCurrent()
        assertTrue(coordinator.state.value is OpenAIDeviceLoginState.WaitingForUser)
        assertNull(coordinator.claimTokenLease(1L))
    }

    @Test
    fun `token lease is single claim and cancellation invalidates it`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        val authenticated = coordinator.state.value as OpenAIDeviceLoginState.Authenticated

        assertNull(coordinator.claimTokenLease(authenticated.attemptId + 1))
        val lease = coordinator.claimTokenLease(authenticated.attemptId)!!
        assertTrue(lease.isValid())
        assertEquals("access-secret", lease.tokensIfValid()!!.accessToken)
        assertNull(coordinator.claimTokenLease(authenticated.attemptId))
        assertFalse(coordinator.start())

        assertTrue(coordinator.cancel())
        assertFalse(lease.isValid())
        assertNull(lease.tokensIfValid())
        assertTrue(coordinator.start())
    }

    @Test
    fun `authenticated token is discarded when cancelled before claim`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
        }
        val coordinator = coordinator(protocol)
        coordinator.start()
        runCurrent()
        val authenticated = coordinator.state.value as OpenAIDeviceLoginState.Authenticated

        assertTrue(coordinator.cancel())
        assertNull(coordinator.claimTokenLease(authenticated.attemptId))
        assertEquals(OpenAIDeviceLoginState.Cancelled, coordinator.state.value)
    }

    @Test
    fun `close invalidates an already claimed token lease`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
        }
        val coordinator = coordinator(protocol)
        coordinator.start()
        runCurrent()
        val authenticated = coordinator.state.value as OpenAIDeviceLoginState.Authenticated
        val lease = coordinator.claimTokenLease(authenticated.attemptId)!!

        coordinator.close()

        assertFalse(lease.isValid())
        assertNull(lease.tokensIfValid())
        assertFalse(coordinator.completeTokenCommit(authenticated.attemptId))
    }

    @Test
    fun `successful token commit requires live lease and invalidates it`() = runTest {
        val protocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
        }
        val coordinator = coordinator(protocol)
        coordinator.start()
        runCurrent()
        val authenticated = coordinator.state.value as OpenAIDeviceLoginState.Authenticated
        val lease = coordinator.claimTokenLease(authenticated.attemptId)!!

        assertTrue(coordinator.completeTokenCommit(authenticated.attemptId))
        assertFalse(lease.isValid())
        assertNull(lease.tokensIfValid())
        assertFalse(coordinator.completeTokenCommit(authenticated.attemptId))
    }

    @Test
    fun `new coordinator starts idle and cannot recover another instance secrets`() = runTest {
        val firstProtocol = FakeProtocol()
        val first = coordinator(firstProtocol)
        first.start()
        runCurrent()
        assertTrue(first.state.value is OpenAIDeviceLoginState.WaitingForUser)

        val rebuilt = coordinator(FakeProtocol())
        assertEquals(OpenAIDeviceLoginState.Idle, rebuilt.state.value)
        assertFalse(rebuilt.state.toString().contains("raw-user-code"))
    }

    @Test
    fun `close cancels active work clears state and rejects future starts`() = runTest {
        var cancelled = false
        val protocol = FakeProtocol().apply {
            requestHandler = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()
        coordinator.close()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(OpenAIDeviceLoginState.Idle, coordinator.state.value)
        assertFalse(coordinator.start())
    }

    @Test
    fun `protocol errors terminate in exact stage without retry`() = runTest {
        val requestError = OpenAIDeviceAuthError.InvalidResponse(
            OpenAIDeviceProtocolError.INVALID_JSON,
        )
        val requestProtocol = FakeProtocol().apply {
            requestResults.add(
                OpenAIDeviceAuthorizationResult.Failure(requestError),
            )
        }
        val requestCoordinator = coordinator(requestProtocol)
        requestCoordinator.start()
        runCurrent()
        assertEquals(
            OpenAIDeviceLoginState.AuthFailed(
                OpenAIDeviceLoginStage.REQUEST_CODE,
                requestError,
            ),
            requestCoordinator.state.value,
        )
        assertEquals(1, requestProtocol.requestCalls)

        val pollError = OpenAIDeviceAuthError.HttpStatus(500)
        val pollProtocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Failure(pollError))
        }
        val pollCoordinator = coordinator(pollProtocol)
        pollCoordinator.start()
        runCurrent()
        assertEquals(
            OpenAIDeviceLoginState.AuthFailed(
                OpenAIDeviceLoginStage.POLL,
                pollError,
            ),
            pollCoordinator.state.value,
        )
        assertEquals(1, pollProtocol.pollCalls)

        val exchangeError = OpenAIDeviceAuthError.HttpStatus(400)
        val exchangeProtocol = FakeProtocol().apply {
            pollResults.add(OpenAIDevicePollResult.Authorized(authorizationCode()))
            tokenResults.add(OpenAIDeviceTokenResult.Failure(exchangeError))
        }
        val exchangeCoordinator = coordinator(exchangeProtocol)
        exchangeCoordinator.start()
        runCurrent()
        assertEquals(
            OpenAIDeviceLoginState.AuthFailed(
                OpenAIDeviceLoginStage.EXCHANGE_TOKEN,
                exchangeError,
            ),
            exchangeCoordinator.state.value,
        )
        assertEquals(1, exchangeProtocol.exchangeCalls)
    }

    @Test
    fun `unsupported response terminates without polling`() = runTest {
        val protocol = FakeProtocol().apply {
            requestResults.add(OpenAIDeviceAuthorizationResult.Unsupported)
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()

        assertEquals(OpenAIDeviceLoginState.Unsupported, coordinator.state.value)
        assertEquals(0, protocol.pollCalls)
    }

    @Test
    fun `unexpected protocol exception becomes safe terminal error`() = runTest {
        val protocol = FakeProtocol().apply {
            requestHandler = { throw IllegalStateException("secret diagnostic") }
        }
        val coordinator = coordinator(protocol)

        coordinator.start()
        runCurrent()

        val state = coordinator.state.value
        assertEquals(
            OpenAIDeviceLoginState.AuthFailed(
                OpenAIDeviceLoginStage.REQUEST_CODE,
                OpenAIDeviceAuthError.Internal,
            ),
            state,
        )
        assertFalse(state.toString().contains("secret diagnostic"))
    }

    @Test
    fun `state collectors and resubscription never start network work`() = runTest {
        val protocol = FakeProtocol()
        val coordinator = coordinator(protocol)
        var firstCollectorEvents = 0
        var secondCollectorEvents = 0
        val first = backgroundScope.launch {
            coordinator.state.collect { firstCollectorEvents += 1 }
        }
        val second = backgroundScope.launch {
            coordinator.state.collect { secondCollectorEvents += 1 }
        }
        runCurrent()
        assertEquals(0, protocol.requestCalls)

        first.cancel()
        coordinator.start()
        runCurrent()
        assertEquals(1, protocol.requestCalls)

        val replacement = backgroundScope.launch {
            coordinator.state.collect { firstCollectorEvents += 1 }
        }
        runCurrent()
        assertEquals(1, protocol.requestCalls)
        assertTrue(firstCollectorEvents > 0)
        assertTrue(secondCollectorEvents > 0)
        replacement.cancel()
        second.cancel()
    }

    @Test
    fun `observable state strings never expose user code or token`() = runTest {
        val protocol = FakeProtocol()
        val coordinator = coordinator(protocol)
        coordinator.start()
        runCurrent()

        val diagnostic = coordinator.state.value.toString()
        assertFalse(diagnostic.contains("raw-user-code"))
        assertFalse(diagnostic.contains("device-secret"))
        assertFalse(diagnostic.contains("access-secret"))
        assertFalse(diagnostic.contains("refresh-secret"))
        assertFalse(diagnostic.contains("id-secret"))
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        protocol: FakeProtocol,
        policy: OpenAIDeviceLoginPolicy = OpenAIDeviceLoginPolicy(),
    ) = OpenAIDeviceLoginCoordinator(
        protocol = protocol,
        scope = backgroundScope,
        policy = policy,
    )

    private class FakeProtocol : OpenAIDeviceAuthProtocol {
        var requestCalls = 0
        var pollCalls = 0
        var exchangeCalls = 0
        var authorizationIntervalSeconds = 5L

        val requestResults = ArrayDeque<OpenAIDeviceAuthorizationResult>()
        val pollResults = ArrayDeque<OpenAIDevicePollResult>()
        val tokenResults = ArrayDeque<OpenAIDeviceTokenResult>()

        var requestHandler: (suspend (Int) -> OpenAIDeviceAuthorizationResult)? = null
        var pollHandler:
            (suspend (Int, OpenAIDeviceAuthorization) -> OpenAIDevicePollResult)? = null
        var tokenHandler:
            (suspend (Int, OpenAIDeviceAuthorizationCode) -> OpenAIDeviceTokenResult)? = null

        override suspend fun requestDeviceAuthorization(): OpenAIDeviceAuthorizationResult {
            requestCalls += 1
            requestHandler?.let { return it(requestCalls) }
            return requestResults.removeFirstOrNull()
                ?: readyAuthorization(authorizationIntervalSeconds)
        }

        override suspend fun pollOnce(
            authorization: OpenAIDeviceAuthorization,
        ): OpenAIDevicePollResult {
            pollCalls += 1
            pollHandler?.let { return it(pollCalls, authorization) }
            return pollResults.removeFirstOrNull()
                ?: OpenAIDevicePollResult.Pending
        }

        override suspend fun exchangeToken(
            authorizationCode: OpenAIDeviceAuthorizationCode,
        ): OpenAIDeviceTokenResult {
            exchangeCalls += 1
            tokenHandler?.let { return it(exchangeCalls, authorizationCode) }
            return tokenResults.removeFirstOrNull() ?: tokenSuccess()
        }
    }

    companion object {
        private fun authorization(
            userCode: String = "raw-user-code",
            intervalSeconds: Long = 5L,
        ) = OpenAIDeviceAuthorization(
            deviceAuthId = "device-secret",
            userCode = userCode,
            verificationUrl = "https://example.test/codex/device".toHttpUrl(),
            intervalSeconds = intervalSeconds,
        )

        private fun readyAuthorization(
            intervalSeconds: Long = 5L,
        ) = OpenAIDeviceAuthorizationResult.Ready(
            authorization(intervalSeconds = intervalSeconds),
        )

        private fun authorizationCode() = OpenAIDeviceAuthorizationCode(
            authorizationCode = "authorization-secret",
            codeChallenge = "challenge-secret",
            codeVerifier = "verifier-secret",
        )

        private fun tokenSuccess() = OpenAIDeviceTokenResult.Success(
            OpenAIDeviceTokens(
                accessToken = "access-secret",
                refreshToken = "refresh-secret",
                idToken = "id-secret",
                expiresInSeconds = 3_600L,
                expiresAtEpochMillis = 3_601_000L,
            ),
        )

        private fun networkAuthorizationFailure() =
            OpenAIDeviceAuthorizationResult.Failure(OpenAIDeviceAuthError.Network)

        private fun networkPollFailure() =
            OpenAIDevicePollResult.Failure(OpenAIDeviceAuthError.Network)
    }
}
