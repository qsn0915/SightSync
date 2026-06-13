package com.sightsync.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiServiceConnectionConfigStoreTest {
    @Test
    fun saveAndLoadReturnsNormalizedProxyConfig() {
        val keyValueStore = FakeKeyValueStore()
        val store = AiServiceConnectionConfigStore(keyValueStore)

        store.save(
            AiServiceConnectionConfig(
                proxyBaseUrl = " https://proxy.example.com/ ",
                appToken = " app-token-123 ",
            ),
        )

        val loaded = store.load()
        assertEquals("https://proxy.example.com", loaded.proxyBaseUrl)
        assertEquals("app-token-123", loaded.appToken)
        assertTrue(loaded.isConfigured)
    }

    @Test
    fun partialConfigIsNotConfigured() {
        val missingToken = AiServiceConnectionConfig(
            proxyBaseUrl = "https://proxy.example.com",
            appToken = "",
        )
        val missingBaseUrl = AiServiceConnectionConfig(
            proxyBaseUrl = "",
            appToken = "app-token-123",
        )

        assertFalse(missingToken.isConfigured)
        assertFalse(missingBaseUrl.isConfigured)
    }

    @Test
    fun clearRemovesSavedConfig() {
        val store = AiServiceConnectionConfigStore(FakeKeyValueStore())
        store.save(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com",
                appToken = "app-token-123",
            ),
        )

        store.clear()

        assertEquals(AiServiceConnectionConfig.Empty, store.load())
    }

    @Test
    fun saveOnlyPersistsProxyAddressAndAppToken() {
        val keyValueStore = FakeKeyValueStore()
        val store = AiServiceConnectionConfigStore(keyValueStore)

        store.save(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com",
                appToken = "app-token-123",
            ),
        )

        assertEquals(setOf("proxy_base_url", "app_token"), keyValueStore.keys)
    }
}

private class FakeKeyValueStore : AiServiceConnectionConfigStore.KeyValueStore {
    private val values = mutableMapOf<String, String>()
    val keys: Set<String>
        get() = values.keys

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
