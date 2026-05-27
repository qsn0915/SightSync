# V2 语音稳定性验收清单

## 自动化测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
cd backend
npm test
```

## 本机虚拟机验收

1. 安装 debug APK 并启动 SightSync。
2. 开启无障碍、麦克风、通知、悬浮窗权限。
3. 确认通知显示 SightSync 正在连续聆听，悬浮窗可见。
4. 等助手朗读结果时保持安静，确认下一轮不会把助手自己的朗读识别成用户命令。
5. 慢速说“点击设置里的 WLAN”，确认不会在 4 秒处截断。
6. 快速说“返回”，确认不需要等待固定 4 秒。
7. 让连续聆听保持开启一段较长时间，确认仍能响应语音。
8. 通过通知动作停止聆听，确认麦克风停止。
9. 通过悬浮窗再次开启并停止聆听，确认状态一致。

## 验收记录

- 日期：2026-05-19
- 虚拟机镜像/Android 版本：SightSyncApi35 / Android 15, API 35, sdk_gphone64_x86_64
- App 提交：45cba675d25f2c6b237cd975a429d54e6b7a601c
- 后端配置：默认本地配置，`AI_PROXY_BASE_URL=http://10.0.2.2:8787/`，`APP_API_TOKEN=dev-token`；后端测试使用 mock/default provider 配置，未接入新的 AI provider 或 API key。
- 自动化测试结果：`.\gradlew.bat :app:testDebugUnitTest` 通过；`backend` 下 `npm test` 通过，26/26 pass；`.\gradlew.bat :app:assembleDebug` 通过。
- 人工验收结果：`.\gradlew.bat :app:installDebug` 成功安装到 SightSyncApi35；`adb shell am start -n com.sightsync.assistant/.MainActivity` 后前台 Activity 为 `com.sightsync.assistant/.MainActivity`；通过 ADB 授权麦克风、通知和悬浮窗，并启用 `AssistantAccessibilityService` 后，`dumpsys notification` 确认 `SightSync 正在连续聆听` 前台通知及 `停止聆听` action 存在，`dumpsys window` 确认 SightSync `APPLICATION_OVERLAY` 悬浮窗可见；通过 ADB 点击悬浮窗区域验证连续聆听可停止、再次开启，前台通知随状态消失/恢复；连续聆听保持 35 秒后通知和悬浮窗仍存在，最近 logcat 未发现 `FATAL EXCEPTION`。
- 未通过场景：无。
- 受限未覆盖场景：本次在隐藏启动的本机模拟器中执行，未进行真实口播音频验收；“不录入 TTS 自身朗读”“慢速超过 4 秒语句不截断”“短命令不等待固定 4 秒”和真实通知按钮点击仍需在可交互、有音频输入输出的模拟器窗口中复验。对应时序、录音和可见入口已由单元/集成测试、source test 与 ADB 状态验证覆盖。

## 复验记录

- 日期：2026-05-19
- 虚拟机镜像/Android 版本：SightSyncApi35 / Android 15, API 35, sdk_gphone64_x86_64
- App 提交：7a362c8460bf5623eb35e2e71d50ed70c3400515
- 后端配置：默认本地配置，`AI_PROXY_BASE_URL=http://10.0.2.2:8787/`，`APP_API_TOKEN=dev-token`；未在 Android App 内新增云端 AI API key。
- 自动化测试结果：`.\gradlew.bat :app:testDebugUnitTest` 通过；`backend` 下 `npm test` 通过，26/26 pass；`.\gradlew.bat :app:assembleDebug` 通过。
- 模拟器验证结果：`.\gradlew.bat :app:installDebug` 成功安装到 SightSyncApi35；`adb shell am start -n com.sightsync.assistant/.MainActivity` 后前台 Activity 为 `com.sightsync.assistant/.MainActivity`；通过 ADB 授权麦克风、通知和悬浮窗，并启用 `AssistantAccessibilityService` 后，`dumpsys accessibility` 确认服务已启用且无 crashed services；连续聆听保持 35 秒后，`dumpsys notification --noredact` 确认 `SightSync 正在连续聆听` 前台通知及 `停止聆听` action 存在，`dumpsys window windows` 确认 SightSync `APPLICATION_OVERLAY` 悬浮窗可见；通过 ADB 点击悬浮窗区域验证连续聆听可停止、再次开启，最近 logcat 未发现 `FATAL EXCEPTION`。
- 通知停止入口说明：直接用 ADB shell 向 `ContinuousListeningActionReceiver` 发广播会被 Android 跳过，原因是 receiver `android:exported="false"`，这是对外部 shell 广播的系统限制，不等价于 App 自己创建的通知 PendingIntent；本次复验通过 dumpsys 确认通知 action 存在，但未在隐藏模拟器窗口中完成人工点击通知按钮。
- 未通过场景：无。
- 受限未覆盖场景：本次仍未进行真实口播音频验收；“不录入 TTS 自身朗读”“慢速超过 4 秒语句不截断”“短命令不等待固定 4 秒”和真实通知按钮点击，需要在可交互、有音频输入输出的模拟器窗口或真机中复验。对应时序、录音和可见入口已由单元/集成测试、source test 与 ADB 状态验证覆盖。

## V2 本地 Agent 执行复验记录

- 日期：2026-05-25
- 分支：`codex/v2-local-agent-execution`
- 虚拟机镜像/Android 版本：`SightSyncApi35` / Android 15, API 35
- 后端配置：默认本地配置，未新增 AI provider/model，未在 Android App 内新增云端 AI API key。
- 自动化测试结果：`.\gradlew.bat :app:testDebugUnitTest` 通过，105/105 pass；`backend` 下 `npm test` 通过，26/26 pass；`.\gradlew.bat :app:assembleDebug` 通过。
- 模拟器验证结果：`.\gradlew.bat :app:installDebug` 成功安装；通过 ADB 授权麦克风、通知和悬浮窗，并启用 `AssistantAccessibilityService` 后，`dumpsys accessibility` 确认服务已启用且无 crashed services；`dumpsys notification --noredact` 确认 `SightSync 正在连续聆听` 前台通知及 `停止聆听` action 存在；`dumpsys window windows` 确认 SightSync `APPLICATION_OVERLAY` 悬浮窗可见；通过 ADB 点击悬浮窗区域验证连续聆听可停止、再次开启；最近 logcat 未发现 `FATAL EXCEPTION` 或 `AndroidRuntime` 崩溃。
- 本地 Agent 覆盖：单元测试验证“帮我打开设置”在本地解析为 `OPEN_APP`，不调用屏幕采集或后端 AI；“打开浏览器”优先默认浏览器；多个候选只追问；无本地匹配回落现有 AI 流程；高风险本地打开应用需要确认。
- 受限未覆盖场景：本次模拟器以 headless `-no-audio` 启动，未进行真实口播音频验收，也未通过真实语音触发“打开浏览器/设置”。短句、长句、TTS 不自录、语音打开应用的真实链路仍需在可交互、有音频输入输出的模拟器窗口或真机中复验。

## V2 打开浏览器控制修复复验记录

- 日期：2026-05-25
- 分支：`codex/v2-open-browser-control-fix`
- 虚拟机镜像/Android 版本：`SightSyncApi35` / Android 15, API 35
- 后端配置：默认本地配置，未新增 AI provider/model，未在 Android App 内新增云端 AI API key。
- 自动化测试结果：`.\gradlew.bat :app:testDebugUnitTest` 通过；`backend` 下 `npm test` 通过，26/26 pass；`.\gradlew.bat :app:assembleDebug` 通过。
- 模拟器验证结果：`.\gradlew.bat :app:installDebug` 成功安装；通过 ADB 授权麦克风、通知和悬浮窗，并启用 `AssistantAccessibilityService` 后，`dumpsys accessibility` 确认服务已启用且无 crashed services；`dumpsys notification --noredact` 确认 `SightSync 正在连续聆听` 前台通知及 `停止聆听` action 存在；`dumpsys window windows` 确认 SightSync `APPLICATION_OVERLAY` 悬浮窗可见；系统 Web 默认解析到 `com.android.chrome/com.google.android.apps.chrome.IntentDispatcher`，`monkey -p com.android.chrome` 可将 Chrome 启动到前台；通过 ADB 点击悬浮窗区域验证连续聆听可停止、再次开启；最近 logcat 未发现 `FATAL EXCEPTION` 或 `AndroidRuntime` 崩溃。
- 本地 Agent 覆盖：单元测试验证“帮我打开谷歌浏览器”“打开 Google Chrome”“启动 Chrome 浏览器”“进入 google”会命中 Chrome/默认浏览器；“打开不存在的应用”不采集屏幕、不调用后端 AI，直接朗读未找到；多候选追问后一轮可接受“谷歌浏览器”裸目标；取消会清空追问状态；`OPEN_APP` 启动入口缺失或启动异常会返回明确语音失败提示。
- 受限未覆盖场景：本次模拟器由自动化命令隐藏启动，未进行真实口播音频验收，也未通过真实语音触发 SightSync 打开浏览器；真实语音链路仍需在可交互、有音频输入输出的模拟器窗口或真机中复验。

## V2 打开应用真机验收记录

- 日期：2026-05-27
- 分支：`codex/v2-open-browser-control-fix`
- 真机：iQOO Neo6 SE / package model `V2199A`
- 后端配置：本地代理 `http://127.0.0.1:8787/`，通过 `adb reverse tcp:8787 tcp:8787` 暴露给真机；未在 Android App 内新增云端 AI API key。
- 安装方式：`adb install -r` 覆盖安装最新 debug APK；未执行 `adb uninstall`、`adb shell pm clear`、`adb shell settings put secure enabled_accessibility_services` 或其他会清除手机数据/修改系统无障碍配置的命令。
- 验收结果：用户确认真机验收算成功，本地打开应用控制链路可继续作为后续修改基础。
- 残留注意点：该真机未安装 Google Chrome，因此“打开谷歌浏览器”的 Chrome 专项验收不能在该设备上证明 Chrome 启动；可改测“打开浏览器”“打开 vivo 浏览器”“打开夸克浏览器”等已安装应用。USB 线插拔会清空 `adb reverse`，可能导致“语音转写不可用”，需要重新执行 `adb reverse tcp:8787 tcp:8787` 后再测。
