# Phase 3 屏幕理解 V2 阶段验收记录

日期：2026-06-15

分支：`codex/phase3-screen-summary`

基线提交：`a2dd608`

验收范围：Phase 3 屏幕理解 V2。只验收屏幕摘要、节点树增强、读屏模式、截图辅助策略和隐私控制；不进入 Phase 4。

## 当前工作区

本次验收基于当前未提交的 Phase 3 小片 1-4 改动：

- 后端 `screen_summary_v2` 摘要协议。
- Android 节点树层级、父节点、区域、可操作类型、滚动容器、输入框上下文。
- 简短读屏、详细读屏、只说明可操作项。
- 带原因的截图隐私策略：节点树不足时才请求截图，敏感或已脱敏内容阻断截图。

## 自动化验证

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `Remove-Item Env:QWEN_API_KEY ...; npm test` | 通过，35/35 |
| Android 单元测试 | `.\gradlew.bat :app:testDebugUnitTest` | 通过，exit 0 |
| Debug 构建 | `.\gradlew.bat :app:assembleDebug` | 通过 |
| 差异检查 | `git diff --check` | 通过，无输出 |

## Fixture 验收

后端本地 fixture 覆盖五类页面，不调用真实 AI provider，不上传真实截图。

| 场景 | 结果摘要 | 结论 |
| --- | --- | --- |
| 设置页 rich node tree | 标题为“设置”，主要内容包含 WLAN、蓝牙、显示与亮度；无截图 | 通过 |
| 稀疏浏览器 WebView | `screenshotAttached=true`，`screenshotPolicy.reason=sparse_node_tree`；fallback 不编造网页内容 | 通过 |
| 聊天列表 | 简短读屏输出页面主题和少量主要内容；无截图 | 通过 |
| 输入页面 | 只说明可操作项，输出输入框上下文“昵称”和保存按钮；无动作 | 通过 |
| 敏感支付页 | 风险提示包含付款、提交、验证码；`privacyBlocked=true` 且无截图；无动作 | 通过 |

所有 fixture 的本地 fallback `actions` 均为空，符合读屏不执行动作的边界。

## 真机运行验收

连接方式：使用电脑后端代理，手机通过 `adb reverse tcp:8787 tcp:8787` 连接本机 `http://127.0.0.1:8787/`。

设备状态：

```text
List of devices attached
10AC7Z0290001EF	device
```

App 更新和启动：

- 已执行当前工作区 debug APK 构建。
- 已执行 `adb install -r app-debug.apk`，结果为 `Success`，保留用户数据。
- 已启动 `com.sightsync.assistant/.MainActivity`。
- 已设置 `adb reverse tcp:8787 tcp:8787`。

后端代理状态：

- `/v1/health` 使用 `Bearer dev-token` 返回 200：`provider=configured`，`asrProvider=configured`。
- 但真实 `/v1/assist` 最小请求返回 502，错误 detail 为 `provider returned 403`。
- `/v1/transcribe` 最小空 wav 探针返回 502，错误 detail 为 `asr provider returned 400`；该探针不能替代真实录音验收，但说明健康检查没有覆盖 ASR 真实调用可用性。

用户人工验收结果：

- 用户只进行到第 2 条验收指令附近即无法继续。
- 现场反馈：“语音极其混乱”“一直提示 AI 服务不可用和语音转写网络不可用”。
- 聚焦日志 `generated-docs/logs/sightsync-acceptance-raw-20260615-212552.txt` 中有 49 条 `ASR utterance`，包含大量 `嗯。`、`剪短读屏`、`剪断毒品`、`剪短毒品` 等误识别。
- 聚焦日志未发现 `FATAL EXCEPTION`、`AndroidRuntime` 崩溃或 ANR。

## 验收门槛

| Phase 3 门槛 | 证据 | 状态 |
| --- | --- | --- |
| 常见设置页、浏览器网页、聊天列表、输入页面能给出结构化叙述 | 后端 fixture 覆盖四类页面 | 自动化通过，真机未通过 |
| 读屏结果包含页面主题、主要内容、可操作项和风险提示 | fixture 和后端测试覆盖 | 自动化通过 |
| 节点树足够时不附带截图 | fixture 与 Android 单测覆盖 | 自动化通过 |
| 节点不足时才按策略附带截图 | fixture 与 Android 单测覆盖 | 自动化通过 |
| 敏感字段本地优先脱敏 | Android 单测覆盖 | 自动化通过 |
| 敏感或已脱敏内容阻断截图 | Android 单测与敏感支付 fixture 覆盖 | 自动化通过 |
| 复杂页面理解失败时说明无法确定，不编造内容 | 稀疏 WebView fallback 覆盖 | 自动化通过 |
| 真实 App 运行读屏 | App 已更新并启动，但后端 provider 403 且语音链路混乱 | 失败 |

## 结论

Phase 3 阶段验收状态：**未通过**。

原因：自动化测试、后端 fixture、Android 单元测试和 debug 构建通过；真机 App 已更新并启动，电脑后端代理与 `adb reverse` 也已就绪。但真实运行验收中，后端 provider 实际调用失败，且连续语音链路出现大量误识别和重复失败提示，用户无法完成验收。

因此 Phase 3 不能判定为通过，不能进入 Phase 4。

## 本地证据

- 聚焦日志：`generated-docs/logs/sightsync-acceptance-raw-20260615-212552.txt`
- 聚焦关键日志：`generated-docs/logs/sightsync-acceptance-key-20260615-212552.txt`
- 全量筛选日志：`generated-docs/logs/sightsync-debug-key-20260615-212551.txt`
- 后端代理 stdout：`generated-docs/logs/2026-06-15-phase3-backend-proxy-stdout.log`
- 问题 handoff：`generated-docs/handoffs/2026-06-15-phase3-runtime-ai-voice-blocker.md`

## 恢复条件

1. 解决电脑后端 provider 真实 `/v1/assist` 返回 403 的问题，不能只依赖 `/v1/health`。
2. 确认真实 `/v1/transcribe` 对真机录音稳定可用。
3. 降低连续聆听在服务失败或误识别时的重复播报/重复录音干扰。
4. 重新安装当前 App 后，补跑设置页、浏览器网页、聊天列表、输入页面和敏感页面读屏验收。

## 运行修复复验记录

日期：2026-06-15

修复计划：`generated-docs/plans/2026-06-15-phase3-runtime-acceptance-repair.md`

已完成修复：

- 后端新增 `GET /v1/health?probe=provider`，App 连接测试改为真实 provider probe，避免旧模型或云端 403 被 `/v1/health` 假阳性掩盖。
- 电脑后端验收启动显式使用用户批准的 `QWEN_MODEL=qwen3.7-plus`。
- 后端新增脱敏请求诊断日志，不记录 token、真实 provider key、音频 base64 或截图 base64。
- Android 连续聆听增加连续服务/网络失败抑制：第二次连续失败后暂停连续聆听，避免重复播报“AI 服务不可用/语音转写网络不可用”。
- Android 诊断日志新增 assist 失败类型和 status 记录。

自动化复验：

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `cd backend; npm test` | 通过，38/38 |
| Android 单元测试 | `.\gradlew.bat :app:testDebugUnitTest` | 通过 |
| Debug 构建 | `.\gradlew.bat :app:assembleDebug` | 通过 |
| 差异检查 | `git diff --check` | 通过，无输出 |

电脑后端代理 smoke：

| 项目 | 结果 |
| --- | --- |
| `/v1/health?probe=provider` | 200，`model=qwen3.7-plus`，`providerProbe=ok` |
| 最小 `/v1/assist` | 200，返回结构化读屏结果，`actions=[]` |
| 真实 wav `/v1/transcribe` | 200，返回 `简短读屏。` |

真机更新复验：

- 设备：`10AC7Z0290001EF`
- 已设置：`adb reverse tcp:8787 tcp:8787`
- App 更新命令：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 结果：两次失败，`INSTALL_FAILED_ABORTED: User rejected permissions`
- 处理：按真机验收安全边界，未卸载、未清数据、未授予权限、未修改系统设置。

当前结论：运行修复代码和电脑后端代理已验证通过，但 Phase 3 真机复验仍未完成，因为当前 APK 未能安装到手机。用户需要在手机端允许安装后，继续执行 `adb install -r`、启动 App，并补跑 Phase 3 五条人工读屏验收。

## 2026-06-21 后续复验

修复版 App 后续已成功安装。本次使用电脑后端代理完成五类页面真机复验：用户确认屏幕播报内容大致正确，provider probe、transcribe 和 assist 均可用；但连续聆听仍频繁把环境声识别为新指令，并存在固定读屏命令误识别。Phase 3 屏幕理解内容质量通过，整体阶段验收仍未通过，不能进入 Phase 4。

完整记录见：`generated-docs/acceptance/2026-06-21-phase3-runtime-reacceptance.md`。
