# Phase 4 小片 1：受限多步计划协议与 Android 校验器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持现有单动作协议兼容的前提下，定义受限多步计划 JSON 协议，并让后端与 Android 在任何执行发生前一致拒绝越界、非白名单或缺少页面前置条件的计划。

**Architecture:** `AssistResponse` 新增可选 `plan`，与顶层 `actions` 强制互斥；旧单动作继续走 `actions`，多步任务必须走 `plan`。计划由 2–8 个 `ACTION`、`WAIT_FOR_UI` 或 `VALIDATE_PAGE` 步骤组成，并携带 60 秒总时长、单步 15 秒和最多 2 次连续失败的硬上限。Android 只解析和校验计划；本小片不生成、不调度、不执行计划，因此不会绕过现有 `ActionRunner`、上下文风控、二次确认或半双工语音状态机。

**Tech Stack:** Kotlin 2.x、kotlinx.serialization、JUnit 4、Node.js ESM、`node:test`、现有 SightSync `AssistResponse`/白名单动作协议。

---

## 文件范围

- 新增：`app/src/main/java/com/sightsync/assistant/ai/AgentPlan.kt`
  - 保存可序列化的 `AgentPlan`、`AgentPlanStep` 和 `PageExpectation`。
- 新增：`app/src/main/java/com/sightsync/assistant/ai/AgentPlanValidator.kt`
  - 验证计划边界、步骤类型、页面前置条件、动作白名单和字段互斥。
- 新增：`app/src/test/java/com/sightsync/assistant/ai/AgentPlanValidatorTest.kt`
  - 覆盖合法计划、越界计划、非法动作、缺少页面条件、内部步骤夹带动作和 JSON 解析。
- 修改：`app/src/main/java/com/sightsync/assistant/ai/Protocol.kt`
  - `AssistResponse` 增加可选 `plan`；现有校验器委托给 `AgentPlanValidator`，并限制旧 `actions` 最多一个。
- 修改：`app/src/test/java/com/sightsync/assistant/ai/AiProtocolValidatorTest.kt`
  - 覆盖 `actions`/`plan` 互斥和旧单动作兼容。
- 修改：`backend/src/protocol.js`
  - 增加同构计划校验和深度净化；只读屏响应拒绝携带计划。
- 修改：`backend/test/protocol.test.js`
  - 先写失败测试，覆盖计划接受、越界拒绝、互斥、净化和只读屏安全。
- 修改：`backend/test/server.test.js`
  - 验证合法 provider 计划可经 `/v1/assist` 返回，但未知字段不会穿透。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 完成所有验证后记录 Phase 4 小片 1 的执行结果和小片 2 的位置。

## 协议与安全边界

计划 JSON 采用下列结构：

```json
{
  "spoken": "我会分步完成浏览器搜索。",
  "requiresConfirmation": false,
  "actions": [],
  "plan": {
    "goal": "在浏览器中搜索无障碍新闻",
    "maxDurationMillis": 30000,
    "maxConsecutiveFailures": 2,
    "steps": [
      {
        "id": "open_browser",
        "kind": "ACTION",
        "action": { "type": "OPEN_APP", "appPackage": "com.android.chrome" },
        "precondition": { "packageName": "com.android.launcher" },
        "timeoutMillis": 5000,
        "requiresConfirmation": false
      },
      {
        "id": "wait_browser",
        "kind": "WAIT_FOR_UI",
        "precondition": { "packageName": "com.android.chrome" },
        "timeoutMillis": 5000,
        "requiresConfirmation": false
      }
    ]
  }
}
```

硬规则：

- `actions` 与 `plan` 不能同时非空；旧 `actions` 最多一个，多步必须使用 `plan`。
- `plan` 存在时顶层 `requiresConfirmation` 必须为 `false`；二次确认只允许声明在具体 `ACTION` 步骤上，避免整计划确认绕过执行时上下文复核。
- 计划必须有 2–8 步；总时长为 1–60 秒；连续失败上限为 1–2 次。
- 每步 ID 非空且唯一；单步超时为 250–15000 ms；所有单步超时之和不能超过计划总时长。
- 步骤类型只允许 `ACTION`、`WAIT_FOR_UI`、`VALIDATE_PAGE`。
- 每步必须有至少一个页面前置条件：包名、Activity、节点 ID 或可见文本。
- `ACTION` 必须且只能携带现有白名单 `AssistantAction`；内部步骤不得携带动作，也不得声明二次确认。
- 后端只负责协议校验和字段净化；Android 仍是最终安全边界。
- 本小片不修改 Qwen prompt，不让 provider 主动生成计划，不新增动作类型，不实现等待、页面校验或计划执行。

## Task 1：后端协议 RED/GREEN

**Files:**
- Modify: `backend/test/protocol.test.js`
- Modify: `backend/src/protocol.js`

- [x] **Step 1：写合法计划和互斥规则的失败测试**

在 `backend/test/protocol.test.js` 导入 `sanitizeAssistResponse`，并增加：

```js
test('validateAssistResponse accepts a bounded multi-step plan and rejects mixed actions', () => {
  const response = boundedPlanResponse();

  assert.equal(validateAssistResponse(response).valid, true);
  assert.equal(validateAssistResponse({
    ...response,
    actions: [{ type: 'GLOBAL_BACK' }]
  }).valid, false);
});

test('validateAssistResponse requires legacy multi-action responses to use plan', () => {
  const result = validateAssistResponse({
    spoken: '我会连续操作。',
    requiresConfirmation: false,
    actions: [{ type: 'GLOBAL_BACK' }, { type: 'GLOBAL_HOME' }]
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'single-step response allows at most one action; use plan for multiple steps');
});
```

测试文件底部增加固定 fixture：

```js
function boundedPlanResponse() {
  return {
    spoken: '我会分步完成。',
    requiresConfirmation: false,
    actions: [],
    plan: {
      goal: '打开浏览器并等待页面稳定',
      maxDurationMillis: 10000,
      maxConsecutiveFailures: 2,
      steps: [
        {
          id: 'open_browser',
          kind: 'ACTION',
          action: { type: 'OPEN_APP', appPackage: 'com.android.chrome' },
          precondition: { packageName: 'com.android.launcher' },
          timeoutMillis: 5000,
          requiresConfirmation: false
        },
        {
          id: 'wait_browser',
          kind: 'WAIT_FOR_UI',
          precondition: { packageName: 'com.android.chrome' },
          timeoutMillis: 5000,
          requiresConfirmation: false
        }
      ]
    }
  };
}
```

- [x] **Step 2：运行后端目标测试，确认 RED**

Run:

```powershell
cd D:\project\backend
node --test test/protocol.test.js
```

Expected: 合法计划测试因当前 `validateAssistResponse` 不理解 `plan` 或多动作限制尚不存在而失败。

- [x] **Step 3：写越界、结构错误、净化和只读屏安全的失败测试**

增加参数化断言：超过 8 步、总时长超过 60000 ms、连续失败超过 2、重复 ID、超时和超过总时长、未知 kind、缺少前置条件、计划中的 `RUN_SCRIPT`、内部步骤夹带动作都必须失败。每个断言检查具体 `reason`，避免只验证布尔值。

```js
test('validateAssistResponse rejects unbounded or unsafe plan steps', () => {
  const base = boundedPlanResponse();
  const invalidCases = [
    [{ ...base, plan: { ...base.plan, steps: Array.from({ length: 9 }, (_, index) => ({
      ...base.plan.steps[0], id: `step_${index}`
    })) } }, 'plan supports 2 to 8 steps'],
    [{ ...base, plan: { ...base.plan, maxDurationMillis: 60001 } }, 'plan maxDurationMillis must be between 1000 and 60000'],
    [{ ...base, plan: { ...base.plan, maxConsecutiveFailures: 3 } }, 'plan maxConsecutiveFailures must be between 1 and 2'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step) => ({ ...step, id: 'duplicate' })) } }, 'plan step ids must be unique'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 0
      ? { ...step, action: { type: 'RUN_SCRIPT' } }
      : step) } }, 'unsupported action type: RUN_SCRIPT'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 0
      ? { ...step, precondition: {} }
      : step) } }, 'plan step open_browser requires a page precondition'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 1
      ? { ...step, action: { type: 'GLOBAL_BACK' } }
      : step) } }, 'WAIT_FOR_UI step wait_browser must not contain action']
  ];

  for (const [response, reason] of invalidCases) {
    assert.equal(validateAssistResponse(response).reason, reason);
  }
});
```

同一步增加测试，确认 plan/step/action 中的 `providerInternal` 等未知字段被 `sanitizeAssistResponse` 移除；`validateScreenReadingProviderResponse` 对任何非空 `plan` 返回 `screen reading response must not contain actions, plan, or confirmation`。

- [x] **Step 4：写 provider 计划经 HTTP 返回的失败测试**

在 `backend/test/server.test.js` 中复用 `POST /v1/assist calls Qwen provider and validates returned protocol` 的 server fixture，令 provider 返回合法 `plan`，断言 HTTP 200、顶层 `actions` 为空、`plan.steps` 为两步，并断言 provider 添加的未知字段未出现在响应中。

- [x] **Step 5：运行全部新增后端测试，确认 RED**

Run:

```powershell
cd D:\project\backend
node --test test/protocol.test.js test/server.test.js
```

Expected: 计划边界/互斥断言失败，且 HTTP 响应因当前 `sanitizeAssistResponse` 丢弃 `plan` 而失败；失败均来自协议尚未实现，不是 fixture 或语法错误。

- [x] **Step 6：实现后端最小校验和深度净化**

在 `backend/src/protocol.js` 中：

1. 将单个动作检查提取为 `validateAction(action)`，旧路径和计划路径共用。
2. 新增常量：`MAX_PLAN_STEPS = 8`、`MAX_PLAN_DURATION_MILLIS = 60000`、`MAX_PLAN_STEP_TIMEOUT_MILLIS = 15000`、`MAX_PLAN_CONSECUTIVE_FAILURES = 2`。
3. 新增 `validateAgentPlan(plan)` 与 `validatePageExpectation(expectation, stepId)`。
4. `validateAssistResponse` 强制顶层互斥、旧路径最多一个动作，并委托计划校验。
5. `sanitizeAssistResponse` 仅保留计划与步骤的已知字段；计划动作只保留 `type`、`nodeId`、`text`、`appPackage`。

核心分支必须等价于：

```js
if (response.plan != null && response.actions.length > 0) {
  return invalid('actions and plan are mutually exclusive');
}
if (response.plan == null && response.actions.length > 1) {
  return invalid('single-step response allows at most one action; use plan for multiple steps');
}
for (const action of response.actions) {
  const actionValidation = validateAction(action);
  if (!actionValidation.valid) return actionValidation;
}
if (response.plan != null) {
  const planValidation = validateAgentPlan(response.plan);
  if (!planValidation.valid) return planValidation;
}
```

页面条件有效性必须只接受非空 `packageName`、非空 `activityName`、至少一个非空 `requiredNodeIds` 或至少一个非空 `requiredTexts`；空数组和空白字符串不算条件。

- [x] **Step 7：确认后端 GREEN**

Run:

```powershell
cd D:\project\backend
node --test test/protocol.test.js test/server.test.js
```

Expected: 两个测试文件全部通过；server 不复制计划校验逻辑，只复用 `validateAssistResponse` 和 `sanitizeAssistResponse`。

## Task 2：Android 数据模型与校验器 RED/GREEN

**Files:**
- Create: `app/src/main/java/com/sightsync/assistant/ai/AgentPlan.kt`
- Create: `app/src/main/java/com/sightsync/assistant/ai/AgentPlanValidator.kt`
- Create: `app/src/test/java/com/sightsync/assistant/ai/AgentPlanValidatorTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/ai/Protocol.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/ai/AiProtocolValidatorTest.kt`

- [x] **Step 1：先写 Android JSON 解析和合法计划失败测试**

`AgentPlanValidatorTest.kt` 先按期望 API 使用：

```kotlin
@Test
fun parsesAndAcceptsBoundedPlan() {
    val response = json.decodeFromString<AssistResponse>(VALID_PLAN_JSON)

    assertEquals("打开浏览器并等待页面稳定", response.plan?.goal)
    assertEquals(2, response.plan?.steps?.size)
    assertTrue(AiProtocolValidator.validate(response).isValid)
}
```

`VALID_PLAN_JSON` 与“协议与安全边界”示例一致，且 `actions` 为空。

- [x] **Step 2：运行 Android 目标测试确认 RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.ai.AgentPlanValidatorTest"
```

Expected: 编译失败，原因是 `AssistResponse.plan`、`AgentPlan` 或校验器尚不存在。

- [x] **Step 3：新增可序列化模型**

`AgentPlan.kt` 定义：

```kotlin
@Serializable
data class AgentPlan(
    val goal: String,
    val maxDurationMillis: Long,
    val maxConsecutiveFailures: Int,
    val steps: List<AgentPlanStep>,
)

@Serializable
data class AgentPlanStep(
    val id: String,
    val kind: String,
    val action: AssistantAction? = null,
    val precondition: PageExpectation,
    val timeoutMillis: Long,
    val requiresConfirmation: Boolean,
)

@Serializable
data class PageExpectation(
    val packageName: String? = null,
    val activityName: String? = null,
    val requiredNodeIds: List<String> = emptyList(),
    val requiredTexts: List<String> = emptyList(),
)
```

`AssistResponse` 增加 `val plan: AgentPlan? = null`，保持旧 JSON 可解析。

- [x] **Step 4：写 Android 越界与互斥失败测试**

逐项覆盖：

- `actions` 与 `plan` 同时存在。
- 旧 `actions` 超过一个。
- 步数少于 2 或超过 8。
- 总时长、连续失败次数、单步超时越界。
- 单步超时之和超过计划总时长。
- 空白或重复 step ID。
- 未知 kind。
- 缺少页面前置条件。
- `ACTION` 缺少动作或携带 `RUN_SCRIPT`。
- `WAIT_FOR_UI` / `VALIDATE_PAGE` 夹带动作或要求二次确认。

每个测试断言与后端相同语义的中文 reason，确保 Android 是独立的最终校验边界。

- [x] **Step 5：实现 Android 最小校验器**

`AgentPlanValidator.kt` 只做纯数据校验，不依赖 Android framework。常量与后端一致；`AiProtocolValidator` 提取 `validateAction()` 供计划校验复用。`AiProtocolValidator.validate()` 的顺序为：spoken、互斥/旧动作数量、旧动作校验、计划校验。

计划校验成功只返回 `ProtocolValidationResult(true)`，不触发 `ActionRunner`。现有 `AssistantSessionManager` 仍只读取顶层 `actions`，而合法计划强制顶层 `actions` 为空，因此本小片不会执行任何计划步骤。

- [x] **Step 6：运行 Android 目标测试确认 GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.ai.AgentPlanValidatorTest" --tests "com.sightsync.assistant.ai.AiProtocolValidatorTest"
```

Expected: 两个测试类全部通过。

## Task 3：回归、构建与 Android 模拟器验证

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [x] **Step 1：后端全量回归**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 全量后端测试通过，且不使用真实 provider key。

- [x] **Step 2：Android 全量单元测试**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android 单元测试全部通过。

- [x] **Step 3：构建 debug APK 与差异检查**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: APK 构建成功，`git diff --check` 无错误。

- [x] **Step 4：本机 Android 模拟器安装与启动验证**

先用 `adb devices` 选择序列号以 `emulator-` 开头且状态为 `device` 的本机模拟器；只覆盖安装，不卸载、不清数据、不改权限：

```powershell
adb -s <emulator-serial> install -r D:\project\app\build\outputs\apk\debug\app-debug.apk
adb -s <emulator-serial> shell am start -n com.sightsync.assistant/.MainActivity
adb -s <emulator-serial> shell pidof com.sightsync.assistant
adb -s <emulator-serial> logcat -d -t 300 | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|com.sightsync.assistant"
```

Expected: 覆盖安装和启动成功，进程存在，最新日志无 SightSync 崩溃。由于本小片不启用多步执行，不在模拟器执行真实多步动作。

- [x] **Step 5：更新长期计划进度**

在 `SIGHTSYNC_LONG_TERM_PLAN.md` 的“当前进度”追加：

```markdown
- Phase 4 小片 1 已完成：新增与旧单动作互斥的受限多步 `plan` 协议，后端和 Android 共同校验 2–8 步、60 秒总时长、15 秒单步时长、最多 2 次连续失败、页面前置条件和白名单动作；provider prompt 与计划执行仍未启用，等待小片 2 实现内部等待和页面稳定校验。
```

仅在 Task 1–3 的测试、构建和模拟器验证均完成后标记该条；如模拟器不可用，记录具体阻塞且不宣称 Android 验证通过。

---

## 计划自检

- 规格覆盖：覆盖 Phase 4 小片 1 的计划协议、Android 最终校验、动作白名单、页面前置条件、步数/时间/失败边界；没有提前实现小片 2 的等待和页面稳定执行，也没有进入浏览器任务。
- 安全检查：计划不能与旧动作并行；计划内动作不会在本小片执行；只读屏响应禁止携带计划；高风险声明保留在步骤但后续执行器仍必须用 Android 上下文风控重新判定。
- 兼容检查：旧响应缺少 `plan` 时由 Kotlin 默认 `null`；现有单动作响应保持可用；旧多动作批处理被收紧为必须使用受限计划。
- 完整性检查：没有待补字段或未定义类型；模拟器序列号由运行时设备发现，不写死到实现中。
- 类型一致性：后端与 Kotlin 均使用 `goal`、`maxDurationMillis`、`maxConsecutiveFailures`、`steps`、`id`、`kind`、`action`、`precondition`、`timeoutMillis`、`requiresConfirmation` 以及同一组页面条件字段。
