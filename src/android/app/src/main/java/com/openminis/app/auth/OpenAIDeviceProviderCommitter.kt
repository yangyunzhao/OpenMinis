package com.openminis.app.auth

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 不含任何凭据的崩溃恢复阶段。 */
enum class OpenAIDevicePendingCommitStage {
    PREPARED,
    PROVIDER_SAVED,
    COMMIT_CONFIRMED,
}

/** 启动恢复看到的 Provider 归属，避免损坏 marker 误删用户原有的其他 Provider。 */
internal enum class OpenAIDeviceRecoveryProviderKind {
    ABSENT,
    TARGET_OPENAI_OAUTH,
    UNRELATED,
}

internal enum class OpenAIDeviceRecoveryAction {
    FINALIZE,
    ROLLBACK,
    CLEAR_MARKER_ONLY,
}

/**
 * 启动恢复的纯决策表。
 *
 * 只有“已最终确认 + Provider 类型正确 + 两份凭据一致”才能保留结果。其余属于本次
 * 设备登录的半提交状态并回滚；marker 若误指向用户既有的其他 Provider，则只能清除
 * marker，绝不能删除该 Provider 或它的凭据。
 */
internal fun decideOpenAIDeviceRecoveryAction(
    stage: OpenAIDevicePendingCommitStage?,
    providerKind: OpenAIDeviceRecoveryProviderKind,
    credentialsCompleteAndMatching: Boolean,
): OpenAIDeviceRecoveryAction = when {
    providerKind == OpenAIDeviceRecoveryProviderKind.UNRELATED ->
        OpenAIDeviceRecoveryAction.CLEAR_MARKER_ONLY

    stage == OpenAIDevicePendingCommitStage.COMMIT_CONFIRMED &&
        providerKind == OpenAIDeviceRecoveryProviderKind.TARGET_OPENAI_OAUTH &&
        credentialsCompleteAndMatching ->
        OpenAIDeviceRecoveryAction.FINALIZE

    else -> OpenAIDeviceRecoveryAction.ROLLBACK
}

/**
 * Phase 4 提交器依赖的窄存储端口。
 *
 * 生产适配器必须让前五个写操作在失败时抛出异常；不能复用会吞异常或使用 apply() 的
 * 普通 Provider 保存 API。Marker 只能保存 instance ID 与 stage。
 */
interface OpenAIDeviceProviderCommitStore {
    /**
     * 把 Provider、凭据、marker 与最终 generation 握手纳入同一进程内事务边界。
     *
     * 生产实现还必须让普通 Provider 增删改使用同一把互斥锁，避免删除操作插入
     * “Provider 已保存、凭据尚未保存”的窗口并留下孤立凭据。
     */
    suspend fun <T> withProviderTransaction(
        instanceId: String,
        block: suspend () -> T,
    ): T

    suspend fun writePendingMarker(
        instanceId: String,
        stage: OpenAIDevicePendingCommitStage,
    )

    suspend fun saveProviderStrict(instance: ProviderInstance)

    suspend fun saveCredentialsStrict(
        instanceId: String,
        tokens: OpenAIDeviceTokens,
    )

    /** 最终确认前重新验证事务目标仍是刚写入的完整 Provider 快照。 */
    suspend fun verifyProviderStrict(instance: ProviderInstance)

    suspend fun clearPendingMarker(instanceId: String)

    suspend fun rollbackStrict(instanceId: String)

    suspend fun applyOpenAIOAuthModels(instance: ProviderInstance)
}

enum class OpenAIDeviceProviderCommitFailureStage {
    PREPARE,
    PROVIDER,
    CREDENTIALS,
    FINAL_CONFIRMATION,
}

sealed interface OpenAIDeviceProviderCommitResult {
    data object Saved : OpenAIDeviceProviderCommitResult
    data object SavedWithModelLoadFailure : OpenAIDeviceProviderCommitResult
    data object StaleAttempt : OpenAIDeviceProviderCommitResult

    data class Failed(
        val stage: OpenAIDeviceProviderCommitFailureStage,
    ) : OpenAIDeviceProviderCommitResult
}

/**
 * 把设备登录得到的内存 Token 提交为可管理的官方 OpenAI OAuth Provider。
 *
 * UI 不调用本函数，也不接触 [lease]。路由级 ViewModel 在 viewModelScope 内持有租约，
 * 并提供 [completeTokenCommit] 作为协调器的最终 generation 握手。
 */
suspend fun commitOpenAIDeviceProvider(
    instance: ProviderInstance,
    lease: OpenAIDeviceTokenLease,
    store: OpenAIDeviceProviderCommitStore,
    completeTokenCommit: (Long) -> Boolean,
): OpenAIDeviceProviderCommitResult {
    require(instance.id.isNotBlank())
    require(instance.providerType == ProviderType.openAI)
    require(instance.credentialType == ProviderCredential.oauth)
    require(instance.customBaseURL == null)

    if (!lease.isValid()) return OpenAIDeviceProviderCommitResult.StaleAttempt

    val transactionResult = store.withProviderTransaction(instance.id) {
        commitOpenAIDeviceProviderTransaction(
            instance = instance,
            lease = lease,
            store = store,
            completeTokenCommit = completeTokenCommit,
        )
    }
    transactionResult?.let { return it }

    return try {
        store.applyOpenAIOAuthModels(instance)
        OpenAIDeviceProviderCommitResult.Saved
    } catch (cancelled: CancellationException) {
        // Provider 已提交，不再回滚；但仍尊重上层生命周期取消，不能伪装为模型错误。
        throw cancelled
    } catch (_: Exception) {
        // Provider 与凭据已经一致性提交。模型列表失败不得回滚有效登录。
        OpenAIDeviceProviderCommitResult.SavedWithModelLoadFailure
    }
}

/**
 * 返回 null 表示跨存储提交已经最终确认；失败或过期则返回可直接交给 UI 的结果。
 * 模型加载不属于登录原子提交，调用者会在释放事务互斥后单独执行它。
 */
private suspend fun commitOpenAIDeviceProviderTransaction(
    instance: ProviderInstance,
    lease: OpenAIDeviceTokenLease,
    store: OpenAIDeviceProviderCommitStore,
    completeTokenCommit: (Long) -> Boolean,
): OpenAIDeviceProviderCommitResult? {
    if (!lease.isValid()) return OpenAIDeviceProviderCommitResult.StaleAttempt

    var failureStage = OpenAIDeviceProviderCommitFailureStage.PREPARE
    var markerWritten = false
    var commitConfirmed = false
    try {
        store.writePendingMarker(
            instance.id,
            OpenAIDevicePendingCommitStage.PREPARED,
        )
        markerWritten = true
        if (!lease.isValid()) {
            rollbackAfterFailure(store, instance.id)
            return OpenAIDeviceProviderCommitResult.StaleAttempt
        }

        failureStage = OpenAIDeviceProviderCommitFailureStage.PROVIDER
        store.saveProviderStrict(instance)
        store.writePendingMarker(
            instance.id,
            OpenAIDevicePendingCommitStage.PROVIDER_SAVED,
        )
        if (!lease.isValid()) {
            rollbackAfterFailure(store, instance.id)
            return OpenAIDeviceProviderCommitResult.StaleAttempt
        }

        failureStage = OpenAIDeviceProviderCommitFailureStage.CREDENTIALS
        val tokens = lease.tokensIfValid()
            ?: run {
                rollbackAfterFailure(store, instance.id)
                return OpenAIDeviceProviderCommitResult.StaleAttempt
            }
        store.saveCredentialsStrict(instance.id, tokens)

        failureStage = OpenAIDeviceProviderCommitFailureStage.FINAL_CONFIRMATION
        store.verifyProviderStrict(instance)
        if (!lease.isValid() || !completeTokenCommit(lease.attemptId)) {
            rollbackAfterFailure(store, instance.id)
            return OpenAIDeviceProviderCommitResult.StaleAttempt
        }
        store.writePendingMarker(
            instance.id,
            OpenAIDevicePendingCommitStage.COMMIT_CONFIRMED,
        )
        commitConfirmed = true

        // COMMIT_CONFIRMED 已足以让启动恢复验证两侧后完成提交。删除 marker 失败时不能
        // 回滚一个已经由协调器确认的完整 Provider；下次启动会幂等清理 marker。
        runCatching { store.clearPendingMarker(instance.id) }
    } catch (cancelled: CancellationException) {
        if (!commitConfirmed && markerWritten) {
            rollbackAfterFailure(store, instance.id)
        }
        throw cancelled
    } catch (_: Exception) {
        if (!commitConfirmed && markerWritten) {
            rollbackAfterFailure(store, instance.id)
        }
        return OpenAIDeviceProviderCommitResult.Failed(failureStage)
    }
    return null
}

private suspend fun rollbackAfterFailure(
    store: OpenAIDeviceProviderCommitStore,
    instanceId: String,
) {
    withContext(NonCancellable) {
        // 只有两侧补偿都成功后才能删除 marker。若回滚本身失败，保留 marker 让下次
        // 启动继续恢复；否则一次短暂的磁盘/数据库错误会永久留下半提交 Provider。
        val rolledBack = runCatching {
            store.rollbackStrict(instanceId)
        }.isSuccess
        if (rolledBack) {
            runCatching { store.clearPendingMarker(instanceId) }
        }
    }
}
