# Phase 2 连接电脑本地代理日志复盘

## 环境

- 日期：2026-06-13
- 设备：`10AC7Z0290001EF`
- App：`com.sightsync.assistant`，`versionName=0.1.0`
- 后端：电脑本地 `http://127.0.0.1:8787`
- 手机到电脑：`adb reverse tcp:8787 tcp:8787`
- 说明：本次不是断开电脑验收，不能作为 Phase 2 “脱离电脑运行”最终通过证据。

## 日志文件

- 原始实时日志：`generated-docs/logs/sightsync-live-raw-20260613-153605.txt`
- 关键过滤日志：`generated-docs/logs/sightsync-live-key-20260613-153605.txt`
- helper 聚焦日志：`generated-docs/logs/sightsync-acceptance-key-20260613-153518.txt`
- 宽过滤日志：`generated-docs/logs/sightsync-wide-logcat-20260613-153518.txt`

## 观察结果

- 后端 health 在电脑端返回 `status=ok`，`provider=configured`，`asrProvider=configured`。
- 日志中未发现 `AndroidRuntime` 或 `FATAL EXCEPTION`。
- 日志显示 SightSync App 启动并进行了多轮录音。
- 日志显示 ASR 成功识别到：
  - `对。`
  - `这里有什么？`
  - `嗯。`
  - `停止聆听。`
- 日志显示多次连接到 `:8787`，与本地代理链路一致。
- 未捕获到 `SightSyncAction`、`GLOBAL_BACK`、`OPEN_APP succeeded` 或其他动作执行日志。
- 未捕获到“返回”被 ASR 识别；疑似该轮被识别为 `嗯。`。

## 当前结论

- 通过证据：
  - App 未崩溃。
  - 录音链路工作。
  - ASR 代理链路工作。
  - “停止聆听”至少被 ASR 识别。
- 不足证据：
  - 没有动作执行日志，不能确认“返回”动作通过。
  - 本次使用 `adb reverse` 和电脑本地后端，不能证明断开电脑可用。

## 后续动作

- 重新做一次低风险动作验收，建议明确说“返回上一页”，并观察是否真的返回。
- 为后续验收增加更稳定的日志点：连接测试结果、assist 请求开始/成功、动作协议解析结果、动作执行结果。
- 完成公网代理配置后，再执行 Phase 2 断开电脑验收。
