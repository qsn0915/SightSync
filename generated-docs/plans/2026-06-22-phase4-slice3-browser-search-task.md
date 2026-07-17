# Phase 4 小片 3：浏览器搜索任务实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 SightSync Android App 中实现由明确语音发起、可取消、逐步校验的本地浏览器搜索任务：打开默认或唯一浏览器，唯一定位搜索/地址栏，输入查询，唯一定位搜索建议或提交控件并提交。

**Architecture:** 任务采用本地确定性编排，不启用后端 `plan`。`BrowserSearchCommandResolver` 只接受同时包含浏览器和搜索意图的明确命令，并复用现有本地应用目录解析默认浏览器；`BrowserSearchNodeLocator` 只根据验证快照中的可编辑性、可点击性、文本/描述/输入上下文和父子关系选出唯一控件；`BrowserSearchTaskRunner` 在每个动态定位点重新采集无截图快照，并用现有 `AgentPlanExecutor` 执行两步小计划。`AssistantSessionManager` 在远端 AI 和普通打开应用之前处理该命令，使整个任务继续处于同一个半双工 turn 和同一可取消协程中。

**Tech Stack:** Kotlin、kotlinx.coroutines、JUnit 4、kotlinx-coroutines-test、现有 `OpenAppCommandResolver`、`AgentPlanExecutor`、`PageStabilityWaiter`、`ScreenContextProvider`、`ActionRunner` 和 `VoiceTurnCoordinator`。

---

## 文件范围

- 新增：`app/src/main/java/com/sightsync/assistant/apps/BrowserSearchCommandResolver.kt`
  - 解析明确浏览器搜索语音；通过现有本地打开应用解析器取得浏览器包名，不上传应用列表。
- 新增：`app/src/test/java/com/sightsync/assistant/apps/BrowserSearchCommandResolverTest.kt`
  - 覆盖明确命令、空查询、普通环境语音、默认浏览器和浏览器候选不唯一。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/BrowserSearchNodeLocator.kt`
  - 纯 Kotlin 唯一定位地址栏和搜索提交目标；候选缺失或不唯一时返回明确结果。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/BrowserSearchNodeLocatorTest.kt`
  - 覆盖直接可编辑地址栏、可点击地址栏、父级可点击搜索建议、通用提交按钮和歧义拒绝。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/BrowserSearchTaskRunner.kt`
  - 定义 Session 依赖的窄 `BrowserSearchTaskExecutor` 接口；真实 runner 在 60 秒总时限内动态构造最多四个“动作 + 等待/校验”两步计划，并调用 `AgentPlanExecutor`。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/BrowserSearchTaskRunnerTest.kt`
  - 覆盖成功任务、直接可编辑分支、定位失败、歧义、动作失败、页面变化、确认拦截、总超时和外部取消。
- 修改：`app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
  - 在普通打开应用和远端 AI 之前处理浏览器搜索；播报意图、成功或停止原因；取消时不吞掉 `CancellationException`。
- 修改：`app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
  - 复用同一 `PackageManagerAppCatalogProvider`、`ScreenContextCollector` 和 `ActionExecutor` 装配本地浏览器任务。
- 修改：`app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
  - 覆盖显式浏览器搜索不调用 AI、同 turn 顺序和失败播报。
- 修改：`app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
  - 更新生产装配源码断言。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 全部验证通过后记录小片 3；不进入小片 4。

## 行为与安全边界

- 只接受明确包含“浏览器/默认浏览器”与“搜索/查找/查询”的命令，例如“用浏览器搜索无障碍新闻”“打开浏览器，查找天气”。“今天天气不错”“搜索一下”或普通“打开浏览器”不进入本任务。
- 查询文本去除首尾空白和句末标点后必须非空，最长 200 个 Unicode 字符；超长或空查询只播报失败，不打开浏览器。
- 浏览器选择复用现有本地应用目录：优先默认浏览器；没有默认且不是唯一候选时追问/停止，不猜测，不把安装列表上传后端。
- 任务只使用既有白名单 `OPEN_APP`、`CLICK_NODE`、`SET_TEXT` 和已批准的等待/页面校验；不新增键盘 Enter、坐标点击、URL deep link、任意 Intent 或脚本动作。
- 验证和定位只调用 `collectForValidation()`，不得截图、不得调用后端 AI。
- 地址栏候选必须带“搜索/地址/网址/search/address/URL”等语义且唯一；优先可编辑候选，否则选择唯一可点击候选。候选文本节点可以上溯到最近的可点击父节点。
- 提交目标优先选择与查询文本精确匹配的唯一可点击搜索建议，其次选择带查询和搜索语义的唯一建议，最后选择唯一的“搜索/前往/Go/Search”提交控件；多个同优先级候选一律停止。
- 每个动作前置条件至少包含当前包名和目标 node ID（`OPEN_APP` 使用打开前当前包名）；动作后由 `AgentPlanExecutor` 等待稳定并校验浏览器包名。
- 动态任务最多四个动作，每个动作配一个等待/校验步骤，总计不超过八个协议步骤；全任务总时限 60 秒，第一次失败立即停止，不自动重试点击或提交。
- `AgentPlanExecutionResult.ConfirmationRequired` 不在本小片自动继续，统一停止并提示需要确认；高风险上下文仍由 Android `RiskClassifier` 拦截。
- 浮窗/通知停止或一次唤起取消会取消承载任务的协程；外部 `CancellationException` 必须向上传播，后续动作不得执行。
- 远端 `AssistResponse.plan` 仍不接入 `AssistantSessionManager`，本小片仅启用明确的本地浏览器搜索任务。

## Task 1：明确命令与本地浏览器解析 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/apps/BrowserSearchCommandResolverTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/apps/BrowserSearchCommandResolver.kt`

- [x] **Step 1：写失败测试**

测试期望 API：

```kotlin
sealed interface BrowserSearchCommandResult {
    data class Resolved(
        val query: String,
        val browserPackage: String,
    ) : BrowserSearchCommandResult
    data class Unavailable(val spoken: String) : BrowserSearchCommandResult
    data object NotBrowserSearchCommand : BrowserSearchCommandResult
}

class BrowserSearchCommandResolver(
    private val openAppCommandResolver: OpenAppCommandResolver,
) {
    fun resolve(utterance: String): BrowserSearchCommandResult
}
```

覆盖：

```kotlin
assertEquals(
    BrowserSearchCommandResult.Resolved("无障碍新闻", "com.android.chrome"),
    resolver(defaultChrome).resolve("请用浏览器搜索无障碍新闻。"),
)
assertEquals(
    BrowserSearchCommandResult.NotBrowserSearchCommand,
    resolver(defaultChrome).resolve("今天天气不错。"),
)
assertTrue(resolver(defaultChrome).resolve("打开浏览器搜索。") is BrowserSearchCommandResult.Unavailable)
assertTrue(resolver(noDefaultTwoBrowsers).resolve("用浏览器搜索天气") is BrowserSearchCommandResult.Unavailable)
```

- [x] **Step 2：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.apps.BrowserSearchCommandResolverTest"
```

Expected: 编译失败，原因是浏览器搜索解析器和结果类型不存在。

- [x] **Step 3：实现最小解析器**

使用锚定正则，只接受明确浏览器搜索语序：

```kotlin
private val commandPattern = Regex(
    """^(?:请|麻烦你|帮我|你帮我|请帮我)?\\s*(?:用|使用|打开)?\\s*(默认)?\\s*浏览器[，, ]*(?:搜索|查找|查询)(?:一下)?[：:]?\\s*(.*)$""",
    RegexOption.IGNORE_CASE,
)
```

查询清理后为空或长度大于 200 返回 `Unavailable`。浏览器通过 `openAppCommandResolver.resolve("打开浏览器")` 取得；仅接受单个 `OPEN_APP` 动作及非空包名。`Ambiguous`、`Alternatives`、`NoMatch` 映射为其现有安全播报，`NotOpenAppCommand` 映射为“没有找到可用的浏览器。”；不从播报文案反向解析应用名称。

- [x] **Step 4：运行测试确认 GREEN**

运行 Task 1 目标测试，Expected: 全部通过。

## Task 2：地址栏与提交控件唯一定位 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/BrowserSearchNodeLocatorTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/BrowserSearchNodeLocator.kt`

- [x] **Step 1：写地址栏定位失败测试**

期望结果：

```kotlin
sealed interface BrowserNodeLookup {
    data class Found(val node: ScreenNode) : BrowserNodeLookup
    data class Missing(val reason: String) : BrowserNodeLookup
    data class Ambiguous(val reason: String) : BrowserNodeLookup
}
```

测试唯一可编辑 `EditText(contentDescription="Search or type web address")` 返回 `Found`；没有可编辑候选时，唯一 `clickable` 的“搜索或输入网址”返回 `Found`；两个同级候选返回 `Ambiguous`；无语义的普通输入框返回 `Missing`。

- [x] **Step 2：写提交定位失败测试**

覆盖：

- 直接 `clickable` 且文本精确等于查询的建议；
- 文本子节点精确等于查询、父节点可点击时返回父节点；
- 唯一“Search/Go/搜索/前往”按钮；
- 两个同优先级精确建议返回 `Ambiguous`；
- 地址栏本身即使含查询也因 `editable=true` 被排除。

- [x] **Step 3：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.BrowserSearchNodeLocatorTest"
```

Expected: 编译失败，原因是 locator 和结果类型不存在。

- [x] **Step 4：实现纯 Kotlin 唯一定位器**

`BrowserSearchNodeLocator` 暴露：

```kotlin
object BrowserSearchNodeLocator {
    fun findAddressOrSearchField(screen: ScreenContext): BrowserNodeLookup
    fun findSubmitTarget(screen: ScreenContext, query: String): BrowserNodeLookup
}
```

节点语义由 `text`、`contentDescription`、`inputContext` 和 `role` 归一化后计算。辅助函数通过 `parentNodeId` 最多沿现有节点图上溯到最近可点击父节点；发现循环或缺失父节点即停止上溯。每个优先级先去重 `nodeId`，只在最高非空优先级恰好一个候选时返回 `Found`。

- [x] **Step 5：运行测试确认 GREEN**

运行 Task 2 目标测试，Expected: 所有唯一、缺失和歧义场景通过。

## Task 3：动态受限任务执行 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/BrowserSearchTaskRunnerTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/BrowserSearchTaskRunner.kt`

- [x] **Step 1：写成功与直接可编辑分支失败测试**

期望 API：

```kotlin
sealed interface BrowserSearchTaskResult {
    data object Completed : BrowserSearchTaskResult
    data class Stopped(val reason: String) : BrowserSearchTaskResult
}

fun interface BrowserSearchTaskExecutor {
    suspend fun execute(browserPackage: String, query: String): BrowserSearchTaskResult
}

class BrowserSearchTaskRunner(
    private val screenContextProvider: ScreenContextProvider,
    private val planExecutor: AgentPlanExecutor,
) : BrowserSearchTaskExecutor {
    suspend fun execute(browserPackage: String, query: String): BrowserSearchTaskResult
}
```

队列式 fake 页面覆盖：当前 App → Chrome 稳定主页 → 可点击地址栏 → Chrome 编辑态 → 可编辑输入框 → 查询建议 → 结果页。记录 `ActionRunner`，断言顺序严格为 `OPEN_APP`、`CLICK_NODE`、`SET_TEXT`、`CLICK_NODE`，每次只执行一个动作。另测主页地址栏已可编辑时跳过第一次 `CLICK_NODE`，总动作变为三个。

- [x] **Step 2：写中断、超时和取消失败测试**

分别覆盖地址栏缺失/歧义、提交目标缺失/歧义、动作失败、浏览器包名变化、`ConfirmationRequired`、60 秒总超时。外部取消使用 `async` 启动任务并取消，断言 `await()` 抛 `CancellationException` 且取消后没有后续动作。

- [x] **Step 3：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.BrowserSearchTaskRunnerTest"
```

Expected: 编译失败，原因是 runner 和任务结果不存在。

- [x] **Step 4：实现动态 runner**

`execute` 用 `withTimeoutOrNull(60_000)` 包裹整个流程，但不捕获外部 `CancellationException`。执行顺序：

1. 采集打开前验证快照；构造 `OPEN_APP + WAIT_FOR_UI(browserPackage)` 两步计划。
2. 采集浏览器验证快照并定位地址栏；若非 editable，构造 `CLICK_NODE + VALIDATE_PAGE(browserPackage)` 两步计划（ACTION 已等待稳定，VALIDATE 立即拒绝跨包变化），再重新采集和重新定位，要求此时为 editable。
3. 构造 `SET_TEXT + VALIDATE_PAGE(browserPackage)` 两步计划。
4. 重新采集并定位提交目标；构造 `CLICK_NODE + WAIT_FOR_UI(browserPackage)` 两步计划。

每个计划 `maxConsecutiveFailures=1`；动作 step 前置条件为包名和 node ID；`OPEN_APP` 前置条件为打开前包名；单步 timeout 在 2–8 秒，总和不超过各子计划 `maxDurationMillis`。将 `AgentPlanExecutionResult.Stopped/Invalid/ConfirmationRequired` 映射为 `BrowserSearchTaskResult.Stopped`，首个非完成结果立即返回。

- [x] **Step 5：运行测试确认 GREEN**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.BrowserSearchTaskRunnerTest" --tests "com.sightsync.assistant.accessibility.AgentPlanExecutorTest"
```

Expected: 任务 runner 与既有执行器测试全部通过。

## Task 4：会话与生产装配 RED/GREEN

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`

- [x] **Step 1：写会话失败测试**

为 SessionManager 注入可空 `BrowserSearchCommandResolver` 和 `BrowserSearchTaskExecutor`。测试明确命令“用浏览器搜索无障碍新闻”时：

- AI client 调用次数为 0；
- 普通屏幕 `collect()` 调用次数为 0；
- TTS 先包含“我会用浏览器搜索无障碍新闻。”，任务完成后包含“搜索已提交。”；
- runner 只收到 `com.android.chrome` 和查询文本；
- runner 返回 `Stopped("找不到唯一的搜索地址栏。")` 时播报该原因，不回落 AI；
- 普通“打开浏览器”仍走原有打开应用路径；普通环境语音仍由连续命令门处理。

- [x] **Step 2：写生产装配失败测试**

更新源码断言，要求服务复用同一实例：

```kotlin
val appCatalogProvider = PackageManagerAppCatalogProvider(this)
val openAppCommandResolver = OpenAppCommandResolver(appCatalogProvider)
val screenContextProvider = ScreenContextCollector(this)
val actionRunner = ActionExecutor(this)
```

并装配 `BrowserSearchCommandResolver(openAppCommandResolver)`、`AgentPlanExecutor(screenContextProvider, actionRunner)`、`BrowserSearchTaskRunner(...)`；Session 构造参数使用窄接口类型。

- [x] **Step 3：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test" --tests "com.sightsync.assistant.accessibility.AssistantAccessibilityServiceSourceTest"
```

Expected: 编译或断言失败，因为会话依赖和生产装配尚未增加。

- [x] **Step 4：实现会话处理和服务装配**

`runAssistantTurn` 在 `handleLocalOpenAppCommand` 前调用 `handleLocalBrowserSearchCommand`。处理 `Resolved` 时清空打开应用候选、播报明确意图、设为 `Acting`、执行 runner，并按结果播报成功或停止原因；`Unavailable` 直接播报并结束当前 turn；`NotBrowserSearchCommand` 返回 false。不得在该处理器捕获 `CancellationException`。

服务只创建一份 app catalog、open-app resolver、screen provider 和 action runner，并把同一对象传给 SessionManager、计划执行器和浏览器任务 runner，避免快照与动作执行不一致。

- [x] **Step 5：运行测试确认 GREEN**

运行 Task 4 目标测试，Expected: 新会话场景与原有打开应用/连续聆听回归全部通过。

## Task 5：全量验证、模拟器验收和进度更新

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`
- Modify: `generated-docs/plans/2026-06-22-phase4-slice3-browser-search-task.md`

- [x] **Step 1：运行后端回归**

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 后端全量测试通过；本小片没有后端生产改动。

- [x] **Step 2：运行 Android 全量测试**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android 全量单元测试通过。

- [x] **Step 3：构建与差异检查**

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: debug APK 构建成功，差异检查无错误。

- [x] **Step 4：模拟器覆盖安装、冷启动和浏览器任务验收**

在 `emulator-5554` 只做覆盖安装，不卸载、不清 SightSync 数据：

```powershell
$adb='D:\AndroidDev\Sdk\platform-tools\adb.exe'
$serial='emulator-5554'
& $adb -s $serial install -r D:\project\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial shell am force-stop com.sightsync.assistant
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n com.sightsync.assistant/.MainActivity
```

验证进程存在、`MainActivity` resumed、无 `FATAL EXCEPTION`/SightSync ANR。然后在不接受第三方条款、不清浏览器数据、不绕过 Android 权限的前提下运行明确浏览器搜索命令，检查日志中的动作顺序和最终浏览器页面。当前模拟器 Chrome 若仍停在首次运行条款页，则记录为外部环境阻塞：代码小片只能以自动化测试、浏览器已打开且安全停止、无后续误操作作为结果，不替用户接受 Chrome 条款。

- [x] **Step 5：更新长期进度和计划勾选**

仅在目标测试、全量测试、构建、差异检查和 Android 验证完成后，在长期计划追加小片 3 记录，包含是否完成端到端搜索以及 Chrome 首次运行页阻塞。将本计划实际执行步骤勾为 `[x]`；不开始 Phase 4 小片 4。

---

## 计划自检

- 规格覆盖：任务由明确语音发起；打开、定位、输入和提交均有对应动作/验证；页面变化、歧义、超时、风险和取消会停止后续步骤。
- 动作边界：只复用 `OPEN_APP`、`CLICK_NODE`、`SET_TEXT`；没有新增白名单动作、URL deep link、坐标或键盘事件。
- 隐私检查：应用目录仅本地使用；所有动态定位调用 `collectForValidation()`，不截图、不上传后端。
- 语音检查：任务在同一 `runAssistantTurn` 协程内，意图和结果都通过可等待 TTS 播报，结束后才进入下一轮录音。
- TDD 检查：每个新生产组件都在实现前有独立 RED，目标 GREEN 后才进入下一任务；最后运行全量回归。
- 类型一致性：任务使用现有 `AgentPlan`、`AgentPlanStep`、`PageExpectation`、`AgentPlanExecutionResult`、`ScreenContextProvider` 和 `ActionRunner`，不改线上 JSON 协议。
- 范围检查：不启用远端计划，不实现通用 App 导航、候选消歧框架、微信草稿或 Phase 4 验收。
- 环境检查：模拟器当前默认浏览器为 `com.android.chrome`，且诊断时处于首次运行条款页；计划明确禁止代替用户接受条款，并允许如实记录该外部阻塞。
