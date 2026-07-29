package com.openminis.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.auth.OpenAIDeviceAuthClient
import com.openminis.app.auth.OpenAIDeviceLoginCoordinator
import com.openminis.app.auth.OpenAIDeviceLoginState
import kotlinx.coroutines.flow.StateFlow

/**
 * 新增 Provider 路由持有的 OpenAI 设备码登录状态。
 *
 * Phase 4 会从 NavBackStackEntry 取得这个 ViewModel。`viewModelScope` 能跨 Compose
 * 重组、旋转和 Activity 重建继续运行；进程被系统彻底杀死后会创建新实例并安全回到
 * Idle。这里故意不使用 SavedStateHandle，也不把用户码、设备授权 ID、PKCE 或 Token
 * 放入 Bundle、数据库或普通配置。
 */
class OpenAIDeviceLoginViewModel : ViewModel() {
    private val coordinator = OpenAIDeviceLoginCoordinator(
        protocol = OpenAIDeviceAuthClient(),
        scope = viewModelScope,
    )

    val state: StateFlow<OpenAIDeviceLoginState> = coordinator.state

    fun start(): Boolean = coordinator.start()

    fun cancel(): Boolean = coordinator.cancel()

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }
}
