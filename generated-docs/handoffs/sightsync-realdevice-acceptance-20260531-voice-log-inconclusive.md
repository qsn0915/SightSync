# SightSync 真机验收记录：语音日志未形成有效指令链路

## 2026-05-31 11:41 修正

用户确认验收过程中确实说话，且手机实际执行了命令。随后补充读取 Android 系统任务栈，发现 `com.vivo.browser/.BrowserActivity` 由 `com.sightsync.assistant` 启动：

```text
launchedFromUid=10013 launchedFromPackage=com.sightsync.assistant
Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] ... pkg=com.vivo.browser cmp=com.vivo.browser/.BrowserActivity }
```

补充证据文件：

```text
D:\project\generated-docs\logs\sightsync-browser-launch-evidence-20260531-114117.txt
```

因此，本记录标题中的“未形成有效指令链路”只适用于 SightSync 自身 debug 日志缺失；从 Android 系统任务栈看，`打开vivo浏览器` 的 App 启动动作有通过证据。后续仍需单独调查为什么 `SightSyncSession` / `SightSyncAction` 标签没有记录到对应的 `ASR utterance`、`Local open-app resolved` 和 `OPEN_APP succeeded` 链路。

## 背景

验收目标是验证提交 `42bd84f fix: resolve branded browser app commands` 后，真机上 `打开vivo浏览器` 等品牌浏览器命令是否能被本地解析并执行。

设备已通过 `adb install -r` 更新当前 debug APK，已设置：

```text
adb reverse tcp:8787 tcp:8787
```

本地后端代理已启动并监听：

```text
D:\project\generated-docs\logs\sightsync-backend-proxy-20260531-112855.out.log
```

## 当前日志

- 早先无效日志：`D:\project\generated-docs\logs\sightsync-acceptance-key-20260531-113613.txt`
- 最新 PID 完整日志：`D:\project\generated-docs\logs\sightsync-pid-32345-raw-20260531-113926.txt`
- 最新 PID 关键日志：`D:\project\generated-docs\logs\sightsync-pid-32345-key-20260531-113926.txt`

## 观察结果

`11:36:13` 的聚焦日志只识别到：

```text
ASR utterance='嗯。'
Not a local open-app command.
```

后续清空 logcat 并让用户重新测试后，SightSync 进程仍存在，且系统日志显示持续创建 `AudioRecord`：

```text
AudioRecord: set final client name com.sightsync.assistant(32345)
```

但最新 PID 日志没有出现以下关键链路：

```text
ASR utterance='打开vivo浏览器...'
Local open-app resolved ...
OPEN_APP succeeded ...
```

当前前台窗口仍是 vivo 桌面：

```text
com.bbk.launcher2/.Launcher
```

## 验收结论

本轮真机验收 **未形成有效通过证据**。原因不是品牌浏览器解析逻辑已经失败，而是本轮日志没有捕获到有效 ASR 指令文本；只能证明 SightSync 有录音活动，不能证明转写、解析或打开 App 的链路完成。

## 建议下一步

1. 保持本地代理进程和 `adb reverse tcp:8787 tcp:8787`。
2. 清空 logcat 后只执行一条命令：`打开vivo浏览器`。
3. 等待 SightSync 完整播报结束后立刻抓取：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\codex\.codex\skills\sightsync-real-device-acceptance\scripts\sightsync_acceptance.ps1 -ProjectRoot D:\project -AdbPath D:\platform-tools\adb.exe -Capture
```

4. 通过以下证据判定通过：

```text
ASR utterance='打开vivo浏览器...'
Local open-app resolved ... appPackage=com.vivo.browser
OPEN_APP succeeded. package=com.vivo.browser
```
