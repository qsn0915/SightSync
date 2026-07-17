package com.sightsync.assistant.apps

sealed interface WeChatDraftCommandResult {
    data class Resolved(
        val contact: String,
        val message: String,
    ) : WeChatDraftCommandResult

    data class Invalid(val spoken: String) : WeChatDraftCommandResult

    data object NotCommand : WeChatDraftCommandResult
}

class WeChatDraftCommandResolver {
    fun resolve(utterance: String): WeChatDraftCommandResult {
        val command = utterance.trim()
        val match = commandPatterns.firstNotNullOfOrNull { pattern -> pattern.matchEntire(command) }
        if (match == null) {
            return if (looksLikeDraftCommand(command)) {
                WeChatDraftCommandResult.Invalid(
                    "微信草稿命令不完整，请说在微信给谁写草稿，并在冒号后说出内容。",
                )
            } else {
                WeChatDraftCommandResult.NotCommand
            }
        }

        val contact = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        if (contact.isBlank() || message.isBlank()) {
            return WeChatDraftCommandResult.Invalid("联系人和草稿内容都不能为空，已停止操作。")
        }
        if (contact.codePointLength() > MAX_CONTACT_CODE_POINTS) {
            return WeChatDraftCommandResult.Invalid("联系人名称过长，已停止操作。")
        }
        if (message.codePointLength() > MAX_MESSAGE_CODE_POINTS) {
            return WeChatDraftCommandResult.Invalid("草稿内容过长，已停止操作。")
        }
        return WeChatDraftCommandResult.Resolved(contact, message)
    }

    private fun looksLikeDraftCommand(command: String): Boolean =
        (
            command.startsWith("在微信") &&
                command.contains("给") &&
                (command.contains("写草稿") || command.contains("准备消息") || command.contains("消息草稿"))
            ) ||
            (
                command.startsWith("给") &&
                    command.contains("写微信") &&
                    command.contains("草稿")
                )

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private companion object {
        const val MAX_CONTACT_CODE_POINTS = 40
        const val MAX_MESSAGE_CODE_POINTS = 500

        val commandPatterns = listOf(
            Regex(
                """^在微信(?:里)?给\s*(.*?)\s*(?:写(?:一条)?(?:消息)?草稿|准备(?:一条)?消息)\s*[：:，,]\s*(.*?)\s*$""",
            ),
            Regex("""^给\s*(.*?)\s*写微信(?:消息)?草稿\s*[：:，,]\s*(.*?)\s*$"""),
        )
    }
}
