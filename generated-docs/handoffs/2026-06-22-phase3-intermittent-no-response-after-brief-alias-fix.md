# Phase 3 简短读屏修复后间歇性无响应

日期：2026-06-22

状态：暂缓处理，Phase 3 验收遗留问题

分支：`codex/phase3-screen-summary`

基线提交：`a2dd608`

设备：`10AC7Z0290001EF`

## 现象

在系统设置首页验收“简短读屏”时，用户连续说了几次没有任何回应，之后再次尝试才成功触发读屏。用户要求先搁置该问题，单独记录，暂不继续修改代码。

该现象发生在以下修复之后：

- 后端简短模式改为一到两句覆盖全部主要类别，不再只截取前几项。
- Android 命令门新增“剪短读屏”和“剪断读屏”到“简短读屏”的精确归一化。
- 修复版 App 于 2026-06-22 13:54:59 通过 `adb install -r` 覆盖安装。

## 已确认事实

- 修复前日志在 13:50:26 记录到 `ASR utterance='剪短读屏。'`，随后被标记为 `Continuous utterance ignored reason=not_explicit_command`。该精确别名缺口已经通过 TDD 修复。
- 新增别名测试先失败，随后通过；完整 Android 单元测试和 debug 构建也通过。
- 后端简短覆盖修复通过全部 51 个后端测试。
- 使用九个设置页类别做真实 provider 预检时，`qwen3.7-plus` 在一句话中覆盖了电池、蓝心智能、游戏魔盒、健康使用设备、钱包与支付、快捷与辅助、系统管理与升级、关于手机、用户与账号。
- 用户复测时，前几次没有回应，后来至少一次成功。后端日志最终记录到一次 `/v1/assist` 200，耗时约 2000ms，`assistSource='provider'`。
- 未发现 SightSync `FATAL EXCEPTION` 证据。

## 证据缺口

- 修复后定向日志文件 `2026-06-22-phase3-brief-alias-retest-live-logcat.txt` 长度为 0，未捕获到前几次失败尝试对应的 ASR 文本、NoSpeech、Recorder 失败或命令门决策。
- 因此目前无法确认前几次无响应属于以下哪一层：录音未取得有效语音、ASR 返回新的固定短语变体、命令门静默忽略、安装后无障碍服务重连延迟，或其他时序问题。
- 后端最终成功不能证明此前失败请求到达过 `/v1/assist`。

## 当前影响

- 固定读屏命令仍可能出现用户无可见反馈的间歇性失败。
- “未知语音静默忽略”能抑制环境误触发，但当真实命令被 ASR 错转为未覆盖变体时，也会表现为完全无响应。
- Phase 3 的简短读屏完整性和稳定性尚未完成最终人工验收，不能据此推进 Phase 4。

## 后续复现步骤

1. 启动电脑后端，显式使用 `QWEN_MODEL=qwen3.7-plus`，确认 provider probe 为 200。
2. 设置 `adb reverse tcp:8787 tcp:8787`，确认当前修复版 App 已安装。
3. 使用直接 `adb logcat -d` 快照和实时定向日志双通道记录，避免单一重定向日志为空。
4. 在系统设置首页连续执行 5 次“简短读屏”，每次等待当前录音回合结束后再说下一次。
5. 对每次尝试关联录音完成、ASR utterance、normalize/decision、assist 请求和 TTS 开始时间。
6. 只有取得至少一次失败尝试的完整链路后再决定修复，不继续猜测增加宽泛别名。

## 建议调查方向

- 统计失败尝试是否产生 `/v1/transcribe`，先区分录音/ASR 与命令门问题。
- 如果 ASR 返回新的稳定变体，只增加日志中真实出现的精确别名，不启用编辑距离或模糊语义匹配。
- 如果 ASR 返回 NoSpeech 或 Recorder，检查安装后服务重连、AudioRecord 清理和下一 generation 启动时序。
- 如果命令被门控接受但没有 assist，按 session generation/turn 和后端 requestId 追踪 Android 到代理的边界。
- 不以普通环境语音触发提示作为补救，避免恢复此前的播报风暴。

## 本地证据

- 修复前命令与服务日志：`generated-docs/logs/2026-06-22-phase3-brief-coverage-live-logcat.txt`
- 修复后空日志文件：`generated-docs/logs/2026-06-22-phase3-brief-alias-retest-live-logcat.txt`
- 修复后后端日志：`generated-docs/logs/2026-06-22-phase3-brief-coverage-backend-stdout.log`
- 读屏与单实例修复计划：`generated-docs/plans/2026-06-21-phase3-screen-reading-singleton-repair.md`

## 安全边界

- 不上传原始日志到 GitHub。
- 不记录或复制 provider key、App token、音频、截图或敏感页面正文。
- 暂缓期间不新增模糊命令匹配，不调整 VAD，不切换 provider/model，不进入 Phase 4。
