# Phase 3 屏幕理解 V2 真机复验记录

日期：2026-06-21

分支：`codex/phase3-screen-summary`

基线提交：`a2dd608`

设备：`10AC7Z0290001EF`

验收范围：复验 Phase 3 屏幕理解运行链路和 2026-06-15 语音/代理修复；不进入 Phase 4。

## 环境准备

- 使用手机中已安装的修复版 SightSync，未重复安装、未卸载、未清数据、未修改权限或系统设置。
- 电脑后端代理监听 `127.0.0.1:8787`，手机设置 `adb reverse tcp:8787 tcp:8787`。
- 后端进程显式使用用户批准的 `QWEN_MODEL=qwen3.7-plus`，真实 provider key 只保存在进程环境中。
- `GET /v1/health?probe=provider` 返回 200：`providerProbe=ok`、`model=qwen3.7-plus`、`asrModel=qwen3-asr-flash`。

## 五类页面人工验收

| 场景 | 指令和日志证据 | 用户判断 | 结论 |
| --- | --- | --- | --- |
| 系统设置首页 | ASR 正确识别“详细读屏”，transcribe/assist 均为 200 | 页面内容大致正确 | 屏幕理解通过 |
| 浏览器普通网页 | ASR 正确识别“简短读屏”，transcribe/assist 均为 200 | 页面内容大致正确 | 屏幕理解通过 |
| 短信或聊天会话列表 | ASR 正确识别“简短读屏”，transcribe/assist 均为 200 | 页面内容大致正确 | 屏幕理解通过 |
| 普通输入页面 | “只说明可操作项”被识别为“只说明可操作性”，assist 返回 200 | 页面内容大致正确 | 部分通过，指令容错不足 |
| 含测试敏感词的浏览器结果页 | ASR 首次正确识别“详细读屏”，assist 返回 200；后续又误识别为“详细读拼音” | 页面内容大致正确 | 内容通过，语音稳定性不足 |

所有场景只做读屏，没有要求或执行高风险动作，没有输入真实密码、验证码或支付信息。

## 运行日志结果

- 五条预期指令期间共记录 13 条 `ASR utterance`，至少 8 条是额外环境声或误识别，例如“嗯”“真的假的”“外面快飙疯了”“炮塔被摧毁”。
- 额外环境声被继续送入普通 assist 流程，造成不必要的录音、转写和 AI 请求。
- 记录到 2 次 `Speech input failed type=ServiceOrNetwork`。
- 后端共记录 2 次 health、17 次 transcribe、12 次 assist，所有请求状态码均为 200；2026-06-15 的 provider 403 已消失。
- 聚焦日志未发现 `FATAL EXCEPTION`、ANR 或 `AndroidRuntime` 崩溃。

## 结论

Phase 3 屏幕理解内容质量：**通过**。用户确认五类页面播报内容“大致正确”，真实 transcribe 和 assist 链路可用。

Phase 3 整体阶段验收：**未通过**。原因不是后端 provider，而是连续聆听仍会频繁把环境声当成用户指令，并存在读屏命令误识别；这不满足 V2 语音稳定性和少打扰要求。因此不能进入 Phase 4。

下一修复小片应聚焦：连续聆听环境声误触发抑制、读屏固定命令容错，以及转写网络失败的可诊断性。修复后只需复验语音稳定性和五类页面关键命令，不重复已经通过的后端 provider 配置修复。

## 本地证据

- 全程实时日志：`generated-docs/logs/2026-06-21-phase3-live-logcat.txt`
- 标准原始日志：`generated-docs/logs/sightsync-acceptance-raw-20260621-211059.txt`
- 标准关键日志：`generated-docs/logs/sightsync-acceptance-key-20260621-211059.txt`
- 后端 stdout：`generated-docs/logs/2026-06-21-phase3-acceptance-backend-stdout.log`
- 后端 stderr：`generated-docs/logs/2026-06-21-phase3-acceptance-backend-stderr.log`

## 连续命令门修复后复验（21:40-21:56）

修复版 App 通过 `adb install -r` 覆盖安装并启动，保留了原有数据。`adb reverse tcp:8787 tcp:8787` 已恢复，真实 provider probe 继续返回 `qwen3.7-plus / ok`。

### 已通过

- 普通聊天和环境人声被多次转写后均记录为 `Continuous utterance ignored reason=not_explicit_command`，没有继续触发 assist。命令门的静默抑制目标通过。
- “详细读屏”和“简短读屏”均被准确识别，并记录为 `Continuous utterance accepted category=ScreenReading`。
- TTS 回灌被误转写为“剪断读屏”时，命令门将其静默忽略，没有形成新的读屏请求。
- 后端请求均为 200，未复现 provider 403、AI 服务不可用或语音转写网络不可用风暴；聚焦日志未发现崩溃。

### 未通过

- 用户在系统设置首页确认：“详细读屏”包含大量无法理解的英文；“简短读屏”概括不到位；尝试“只说明可操作项”后仍听到英文。
- 后端日志显示这些明确读屏请求的 `/v1/assist` 仅耗时 0-4ms。代码核查确认 `createLocalAssistResponse()` 在 provider 之前截获读屏命令，实际输出来自 `buildScreenSummarySpoken()` 对节点标签的本地拼接，不是 Qwen 的语义总结。
- 简短模式仅使用 `mainContent` 前四项；详细和可操作项模式会直接朗读节点 `text/contentDescription`。当前没有面向用户的语言过滤或父子节点标签合并，因此系统节点中的英文标签可直接进入 TTS。
- 日志从 21:51 起同时出现录音线程 `31570` 和 `31571` 的重叠录音周期。同一进程存在两条连续聆听循环，可能造成上一轮 TTS 被另一条循环转写；最后一次“只说明可操作项”未形成可归因的 accepted 日志，不能判定该单项通过。

### 最终结论

连续命令门修复本身：**通过**。

Phase 3 阶段验收：**仍未通过**。当前阻塞已从 provider/环境误触发收敛为两个可复现方向：本地读屏摘要不满足“理解复杂页面并清楚叙述”的门槛，以及重复连续聆听循环导致语音串扰。Phase 4 继续冻结。

新增本地证据：

- 命令门实时日志：`generated-docs/logs/2026-06-21-phase3-command-gate-live-logcat.txt`
- 命令门 stderr：`generated-docs/logs/2026-06-21-phase3-command-gate-live-logcat-stderr.txt`

## 读屏质量与单实例修复后最终验收（2026-06-22）

- 后端读屏改为 provider-first，仅使用清洗后的 `screen_summary_v2`；技术标识、过量未知英文或带动作的读屏响应会被拒绝并安全降级。
- 连续聆听改为单实例生命周期；真机快速停止/重开时，generation 3 完成后 generation 4 才启动，未再出现重叠录音回合。
- 环境人声被命令门静默忽略，未继续触发 assist 或 TTS。
- 设置页“详细读屏”经用户确认内容准确，且修复后未再播报未知技术英文。“简短读屏”曾只概括部分内容，已通过 TDD 改为一到两句覆盖所有主要类别；真实 `qwen3.7-plus` 预检已覆盖设置页九个主要类别。
- 浏览器、聊天列表、普通输入页和测试敏感词页面已在本 Phase 先前真机验收中完成，用户确认五类页面内容“大致正确”，并于 2026-06-22 明确确认该验收已完成，不重复执行。
- 自动化结果：后端 51 个测试通过，Android `:app:testDebugUnitTest` 通过，`:app:assembleDebug` 通过，`git diff --check` 通过。
- 最终快照中唯一的 `FATAL EXCEPTION` 属于百度输入法进程 `com.baidu.input_vivo`，不是 SightSync 崩溃。

已知遗留风险：修复后“简短读屏”曾出现前几次无响应、后来成功的间歇性现象。当时定向日志为空，不足以确定录音、ASR、命令门或服务重连中的具体根因。用户要求暂缓处理，已单独记录在 `generated-docs/handoffs/2026-06-22-phase3-intermittent-no-response-after-brief-alias-fix.md`。

### 最终结论

Phase 3 阶段验收：**通过，保留已知风险**。

用户已确认 Phase 3 验收完成；不再重复已验收的五类页面。Phase 4 可从长期计划的第一个未完成小片开始，但不得忽略上述间歇性无响应风险。

新增本地证据：

- 最终定向快照：`generated-docs/logs/2026-06-22-phase3-final-logcat-snapshot.txt`
- 间歇性无响应交接：`generated-docs/handoffs/2026-06-22-phase3-intermittent-no-response-after-brief-alias-fix.md`
