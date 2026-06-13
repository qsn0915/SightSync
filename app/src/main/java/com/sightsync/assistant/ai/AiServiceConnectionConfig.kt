package com.sightsync.assistant.ai

import android.content.Context
import android.content.SharedPreferences

data class AiServiceConnectionConfig(
    val proxyBaseUrl: String,
    val appToken: String,
) {
    val isConfigured: Boolean
        get() = proxyBaseUrl.trim().isNotEmpty() && appToken.trim().isNotEmpty()

    fun normalized(): AiServiceConnectionConfig =
        copy(
            proxyBaseUrl = proxyBaseUrl.trim().trimEnd('/'),
            appToken = appToken.trim(),
        )

    companion object {
        val Empty = AiServiceConnectionConfig(
            proxyBaseUrl = "",
            appToken = "",
        )
    }
}

class AiServiceConnectionConfigStore(
    private val keyValueStore: KeyValueStore,
) {
    fun load(): AiServiceConnectionConfig =
        AiServiceConnectionConfig(
            proxyBaseUrl = keyValueStore.getString(KEY_PROXY_BASE_URL).orEmpty(),
            appToken = keyValueStore.getString(KEY_APP_TOKEN).orEmpty(),
        ).normalized()

    fun save(config: AiServiceConnectionConfig) {
        val normalized = config.normalized()
        keyValueStore.putString(KEY_PROXY_BASE_URL, normalized.proxyBaseUrl)
        keyValueStore.putString(KEY_APP_TOKEN, normalized.appToken)
    }

    fun clear() {
        keyValueStore.remove(KEY_PROXY_BASE_URL)
        keyValueStore.remove(KEY_APP_TOKEN)
    }

    interface KeyValueStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    private class SharedPreferencesKeyValueStore(
        private val sharedPreferences: SharedPreferences,
    ) : KeyValueStore {
        override fun getString(key: String): String? = sharedPreferences.getString(key, null)

        override fun putString(key: String, value: String) {
            sharedPreferences.edit().putString(key, value).apply()
        }

        override fun remove(key: String) {
            sharedPreferences.edit().remove(key).apply()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "sightsync_ai_service_connection"
        private const val KEY_PROXY_BASE_URL = "proxy_base_url"
        private const val KEY_APP_TOKEN = "app_token"

        fun create(context: Context): AiServiceConnectionConfigStore =
            AiServiceConnectionConfigStore(
                SharedPreferencesKeyValueStore(
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
                ),
            )
    }
}
