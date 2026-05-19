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
