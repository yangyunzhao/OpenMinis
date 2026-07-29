package com.openminis.app.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Timeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.Proxy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Phase 2 protocol contract tests.
 *
 * Every HTTP call targets MockWebServer through an explicit no-proxy client.
 * The tests therefore cannot contact the real OpenAI service or consume a real
 * account authorization.
 */
class OpenAIDeviceAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAIDeviceAuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAIDeviceAuthClient(
            endpoints = OpenAIDeviceAuthEndpoints(
                requestUserCodeUrl = server.url("/api/accounts/deviceauth/usercode"),
                pollTokenUrl = server.url("/api/accounts/deviceauth/token"),
                verificationUrl = server.url("/codex/device"),
                exchangeTokenUrl = server.url("/oauth/token"),
                exchangeRedirectUrl = server.url("/deviceauth/callback"),
                clientId = "test-client",
            ),
            callFactory = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `production defaults match the pinned OpenAI device endpoints`() {
        assertEquals("https://auth.openai.com", OpenAIDeviceAuthDefaults.ISSUER)
        assertEquals(
            "https://auth.openai.com/api/accounts/deviceauth/usercode",
            OpenAIDeviceAuthDefaults.endpoints.requestUserCodeUrl.toString(),
        )
        assertEquals(
            "https://auth.openai.com/api/accounts/deviceauth/token",
            OpenAIDeviceAuthDefaults.endpoints.pollTokenUrl.toString(),
        )
        assertEquals(
            "https://auth.openai.com/codex/device",
            OpenAIDeviceAuthDefaults.endpoints.verificationUrl.toString(),
        )
        assertEquals(
            "https://auth.openai.com/oauth/token",
            OpenAIDeviceAuthDefaults.endpoints.exchangeTokenUrl.toString(),
        )
        assertEquals(
            "https://auth.openai.com/deviceauth/callback",
            OpenAIDeviceAuthDefaults.endpoints.exchangeRedirectUrl.toString(),
        )
        assertEquals(15 * 60L, OpenAIDeviceAuthDefaults.MAX_AUTH_DURATION_SECONDS)
    }

    @Test
    fun `request user code posts exact JSON and preserves opaque response fields`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"device_auth_id":" device-secret ","user_code":" Ab-Cd ","interval":"5"}""",
                ),
        )

        val result = client.requestDeviceAuthorization()
            as OpenAIDeviceAuthorizationResult.Ready
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/api/accounts/deviceauth/usercode", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val requestJson = JSONObject(request.body.readUtf8())
        assertEquals(1, requestJson.length())
        assertEquals("test-client", requestJson.getString("client_id"))
        assertEquals(" device-secret ", result.authorization.deviceAuthId)
        assertEquals(" Ab-Cd ", result.authorization.userCode)
        assertEquals(5L, result.authorization.intervalSeconds)
        assertEquals(server.url("/codex/device"), result.authorization.verificationUrl)
    }

    @Test
    fun `request user code accepts legacy alias and trimmed decimal interval`() {
        val result = OpenAIDeviceAuthClient.classifyDeviceAuthorizationResponse(
            statusCode = 200,
            body = """{"device_auth_id":"d","usercode":"raw-code","interval":" 7 "}""",
            verificationUrl = "https://example.test/codex/device".toHttpUrl(),
        ) as OpenAIDeviceAuthorizationResult.Ready

        assertEquals("raw-code", result.authorization.userCode)
        assertEquals(7L, result.authorization.intervalSeconds)
    }

    @Test
    fun `request user code rejects invalid response fields`() {
        val bodies = listOf(
            "{" to OpenAIDeviceProtocolError.INVALID_JSON,
            """{"user_code":"u","interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_DEVICE_AUTH_ID,
            """{"device_auth_id":1,"user_code":"u","interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_DEVICE_AUTH_ID,
            """{"device_auth_id":" ","user_code":"u","interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_DEVICE_AUTH_ID,
            """{"device_auth_id":"d","interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_USER_CODE,
            """{"device_auth_id":"d","user_code":2,"interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_USER_CODE,
            """{"device_auth_id":"d","user_code":" ","interval":"5"}""" to
                OpenAIDeviceProtocolError.MISSING_USER_CODE,
            """{"device_auth_id":"d","user_code":"u"}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
            """{"device_auth_id":"d","user_code":"u","interval":5}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
            """{"device_auth_id":"d","user_code":"u","interval":"0"}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
            """{"device_auth_id":"d","user_code":"u","interval":"-1"}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
            """{"device_auth_id":"d","user_code":"u","interval":"1.5"}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
            """{"device_auth_id":"d","user_code":"u","interval":"9223372036854775808"}""" to
                OpenAIDeviceProtocolError.INVALID_INTERVAL,
        )

        bodies.forEach { (body, expectedReason) ->
            val failure = OpenAIDeviceAuthClient.classifyDeviceAuthorizationResponse(
                200,
                body,
                "https://example.test/codex/device".toHttpUrl(),
            ) as OpenAIDeviceAuthorizationResult.Failure
            assertEquals(
                OpenAIDeviceAuthError.InvalidResponse(expectedReason),
                failure.error,
            )
        }
    }

    @Test
    fun `request user code classifies 404 separately and other statuses as terminal`() {
        assertTrue(
            OpenAIDeviceAuthClient.classifyDeviceAuthorizationResponse(
                404,
                "secret body",
                "https://example.test/codex/device".toHttpUrl(),
            ) is OpenAIDeviceAuthorizationResult.Unsupported,
        )

        listOf(400, 401, 429, 500).forEach { status ->
            val failure = OpenAIDeviceAuthClient.classifyDeviceAuthorizationResponse(
                status,
                "secret body",
                "https://example.test/codex/device".toHttpUrl(),
            ) as OpenAIDeviceAuthorizationResult.Failure
            assertEquals(OpenAIDeviceAuthError.HttpStatus(status), failure.error)
            assertFalse(failure.toString().contains("secret body"))
        }
    }

    @Test
    fun `poll treats only 403 and 404 as pending without parsing the body`() {
        listOf(403, 404).forEach { status ->
            assertTrue(
                OpenAIDeviceAuthClient.classifyPollResponse(status, "not-json-secret")
                    is OpenAIDevicePollResult.Pending,
            )
        }
        listOf(400, 401, 429, 500).forEach { status ->
            val result = OpenAIDeviceAuthClient.classifyPollResponse(status, "not-json-secret")
                as OpenAIDevicePollResult.Failure
            assertEquals(OpenAIDeviceAuthError.HttpStatus(status), result.error)
        }
    }

    @Test
    fun `poll posts only opaque identifiers and parses authorization material`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"authorization_code":"auth-code","code_challenge":"challenge","code_verifier":"verifier"}""",
            ),
        )
        val authorization = authorization()

        val result = client.pollOnce(authorization) as OpenAIDevicePollResult.Authorized
        val request = server.takeRequest()
        val requestJson = JSONObject(request.body.readUtf8())

        assertEquals("POST", request.method)
        assertEquals("/api/accounts/deviceauth/token", request.path)
        assertEquals(2, requestJson.length())
        assertEquals("device-secret", requestJson.getString("device_auth_id"))
        assertEquals("user-secret", requestJson.getString("user_code"))
        assertEquals("auth-code", result.code.authorizationCode)
        assertEquals("challenge", result.code.codeChallenge)
        assertEquals("verifier", result.code.codeVerifier)
    }

    @Test
    fun `poll rejects missing wrong type and blank authorization fields`() {
        val bodies = listOf(
            "{" to OpenAIDeviceProtocolError.INVALID_JSON,
            """{"code_challenge":"c","code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_AUTHORIZATION_CODE,
            """{"authorization_code":1,"code_challenge":"c","code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_AUTHORIZATION_CODE,
            """{"authorization_code":" ","code_challenge":"c","code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_AUTHORIZATION_CODE,
            """{"authorization_code":"a","code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_CODE_CHALLENGE,
            """{"authorization_code":"a","code_challenge":2,"code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_CODE_CHALLENGE,
            """{"authorization_code":"a","code_challenge":" ","code_verifier":"v"}""" to
                OpenAIDeviceProtocolError.MISSING_CODE_CHALLENGE,
            """{"authorization_code":"a","code_challenge":"c"}""" to
                OpenAIDeviceProtocolError.MISSING_CODE_VERIFIER,
            """{"authorization_code":"a","code_challenge":"c","code_verifier":" "}""" to
                OpenAIDeviceProtocolError.MISSING_CODE_VERIFIER,
        )
        bodies.forEach { (body, expectedReason) ->
            val failure = OpenAIDeviceAuthClient.classifyPollResponse(200, body)
                as OpenAIDevicePollResult.Failure
            assertEquals(
                OpenAIDeviceAuthError.InvalidResponse(expectedReason),
                failure.error,
            )
        }
    }

    @Test
    fun `token exchange uses exact form fields and produces storage compatible whitelist`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"access-secret","refresh_token":"refresh-secret","id_token":"id-secret","expires_in":3600,"unknown_secret":"discard-me"}""",
                ),
            )

            val result = client.exchangeToken(
                OpenAIDeviceAuthorizationCode(
                    authorizationCode = "auth + / ?",
                    codeChallenge = "must-not-be-sent",
                    codeVerifier = "verify + / ?",
                ),
            ) as OpenAIDeviceTokenResult.Success
            val request = server.takeRequest()
            val fields = parseForm(request.body.readUtf8())

            assertEquals("POST", request.method)
            assertEquals("/oauth/token", request.path)
            assertTrue(
                request.getHeader("Content-Type")!!
                    .startsWith("application/x-www-form-urlencoded"),
            )
            assertEquals(5, fields.size)
            assertEquals("authorization_code", fields["grant_type"])
            assertEquals("test-client", fields["client_id"])
            assertEquals("auth + / ?", fields["code"])
            assertEquals(server.url("/deviceauth/callback").toString(), fields["redirect_uri"])
            assertEquals("verify + / ?", fields["code_verifier"])
            assertFalse(fields.containsKey("code_challenge"))
            assertEquals("access-secret", result.tokens.accessToken)
            assertEquals("refresh-secret", result.tokens.refreshToken)
            assertEquals("id-secret", result.tokens.idToken)
            assertEquals(3600L, result.tokens.expiresInSeconds)

            val stored = JSONObject(result.tokens.toOAuthStorageJson(1_000L))
            assertEquals("access-secret", stored.getString("access_token"))
            assertEquals("refresh-secret", stored.getString("refresh_token"))
            assertEquals("id-secret", stored.getString("id_token"))
            assertEquals(3600L, stored.getLong("expires_in"))
            assertEquals(3_601_000L, stored.getLong("expire_at"))
            assertFalse(stored.has("unknown_secret"))
        }

    @Test
    fun `token storage expiration saturates instead of overflowing`() {
        val result = OpenAIDeviceAuthClient.classifyTokenResponse(
            200,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":2}""",
        ) as OpenAIDeviceTokenResult.Success

        val stored = JSONObject(result.tokens.toOAuthStorageJson(Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE, stored.getLong("expire_at"))
    }

    @Test
    fun `token exchange rejects unsafe responses without retaining body`() {
        listOf(400, 401, 429, 500).forEach { status ->
            val failure = OpenAIDeviceAuthClient.classifyTokenResponse(status, "response-secret")
                as OpenAIDeviceTokenResult.Failure
            assertEquals(OpenAIDeviceAuthError.HttpStatus(status), failure.error)
            assertFalse(failure.toString().contains("response-secret"))
        }

        val invalidBodies = listOf(
            "{" to OpenAIDeviceProtocolError.INVALID_JSON,
            """{"refresh_token":"r","id_token":"i"}""" to
                OpenAIDeviceProtocolError.MISSING_ACCESS_TOKEN,
            """{"access_token":1,"refresh_token":"r","id_token":"i"}""" to
                OpenAIDeviceProtocolError.MISSING_ACCESS_TOKEN,
            """{"access_token":" ","refresh_token":"r","id_token":"i"}""" to
                OpenAIDeviceProtocolError.MISSING_ACCESS_TOKEN,
            """{"access_token":"a","id_token":"i"}""" to
                OpenAIDeviceProtocolError.INVALID_REFRESH_TOKEN,
            """{"access_token":"a","refresh_token":null,"id_token":"i"}""" to
                OpenAIDeviceProtocolError.INVALID_REFRESH_TOKEN,
            """{"access_token":"a","refresh_token":" ","id_token":"i"}""" to
                OpenAIDeviceProtocolError.INVALID_REFRESH_TOKEN,
            """{"access_token":"a","refresh_token":"r"}""" to
                OpenAIDeviceProtocolError.INVALID_ID_TOKEN,
            """{"access_token":"a","refresh_token":"r","id_token":1}""" to
                OpenAIDeviceProtocolError.INVALID_ID_TOKEN,
            """{"access_token":"a","refresh_token":"r","id_token":" "}""" to
                OpenAIDeviceProtocolError.INVALID_ID_TOKEN,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":0}""" to
                OpenAIDeviceProtocolError.INVALID_EXPIRES_IN,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":1.0}""" to
                OpenAIDeviceProtocolError.INVALID_EXPIRES_IN,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":1e3}""" to
                OpenAIDeviceProtocolError.INVALID_EXPIRES_IN,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":"10"}""" to
                OpenAIDeviceProtocolError.INVALID_EXPIRES_IN,
            """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":18446744073709551617}""" to
                OpenAIDeviceProtocolError.INVALID_EXPIRES_IN,
        )
        invalidBodies.forEach { (body, expectedReason) ->
            val failure = OpenAIDeviceAuthClient.classifyTokenResponse(200, body)
                as OpenAIDeviceTokenResult.Failure
            assertEquals(
                OpenAIDeviceAuthError.InvalidResponse(expectedReason),
                failure.error,
            )
        }
    }

    @Test
    fun `token response may omit expiry without manufacturing an expire at value`() {
        val success = OpenAIDeviceAuthClient.classifyTokenResponse(
            200,
            """{"access_token":"a","refresh_token":"r","id_token":"i"}""",
        ) as OpenAIDeviceTokenResult.Success

        assertEquals(null, success.tokens.expiresInSeconds)
        val stored = JSONObject(success.tokens.toOAuthStorageJson(1_000L))
        assertFalse(stored.has("expires_in"))
        assertFalse(stored.has("expire_at"))
    }

    @Test
    fun `transport failure is classified without exposing exception details`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client.requestDeviceAuthorization()

        assertTrue(result is OpenAIDeviceAuthorizationResult.Failure)
        val failure = result as OpenAIDeviceAuthorizationResult.Failure
        assertEquals(OpenAIDeviceAuthError.Network, failure.error)
    }

    @Test
    fun `cancelling coroutine cancels in flight poll and delivers no late result`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = async { client.pollOnce(authorization()) }
        kotlinx.coroutines.yield()
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

        withTimeout(1_000L) {
            operation.cancelAndJoin()
        }

        assertTrue(operation.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling coroutine directly invokes call cancel`() = runBlocking {
        val callFactory = CancelRecordingCallFactory()
        val recordingClient = OpenAIDeviceAuthClient(
            endpoints = OpenAIDeviceAuthEndpoints(
                requestUserCodeUrl = server.url("/user-code"),
                pollTokenUrl = server.url("/poll"),
                verificationUrl = server.url("/verify"),
                exchangeTokenUrl = server.url("/token"),
                exchangeRedirectUrl = server.url("/callback"),
                clientId = "test-client",
            ),
            callFactory = callFactory,
        )
        val operation = async { recordingClient.pollOnce(authorization()) }
        withTimeout(1_000L) {
            while (callFactory.lastCall?.isExecuted() != true) {
                kotlinx.coroutines.yield()
            }
            operation.cancelAndJoin()
        }

        assertTrue(callFactory.lastCall!!.isCanceled())
    }

    @Test
    fun `all result strings redact opaque secrets`() {
        val secrets = listOf(
            "device-secret",
            "user-secret",
            "auth-secret",
            "challenge-secret",
            "verifier-secret",
            "access-secret",
            "refresh-secret",
            "id-secret",
        )
        val values = listOf(
            OpenAIDeviceAuthorizationResult.Ready(
                OpenAIDeviceAuthorization(
                    "device-secret",
                    "user-secret",
                    "https://example.test/codex/device".toHttpUrl(),
                    5,
                ),
            ),
            OpenAIDevicePollResult.Authorized(
                OpenAIDeviceAuthorizationCode(
                    "auth-secret",
                    "challenge-secret",
                    "verifier-secret",
                ),
            ),
            OpenAIDeviceTokenResult.Success(
                OpenAIDeviceTokens(
                    "access-secret",
                    "refresh-secret",
                    "id-secret",
                    3600,
                ),
            ),
        )
        val diagnostic = values.joinToString()

        secrets.forEach { secret ->
            assertFalse("$secret leaked through toString", diagnostic.contains(secret))
        }
    }

    private fun authorization() = OpenAIDeviceAuthorization(
        deviceAuthId = "device-secret",
        userCode = "user-secret",
        verificationUrl = server.url("/codex/device"),
        intervalSeconds = 1,
    )

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private class CancelRecordingCallFactory : Call.Factory {
        var lastCall: CancelRecordingCall? = null

        override fun newCall(request: Request): Call =
            CancelRecordingCall(request).also { lastCall = it }
    }

    /**
     * A deliberately non-completing call. It isolates the coroutine adapter's
     * cancellation contract without relying on socket timing.
     */
    private class CancelRecordingCall(
        private val request: Request,
    ) : Call {
        private var executed = false
        private var cancelled = false

        override fun request(): Request = request

        override fun execute(): Response =
            throw UnsupportedOperationException("Synchronous execution is not expected")

        override fun enqueue(responseCallback: Callback) {
            executed = true
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = CancelRecordingCall(request)
    }
}
