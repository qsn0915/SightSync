# Phase 2 小片 4：App 使用动态代理配置

## 范围

- 让 App 运行时从 `AiServiceConnectionConfigStore` 读取代理地址和 App token。
- 移除无障碍服务对固定 `BuildConfig.AI_PROXY_BASE_URL` / `BuildConfig.APP_API_TOKEN` 的运行时依赖。
- 让连接配置页的“测试连接”调用后端 `/v1/health`，替代占位 tester。
- 不改 AI provider，不在 App 内硬编码云端 AI key，不进入 Phase 2 后续小片。

## TDD 计划

- [x] 先新增/扩展 App 单元测试与源码约束测试，覆盖：
  - `AiProxyClient` 能请求 `/v1/health`，并把 200/401/503/网络异常映射为现有错误类型。
  - 连接测试 tester 使用动态配置请求 health endpoint。
  - 无障碍服务源码不再引用固定 BuildConfig 代理字段，而是读取 `AiServiceConnectionConfigStore` 并使用动态 client factory。
- [x] 运行目标测试，确认新增测试先失败。
- [x] 实现：
  - `AiProxyClient.checkHealth()`。
  - 真实 `AiServiceConnectionHealthTester`。
  - 动态 `ConfiguredAiProxyClientFactory` 和未配置时的受控失败 client。
  - `MainActivity` 与 `AssistantAccessibilityService` 的动态配置接线。
- [x] 运行 App 单元测试。
- [x] 构建并安装到 Android 设备/虚拟机，完成基础启动验证。
- [x] 更新 `SIGHTSYNC_LONG_TERM_PLAN.md` 中该小片进度。

## 验收标准

- App 侧不再通过 `BuildConfig.AI_PROXY_BASE_URL` 作为 AI 代理唯一入口。
- 未配置代理时，不会回退到固定地址或隐藏默认 token。
- 配置页连接测试能真实命中后端 health endpoint，并对鉴权失败、provider 未配置、网络不可达给出可解释状态。
- 单元测试和 Android 基础验证通过，或明确记录阻塞原因。
