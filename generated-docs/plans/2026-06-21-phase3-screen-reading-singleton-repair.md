# Phase 3 Screen Reading Quality And Singleton Listening Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 让三种读屏模式使用安全的云端中文语义总结，并保证 Android 任意时刻只有一个连续录音回合。

**Architecture:** 后端先识别读屏模式，使用清洗后的 `screen_summary_v2` 调用 `qwen3.7-plus`，并对读屏响应施加无动作和英文技术内容约束；provider 不可用或输出不安全时明确降级到本地摘要。Android 使用期望状态和 Job 完成回调串行化连续聆听，服务重复连接只复用既有控制器。

**Tech Stack:** Node.js、Kotlin、Coroutines、JUnit、现有 SightSync Android App、adb 真机验收。

---

## Task 1: User-Facing Screen Summary Labels

**Files:**
- Modify: `backend/src/screen-summary.js`
- Test: `backend/test/screen-summary.test.js`

- [x] 写失败测试：可点击父节点没有可用标签时，使用其中文子节点标签。
- [x] 写失败测试：过滤 `android.widget.*`、包名、资源 ID、`node_12`、`Button/TextView` 等技术标签。
- [x] 写失败测试：保留页面中真实出现的品牌名以及 AI、Wi-Fi、WLAN、Bluetooth。
- [x] 运行 `node --test test/screen-summary.test.js`，确认因现有摘要直接选择节点自身标签而失败。
- [x] 实现最小标签清洗、后代标签回退和去重，不改变敏感文本脱敏边界。
- [x] 本地 fallback 增加固定降级提示；简短、详细和可操作项模式继续保持无动作。
- [x] 运行目标测试确认通过。

## Task 2: Provider-First Screen Reading Route And Safety

**Files:**
- Modify: `backend/src/protocol.js`
- Modify: `backend/src/qwen.js`
- Modify: `backend/src/server.js`
- Test: `backend/test/protocol.test.js`
- Test: `backend/test/qwen.test.js`
- Test: `backend/test/server.test.js`

- [x] 写失败测试：配置 provider 时，读屏命令调用 provider；点击和输入命令继续本地处理。
- [x] 写失败测试：读屏 prompt 只包含清洗后的摘要，不包含完整原始节点 JSON 或技术标签；允许的截图仍作为 image URL 附加。
- [x] 写失败测试：读屏 provider 响应必须 `actions=[]`、`requiresConfirmation=false`。
- [x] 写失败测试：技术标识或超过两个非页面来源英文词会触发本地降级。
- [x] 写失败测试：provider 未配置、403、超时或无效 JSON 时返回 200 和带固定提示的安全摘要。
- [x] 写失败测试：日志只增加 `assistSource`、固定枚举 `fallbackReason` 和 provider 状态，不记录凭据、节点正文或 base64。
- [x] 运行后端目标测试，确认当前本地读屏优先和全局错误路径导致失败。
- [x] 导出读屏模式识别和本地降级响应；非读屏本地动作逻辑保持原样。
- [x] 对读屏请求执行 provider-first 分支，并使用固定降级原因：`provider_not_configured`、`provider_http_error`、`provider_timeout`、`provider_response_invalid`、`provider_screen_reading_unsafe`。
- [x] 对 provider 读屏结果执行无动作、技术标识和英文词来源校验；不安全结果不进入 Android。
- [x] 运行后端目标测试确认通过。

## Task 3: Singleton Continuous Listening Lifecycle

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
- Test: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
- Test: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`

- [x] 写失败测试：停止后立即重新开启时，旧 `listenOnce()` 未结束前不得开始第二次录音，最大并发数为 1。
- [x] 写失败测试：快速停开后只保留最终开启状态，不播报过期“已停止聆听”。
- [x] 写失败测试：`dispose()` 取消录音和 TTS，但不播报停止提示。
- [x] 写失败测试：服务重复 `onServiceConnected()` 时复用既有 SessionManager、录音器和 TTS。
- [x] 运行 Android 目标测试，确认现有 stop 提前清空 Job 和服务重建控制器导致失败。
- [x] 增加连续聆听期望状态；保留旧 Job 引用直到完成，并用 Job 身份检查保证只启动一个后继循环。
- [x] 停止提示延迟到旧 Job 完成后，若期间重新开启则取消过期提示。
- [x] 增加无播报 `dispose()`，清理 active/continuous Job、确认状态、录音和 TTS。
- [x] 让 `onServiceConnected()` 幂等；`onDestroy()` 使用 `dispose()`。
- [x] 增加不含用户内容的 generation/turn 生命周期日志。
- [x] 运行 Android 目标测试确认通过。

## Task 4: Automated Regression

- [x] 运行 `backend/npm test`，51 个后端测试通过。
- [x] 运行 `.\gradlew.bat :app:testDebugUnitTest`，Android 单元测试通过。
- [x] 运行 `.\gradlew.bat :app:assembleDebug`，debug APK 构建成功。
- [x] 运行 `git diff --check`，无格式错误。
- [x] 检查差异：保留工作区中既有 Phase 3 改动，未回滚用户改动。

## Task 5: Real Device Update And Full Phase 3 Reacceptance

- [x] 确认单台授权设备、`qwen3.7-plus` provider probe 200 和 `adb reverse tcp:8787 tcp:8787`。
- [x] 通过 `adb install -r` 覆盖安装并启动，保留 App 数据与设备设置。
- [x] 捕获 `SightSyncSession`、`SightSyncRecorder`、`SightSyncAction` 和 `AndroidRuntime` 定向日志。
- [x] 停止后立即重新开启连续聆听；generation 3 完成后 generation 4 才启动，未见重叠录音回合。
- [x] 环境人声产生 `Ignored`，未继续调用 assist 或触发 TTS。
- [x] 设置页已执行读屏模式验收；详细模式内容准确，简短模式的覆盖不足已修复并最终成功触发。
- [x] 浏览器、聊天列表、普通输入页和测试敏感词页面已在本轮 Phase 3 人工验收中完成；用户明确指出不重复验收。
- [x] 未发现 SightSync 崩溃、高风险动作或修复后未知技术英文；“简短读屏”偶发无响应的单次链路证据不足，按用户要求作为遗留风险单独记录。

## Task 6: Progress Records

- [x] 更新 `generated-docs/acceptance/2026-06-21-phase3-runtime-reacceptance.md`，记录自动化、真机证据和遗留风险。
- [x] 更新 `SIGHTSYNC_LONG_TERM_PLAN.md` 当前进度。
- [x] 用户确认 Phase 3 验收已完成；Phase 3 以“通过但保留已知风险”收口，Phase 4 解冻。

## Safety Boundaries

- 继续使用用户批准的 `qwen3.7-plus`，不自动切换 provider 或模型。
- provider API key 只存在电脑后端代理，不进入 Android。
- 不调整 VAD，不新增热词、离线 ASR、动作类型或 Phase 4 能力。
- 不卸载 App、不清数据、不修改权限或 Android 系统设置。
- 不记录完整朗读正文、节点正文、音频、截图、token 或 provider key。
- 本轮不创建 Git 提交。
