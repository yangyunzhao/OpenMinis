package com.openminis.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class OpenAIDeviceTokenRefreshTest {

    @Test
    fun `expired device tokens refresh through existing OAuth path without real network`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "access_token": "new-access",
                              "expires_in": 3600
                            }
                            """.trimIndent(),
                        ),
                )
                server.start()

                var stored: JSONObject? = expiredDeviceTokens()
                var cleared = false
                val refreshed = suspend {
                    refreshOAuthTokens(
                        stored = checkNotNull(stored),
                        tokenUrl = server.url("/oauth/token").toString(),
                        clientId = "test-client",
                        clientSecret = null,
                        callFactory = noProxyClient(),
                        nowEpochMillis = NOW,
                        persist = { stored = JSONObject(it.toString()) },
                    )
                }

                val result = resolveValidOAuthAccessToken(
                    stored = stored,
                    nowEpochMillis = NOW,
                    refresh = refreshed,
                    reload = { stored },
                    clearCredentials = { cleared = true },
                )

                assertEquals("new-access", result)
                assertFalse(cleared)
                assertEquals("device-refresh", stored?.getString("refresh_token"))
                assertEquals("old-id-token", stored?.getString("id_token"))
                assertEquals(NOW + 3_600_000L, stored?.getLong("expire_at"))

                val request = server.takeRequest(1, TimeUnit.SECONDS)
                requireNotNull(request)
                assertEquals("/oauth/token", request.path)
                assertTrue(
                    request.getHeader("Content-Type")
                        .orEmpty()
                        .startsWith("application/x-www-form-urlencoded"),
                )
                val form = request.body.readUtf8()
                assertEquals(
                    setOf(
                        "grant_type=refresh_token",
                        "refresh_token=device-refresh",
                        "client_id=test-client",
                    ),
                    form.split("&").toSet(),
                )
                assertFalse(form.contains("old-device-access"))
                assertFalse(form.contains("old-id-token"))
                assertFalse(form.contains("code_verifier"))
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `expired device tokens are cleared after refresh rejection`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()

            val original = expiredDeviceTokens()
            var stored: JSONObject? = original
            var cleared = false
            val result = resolveValidOAuthAccessToken(
                stored = stored,
                nowEpochMillis = NOW,
                refresh = {
                    refreshOAuthTokens(
                        stored = checkNotNull(stored),
                        tokenUrl = server.url("/oauth/token").toString(),
                        clientId = "test-client",
                        clientSecret = null,
                        callFactory = noProxyClient(),
                        nowEpochMillis = NOW,
                        persist = { stored = JSONObject(it.toString()) },
                    )
                },
                reload = { stored },
                clearCredentials = {
                    cleared = true
                    stored = null
                },
            )

            assertNull(result)
            assertTrue(cleared)
            assertNull(stored)
            assertEquals("old-device-access", original.getString("access_token"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `device tokens outside refresh window do not make a request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val stored = JSONObject(
                OpenAIDeviceTokens(
                    accessToken = "still-valid-access",
                    refreshToken = "device-refresh",
                    idToken = "old-id-token",
                    expiresInSeconds = 18_000,
                    expiresAtEpochMillis = NOW + 18_000_000L,
                ).toOAuthStorageJson(),
            )
            var refreshCalled = false

            val result = resolveValidOAuthAccessToken(
                stored = stored,
                nowEpochMillis = NOW,
                refresh = {
                    refreshCalled = true
                    false
                },
                reload = { stored },
                clearCredentials = {
                    throw AssertionError("valid credentials must not be cleared")
                },
            )

            assertEquals("still-valid-access", result)
            assertFalse(refreshCalled)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `failed concurrent refresh does not clear a newer access token`() = runTest {
        val original = expiredDeviceTokens()
        val newer = JSONObject(original.toString())
            .put("access_token", "concurrently-refreshed-access")
            .put("expire_at", NOW + 3_600_000L)
        var cleared = false

        val result = resolveValidOAuthAccessToken(
            stored = original,
            nowEpochMillis = NOW,
            refresh = { false },
            reload = { newer },
            clearCredentials = { cleared = true },
        )

        assertEquals("concurrently-refreshed-access", result)
        assertFalse(cleared)
    }

    @Test
    fun `failed concurrent refresh does not clear a rotated refresh token`() = runTest {
        val original = expiredDeviceTokens()
        val newer = JSONObject(original.toString())
            .put("refresh_token", "concurrently-rotated-refresh")
            .put("expire_at", NOW + 3_600_000L)
        var cleared = false

        val result = resolveValidOAuthAccessToken(
            stored = original,
            nowEpochMillis = NOW,
            refresh = { false },
            reload = { newer },
            clearCredentials = { cleared = true },
        )

        assertEquals("old-device-access", result)
        assertFalse(cleared)
    }

    @Test
    fun `failed concurrent refresh does not clear a renewed expiry snapshot`() = runTest {
        val original = expiredDeviceTokens()
        val newer = JSONObject(original.toString())
            .put("expire_at", NOW + 3_600_000L)
        var cleared = false

        val result = resolveValidOAuthAccessToken(
            stored = original,
            nowEpochMillis = NOW,
            refresh = { false },
            reload = { newer },
            clearCredentials = { cleared = true },
        )

        assertEquals("old-device-access", result)
        assertFalse(cleared)
    }

    @Test
    fun `per-instance mutex coalesces concurrent refresh and protects new credentials`() = runTest {
        val mutex = Mutex()
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefreshToFinish = CompletableDeferred<Unit>()
        var stored: JSONObject? = expiredDeviceTokens()
        var refreshCount = 0
        var cleared = false
        val refresh = suspend {
            refreshCount += 1
            refreshStarted.complete(Unit)
            allowRefreshToFinish.await()
            stored = JSONObject(checkNotNull(stored).toString())
                .put("access_token", "single-flight-access")
                .put("refresh_token", "single-flight-refresh")
                .put("expire_at", NOW + 3_600_000L)
            true
        }
        val resolve = suspend {
            resolveValidOAuthAccessToken(
                stored = stored,
                nowEpochMillis = NOW,
                refresh = refresh,
                reload = { stored },
                clearCredentials = { cleared = true },
                refreshMutex = mutex,
            )
        }

        val first = async { resolve() }
        refreshStarted.await()
        // 立即运行到 Mutex 挂起点，确保第二个调用确实携带首个刷新前的旧快照，
        // 避免测试结果依赖 StandardTestDispatcher 在同一虚拟时刻的排队顺序。
        val second = async(start = CoroutineStart.UNDISPATCHED) { resolve() }
        allowRefreshToFinish.complete(Unit)

        assertEquals("single-flight-access", first.await())
        assertEquals("single-flight-access", second.await())
        assertEquals(1, refreshCount)
        assertFalse(cleared)
    }

    @Test
    fun `failed refresh keeps an access token that is still unexpired`() = runTest {
        val stored = JSONObject(expiredDeviceTokens().toString())
            .put("expire_at", NOW + 60_000L)
        var cleared = false

        val result = resolveValidOAuthAccessToken(
            stored = stored,
            nowEpochMillis = NOW,
            refresh = { false },
            reload = { stored },
            clearCredentials = { cleared = true },
        )

        assertEquals("old-device-access", result)
        assertFalse(cleared)
    }

    @Test
    fun `refresh window boundary is exact`() = runTest {
        var refreshCount = 0
        val exactlyAtBoundary = JSONObject(expiredDeviceTokens().toString())
            .put("expire_at", NOW + 4L * 60 * 60 * 1000)
        val justInsideBoundary = JSONObject(exactlyAtBoundary.toString())
            .put("expire_at", NOW + 4L * 60 * 60 * 1000 - 1)

        assertEquals(
            "old-device-access",
            resolveValidOAuthAccessToken(
                stored = exactlyAtBoundary,
                nowEpochMillis = NOW,
                refresh = {
                    refreshCount += 1
                    false
                },
                reload = { exactlyAtBoundary },
                clearCredentials = {},
            ),
        )
        assertEquals(0, refreshCount)

        assertEquals(
            "old-device-access",
            resolveValidOAuthAccessToken(
                stored = justInsideBoundary,
                nowEpochMillis = NOW,
                refresh = {
                    refreshCount += 1
                    false
                },
                reload = { justInsideBoundary },
                clearCredentials = {},
            ),
        )
        assertEquals(1, refreshCount)
    }

    @Test
    fun `refresh response persists a rotated refresh token`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "access_token": "rotated-access",
                          "refresh_token": "rotated-refresh",
                          "expires_in": 60
                        }
                        """.trimIndent(),
                    ),
            )
            server.start()
            var persisted: JSONObject? = null

            val result = refreshOAuthTokens(
                stored = expiredDeviceTokens(),
                tokenUrl = server.url("/oauth/token").toString(),
                clientId = "test-client",
                clientSecret = null,
                callFactory = noProxyClient(),
                nowEpochMillis = NOW,
                persist = { persisted = JSONObject(it.toString()) },
            )

            assertTrue(result)
            assertEquals("rotated-access", persisted?.getString("access_token"))
            assertEquals("rotated-refresh", persisted?.getString("refresh_token"))
        }
    }

    @OptIn(
        kotlinx.coroutines.DelicateCoroutinesApi::class,
        kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    )
    @Test
    fun `refresh parsing and persistence do not run on the caller thread`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"access_token":"io-access","expires_in":60}"""),
            )
            server.start()
            var persistenceThread = ""

            newSingleThreadContext("simulated-main").use { caller ->
                withContext(caller) {
                    assertTrue(
                        refreshOAuthTokens(
                            stored = expiredDeviceTokens(),
                            tokenUrl = server.url("/oauth/token").toString(),
                            clientId = "test-client",
                            clientSecret = null,
                            callFactory = noProxyClient(),
                            nowEpochMillis = NOW,
                            persist = {
                                persistenceThread = Thread.currentThread().name
                            },
                        ),
                    )
                }
            }

            assertFalse(persistenceThread.contains("simulated-main"))
        }
    }

    private fun expiredDeviceTokens(): JSONObject =
        JSONObject(
            OpenAIDeviceTokens(
                accessToken = "old-device-access",
                refreshToken = "device-refresh",
                idToken = "old-id-token",
                expiresInSeconds = 3_600,
                expiresAtEpochMillis = NOW - 1,
            ).toOAuthStorageJson(),
        )

    private fun noProxyClient(): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .build()

    companion object {
        private const val NOW = 1_800_000_000_000L
    }
}
