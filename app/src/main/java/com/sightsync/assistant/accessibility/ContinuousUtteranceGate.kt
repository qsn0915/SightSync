package com.sightsync.assistant.accessibility

class ContinuousUtteranceGate {
    fun normalize(utterance: String): String {
        val cleaned = utterance.trim().trimEnd('。', '.', '！', '!', '？', '?')
        val matching = cleaned.normalizedForMatching()
        return readScreenCanonicalAliases[matching] ?: cleaned
    }

    fun decide(utterance: String): ContinuousUtteranceDecision {
        val canonical = normalize(utterance)
        val matching = canonical.normalizedForMatching()
        val category = when {
            matching in screenReadingCommands -> ContinuousCommandCategory.ScreenReading
            matching in navigationCommands -> ContinuousCommandCategory.Navigation
            clickCommand.matches(matching) -> ContinuousCommandCategory.Click
            inputCommand.matches(matching) -> ContinuousCommandCategory.Input
            openAppCommand.matches(matching) -> ContinuousCommandCategory.OpenApp
            explicitActionCommand.matches(matching) -> ContinuousCommandCategory.Action
            matching in controlCommands -> ContinuousCommandCategory.Control
            else -> return ContinuousUtteranceDecision.Ignored(reason = "not_explicit_command")
        }
        return ContinuousUtteranceDecision.Accepted(
            canonicalUtterance = canonical,
            category = category,
        )
    }

    private fun String.normalizedForMatching(): String =
        lowercase().replace(whitespace, "")

    private companion object {
        val whitespace = Regex("""\s+""")
        val clickCommand = Regex("""^(?:请|麻烦你|帮我|你帮我)?(?:点击|点一下|点开|按下|选择).+""")
        val inputCommand = Regex("""^(?:请|麻烦你|帮我|你帮我)?(?:输入|填写|写入|键入)(?:[:：])?.*""")
        val openAppCommand = Regex("""^(?:请|麻烦你|帮我|你帮我)?(?:打开|启动|开启|进入).+""")
        val explicitActionCommand = Regex("""^(?:请|麻烦你|帮我|你帮我)?(?:发送|提交|删除|保存).+""")

        val readScreenCanonicalAliases = mapOf(
            "只说明可操作性" to "只说明可操作项",
            "详细读拼音" to "详细读屏",
            "详细读屏幕" to "详细读屏",
            "简短读屏幕" to "简短读屏",
            "剪短读屏" to "简短读屏",
            "剪断读屏" to "简短读屏",
        )
        val screenReadingCommands = setOf(
            "这里有什么",
            "当前页面有什么",
            "读一下当前页面",
            "读当前页面",
            "朗读当前页面",
            "看一下当前屏幕",
            "查看当前屏幕",
            "当前屏幕有什么",
            "可操作项",
            "有哪些可操作项",
            "当前页面有哪些可操作项",
            "当前页面有什么可操作项",
            "这里有哪些可操作项",
            "当前屏幕有哪些可操作项",
            "当前页面能点什么",
            "这里能点什么",
            "当前页面可以点什么",
            "有哪些按钮",
            "只说明可操作项",
            "只说可操作项",
            "只读可操作项",
            "详细读屏",
            "详细读一下当前页面",
            "详细朗读当前页面",
            "详细说明当前页面",
            "读详细一点",
            "详细看一下当前屏幕",
            "简短读屏",
            "简单读屏",
            "快速读屏",
            "简短读一下当前页面",
            "简单读一下当前页面",
            "简单说一下当前页面",
            "再读一次",
        )
        val navigationCommands = setOf(
            "返回",
            "后退",
            "回退",
            "返回上一页",
            "主页",
            "回到主页",
            "回主页",
            "返回主页",
            "回到桌面",
            "回桌面",
            "返回桌面",
            "向下滚动",
            "往下滚动",
            "下滑",
            "往下滑",
            "向下滑动",
            "下一页",
            "向上滚动",
            "往上滚动",
            "上滑",
            "往上滑",
            "向上滑动",
            "上一页",
        )
        val controlCommands = setOf(
            "停止",
            "停止聆听",
            "停止监听",
            "停止助手",
            "暂停助手",
            "取消",
            "退出",
            "确认",
            "确认执行",
            "继续执行",
            "执行吧",
        )
    }
}

enum class ContinuousCommandCategory {
    ScreenReading,
    Navigation,
    Click,
    Input,
    OpenApp,
    Action,
    Control,
}

sealed interface ContinuousUtteranceDecision {
    data class Accepted(
        val canonicalUtterance: String,
        val category: ContinuousCommandCategory,
    ) : ContinuousUtteranceDecision

    data class Ignored(val reason: String) : ContinuousUtteranceDecision
}
