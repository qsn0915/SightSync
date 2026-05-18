package com.sightsync.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AiProtocolValidatorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun acceptsWhitelistedActions() {
        val response = AssistResponse(
            spoken = "我会返回上一页。",
            requiresConfirmation = false,
            actions = listOf(AssistantAction(type = "GLOBAL_BACK")),
        )

        assertTrue(AiProtocolValidator.validate(response).isValid)
    }

    @Test
    fun rejectsUnknownActionTypes() {
        val response = AssistResponse(
            spoken = "我会执行脚本。",
            requiresConfirmation = false,
            actions = listOf(AssistantAction(type = "RUN_SCRIPT")),
        )

        val result = AiProtocolValidator.validate(response)

        assertEquals(false, result.isValid)
        assertEquals("不支持的动作类型：RUN_SCRIPT", result.reason)
    }

    @Test
    fun requiresNodeIdForClickActions() {
        val response = AssistResponse(
            spoken = "我会点击。",
            requiresConfirmation = false,
            actions = listOf(AssistantAction(type = "CLICK_NODE")),
        )

        val result = AiProtocolValidator.validate(response)

        assertEquals(false, result.isValid)
        assertEquals("CLICK_NODE 缺少 nodeId", result.reason)
    }

    @Test
    fun parsesResponseJsonAndAcceptsWhitelistedSetText() {
        val response = json.decodeFromString<AssistResponse>(
            """
            {
              "spoken": "我会输入测试内容。",
              "requiresConfirmation": false,
              "actions": [
                {
                  "type": "SET_TEXT",
                  "nodeId": "node_2",
                  "text": "测试内容"
                }
              ],
              "extraProviderField": "ignored"
            }
            """.trimIndent(),
        )

        assertEquals("我会输入测试内容。", response.spoken)
        assertEquals("SET_TEXT", response.actions.single().type)
        assertEquals("测试内容", response.actions.single().text)
        assertTrue(AiProtocolValidator.validate(response).isValid)
    }

    @Test
    fun parsedIllegalActionIsRejectedBeforeExecution() {
        val response = json.decodeFromString<AssistResponse>(
            """
            {
              "spoken": "我会运行脚本。",
              "requiresConfirmation": false,
              "actions": [
                { "type": "RUN_SCRIPT" }
              ]
            }
            """.trimIndent(),
        )

        val result = AiProtocolValidator.validate(response)

        assertFalse(result.isValid)
        assertEquals("不支持的动作类型：RUN_SCRIPT", result.reason)
    }

    @Test
    fun requiresTextForSetTextActions() {
        val response = AssistResponse(
            spoken = "我会输入。",
            actions = listOf(AssistantAction(type = "SET_TEXT", nodeId = "node_1")),
        )

        val result = AiProtocolValidator.validate(response)

        assertFalse(result.isValid)
        assertEquals("SET_TEXT 缺少 text", result.reason)
    }

    @Test
    fun requiresAppPackageForOpenAppActions() {
        val response = AssistResponse(
            spoken = "我会打开应用。",
            actions = listOf(AssistantAction(type = "OPEN_APP")),
        )

        val result = AiProtocolValidator.validate(response)

        assertFalse(result.isValid)
        assertEquals("OPEN_APP 缺少 appPackage", result.reason)
    }

    @Test
    fun rejectsBlankSpokenText() {
        val response = AssistResponse(
            spoken = " ",
            actions = emptyList(),
        )

        val result = AiProtocolValidator.validate(response)

        assertFalse(result.isValid)
        assertEquals("spoken 不能为空", result.reason)
    }
}
