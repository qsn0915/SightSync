# Phase 3 Continuous Command Gate Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 阻止连续聆听中的环境语音触发采屏、assist、TTS 或动作，同时保留明确命令和单次唤起的自由表达。

**Architecture:** 云端 ASR 链路保持不变。Android 在收到转写文本后先做安全的读屏短语归一化，再仅对连续模式应用本地命令门；未知语句静默返回 `Ignored`。转写失败改为 typed result，连续失败逻辑不再依赖中文提示文本。

**Tech Stack:** Kotlin、Coroutines、JUnit、现有 SightSync Android App、Node.js 后端回归测试、adb 真机验收。

---

## Task 1: Continuous Utterance Gate And Read-Screen Normalization

**Files:**
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/ContinuousUtteranceGate.kt`
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/ContinuousUtteranceGateTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Test: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`

- [x] 写失败测试：明确读屏、导航、滚动、点击、输入和打开 App 命令返回 `Accepted`；本次日志中的环境语句返回 `Ignored`。
- [x] 写失败测试：将“只说明可操作性”归一化为“只说明可操作项”，将“详细读拼音”归一化为“详细读屏”。
- [x] 运行目标测试并确认因分类器不存在或当前连续模式仍调用 AI 而失败。
- [x] 实现最小分类器：精确读屏别名、现有导航别名、明确动作前缀；不做编辑距离或通用语义猜测。
- [x] 在 `AssistantSessionManager` 中仅对连续模式应用命令门；单次唤起直接进入现有链路。
- [x] 新增 `TurnResult.Ignored`：不采屏、不请求 assist、不播报，且不改变连续服务失败计数。
- [x] 增加脱敏决策日志：accepted category/canonical command 或 ignored reason；不增加音频、屏幕或 token 日志。
- [x] 运行目标测试确认通过。

## Task 2: Typed Speech Failures And Strict Confirmation

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/speech/ProxySpeechInputController.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/ConfirmationManager.kt`
- Test: `app/src/test/java/com/sightsync/assistant/speech/ProxySpeechInputControllerTest.kt`
- Test: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
- Test: `app/src/test/java/com/sightsync/assistant/accessibility/ConfirmationManagerTest.kt`

- [x] 写失败测试：无语音、权限、网络、provider、超时和录音失败映射为稳定的 `SpeechInputFailureKind`，provider HTTP 状态码可保留。
- [x] 写失败测试：连续失败判断使用 typed kind；ignored/no-speech 不误当成功回合。
- [x] 写失败测试：“嗯”“好的”“行”“对”不能确认高风险动作，“确认”“确认执行”“继续执行”“执行吧”可以确认。
- [x] 运行目标测试确认失败原因正确。
- [x] 为 `SpeechInputResult.Failed` 增加带默认值的 `kind` 和可选 `statusCode`，保持已有测试构造兼容。
- [x] 更新 `ProxySpeechInputController` 和 session 日志/失败抑制逻辑。
- [x] 收紧 `ConfirmationManager` 确认词；待确认期间未知环境语句不得执行动作。
- [x] 运行目标测试确认通过。

## Task 3: Automated Regression And Build

- [x] 运行 `./gradlew.bat :app:testDebugUnitTest`。
- [x] 运行 `./gradlew.bat :app:assembleDebug`。
- [x] 在 `backend` 运行 `npm test`，不得在输出中暴露真实 provider key。
- [x] 运行 `git diff --check`。
- [x] 检查 diff 只包含本修复、既有 Phase 3 改动和生成记录，不回滚用户改动。

## Task 4: Real Device Update And Acceptance

- [x] 确认单台授权设备，电脑后端显式使用用户批准的 `qwen3.7-plus`，provider probe 返回 200。
- [x] 使用验收 helper 执行 `adb install -r`、`adb reverse` 和启动，保留 App 数据和系统设置。
- [x] 持续捕获 `SightSyncSession`、`SightSyncRecorder`、`SightSyncAction` 和 `AndroidRuntime`。
- [x] 在背景人声存在时观察 60–90 秒：允许 transcribe，但环境语句必须记录为 `Ignored`，不得出现 screen/assist/TTS/动作。
- [ ] 人工执行“详细读屏”“简短读屏”“只说明可操作项”；每条必须只产生一个 accepted turn，误识别别名应归一化为 canonical command。
- [ ] 无 `FATAL EXCEPTION`、ANR、重复服务失败风暴或环境声触发的 assist 后，才可判定修复通过。

## Task 5: Progress And Acceptance Records

- [x] 更新 `generated-docs/acceptance/2026-06-21-phase3-runtime-reacceptance.md`，记录修复后测试和真机证据。
- [x] 更新 `SIGHTSYNC_LONG_TERM_PLAN.md` 当前进度。
- [x] 只有语音稳定性复验通过后才把 Phase 3 标记通过；否则记录具体阻塞并继续停留在 Phase 3。

## 2026-06-21 执行结论

- 自动化测试、构建、provider probe、覆盖安装和环境语音静默门控均通过。
- “详细读屏”和“简短读屏”被命令门正确接受，但用户确认本地摘要不准确且混入大量英文。
- “只说明可操作项”未在聚焦日志中形成可归因的 accepted turn；同一进程同时出现两条录音循环，验收结果受到上一轮 TTS/ASR 串扰影响。
- 本计划实现目标中的命令门已完成，但 Phase 3 阶段验收仍失败。未勾选的真机验收项保留为失败证据，不推进 Phase 4。

## Safety Boundaries

- 环境音频仍会先发往电脑后端 ASR；未知文本不得继续上传屏幕上下文或调用 assist。
- 不引入热词、本地关键词模型、离线 ASR、provider/model 切换或新的动作类型。
- 不提高固定 VAD 音量阈值，避免伤害轻声用户；本小片解决 ASR 后误触发。
- 不卸载 App、不清数据、不修改 Android 权限或系统设置、不脚本化用户输入。
