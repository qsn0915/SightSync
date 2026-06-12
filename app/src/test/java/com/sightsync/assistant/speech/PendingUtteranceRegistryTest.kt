package com.sightsync.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingUtteranceRegistryTest {
    @Test
    fun removeReturnsStoredUtteranceAndExcludesItFromDrain() {
        val registry = PendingUtteranceRegistry<String>()

        registry.put("first", "one")
        registry.put("second", "two")

        assertEquals("one", registry.remove("first"))
        assertEquals(listOf("two"), registry.drain())
        assertEquals(emptyList<String>(), registry.drain())
    }

    @Test
    fun drainReturnsAllPendingUtterancesAndClearsRegistry() {
        val registry = PendingUtteranceRegistry<String>()

        registry.put("first", "one")
        registry.put("second", "two")

        assertEquals(listOf("one", "two"), registry.drain())
        assertNull(registry.remove("first"))
        assertNull(registry.remove(null))
    }
}
