package com.sightsync.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sightsync.assistant.ai.AiServiceConnectionConfig
import com.sightsync.assistant.ai.AiServiceConnectionConfigStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiServiceConnectionUiState(
    val proxyBaseUrl: String = "",
    val appToken: String = "",
    val connectionStatus: AiServiceConnectionStatus = AiServiceConnectionStatus.NotConfigured,
) {
    val isTestingConnection: Boolean
        get() = connectionStatus is AiServiceConnectionStatus.Testing
}

sealed interface AiServiceConnectionStatus {
    val message: String

    data object NotConfigured : AiServiceConnectionStatus {
        override val message = "未配置代理地址和 App token"
    }

    data object UnsavedChanges : AiServiceConnectionStatus {
        override val message = "有未保存的连接配置"
    }

    data object MissingRequiredFields : AiServiceConnectionStatus {
        override val message = "请填写代理地址和 App token"
    }

    data object Saved : AiServiceConnectionStatus {
        override val message = "连接配置已保存，尚未测试"
    }

    data object Testing : AiServiceConnectionStatus {
        override val message = "正在测试连接"
    }

    data object TestSucceeded : AiServiceConnectionStatus {
        override val message = "连接测试成功"
    }

    data class TestFailed(
        val reason: String,
    ) : AiServiceConnectionStatus {
        override val message = "连接测试失败：$reason"
    }
}

sealed interface AiServiceConnectionTestResult {
    data object Success : AiServiceConnectionTestResult
    data class Failed(val reason: String) : AiServiceConnectionTestResult
}

fun interface AiServiceConnectionTester {
    suspend fun test(config: AiServiceConnectionConfig): AiServiceConnectionTestResult
}

class AiServiceConnectionViewModel(
    private val configStore: AiServiceConnectionConfigStore,
    private val connectionTester: AiServiceConnectionTester = AiServiceConnectionHealthTester(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private var savedConfig = configStore.load()
    private val _state = MutableStateFlow(savedConfig.toUiState())
    val state: StateFlow<AiServiceConnectionUiState> = _state.asStateFlow()

    fun onProxyBaseUrlChanged(value: String) {
        _state.update { current ->
            current.copy(
                proxyBaseUrl = value,
                connectionStatus = AiServiceConnectionStatus.UnsavedChanges,
            )
        }
    }

    fun onAppTokenChanged(value: String) {
        _state.update { current ->
            current.copy(
                appToken = value,
                connectionStatus = AiServiceConnectionStatus.UnsavedChanges,
            )
        }
    }

    fun save() {
        val config = currentConfig()
        if (!config.isConfigured) {
            _state.update { it.copy(connectionStatus = AiServiceConnectionStatus.MissingRequiredFields) }
            return
        }

        configStore.save(config)
        savedConfig = configStore.load()
        _state.value = savedConfig.toUiState()
    }

    fun testConnection() {
        val config = currentConfig()
        if (!config.isConfigured) {
            _state.update { it.copy(connectionStatus = AiServiceConnectionStatus.MissingRequiredFields) }
            return
        }

        configStore.save(config)
        savedConfig = configStore.load()
        _state.value = savedConfig.toUiState(AiServiceConnectionStatus.Testing)
        viewModelScope.launch(dispatcher) {
            val result = connectionTester.test(savedConfig)
            _state.update { current ->
                current.copy(
                    connectionStatus = when (result) {
                        AiServiceConnectionTestResult.Success -> AiServiceConnectionStatus.TestSucceeded
                        is AiServiceConnectionTestResult.Failed ->
                            AiServiceConnectionStatus.TestFailed(result.reason)
                    },
                )
            }
        }
    }

    private fun currentConfig(): AiServiceConnectionConfig =
        AiServiceConnectionConfig(
            proxyBaseUrl = _state.value.proxyBaseUrl,
            appToken = _state.value.appToken,
        ).normalized()

    private fun AiServiceConnectionConfig.toUiState(
        status: AiServiceConnectionStatus = if (isConfigured) {
            AiServiceConnectionStatus.Saved
        } else {
            AiServiceConnectionStatus.NotConfigured
        },
    ): AiServiceConnectionUiState =
        AiServiceConnectionUiState(
            proxyBaseUrl = proxyBaseUrl,
            appToken = appToken,
            connectionStatus = status,
        )

    companion object {
        fun factory(
            configStore: AiServiceConnectionConfigStore,
            connectionTester: AiServiceConnectionTester = AiServiceConnectionHealthTester(),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AiServiceConnectionViewModel(
                        configStore = configStore,
                        connectionTester = connectionTester,
                    ) as T
                }
            }
    }
}
