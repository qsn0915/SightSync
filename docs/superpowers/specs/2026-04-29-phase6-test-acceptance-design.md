# Phase 6 V1 测试与验收设计

## 目标

按 `纲领.md` 的 Phase 6 要求，为现有 SightSync Android App 补齐 V1 测试与验收支撑。范围限定在现有 `:app` 模块和已有 Node.js 后端代理内，不新建替代 App，不新增 AI provider，不在 Android 端放置云端 API key。

## 推荐方案

采用“单元测试 + 本地集成测试 + 人工验收清单”：

- 单元测试覆盖屏幕上下文采集策略、敏感字段脱敏、AI 响应 JSON 解析与动作白名单、风险判断、节点匹配。
- 本地集成测试用 fake speech input、mock-style assistant client、fake screen provider、fake action runner 验证“语音指令 -> 屏幕采集 -> AI 响应 -> 动作执行”的完整链路。
- 人工验收清单覆盖系统设置朗读、明确按钮点击、输入、滚动、返回、断网失败提示等 Phase 6 场景。

## 架构边界

`ScreenNodeTreeExtractor` 继续负责从节点树生成 `ScreenNode`。`ScreenContextCollector` 保留对 Android `AccessibilityService` 的依赖，但把“从 root source 组装 ScreenContext 并决定是否带截图”的逻辑抽成可单元测试的小单元。`AssistantSessionManager` 继续作为语音、屏幕、AI、动作执行的链路编排点。

## 错误处理

Phase 6 的失败场景不引入复杂恢复流程。网络超时、网络失败、AI 非法 JSON、非法动作继续通过语音明确提示。无障碍权限关闭或屏幕采集权限异常时，助手应朗读“无障碍权限已关闭，请重新开启后再试。”，避免落入泛化错误。

## 验证标准

- `.\gradlew.bat :app:testDebugUnitTest` 通过。
- `cd backend; npm test` 通过。
- Android debug APK 可安装并在本机虚拟机启动。
- `docs/phase6-acceptance.md` 给出人工验收步骤和通过/失败记录格式。
