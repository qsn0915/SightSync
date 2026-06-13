package com.sightsync.assistant

import com.sightsync.assistant.ai.AiServiceConnectionConfig
import com.sightsync.assistant.ai.AiServiceConnectionConfigStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiServiceConnectionViewModelTest {
    @Test
    fun initLoadsSavedConnectionConfig() {
        val store = AiServiceConnectionConfigStore(FakeKeyValueStore())
        store.save(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com/",
                appToken = "stored-token",
            ),
        )

        val viewModel = AiServiceConnectionViewModel(store)

        assertEquals("https://proxy.example.com", viewModel.state.value.proxyBaseUrl)
        assertEquals("stored-token", viewModel.state.value.appToken)
        assertTrue(viewModel.state.value.connectionStatus is AiServiceConnectionStatus.Saved)
    }

    @Test
    fun savePersistsEditedConfigAndShowsSavedStatus() {
        val store = AiServiceConnectionConfigStore(FakeKeyValueStore())
        val viewModel = AiServiceConnectionViewModel(store)

        viewModel.onProxyBaseUrlChanged(" https://proxy.example.com/ ")
        viewModel.onAppTokenChanged(" app-token ")
        viewModel.save()

        assertEquals(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com",
                appToken = "app-token",
            ),
            store.load(),
        )
        assertTrue(viewModel.state.value.connectionStatus is AiServiceConnectionStatus.Saved)
    }

    @Test
    fun testConnectionSavesConfigAndReportsSuccess() = runTest {
        val store = AiServiceConnectionConfigStore(FakeKeyValueStore())
        val tester = FakeConnectionTester(AiServiceConnectionTestResult.Success)
        val viewModel = AiServiceConnectionViewModel(
            configStore = store,
            connectionTester = tester,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.onProxyBaseUrlChanged("https://proxy.example.com")
        viewModel.onAppTokenChanged("app-token")
        viewModel.testConnection()
        advanceUntilIdle()

        assertEquals(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com",
                appToken = "app-token",
            ),
            tester.testedConfig,
        )
        assertEquals(store.load(), tester.testedConfig)
        assertTrue(viewModel.state.value.connectionStatus is AiServiceConnectionStatus.TestSucceeded)
    }

    @Test
    fun missingConfigDoesNotRunConnectionTest() = runTest {
        val tester = FakeConnectionTester(AiServiceConnectionTestResult.Success)
        val viewModel = AiServiceConnectionViewModel(
            configStore = AiServiceConnectionConfigStore(FakeKeyValueStore()),
            connectionTester = tester,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.testConnection()
        advanceUntilIdle()

        assertEquals(null, tester.testedConfig)
        assertTrue(viewModel.state.value.connectionStatus is AiServiceConnectionStatus.MissingRequiredFields)
    }
}

private class FakeConnectionTester(
    private val result: AiServiceConnectionTestResult,
) : AiServiceConnectionTester {
    var testedConfig: AiServiceConnectionConfig? = null

    override suspend fun test(config: AiServiceConnectionConfig): AiServiceConnectionTestResult {
        testedConfig = config
        return result
    }
}

private class FakeKeyValueStore : AiServiceConnectionConfigStore.KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
