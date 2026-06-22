# Phase 3 真机运行验收阻塞：AI provider 与语音链路混乱

日期：2026-06-15

分支：`codex/phase3-screen-summary`

## 背景

Phase 3 小片 5 进行屏幕理解 V2 阶段验收。用户要求使用电脑后端代理。真机 USB 调试已授权，当前工作区 debug APK 已通过 `adb install -r` 更新到设备 `10AC7Z0290001EF`，并通过 `adb reverse tcp:8787 tcp:8787` 连接本机后端。

## 现象

用户人工验收到第 2 条指令附近即无法继续，反馈：

- 语音极其混乱。
- 一直提示 AI 服务不可用。
- 一直提示语音转写网络不可用。

## 已确认事实

- App 已更新并启动。
- `adb reverse tcp:8787 tcp:8787` 已设置。
- 本机 `/v1/health` 返回 200，显示 provider/asrProvider configured。
- 真实 `/v1/assist` 最小请求返回 502，detail 为 `provider returned 403`。
- 真实 `/v1/transcribe` 最小空 wav 探针返回 502，detail 为 `asr provider returned 400`。该探针不是最终 ASR 证据，但说明健康检查没有覆盖真实调用。
- 聚焦 logcat 有 49 条 `ASR utterance`，大量内容为 `嗯。`、`剪短读屏`、`剪断毒品`、`剪短毒品` 等误识别。
- 聚焦 logcat 未发现 `FATAL EXCEPTION`、`AndroidRuntime` 崩溃或 ANR。

## 本地日志

- `generated-docs/logs/sightsync-acceptance-raw-20260615-212552.txt`
- `generated-docs/logs/sightsync-acceptance-key-20260615-212552.txt`
- `generated-docs/logs/sightsync-debug-all-20260615-212551.txt`
- `generated-docs/logs/sightsync-debug-key-20260615-212551.txt`

这些日志保留在本地，不上传。

## 初步根因判断

1. `/v1/health` 当前只能证明 provider 配置存在，不能证明真实 chat/ASR 调用成功；因此 App 连接测试可能显示可用，但实际语音链路失败。
2. 后端 chat provider 真实调用返回 403，属于后端 provider 凭据、模型权限、模型名或 provider 配置问题。Android App 内不得保存真实 provider key，也不应自行选择 provider/model。
3. 连续聆听在失败状态下仍快速进入下一轮录音，现场大量误识别和重复失败提示会使验收不可操作。
4. Phase 3 的本地 fallback 自动化可用，但真实语音验收依赖 ASR provider；provider 不稳定时无法完成端到端验收。

## 建议修复方向

后续小片应先修复验收基础设施，再重跑 Phase 3 验收：

- 后端 `/v1/health` 增加“真实 provider 探针”或单独诊断端点，区分配置存在与真实调用可用。
- 后端代理增加安全的请求级诊断日志：只记录 endpoint、状态码、错误码、耗时，不记录 API key、App token、完整截图 base64 或用户敏感内容。
- App 连续聆听在连续网络/provider 失败时降噪：避免短时间内反复播报同类失败和立即继续录音。
- 真实 provider 凭据/模型配置由用户决定并提供；修复后再用电脑后端代理重跑 Phase 3 设置页、浏览器网页、聊天列表、输入页和敏感页验收。

## 验收恢复标准

- `/v1/assist` 最小读屏请求返回 200，且 `actions=[]`。
- 真机录音 `/v1/transcribe` 能稳定返回用户实际指令。
- 连续聆听失败时不会形成重复失败提示循环。
- Phase 3 五类页面真机读屏验收可完整跑完。
