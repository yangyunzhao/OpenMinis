package com.openminis.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.auth.OpenAIDeviceAuthClient
import com.openminis.app.auth.OpenAIDeviceLoginCoordinator
import com.openminis.app.auth.OpenAIDeviceLoginState
import com.openminis.app.auth.OpenAIDeviceProviderCommitFailureStage
import com.openminis.app.auth.OpenAIDeviceProviderCommitResult
import com.openminis.app.auth.commitOpenAIDeviceProvider
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface OpenAIDeviceProviderSaveState {
    data object Idle : OpenAIDeviceProviderSaveState
    data object Saving : OpenAIDeviceProviderSaveState
    data object Saved : OpenAIDeviceProviderSaveState
    data object SavedWithModelLoadFailure : OpenAIDeviceProviderSaveState
    data object StaleAttempt : OpenAIDeviceProviderSaveState

    data class Failed(
        val stage: OpenAIDeviceProviderCommitFailureStage,
    ) : OpenAIDeviceProviderSaveState
}

/**
 * 新增 Provider 路由持有的 OpenAI 设备码登录状态。
 *
 * Phase 4 会从 NavBackStackEntry 取得这个 ViewModel。`viewModelScope` 能跨 Compose
 * 重组、旋转和 Activity 重建继续运行；进程被系统彻底杀死后会创建新实例并安全回到
 * Idle。这里故意不使用 SavedStateHandle，也不把用户码、设备授权 ID、PKCE 或 Token
 * 放入 Bundle、数据库或普通配置。
 */
class OpenAIDeviceLoginViewModel(
    private val providerRepository: ProviderRepository,
) : ViewModel() {
    private val coordinator = OpenAIDeviceLoginCoordinator(
        protocol = OpenAIDeviceAuthClient(),
        scope = viewModelScope,
    )
    private var pendingInstanceAttemptId: Long? = null
    private var pendingInstanceId: String? = null
    private val _saveState = MutableStateFlow<OpenAIDeviceProviderSaveState>(
        OpenAIDeviceProviderSaveState.Idle,
    )
    private var saveJob: Job? = null
    private var committedInstance: ProviderInstance? = null
    private var browserOpenedForAttemptId: Long? = null

    val state: StateFlow<OpenAIDeviceLoginState> = coordinator.state
    val saveState: StateFlow<OpenAIDeviceProviderSaveState> = _saveState.asStateFlow()

    fun start(): Boolean {
        // cancel() 后 NonCancellable 补偿可能仍在运行；必须等整个旧 Job 完成，不能仅
        // 检查 isActive，否则新事务会与旧回滚交叉。
        if (saveJob?.isCompleted == false) return false
        _saveState.value = OpenAIDeviceProviderSaveState.Idle
        val started = coordinator.start()
        if (started) {
            val request = state.value as OpenAIDeviceLoginState.RequestingCode
            pendingInstanceAttemptId = request.attemptId
            // 每次登录尝试独立 ID。旧事务补偿永远不能命中新尝试的 Provider/凭据。
            pendingInstanceId = UUID.randomUUID().toString()
        }
        return started
    }

    /** 每次 attempt 只自动打开一次 Custom Tab；旋转重组不会反复打断用户。 */
    @Synchronized
    fun claimAutomaticBrowserOpen(attemptId: Long): Boolean {
        if (browserOpenedForAttemptId == attemptId) return false
        browserOpenedForAttemptId = attemptId
        return true
    }

    fun cancel(): Boolean {
        saveJob?.cancel()
        return coordinator.cancel()
    }

    /**
     * UI 只提交标签。Token 租约在 ViewModel 内一次性领取，完整事务切到 IO，避免
     * SharedPreferences.commit、Room replaceAll 和 JSON mirror 阻塞主线程。
     */
    fun saveProvider(label: String): Boolean {
        if (saveJob?.isCompleted == false) return false
        val authenticated = state.value as? OpenAIDeviceLoginState.Authenticated
            ?: return false
        if (pendingInstanceAttemptId != authenticated.attemptId) return false
        val targetInstanceId = pendingInstanceId ?: return false
        val lease = coordinator.claimTokenLease(authenticated.attemptId)
            ?: return false
        val instance = ProviderInstance(
            id = targetInstanceId,
            label = label.trim().ifBlank { ProviderType.openAI.displayName },
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.oauth,
            customBaseURL = null,
        )
        _saveState.value = OpenAIDeviceProviderSaveState.Saving
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) {
                    commitOpenAIDeviceProvider(
                        instance = instance,
                        lease = lease,
                        store = providerRepository,
                        completeTokenCommit = coordinator::completeTokenCommit,
                    )
                }
                _saveState.value = when (result) {
                    OpenAIDeviceProviderCommitResult.Saved -> {
                        committedInstance = instance
                        OpenAIDeviceProviderSaveState.Saved
                    }
                    OpenAIDeviceProviderCommitResult.SavedWithModelLoadFailure -> {
                        committedInstance = instance
                        OpenAIDeviceProviderSaveState.SavedWithModelLoadFailure
                    }
                    OpenAIDeviceProviderCommitResult.StaleAttempt ->
                        OpenAIDeviceProviderSaveState.StaleAttempt
                    is OpenAIDeviceProviderCommitResult.Failed -> {
                        coordinator.cancel()
                        OpenAIDeviceProviderSaveState.Failed(result.stage)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                saveJob = null
            }
        }
        saveJob = job
        job.start()
        return true
    }

    fun retryModelLoad(): Boolean {
        val instance = committedInstance ?: return false
        if (_saveState.value != OpenAIDeviceProviderSaveState.SavedWithModelLoadFailure) {
            return false
        }
        if (saveJob?.isCompleted == false) return false
        _saveState.value = OpenAIDeviceProviderSaveState.Saving
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    providerRepository.applyOpenAIOAuthModels(instance)
                }
                _saveState.value = OpenAIDeviceProviderSaveState.Saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveState.value =
                    OpenAIDeviceProviderSaveState.SavedWithModelLoadFailure
            } finally {
                saveJob = null
            }
        }
        saveJob = job
        job.start()
        return true
    }

    override fun onCleared() {
        saveJob?.cancel()
        coordinator.close()
        super.onCleared()
    }

    companion object {
        fun factory(providerRepository: ProviderRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(OpenAIDeviceLoginViewModel::class.java))
                    return OpenAIDeviceLoginViewModel(providerRepository) as T
                }
            }
    }
}
