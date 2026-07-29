package com.openminis.app.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Current OpenAI/Codex OAuth constants. Device authorization is not a stable
 * public integration API, so all server-coupled values live here and are
 * pinned by protocol tests instead of being repeated throughout the UI.
 */
object OpenAIDeviceAuthDefaults {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val MAX_AUTH_DURATION_SECONDS = 15 * 60L

    val endpoints = OpenAIDeviceAuthEndpoints(
        requestUserCodeUrl = "$ISSUER/api/accounts/deviceauth/usercode".toHttpUrl(),
        pollTokenUrl = "$ISSUER/api/accounts/deviceauth/token".toHttpUrl(),
        verificationUrl = "$ISSUER/codex/device".toHttpUrl(),
        exchangeTokenUrl = "$ISSUER/oauth/token".toHttpUrl(),
        exchangeRedirectUrl = "$ISSUER/deviceauth/callback".toHttpUrl(),
        clientId = CLIENT_ID,
    )
}

/**
 * Injectable endpoint bundle. Production uses [OpenAIDeviceAuthDefaults],
 * while JVM tests point every request at MockWebServer without touching the
 * real OpenAI service.
 */
data class OpenAIDeviceAuthEndpoints(
    val requestUserCodeUrl: HttpUrl,
    val pollTokenUrl: HttpUrl,
    val verificationUrl: HttpUrl,
    val exchangeTokenUrl: HttpUrl,
    val exchangeRedirectUrl: HttpUrl,
    val clientId: String,
)

/**
 * Successful initial response. The code and device id are intentionally kept
 * only in memory. [toString] never renders them, which prevents accidental
 * leakage when a result is interpolated into a diagnostic message.
 */
class OpenAIDeviceAuthorization(
    val deviceAuthId: String,
    val userCode: String,
    val verificationUrl: HttpUrl,
    val intervalSeconds: Long,
) {
    override fun toString(): String =
        "OpenAIDeviceAuthorization(deviceAuthId=<redacted>, userCode=<redacted>, " +
            "verificationUrl=$verificationUrl, intervalSeconds=$intervalSeconds)"
}

/** Successful single poll containing the material needed for token exchange. */
class OpenAIDeviceAuthorizationCode(
    val authorizationCode: String,
    val codeChallenge: String,
    val codeVerifier: String,
) {
    override fun toString(): String =
        "OpenAIDeviceAuthorizationCode(authorizationCode=<redacted>, " +
            "codeChallenge=<redacted>, codeVerifier=<redacted>)"
}

/**
 * In-memory OAuth credential result. Only the fields explicitly required by
 * the existing OpenAI refresh path are retained. Unknown response fields are
 * discarded so future server additions cannot silently expand the sensitive
 * material kept by the app.
 */
class OpenAIDeviceTokens internal constructor(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresInSeconds: Long?,
    val expiresAtEpochMillis: Long?,
) {
    init {
        require((expiresInSeconds == null) == (expiresAtEpochMillis == null))
    }

    /**
     * Convert to the exact JSON shape consumed by OAuthManager. The absolute
     * expiry was fixed when the HTTP response arrived, so waiting on the
     * confirmation screen cannot incorrectly extend token validity.
     */
    internal fun toOAuthStorageJson(): String {
        val json = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("id_token", idToken)
        expiresInSeconds?.let { seconds ->
            json.put("expires_in", seconds)
            json.put("expire_at", requireNotNull(expiresAtEpochMillis))
        }
        return json.toString()
    }

    override fun toString(): String =
        "OpenAIDeviceTokens(accessToken=<redacted>, refreshToken=<redacted>, " +
            "idToken=<redacted>, expiresInSeconds=$expiresInSeconds, " +
            "expiresAtEpochMillis=$expiresAtEpochMillis)"
}

enum class OpenAIDeviceProtocolError {
    INVALID_JSON,
    MISSING_DEVICE_AUTH_ID,
    MISSING_USER_CODE,
    INVALID_INTERVAL,
    MISSING_AUTHORIZATION_CODE,
    MISSING_CODE_CHALLENGE,
    MISSING_CODE_VERIFIER,
    MISSING_ACCESS_TOKEN,
    INVALID_REFRESH_TOKEN,
    INVALID_ID_TOKEN,
    INVALID_EXPIRES_IN,
}

/** Body-free error classification shared by all three protocol operations. */
sealed interface OpenAIDeviceAuthError {
    data object Network : OpenAIDeviceAuthError
    data object Internal : OpenAIDeviceAuthError
    data class HttpStatus(val statusCode: Int) : OpenAIDeviceAuthError
    data class InvalidResponse(val reason: OpenAIDeviceProtocolError) : OpenAIDeviceAuthError
}

sealed interface OpenAIDeviceAuthorizationResult {
    class Ready(val authorization: OpenAIDeviceAuthorization) : OpenAIDeviceAuthorizationResult {
        override fun toString(): String = "Ready($authorization)"
    }

    /** Initial HTTP 404 means device authorization is not enabled. */
    data object Unsupported : OpenAIDeviceAuthorizationResult

    data class Failure(val error: OpenAIDeviceAuthError) : OpenAIDeviceAuthorizationResult
}

sealed interface OpenAIDevicePollResult {
    /** Current official implementation treats polling HTTP 403 and 404 as pending. */
    data object Pending : OpenAIDevicePollResult

    class Authorized(val code: OpenAIDeviceAuthorizationCode) : OpenAIDevicePollResult {
        override fun toString(): String = "Authorized($code)"
    }

    data class Failure(val error: OpenAIDeviceAuthError) : OpenAIDevicePollResult
}

sealed interface OpenAIDeviceTokenResult {
    class Success(val tokens: OpenAIDeviceTokens) : OpenAIDeviceTokenResult {
        override fun toString(): String = "Success($tokens)"
    }

    data class Failure(val error: OpenAIDeviceAuthError) : OpenAIDeviceTokenResult
}

/** Injectable single-operation boundary consumed by the Phase 3 coordinator. */
interface OpenAIDeviceAuthProtocol {
    suspend fun requestDeviceAuthorization(): OpenAIDeviceAuthorizationResult

    suspend fun pollOnce(
        authorization: OpenAIDeviceAuthorization,
    ): OpenAIDevicePollResult

    suspend fun exchangeToken(
        authorizationCode: OpenAIDeviceAuthorizationCode,
    ): OpenAIDeviceTokenResult
}

/**
 * Single-request OpenAI/Codex device authorization client.
 *
 * This class deliberately does not loop, delay, retry, time out the overall
 * login, open a browser, or persist credentials. Those concerns belong to the
 * Phase 3 coordinator and Phase 4 commit boundary. Each OkHttp call is attached
 * to coroutine cancellation so abandoning an attempt cancels the socket.
 */
class OpenAIDeviceAuthClient(
    private val endpoints: OpenAIDeviceAuthEndpoints = OpenAIDeviceAuthDefaults.endpoints,
    private val callFactory: Call.Factory = defaultHttpClient,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : OpenAIDeviceAuthProtocol {

    override suspend fun requestDeviceAuthorization(): OpenAIDeviceAuthorizationResult {
        val requestBody = JSONObject()
            .put("client_id", endpoints.clientId)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoints.requestUserCodeUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        return try {
            execute(request).use { response ->
                classifyDeviceAuthorizationResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                    verificationUrl = endpoints.verificationUrl,
                )
            }
        } catch (_: IOException) {
            OpenAIDeviceAuthorizationResult.Failure(OpenAIDeviceAuthError.Network)
        }
    }

    override suspend fun pollOnce(
        authorization: OpenAIDeviceAuthorization,
    ): OpenAIDevicePollResult {
        val requestBody = JSONObject()
            .put("device_auth_id", authorization.deviceAuthId)
            .put("user_code", authorization.userCode)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoints.pollTokenUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        return try {
            execute(request).use { response ->
                classifyPollResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                )
            }
        } catch (_: IOException) {
            OpenAIDevicePollResult.Failure(OpenAIDeviceAuthError.Network)
        }
    }

    override suspend fun exchangeToken(
        authorizationCode: OpenAIDeviceAuthorizationCode,
    ): OpenAIDeviceTokenResult {
        val requestBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", endpoints.clientId)
            .add("code", authorizationCode.authorizationCode)
            .add("redirect_uri", endpoints.exchangeRedirectUrl.toString())
            .add("code_verifier", authorizationCode.codeVerifier)
            .build()
        val request = Request.Builder()
            .url(endpoints.exchangeTokenUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        return try {
            execute(request).use { response ->
                classifyTokenResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                    receivedAtEpochMillis = currentTimeMillis(),
                )
            }
        } catch (_: IOException) {
            OpenAIDeviceTokenResult.Failure(OpenAIDeviceAuthError.Network)
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response) { _, cancelledResponse, _ ->
                                cancelledResponse.close()
                            }
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAX_SAFE_SECONDS_FOR_MILLIS = Long.MAX_VALUE / 1_000L

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .apply {
                    ProxySelector.getDefault()?.let { proxySelector(it) }
                }
                .build()
        }

        internal fun classifyDeviceAuthorizationResponse(
            statusCode: Int,
            body: String,
            verificationUrl: HttpUrl,
        ): OpenAIDeviceAuthorizationResult {
            if (statusCode == 404) return OpenAIDeviceAuthorizationResult.Unsupported
            if (statusCode !in 200..299) {
                return OpenAIDeviceAuthorizationResult.Failure(
                    OpenAIDeviceAuthError.HttpStatus(statusCode),
                )
            }

            val json = parseJson(body)
                ?: return invalidAuthorization(OpenAIDeviceProtocolError.INVALID_JSON)
            val deviceAuthId = requiredString(json, "device_auth_id")
                ?: return invalidAuthorization(OpenAIDeviceProtocolError.MISSING_DEVICE_AUTH_ID)
            val userCode = requiredString(json, "user_code")
                ?: requiredString(json, "usercode")
                ?: return invalidAuthorization(OpenAIDeviceProtocolError.MISSING_USER_CODE)
            val interval = positiveDecimalString(json, "interval")
                ?: return invalidAuthorization(OpenAIDeviceProtocolError.INVALID_INTERVAL)

            return OpenAIDeviceAuthorizationResult.Ready(
                OpenAIDeviceAuthorization(
                    deviceAuthId = deviceAuthId,
                    userCode = userCode,
                    verificationUrl = verificationUrl,
                    intervalSeconds = interval,
                ),
            )
        }

        internal fun classifyPollResponse(
            statusCode: Int,
            body: String,
        ): OpenAIDevicePollResult {
            if (statusCode == 403 || statusCode == 404) {
                return OpenAIDevicePollResult.Pending
            }
            if (statusCode !in 200..299) {
                return OpenAIDevicePollResult.Failure(
                    OpenAIDeviceAuthError.HttpStatus(statusCode),
                )
            }

            val json = parseJson(body)
                ?: return invalidPoll(OpenAIDeviceProtocolError.INVALID_JSON)
            val authorizationCode = requiredString(json, "authorization_code")
                ?: return invalidPoll(OpenAIDeviceProtocolError.MISSING_AUTHORIZATION_CODE)
            val codeChallenge = requiredString(json, "code_challenge")
                ?: return invalidPoll(OpenAIDeviceProtocolError.MISSING_CODE_CHALLENGE)
            val codeVerifier = requiredString(json, "code_verifier")
                ?: return invalidPoll(OpenAIDeviceProtocolError.MISSING_CODE_VERIFIER)

            return OpenAIDevicePollResult.Authorized(
                OpenAIDeviceAuthorizationCode(
                    authorizationCode = authorizationCode,
                    codeChallenge = codeChallenge,
                    codeVerifier = codeVerifier,
                ),
            )
        }

        internal fun classifyTokenResponse(
            statusCode: Int,
            body: String,
            receivedAtEpochMillis: Long,
        ): OpenAIDeviceTokenResult {
            if (statusCode !in 200..299) {
                return OpenAIDeviceTokenResult.Failure(
                    OpenAIDeviceAuthError.HttpStatus(statusCode),
                )
            }

            val json = parseJson(body)
                ?: return invalidToken(OpenAIDeviceProtocolError.INVALID_JSON)
            val accessToken = requiredString(json, "access_token")
                ?: return invalidToken(OpenAIDeviceProtocolError.MISSING_ACCESS_TOKEN)
            val refreshToken = requiredString(json, "refresh_token")
                ?: return invalidToken(OpenAIDeviceProtocolError.INVALID_REFRESH_TOKEN)
            val idToken = requiredString(json, "id_token")
                ?: return invalidToken(OpenAIDeviceProtocolError.INVALID_ID_TOKEN)
            val expiresIn = if (json.has("expires_in")) {
                positiveInteger(json, "expires_in", MAX_SAFE_SECONDS_FOR_MILLIS)
                    ?: return invalidToken(OpenAIDeviceProtocolError.INVALID_EXPIRES_IN)
            } else {
                null
            }
            val expiresAt = expiresIn?.let { seconds ->
                addMillisSaturated(
                    receivedAtEpochMillis,
                    seconds * 1_000L,
                )
            }

            return OpenAIDeviceTokenResult.Success(
                OpenAIDeviceTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    expiresInSeconds = expiresIn,
                    expiresAtEpochMillis = expiresAt,
                ),
            )
        }

        private fun addMillisSaturated(base: Long, delta: Long): Long =
            if (base > Long.MAX_VALUE - delta) {
                Long.MAX_VALUE
            } else {
                base + delta
            }

        private fun parseJson(body: String): JSONObject? =
            try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }

        private fun requiredString(json: JSONObject, key: String): String? =
            (json.opt(key) as? String)?.takeIf { it.isNotBlank() }

        /**
         * The current Codex user-code response encodes interval as a string.
         * Match its trim + unsigned decimal parse, while rejecting zero and
         * overflow so Phase 3 can never enter a zero-delay polling loop.
         */
        private fun positiveDecimalString(json: JSONObject, key: String): Long? {
            val raw = (json.opt(key) as? String)?.trim() ?: return null
            if (raw.isEmpty() || raw.any { !it.isDigit() }) return null
            return raw.toLongOrNull()?.takeIf { it > 0 }
        }

        private fun positiveInteger(
            json: JSONObject,
            key: String,
            maximum: Long,
        ): Long? {
            val value = when (val number = json.opt(key)) {
                is Byte -> number.toLong()
                is Short -> number.toLong()
                is Int -> number.toLong()
                is Long -> number
                is BigInteger -> {
                    if (
                        number.signum() <= 0 ||
                        number.compareTo(BigInteger.valueOf(maximum)) > 0
                    ) {
                        return null
                    }
                    number.toLong()
                }
                else -> return null
            }
            return value.takeIf { it in 1..maximum }
        }

        private fun invalidAuthorization(
            reason: OpenAIDeviceProtocolError,
        ) = OpenAIDeviceAuthorizationResult.Failure(
            OpenAIDeviceAuthError.InvalidResponse(reason),
        )

        private fun invalidPoll(
            reason: OpenAIDeviceProtocolError,
        ) = OpenAIDevicePollResult.Failure(
            OpenAIDeviceAuthError.InvalidResponse(reason),
        )

        private fun invalidToken(
            reason: OpenAIDeviceProtocolError,
        ) = OpenAIDeviceTokenResult.Failure(
            OpenAIDeviceAuthError.InvalidResponse(reason),
        )
    }
}
