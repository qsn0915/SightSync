package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.core.ActionResult
import com.sightsync.assistant.core.ScreenContext

interface AssistantClient {
    suspend fun assist(
        sessionId: String,
        locale: String,
        utterance: String,
        screenContext: ScreenContext,
    ): AssistResponse
}

interface ActionRunner {
    fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult>
}
