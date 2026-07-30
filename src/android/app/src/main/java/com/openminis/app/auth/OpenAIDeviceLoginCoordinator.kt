package com.openminis.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import kotlin.math.max
import kotlin.math.min

/**
 * 设备码登录的重试与超时策略。
 *
 * 单次 HTTP 请求仍使用 [OpenAIDeviceAuthClient] 自身的连接和读取超时；这里的 15 分钟
 * 只约束拿到用户码后的轮询阶段。Token 交换不会自动重试，因为响应丢失时无法判断
 * 一次性 authorization code 是否已经被服务端消费。
 */
data class OpenAIDeviceLoginPolicy(
    val maxRequestAttempts: Int = 3,
    val maxConsecutivePollNetworkFailures: Int = 3,
    val retryBaseMillis: Long = 1_000L,
    val retryMaxMillis: Long = 8_000L,
    val maxPollDurationMillis: Long =
        OpenAIDeviceAuthDefaults.MAX_AUTH_DURATION_SECONDS * 1_000L,
) {
    init {
        require(maxRequestAttempts > 0)
        require(maxConsecutivePollNetworkFailures > 0)
        require(retryBaseMillis > 0)
        require(retryMaxMillis >= retryBaseMillis)
        require(maxPollDurationMillis > 0)
    }
}

enum class OpenAIDeviceLoginStage {
    REQUEST_CODE,
    POLL,
    EXCHANGE_TOKEN,
}

/**
 * UI 可观察的非持久化登录状态。
 *
 * 只有 [WaitingForUser] 暴露 UI 必须展示的用户码；设备授权 ID、PKCE 和 Token 从不进入
 * StateFlow。等待状态覆写 [toString]，避免调试器、日志或断言失败消息意外打印完整用户码。
 */
sealed interface OpenAIDeviceLoginState {
    data object Idle : OpenAIDeviceLoginState

    data class RequestingCode(val attemptId: Long) : OpenAIDeviceLoginState

    class WaitingForUser(
        val attemptId: Long,
        val userCode: String,
        val verificationUrl: HttpUrl,
    ) : OpenAIDeviceLoginState {
        override fun toString(): String =
            "WaitingForUser(attemptId=$attemptId, userCode=<redacted>, " +
                "verificationUrl=$verificationUrl)"
    }

    data class ExchangingToken(val attemptId: Long) : OpenAIDeviceLoginState

    /** Token 仍只存在协调器私有内存中，UI 通过 attempt id 一次性领取。 */
    data class Authenticated(val attemptId: Long) : OpenAIDeviceLoginState

    data object Cancelled : OpenAIDeviceLoginState
    data object Expired : OpenAIDeviceLoginState
    data object Unsupported : OpenAIDeviceLoginState

    data class NetworkError(
        val stage: OpenAIDeviceLoginStage,
    ) : OpenAIDeviceLoginState

    data class AuthFailed(
        val stage: OpenAIDeviceLoginStage,
        val error: OpenAIDeviceAuthError,
    ) : OpenAIDeviceLoginState
}

/**
 * Phase 4 跨 Provider 配置与加密凭据存储时使用的短生命周期提交租约。
 *
 * Token 不以公开属性暴露；每次写入前通过 [tokensIfValid] 取得当前仍有效的引用，写入后
 * 必须调用协调器的 `completeTokenCommit`。若用户取消、离页、ViewModel 销毁或新
 * generation 生效，[isValid] 会立即变为 false，提交层必须执行补偿清理。
 */
class OpenAIDeviceTokenLease internal constructor(
    val attemptId: Long,
    private val tokens: OpenAIDeviceTokens,
    private val validityCheck: () -> Boolean,
) {
    fun isValid(): Boolean = validityCheck()

    fun tokensIfValid(): OpenAIDeviceTokens? =
        tokens.takeIf { isValid() }

    override fun toString(): String =
        "OpenAIDeviceTokenLease(attemptId=$attemptId, tokens=<redacted>, " +
            "isValid=${isValid()})"
}

/**
 * 把 Phase 2 的三个单次协议操作组合成一个可取消、可超时的登录尝试。
 *
 * 本类只依赖调用方提供的 [CoroutineScope]。Phase 4 会由路由级 ViewModel 传入
 * `viewModelScope`，因此 Compose 重组和 Activity 重建不会重复启动请求；进程死亡会
 * 创建全新协调器并回到 [OpenAIDeviceLoginState.Idle]。本类不接收 Context、
 * SavedStateHandle、Bundle、Repository 或任何磁盘存储。
 */
class OpenAIDeviceLoginCoordinator(
    private val protocol: OpenAIDeviceAuthProtocol,
    private val scope: CoroutineScope,
    private val policy: OpenAIDeviceLoginPolicy = OpenAIDeviceLoginPolicy(),
) : AutoCloseable {
    private val lock = Any()
    private val _state = MutableStateFlow<OpenAIDeviceLoginState>(
        OpenAIDeviceLoginState.Idle,
    )

    val state: StateFlow<OpenAIDeviceLoginState> = _state.asStateFlow()

    private var generation = 0L
    private var currentJob: Job? = null
    private var pendingTokens: Pair<Long, OpenAIDeviceTokens>? = null
    private var claimedAttemptId: Long? = null
    private var closed = false

    /**
     * 开始一次新登录。活动尝试或尚待保存的成功结果存在时返回 false，保证连续点击
     * 不会创建第二个请求。取消、超时或失败后可再次调用并得到新的 attempt id。
     */
    fun start(): Boolean {
        val attemptId = synchronized(lock) {
            if (closed || _state.value.blocksNewAttempt()) return false
            generation = nextGeneration(generation)
            pendingTokens = null
            claimedAttemptId = null
            _state.value = OpenAIDeviceLoginState.RequestingCode(generation)
            generation
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            runAttempt(attemptId)
        }
        val shouldStart = synchronized(lock) {
            if (!closed && generation == attemptId) {
                currentJob = job
                true
            } else {
                false
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
        return shouldStart
    }

    /**
     * 取消当前活动尝试或丢弃尚未领取的成功 Token。
     *
     * 先增加 generation 并清理内存 Token，再取消 Job。即使底层或测试假实现忽略取消
     * 并迟到返回，旧结果也无法更新状态或进入凭据提交。
     */
    fun cancel(): Boolean {
        val job = synchronized(lock) {
            if (!_state.value.canBeCancelled()) return false
            generation = nextGeneration(generation)
            pendingTokens = null
            claimedAttemptId = null
            val activeJob = currentJob
            currentJob = null
            _state.value = OpenAIDeviceLoginState.Cancelled
            activeJob
        }
        job?.cancel()
        return true
    }

    /**
     * 一次性领取当前成功尝试的提交租约。
     *
     * 领取本身不让 generation 失效，因为 Phase 4 的异步跨存储提交仍需在每一步复核
     * 租约；取消或离页必须能使已经发出的租约失效。
     */
    fun claimTokenLease(attemptId: Long): OpenAIDeviceTokenLease? =
        synchronized(lock) {
            val authenticated = _state.value as? OpenAIDeviceLoginState.Authenticated
                ?: return@synchronized null
            if (authenticated.attemptId != attemptId || generation != attemptId) {
                return@synchronized null
            }
            if (claimedAttemptId != null) return@synchronized null
            val (tokenAttemptId, tokens) = pendingTokens
                ?: return@synchronized null
            if (tokenAttemptId != attemptId) return@synchronized null
            pendingTokens = null
            claimedAttemptId = attemptId
            OpenAIDeviceTokenLease(
                attemptId = attemptId,
                tokens = tokens,
                validityCheck = { isCommitAttemptCurrent(attemptId) },
            )
        }

    /**
     * Phase 4 完成 Provider 与加密凭据的一致性提交后调用。
     *
     * 只有租约仍有效时才返回 true 并使 generation 失效。返回 false 表示提交期间发生
     * 取消、离页或替换，调用方必须按 pending-commit 记录执行补偿清理。
     */
    fun completeTokenCommit(attemptId: Long): Boolean =
        synchronized(lock) {
            val authenticated = _state.value as? OpenAIDeviceLoginState.Authenticated
                ?: return@synchronized false
            if (
                closed ||
                generation != attemptId ||
                authenticated.attemptId != attemptId ||
                claimedAttemptId != attemptId
            ) {
                return@synchronized false
            }
            claimedAttemptId = null
            generation = nextGeneration(generation)
            true
        }

    /**
     * 路由级 ViewModel 销毁时的最终清理。close 后协调器不可重用，所有敏感内存引用
     * 会被清除，任何迟到回调都因 generation 不匹配而失效。
     */
    override fun close() {
        val job = synchronized(lock) {
            if (closed) return
            closed = true
            generation = nextGeneration(generation)
            pendingTokens = null
            claimedAttemptId = null
            val activeJob = currentJob
            currentJob = null
            _state.value = OpenAIDeviceLoginState.Idle
            activeJob
        }
        job?.cancel()
    }

    private suspend fun runAttempt(attemptId: Long) {
        var currentStage = OpenAIDeviceLoginStage.REQUEST_CODE
        try {
            val authorizationResult = requestAuthorizationWithRetry(attemptId)
                ?: return
            val authorization = when (authorizationResult) {
                is OpenAIDeviceAuthorizationResult.Ready -> authorizationResult.authorization
                OpenAIDeviceAuthorizationResult.Unsupported -> {
                    transition(attemptId, OpenAIDeviceLoginState.Unsupported)
                    return
                }
                is OpenAIDeviceAuthorizationResult.Failure -> {
                    transitionAuthorizationFailure(attemptId, authorizationResult.error)
                    return
                }
            }

            if (
                !transition(
                    attemptId,
                    OpenAIDeviceLoginState.WaitingForUser(
                        attemptId = attemptId,
                        userCode = authorization.userCode,
                        verificationUrl = authorization.verificationUrl,
                    ),
                )
            ) {
                return
            }

            currentStage = OpenAIDeviceLoginStage.POLL
            when (val pollOutcome = pollUntilTerminal(attemptId, authorization)) {
                is PollOutcome.Authorized -> {
                    if (
                        !transition(
                            attemptId,
                            OpenAIDeviceLoginState.ExchangingToken(attemptId),
                        )
                    ) {
                        return
                    }
                    currentStage = OpenAIDeviceLoginStage.EXCHANGE_TOKEN
                    exchangeToken(attemptId, pollOutcome.code)
                }
                PollOutcome.Expired -> {
                    transition(attemptId, OpenAIDeviceLoginState.Expired)
                }
                PollOutcome.NetworkFailure -> {
                    transition(
                        attemptId,
                        OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.POLL),
                    )
                }
                is PollOutcome.ProtocolFailure -> {
                    transition(
                        attemptId,
                        OpenAIDeviceLoginState.AuthFailed(
                            OpenAIDeviceLoginStage.POLL,
                            pollOutcome.error,
                        ),
                    )
                }
                PollOutcome.Stale -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            transition(
                attemptId,
                OpenAIDeviceLoginState.AuthFailed(
                    currentStage,
                    OpenAIDeviceAuthError.Internal,
                ),
            )
        } finally {
            synchronized(lock) {
                if (generation == attemptId) {
                    currentJob = null
                }
            }
        }
    }

    private suspend fun requestAuthorizationWithRetry(
        attemptId: Long,
    ): OpenAIDeviceAuthorizationResult? {
        var attemptNumber = 1
        while (isCurrent(attemptId)) {
            val result = protocol.requestDeviceAuthorization()
            if (!isCurrent(attemptId)) return null
            val networkFailure =
                (result as? OpenAIDeviceAuthorizationResult.Failure)?.error ==
                    OpenAIDeviceAuthError.Network
            if (!networkFailure || attemptNumber >= policy.maxRequestAttempts) {
                return result
            }
            delay(retryDelayMillis(attemptNumber))
            attemptNumber += 1
        }
        return null
    }

    private suspend fun pollUntilTerminal(
        attemptId: Long,
        authorization: OpenAIDeviceAuthorization,
    ): PollOutcome {
        val intervalMillis = secondsToMillisSaturated(authorization.intervalSeconds)
        return withTimeoutOrNull(policy.maxPollDurationMillis) {
            var consecutiveNetworkFailures = 0
            while (isCurrent(attemptId)) {
                when (val result = protocol.pollOnce(authorization)) {
                    OpenAIDevicePollResult.Pending -> {
                        consecutiveNetworkFailures = 0
                        delay(intervalMillis)
                    }
                    is OpenAIDevicePollResult.Authorized -> {
                        return@withTimeoutOrNull PollOutcome.Authorized(result.code)
                    }
                    is OpenAIDevicePollResult.Failure -> {
                        if (result.error != OpenAIDeviceAuthError.Network) {
                            return@withTimeoutOrNull PollOutcome.ProtocolFailure(result.error)
                        }
                        consecutiveNetworkFailures += 1
                        if (
                            consecutiveNetworkFailures >=
                            policy.maxConsecutivePollNetworkFailures
                        ) {
                            return@withTimeoutOrNull PollOutcome.NetworkFailure
                        }
                        delay(
                            max(
                                intervalMillis,
                                retryDelayMillis(consecutiveNetworkFailures),
                            ),
                        )
                    }
                }
            }
            PollOutcome.Stale
        } ?: PollOutcome.Expired
    }

    private suspend fun exchangeToken(
        attemptId: Long,
        code: OpenAIDeviceAuthorizationCode,
    ) {
        if (!isCurrent(attemptId)) return
        when (val result = protocol.exchangeToken(code)) {
            is OpenAIDeviceTokenResult.Success -> {
                synchronized(lock) {
                    if (!closed && generation == attemptId) {
                        pendingTokens = attemptId to result.tokens
                        claimedAttemptId = null
                        _state.value = OpenAIDeviceLoginState.Authenticated(attemptId)
                    }
                }
            }
            is OpenAIDeviceTokenResult.Failure -> {
                val state = if (result.error == OpenAIDeviceAuthError.Network) {
                    OpenAIDeviceLoginState.NetworkError(
                        OpenAIDeviceLoginStage.EXCHANGE_TOKEN,
                    )
                } else {
                    OpenAIDeviceLoginState.AuthFailed(
                        OpenAIDeviceLoginStage.EXCHANGE_TOKEN,
                        result.error,
                    )
                }
                transition(attemptId, state)
            }
        }
    }

    private fun transitionAuthorizationFailure(
        attemptId: Long,
        error: OpenAIDeviceAuthError,
    ) {
        val state = if (error == OpenAIDeviceAuthError.Network) {
            OpenAIDeviceLoginState.NetworkError(OpenAIDeviceLoginStage.REQUEST_CODE)
        } else {
            OpenAIDeviceLoginState.AuthFailed(
                OpenAIDeviceLoginStage.REQUEST_CODE,
                error,
            )
        }
        transition(attemptId, state)
    }

    private fun transition(
        attemptId: Long,
        newState: OpenAIDeviceLoginState,
    ): Boolean =
        synchronized(lock) {
            if (closed || generation != attemptId) {
                false
            } else {
                _state.value = newState
                true
            }
        }

    private fun isCurrent(attemptId: Long): Boolean =
        synchronized(lock) {
            !closed && generation == attemptId
        }

    private fun isCommitAttemptCurrent(attemptId: Long): Boolean =
        synchronized(lock) {
            !closed &&
                generation == attemptId &&
                claimedAttemptId == attemptId &&
                (_state.value as? OpenAIDeviceLoginState.Authenticated)?.attemptId ==
                attemptId
        }

    private fun retryDelayMillis(failureNumber: Int): Long {
        var value = policy.retryBaseMillis
        repeat((failureNumber - 1).coerceAtLeast(0)) {
            value = if (value >= policy.retryMaxMillis) {
                policy.retryMaxMillis
            } else if (value > policy.retryMaxMillis / 2L) {
                policy.retryMaxMillis
            } else {
                min(value * 2L, policy.retryMaxMillis)
            }
        }
        return value
    }

    private fun secondsToMillisSaturated(seconds: Long): Long =
        if (seconds > Long.MAX_VALUE / 1_000L) {
            Long.MAX_VALUE
        } else {
            seconds * 1_000L
        }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun OpenAIDeviceLoginState.blocksNewAttempt(): Boolean =
        this is OpenAIDeviceLoginState.RequestingCode ||
            this is OpenAIDeviceLoginState.WaitingForUser ||
            this is OpenAIDeviceLoginState.ExchangingToken ||
            this is OpenAIDeviceLoginState.Authenticated

    private fun OpenAIDeviceLoginState.canBeCancelled(): Boolean =
        this is OpenAIDeviceLoginState.RequestingCode ||
            this is OpenAIDeviceLoginState.WaitingForUser ||
            this is OpenAIDeviceLoginState.ExchangingToken ||
            this is OpenAIDeviceLoginState.Authenticated

    private sealed interface PollOutcome {
        data class Authorized(
            val code: OpenAIDeviceAuthorizationCode,
        ) : PollOutcome

        data object Expired : PollOutcome
        data object NetworkFailure : PollOutcome

        data class ProtocolFailure(
            val error: OpenAIDeviceAuthError,
        ) : PollOutcome

        data object Stale : PollOutcome
    }
}
