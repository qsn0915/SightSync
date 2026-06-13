# Phase 2 连接电脑本地代理验收复盘：模型修正后

## 环境

- 日期：2026-06-13
- 设备：`10AC7Z0290001EF`
- 后端：电脑本地 `http://127.0.0.1:8787`
- 手机到电脑：`adb reverse tcp:8787 tcp:8787`
- 本轮日志：
  - 原始日志：`generated-docs/logs/sightsync-live-raw-20260613-192205.txt`
  - 关键日志：`generated-docs/logs/sightsync-live-key-20260613-192205.txt`

## “AI 服务不可用”根因

- 后端 `/v1/assist` 曾返回 `HTTP 502`，错误 detail 为 `provider returned 403`。
- 环境变量中存在 `QWEN_MODEL=qwen3.6-plus`，覆盖了代码默认的 `qwen3.7-plus`。
- 直连 DashScope 验证 `qwen3.6-plus` 返回 `AllocationQuota.FreeTierOnly`，免费额度模式不可继续访问。
- 将本地后端重启为 `QWEN_MODEL=qwen3.7-plus` 后，`/v1/assist` 最小请求返回 `HTTP 200`。

## 本轮验收观察

- 后端 health 返回 `status=ok`，`provider=configured`，`asrProvider=configured`。
- 日志中未发现 `AndroidRuntime` 或 `FATAL EXCEPTION`。
- 日志显示录音与 ASR 成功：
  - `ASR utterance='这里有什么？'`
  - `ASR utterance='返回上一页。'`
  - `ASR utterance='停止聆听。'`
- 日志显示 App 多次连接本地代理 `:8787`。
- 未再捕获到 provider `403`、`502` 或 `503`。

## 当前结论

- 连接电脑本地代理模式下，录音、ASR、后端连通性和 provider 配置在模型修正后通过。
- “返回上一页”已被 ASR 正确识别；当前代码没有为 `GLOBAL_BACK` 打 `SightSyncAction` 日志，因此本轮日志不能单独证明系统返回动作是否执行成功，需要用户观察结果或后续补充动作执行日志。
- 本轮仍依赖 `adb reverse` 和电脑本地后端，不是 Phase 2 “断开电脑”最终验收。

## 建议

- 后续补充安全日志：`/v1/health`、`/v1/transcribe`、`/v1/assist` 的 endpoint、model、status，不记录 token、API key、截图 base64 或完整音频。
- App 侧补充 `GLOBAL_BACK`、`GLOBAL_HOME`、滚动、点击、输入等动作执行结果日志，方便无障碍动作验收。
