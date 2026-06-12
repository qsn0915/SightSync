# SightSync Phase 1 模拟器验收记录

日期：2026-06-11

范围：Phase 1 稳定性和安全底座验收，覆盖小片 1-5 的实现结果，以及小片 6 的阶段验收记录整理。

验收方式：用户明确选择本次使用 Android 模拟器验收，未执行真机验收。

设备与环境：
- Android 模拟器：`emulator-5554`
- Debug APK：`D:\project\app\build\outputs\apk\debug\app-debug.apk`
- 无障碍服务组件：`com.sightsync.assistant/com.sightsync.assistant.accessibility.AssistantAccessibilityService`

## 自动化测试和构建

1. App 单元测试
   - 命令：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`
   - 结果：通过，`BUILD SUCCESSFUL in 47s`，`24 actionable tasks: 24 executed`

2. Backend 测试
   - 命令：在 `D:\project\backend` 下清空测试进程内的 `QWEN_API_KEY`、`DASHSCOPE_API_KEY`、`AI_API_KEY` 后执行 `npm test`
   - 结果：通过，`tests 27`，`pass 27`，`fail 0`，`duration_ms 913.0968`

3. Debug APK 构建
   - 命令：`.\gradlew.bat :app:assembleDebug --rerun-tasks`
   - 结果：通过，`BUILD SUCCESSFUL in 42s`，`37 actionable tasks: 37 executed`
   - 备注：构建出现非致命 strip warning：`libandroidx.graphics.path.so` 未 strip，按原样打包。

## 模拟器验收结果

安装与启动：
- `adb devices` 检测到 `emulator-5554 device`
- `adb install -r app-debug.apk` 成功，输出 `Success`
- 启动 `com.sightsync.assistant/.MainActivity` 成功
- `dumpsys activity` 显示 `com.sightsync.assistant/.MainActivity` 为 top resumed activity

无障碍与连续聆听可见性：
- 临时启用 SightSync 无障碍服务成功
- `settings get secure enabled_accessibility_services` 显示包含 `com.sightsync.assistant/com.sightsync.assistant.accessibility.AssistantAccessibilityService`
- 验收结束后已恢复模拟器原有无障碍设置

通知与悬浮窗：
- `dumpsys notification` 显示 SightSync 连续聆听通知：
  - 标题：`SightSync 正在连续聆听`
  - 文案：`麦克风正在等待你的语音指令。`
  - channel：`sightsync_continuous_listening`
- `dumpsys window` 显示 SightSync 悬浮窗存在，`appop=SYSTEM_ALERT_WINDOW`

日志：
- 过滤后的近期 `logcat` 未发现 `FATAL EXCEPTION` 或 `AndroidRuntime` 崩溃
- 录音链路出现正常诊断日志：`SightSyncRecorder: recorded duration=8000ms peakAmplitude=8 heardSpeech=false`

## Phase 1 验收门槛对照

- `.\gradlew.bat :app:testDebugUnitTest`：通过
- `cd backend; npm test`：通过，且测试进程内 provider key 已清空
- `.\gradlew.bat :app:assembleDebug`：通过
- Android 模拟器安装 debug APK：通过
- 无障碍服务可启用：通过
- 连续聆听通知可见：通过
- 悬浮窗可见：通过
- 最近 `logcat` 无 SightSync 崩溃：通过
- 停止/取消语义、TTS 被打断、网络错误反馈、敏感字段脱敏、高风险页面拒绝执行：本阶段通过小片 1-5 的 App 单元测试和源码级测试覆盖；本次模拟器验收未进行真实语音口令端到端人工对话。

## 剩余风险

- 本次为模拟器验收，不覆盖真实设备厂商 ROM、真实麦克风、真实 TTS 引擎和真机无障碍行为差异。
- 本次未接入真实 AI provider 做语音到动作的端到端验收；backend 仅执行自动化测试。
- TTS 初始化失败兜底通过测试覆盖，本次未在模拟器上强制制造 TTS 引擎初始化失败。
- 上下文风控当前以关键词和页面文本判断为主，仍可能存在误报或漏报，需要后续阶段持续收敛。

## 结论

Phase 1 模拟器验收通过。按长期计划要求，Phase 1 完成后先等待用户验收确认；确认前不进入 Phase 2。
