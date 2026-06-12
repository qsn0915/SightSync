package com.sightsync.assistant.speech

internal class PendingUtteranceRegistry<T> {
    private val lock = Any()
    private val pending = linkedMapOf<String, T>()

    fun put(utteranceId: String, value: T) {
        synchronized(lock) {
            pending[utteranceId] = value
        }
    }

    fun remove(utteranceId: String?): T? {
        if (utteranceId == null) return null
        return synchronized(lock) {
            pending.remove(utteranceId)
        }
    }

    fun drain(): List<T> =
        synchronized(lock) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
}
