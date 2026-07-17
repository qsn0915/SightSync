package com.sightsync.assistant.apps

sealed interface BrowserSearchCommandResult {
    data class Resolved(
        val query: String,
        val browserPackage: String,
    ) : BrowserSearchCommandResult

    data class Unavailable(val spoken: String) : BrowserSearchCommandResult

    data object NotBrowserSearchCommand : BrowserSearchCommandResult
}

class BrowserSearchCommandResolver(
    private val openAppCommandResolver: OpenAppCommandResolver,
) {
    fun resolve(utterance: String): BrowserSearchCommandResult {
        val match = commandPattern.matchEntire(utterance.trim())
            ?: return BrowserSearchCommandResult.NotBrowserSearchCommand
        val query = match.groupValues[2]
            .trim()
            .trimEnd('。', '.', '！', '!', '？', '?')
            .trim()
        if (query.isBlank()) {
            return BrowserSearchCommandResult.Unavailable("请告诉我要搜索的内容。")
        }
        if (query.codePointCount(0, query.length) > MAX_QUERY_CODE_POINTS) {
            return BrowserSearchCommandResult.Unavailable("搜索内容太长，请缩短后再试。")
        }

        return when (val browser = openAppCommandResolver.resolve("打开浏览器")) {
            is OpenAppCommandResult.Resolved -> {
                val action = browser.response.actions.singleOrNull()
                val packageName = action
                    ?.takeIf { it.type == "OPEN_APP" }
                    ?.appPackage
                if (packageName.isNullOrBlank()) {
                    BrowserSearchCommandResult.Unavailable("没有找到可用的浏览器。")
                } else {
                    BrowserSearchCommandResult.Resolved(
                        query = query,
                        browserPackage = packageName,
                    )
                }
            }

            is OpenAppCommandResult.Ambiguous ->
                BrowserSearchCommandResult.Unavailable(browser.response.spoken)
            is OpenAppCommandResult.Alternatives ->
                BrowserSearchCommandResult.Unavailable(browser.response.spoken)
            is OpenAppCommandResult.NoMatch ->
                BrowserSearchCommandResult.Unavailable(browser.response.spoken)
            OpenAppCommandResult.NotOpenAppCommand ->
                BrowserSearchCommandResult.Unavailable("没有找到可用的浏览器。")
        }
    }

    private companion object {
        const val MAX_QUERY_CODE_POINTS = 200
        val commandPattern = Regex(
            """^(?:请帮我|麻烦你|你帮我|帮我|请)?\s*(?:在\s*)?(?:用|使用|打开)?\s*(默认)?\s*浏览器(?:中|里|里面)?[，, ]*(?:搜索|查找|查询)(?:一下)?[：:]?\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
