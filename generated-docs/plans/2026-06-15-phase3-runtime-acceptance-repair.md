# Phase 3 运行验收修复计划

日期：2026-06-15

## 背景

Phase 3 屏幕理解自动化测试和构建已通过，但真机验收失败。已查明本机后端启动时继承了过期 `QWEN_MODEL=qwen3.6-plus`，真实 `/v1/assist` 返回 provider 403；同时 `/v1/health` 只检查配置存在，导致 App 连接测试假阳性。真实音频验证显示 ASR 代理链路可用，但 Android 连续聆听在服务失败后立即进入下一轮，放大为重复失败提示和混乱语音。

本次只修复 Phase 3 运行验收阻塞，不进入 Phase 4。

## 执行步骤

1. 后端 TDD：为 `GET /v1/health?probe=provider` 添加失败和成功测试。配置存在但 provider 返回 403 时必须返回 503；provider 可用时返回 200，并保留轻量 `/v1/health` 行为。
2. 后端 TDD：添加请求诊断日志脱敏测试，确保日志只包含 endpoint、requestId、模型、状态、错误类型、耗时等信息，不包含 token、API key、audioBase64、screenshotBase64。
3. 后端实现：新增 provider probe、错误归一化和安全日志；验收启动时显式使用 `QWEN_MODEL=qwen3.7-plus`。
4. Android TDD：连接健康检查改为请求 `/v1/health?probe=provider`，并覆盖 provider 不可用提示。
5. Android TDD：连续聆听中连续服务/网络失败第二次后暂停，避免无限播报；成功请求后清零失败计数；无语音、停止/取消和单次请求行为保持不变。
6. Android 实现：按测试补齐健康检查路径、失败抑制和必要诊断日志。
7. 自动化验证：运行 `npm test`、`.\gradlew.bat :app:testDebugUnitTest`、`.\gradlew.bat :app:assembleDebug`、`git diff --check`。
8. 真机验证：启动电脑后端并显式设置 `APP_API_TOKEN=dev-token`、`QWEN_MODEL=qwen3.7-plus`；验证 `/v1/health?probe=provider`、最小 `/v1/assist`、真实 wav `/v1/transcribe`；再 `adb reverse`、`adb install -r` 更新 App 并捕获 focused logcat。
9. 进度更新：更新 Phase 3 验收记录和 `SIGHTSYNC_LONG_TERM_PLAN.md`，记录修复结果、风险和下一步仍是 Phase 3 验收，不推进 Phase 4。

## 安全边界

- 不在 Android App 内保存或硬编码云端 AI API key。
- 不做 provider/model 自动切换；`qwen3.7-plus` 是用户为本次电脑后端验收批准的模型。
- 不上传真实设备日志；生成日志只保存在 `generated-docs/logs/`。
- 不新增动作白名单和多步 Agent 能力。

## 执行记录

截至 2026-06-15 本次修复已完成代码实现和自动化验证：

- 后端新增 `GET /v1/health?probe=provider`，真实探测 provider 可用性；配置存在但 provider 返回 403 时返回 503。
- 后端新增安全请求日志，记录 requestId、endpoint、模型、状态码、错误类型和耗时；测试覆盖 token、API key、音频 base64、截图 base64 不进入日志。
- Android 连接测试改为调用 `/v1/health?probe=provider`。
- Android 连续聆听增加连续服务/网络失败抑制：第二次连续失败后暂停连续聆听，并播报短提示。
- Android 诊断日志新增 assist 失败类型和 status 记录。

验证结果：

- `cd backend; npm test`：通过，38/38。
- `.\gradlew.bat :app:testDebugUnitTest`：通过。
- `.\gradlew.bat :app:assembleDebug`：通过。
- `git diff --check`：通过，无输出。
- 电脑后端代理显式使用 `QWEN_MODEL=qwen3.7-plus` 后，`/v1/health?probe=provider`、最小 `/v1/assist`、真实 wav `/v1/transcribe` 均返回 200。

真机更新状态：

- `adb reverse tcp:8787 tcp:8787` 已设置。
- `adb install -r app-debug.apk` 两次均失败：`INSTALL_FAILED_ABORTED: User rejected permissions`。
- 未执行卸载、清数据、权限授予或系统设置修改。
- Phase 3 真机复验需在用户于手机端允许安装后继续。
