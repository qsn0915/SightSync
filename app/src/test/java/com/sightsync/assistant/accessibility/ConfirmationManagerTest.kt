package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.core.ScreenContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationManagerTest {
    @Test
    fun storesPendingResponseWithSourceScreenUntilConfirmed() {
        val manager = ConfirmationManager()
        val response = AssistResponse(
            spoken = "我会发送这条消息。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_send")),
        )
        val sourceScreen = screen("com.example.chat")

        manager.store(response, sourceScreen)

        assertTrue(manager.hasPending)
        assertNull(manager.consumeIfConfirmed("取消"))

        val pending = manager.consumeIfConfirmed("确认执行")

        assertEquals(response, pending?.response)
        assertEquals(sourceScreen, pending?.sourceScreen)
        assertFalse(manager.hasPending)
    }

    @Test
    fun clearDropsPendingConfirmation() {
        val manager = ConfirmationManager()
        manager.store(
            response = AssistResponse(
                spoken = "我会删除。",
                actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_delete")),
            ),
            sourceScreen = screen("com.example.files"),
        )

        manager.clear()

        assertFalse(manager.hasPending)
        assertNull(manager.consumeIfConfirmed("确认执行"))
    }

    private fun screen(packageName: String): ScreenContext =
        ScreenContext(
            packageName = packageName,
            activityName = null,
            nodes = emptyList(),
            screenshotBase64 = null,
        )
}
