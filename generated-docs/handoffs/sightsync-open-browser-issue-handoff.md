# SightSync 打开浏览器问题交接

## 背景

用户提供的视频文件：

`D:\video\2026-05-25 19-30-21.mp4`

问题现象是：用户对 SightSync 说“打开浏览器”或类似命令时，SightSync 没有真正打开浏览器，而是播报“正在查看当前屏幕”，随后描述当前页面内容。用户明确补充：视频里后面出现的谷歌浏览器是用户自己手动打开的，不是 SightSync 成功执行的结果。

本交接用于后续其他对话继续阅读、复现和修改该问题。

## 视频与语音转写结果

已从视频中抽帧和提取语音。关键结论：

- 第一轮语音识别结果包含：`帮我打开谷歌浏览器。`
- SightSync 随后没有打开浏览器，而是进入读屏流程并播报：`正在查看当前屏幕。`
- 后续视频中 Chrome 打开是用户手动打开，不应被当作 SightSync 执行成功。

按 8 秒音频切片得到的转写片段如下：

| 时间段起点 | 转写内容 |
| --- | --- |
| 0s | 帮我打开谷歌浏览器。 |
| 8s | 我在主屏幕，您可以告诉我您想打开哪个应用，或者询问当前。 |
| 16s | 帮我打开谷歌邮箱。 |
| 24s | 正在查看当前屏幕。 |
| 32s | 请问你想打开哪个应用？ |
| 40s | 正在查看当前屏幕。 |
| 48s | 请问你想打开哪个应用？帮我打开Google。 |
| 56s | 正在查看当前屏幕。 |
| 64s | 我在主屏幕，请问您需要我做什么？帮我关闭。 |
| 72s | 谷歌浏览器。正在查看当前屏幕。 |
| 80s | 这是Chrome浏览器的首次启动欢迎页面，您可以选择添加账户到设备。 |
| 88s | 登录账号，或者选择不使用账户继续。请问您想执行哪个操作？ |
| 96s | 不使用账户。 |
| 104s | 正在查看当前屏幕。 |

## 当前代码观察

相关代码入口：

- `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- `app/src/main/java/com/sightsync/assistant/apps/OpenAppCommandResolver.kt`
- `app/src/main/java/com/sightsync/assistant/apps/AppCatalog.kt`
- `app/src/main/java/com/sightsync/assistant/core/ActionExecutor.kt`

当前 `AssistantSessionManager.runAssistantTurn()` 的关键顺序是：

1. 获取 ASR 文本。
2. 处理确认、取消、停止等语义。
3. 调用 `handleLocalOpenAppCommand(utterance)`。
4. 如果本地打开应用命令没有命中，才播报 `正在查看当前屏幕。`，然后采集屏幕并调用后端 AI。

因此，视频里听到 `正在查看当前屏幕。` 可以反推出：这一轮没有被本地 `OpenAppCommandResolver` 成功处理，或者设备实际运行的不是包含该本地解析器的最新 APK/服务实例。

当前 `OpenAppCommandResolver` 的已知行为：

- 命令必须匹配类似 `打开/启动/开启/进入 X`。
- `浏览器`、`browser`、`chrome`、`网页`、`上网` 会被当作浏览器别名。
- `浏览器` 优先使用系统默认浏览器；否则按本地可启动应用名称匹配。
- `谷歌浏览器。` 这种追问后的裸目标，因为没有“打开/启动”等动词，当前不会被当作打开应用命令。

当前单元测试曾验证：

- `帮我打开设置` 可以本地解析为 `OPEN_APP`，并且不采集屏幕、不调用 AI。
- `打开浏览器` 会优先默认浏览器。
- 多个候选会追问。
- 无本地匹配会回落现有 AI 流程。

## 可能原因

按可能性排序：

1. **设备上运行的不是最新 APK，或无障碍服务仍是旧实例。**
   - 视频第一句 `帮我打开谷歌浏览器。` 理论上至少应被当前源码的打开命令解析路径尝试处理。
   - 如果仍直接播报 `正在查看当前屏幕。`，很可能实际运行包或服务实例没有加载当前实现。

2. **本地应用目录没有识别到 Chrome 或默认浏览器。**
   - `PackageManagerAppCatalogProvider.installedApps()` 依赖 launcher activities。
   - `defaultBrowserPackage()` 依赖 `ACTION_VIEW https://www.example.com` 的 resolve 结果。
   - 如果目录没有 Chrome，`OpenAppCommandResolver` 会返回 `NoMatch`，当前逻辑会回落到屏幕采集和 AI。

3. **别名和追问状态不足。**
   - `谷歌浏览器`、`Google 浏览器`、`Google Chrome` 这类自然说法需要更明确地覆盖。
   - 助手追问“请问你想打开哪个应用？”后，用户只说 `谷歌浏览器`，当前不会被识别为打开应用。

4. **显式打开应用命令失败后回落 AI，造成体验误导。**
   - 对 `打开 X` 这种明确意图，如果本地没有匹配，继续读屏并问 AI 会让用户感觉 SightSync 在“看页面”而不是处理打开应用。

## 建议修改方向

### 打开应用解析

- 扩展浏览器/Chrome 别名：
  - `谷歌浏览器`
  - `Google 浏览器`
  - `Google Chrome`
  - `Chrome 浏览器`
  - `谷歌`
  - `google`
- 对显式 `打开/启动/开启/进入 X` 命令，如果本地找不到应用，直接语音提示没有找到该应用，不再回落到 `正在查看当前屏幕`。
- 在 `AssistantSessionManager` 中增加一轮 pending open-app clarification 状态：
  - 当解析结果需要追问时，记录下一轮允许裸目标。
  - 下一轮用户只说 `谷歌浏览器` 时，也按打开应用目标解析。
  - 成功、失败、取消、停止后清空该状态。

### 执行链路

- 在 `ActionExecutor.openApp()` 中捕获启动失败：
  - `getLaunchIntentForPackage()` 为 null 时提示找不到可启动入口。
  - `startActivity()` 抛出异常时提示打开失败和简要原因。
- 增加本地 debug log，便于确认真实运行路径：
  - ASR 最终文本。
  - 是否进入本地打开应用解析。
  - 解析出的 target。
  - 本地可启动应用数量。
  - 默认浏览器包名。
  - 解析结果：Resolved / Ambiguous / NoMatch / NotOpenAppCommand。
  - `OPEN_APP` 执行成功或失败原因。
- 日志只用于本机调试，不上传应用列表。

## 建议验证

优先先确认运行态，不要只看源码：

1. 安装最新 debug APK。
2. 关闭并重新开启 SightSync 无障碍服务，确保服务实例刷新。
3. 打开 logcat，过滤 SightSync 相关日志。
4. 说 `帮我打开谷歌浏览器`。
5. 预期结果：
   - 不播报 `正在查看当前屏幕。`
   - logcat 显示进入本地 open-app resolver。
   - resolver 命中 Chrome 或默认浏览器。
   - `OPEN_APP` 执行成功。
   - Chrome 成为前台应用。

还应覆盖：

- `打开浏览器`
- `打开设置`
- `打开不存在的应用`
- 助手追问后只说 `谷歌浏览器`
- 非打开应用问题，例如 `这里有什么`

## 当前状态

- 本交接文档创建前，已执行过的视频分析和代码阅读，但没有保留代码改动。
- 曾短暂创建过 `codex/v2-open-app-control` 分支，后来已切回 `main` 并删除该分支。
- 当前问题仍未修复，需要后续对话继续实现和验证。
