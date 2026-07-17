# Phase 4 小片 2：内部等待与页面稳定校验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为小片 1 的受限多步计划增加不截图的页面条件匹配、稳定等待和可取消的逐步执行基础，使页面变化、等待超时、动作失败或高风险步骤在继续前可靠停止。

**Architecture:** Android 端新增三个纯 Kotlin 边界：`PageExpectationMatcher` 只匹配包名、Activity、节点 ID 和可见文本；`PageStabilityWaiter` 轮询轻量页面快照，连续两次页面指纹一致才返回稳定；`AgentPlanExecutor` 逐步解释 `ACTION`、`WAIT_FOR_UI`、`VALIDATE_PAGE`，受单步/总时长和协程取消约束。真实 `ScreenContextCollector` 新增验证专用采集入口，复用节点树但禁止截图。本小片不修改后端或 Qwen prompt，不把执行器接入 `AssistantSessionManager`，因此不会在用户场景中提前启用远端多步执行。

**Tech Stack:** Kotlin、kotlinx.coroutines、kotlinx-coroutines-test、JUnit 4、现有 `ScreenContextProvider`、`ActionRunner`、`RiskClassifier` 和小片 1 `AgentPlan` 协议。

---

## 文件范围

- 修改：`app/src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt`
  - `ScreenContextProvider` 增加默认 `collectForValidation()`。
  - 真实 collector 覆盖该方法，采集节点树但禁止截图。
- 修改：`app/src/test/java/com/sightsync/assistant/core/ScreenContextCollectorTest.kt`
  - 覆盖稀疏页面的验证采集不会调用截图 provider。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/PageExpectationMatcher.kt`
  - 纯函数匹配包名、Activity、节点 ID 和可见文本。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/PageExpectationMatcherTest.kt`
  - 覆盖全部条件和明确的失败原因。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/PageStabilityWaiter.kt`
  - 使用验证快照轮询；连续两次指纹一致后返回稳定。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/PageStabilityWaiterTest.kt`
  - 覆盖瞬态页面、条件不匹配、超时和外部取消。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/AgentPlanExecutor.kt`
  - 逐步解释已校验计划，执行单个白名单动作，处理内部等待/校验和终止结果。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/AgentPlanExecutorTest.kt`
  - 覆盖成功顺序、页面变化、超时、动作失败、高风险/确认拦截和协程取消。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 全部验证完成后记录小片 2，保留小片 3 为下一小片。

## 行为和安全边界

- 验证采集只读取包名、Activity 和无障碍节点树；不得调用 `takeScreenshot()`，不得向后端上传新数据。
- 页面条件全部采用 AND 语义；包名和 Activity 精确匹配，节点 ID 必须存在，可见文本在 `text`、`contentDescription` 或 `inputContext` 中做去空白后的不区分大小写包含匹配。
- 页面指纹只包含包名、Activity 和完整节点列表；忽略截图及截图策略，防止图像变化影响稳定判断。
- 稳定要求连续两次相同指纹，默认轮询间隔 200 ms；不使用固定“等几秒后直接继续”。
- `ACTION` 执行前必须匹配当前步骤前置条件并重新做 Android 本地风控；动作成功后必须等任意页面达到稳定状态。
- `WAIT_FOR_UI` 等待“条件匹配且页面稳定”；`VALIDATE_PAGE` 只做一次即时条件校验。
- 页面不匹配、等待超时、动作失败、上下文高风险或需要二次确认时，立即返回终止结果，不执行后续步骤；静态计划比 `maxConsecutiveFailures` 更严格，首次失败即停止，避免重复点击或重复提交。
- `withTimeoutOrNull` 只处理计划自身超时；外部 `CancellationException` 必须继续向上传播，使浮窗/通知停止入口未来可以中断等待和后续步骤。
- 本小片不创建 `AgentPlanExecutor` 的生产实例，不修改 `AssistantSessionManager`、`AssistantAccessibilityService`、Qwen prompt 或后端协议，不执行真实多步任务。

## Task 1：验证专用无截图采集 RED/GREEN

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenContextCollectorTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt`

- [x] **Step 1：写失败测试**

在 `ScreenContextCollectorTest.kt` 增加：

```kotlin
@Test
fun validationCollectionNeverRequestsScreenshotForSparsePage() = runTest {
    var screenshotCalls = 0
    val assembler = ScreenContextAssembler(
        nodeTreeExtractor = ScreenNodeTreeExtractor(),
        screenshotProvider = ScreenshotProvider {
            screenshotCalls += 1
            "must-not-be-used"
        },
    )
    val root = ScreenNodeSnapshot(
        children = listOf(
            ScreenNodeSnapshot(
                className = "android.webkit.WebView",
                scrollable = true,
            ),
        ),
    )

    val context = assembler.collectFrom(
        packageName = "com.android.browser",
        activityName = "Browser",
        root = root,
        allowScreenshot = false,
    )

    assertNull(context.screenshotBase64)
    assertEquals(0, screenshotCalls)
    assertEquals(1, context.nodes.size)
}
```

- [x] **Step 2：运行测试确认 RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.core.ScreenContextCollectorTest"
```

Expected: 编译失败，原因是 `collectFrom` 尚无 `allowScreenshot` 参数。

- [x] **Step 3：最小实现无截图采集**

将接口扩展为必须显式实现的验证方法，避免默认实现先触发普通采集中的截图再丢弃图片：

```kotlin
interface ScreenContextProvider {
    suspend fun collect(): ScreenContext
    suspend fun collectForValidation(): ScreenContext
}
```

`ScreenContextCollector` 将窗口读取抽成私有 `collect(allowScreenshot: Boolean)`；`collect()` 传 `true`，`collectForValidation()` 传 `false`。现有测试 fake 显式实现 `collectForValidation() = collect()`，因为它们不访问 Android 截图 API。`ScreenContextAssembler.collectFrom` 新增 `allowScreenshot: Boolean = true`，截图分支改为：

```kotlin
val screenshot = if (allowScreenshot && screenshotPolicy.attachScreenshot) {
    screenshotProvider.takeScreenshotBase64()
} else {
    null
}
```

- [x] **Step 4：运行测试确认 GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.core.ScreenContextCollectorTest"
```

Expected: `ScreenContextCollectorTest` 全部通过，原有 AI 理解采集仍会在稀疏页面请求截图，验证采集不会。

## Task 2：页面条件匹配器 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/PageExpectationMatcherTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/PageExpectationMatcher.kt`

- [x] **Step 1：写包名、节点和文本匹配失败测试**

测试按期望 API 使用：

```kotlin
@Test
fun matchesAllDeclaredPageConditions() {
    val screen = screenContext(
        packageName = "com.android.browser",
        activityName = "BrowserActivity",
        nodes = listOf(
            node("node_1", text = "搜索网页"),
            node("node_2", contentDescription = "提交搜索"),
        ),
    )
    val expectation = PageExpectation(
        packageName = "com.android.browser",
        activityName = "BrowserActivity",
        requiredNodeIds = listOf("node_1", "node_2"),
        requiredTexts = listOf("搜索", "提交"),
    )

    assertEquals(PageExpectationMatch.Matched, PageExpectationMatcher.match(expectation, screen))
}

@Test
fun reportsFirstMismatchedCondition() {
    val result = PageExpectationMatcher.match(
        PageExpectation(packageName = "com.expected"),
        screenContext(packageName = "com.actual"),
    )

    assertEquals(PageExpectationMatch.Mismatch("当前应用与计划不一致。"), result)
}
```

另加独立测试覆盖 Activity、缺失 node ID、缺失 visible text；测试 helper 构造完整 `NodeBounds`，不依赖 Android framework。

- [x] **Step 2：运行测试确认 RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.PageExpectationMatcherTest"
```

Expected: 编译失败，原因是 matcher 和结果类型不存在。

- [x] **Step 3：实现纯 Kotlin matcher**

`PageExpectationMatcher.kt` 定义：

```kotlin
sealed interface PageExpectationMatch {
    data object Matched : PageExpectationMatch
    data class Mismatch(val reason: String) : PageExpectationMatch
}

object PageExpectationMatcher {
    fun match(expectation: PageExpectation, screen: ScreenContext): PageExpectationMatch {
        if (!expectation.packageName.isNullOrBlank() && expectation.packageName != screen.packageName) {
            return PageExpectationMatch.Mismatch("当前应用与计划不一致。")
        }
        if (!expectation.activityName.isNullOrBlank() && expectation.activityName != screen.activityName) {
            return PageExpectationMatch.Mismatch("当前页面与计划不一致。")
        }
        val nodeIds = screen.nodes.mapTo(mutableSetOf()) { it.nodeId }
        if (expectation.requiredNodeIds.any { it !in nodeIds }) {
            return PageExpectationMatch.Mismatch("页面缺少计划要求的控件。")
        }
        val visibleValues = screen.nodes.flatMap { node ->
            listOfNotNull(node.text, node.contentDescription, node.inputContext)
        }
        if (expectation.requiredTexts.any { required ->
                visibleValues.none { visible -> visible.contains(required.trim(), ignoreCase = true) }
            }
        ) {
            return PageExpectationMatch.Mismatch("页面缺少计划要求的文字。")
        }
        return PageExpectationMatch.Matched
    }
}
```

- [x] **Step 4：运行测试确认 GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.PageExpectationMatcherTest"
```

Expected: matcher 测试全部通过。

## Task 3：页面稳定等待器 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/PageStabilityWaiterTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/PageStabilityWaiter.kt`

- [x] **Step 1：写稳定、超时和取消失败测试**

使用 `runTest`、`StandardTestDispatcher` 和队列式 `ScreenContextProvider`，覆盖：

```kotlin
@Test
fun returnsOnlyAfterTwoConsecutiveMatchingFingerprints() = runTest {
    val transient = screenContext(packageName = "com.browser", text = "加载中")
    val stable = screenContext(packageName = "com.browser", text = "搜索")
    val provider = QueueScreenProvider(transient, stable, stable)
    val waiter = PageStabilityWaiter(provider, pollIntervalMillis = 100)

    val result = waiter.awaitStable(
        expectation = PageExpectation(packageName = "com.browser", requiredTexts = listOf("搜索")),
        timeoutMillis = 1_000,
    )

    assertEquals(PageStabilityResult.Stable(stable), result)
    assertEquals(3, provider.collectForValidationCount)
}
```

超时测试让 provider 始终交替返回两个不同指纹，期望 `PageStabilityResult.TimedOut`；取消测试启动 `async { waiter.awaitStable(...) }` 后取消 deferred，断言 `await()` 抛出 `CancellationException`。

- [x] **Step 2：运行测试确认 RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.PageStabilityWaiterTest"
```

Expected: 编译失败，原因是 waiter 和结果类型不存在。

- [x] **Step 3：实现稳定轮询**

`PageStabilityWaiter.kt` 定义：

```kotlin
sealed interface PageStabilityResult {
    data class Stable(val screen: ScreenContext) : PageStabilityResult
    data object TimedOut : PageStabilityResult
}

class PageStabilityWaiter(
    private val screenContextProvider: ScreenContextProvider,
    private val pollIntervalMillis: Long = 200L,
) {
    suspend fun awaitStable(
        expectation: PageExpectation?,
        timeoutMillis: Long,
    ): PageStabilityResult = withTimeoutOrNull(timeoutMillis) {
        var previousFingerprint: PageFingerprint? = null
        while (true) {
            val screen = screenContextProvider.collectForValidation()
            val matches = expectation == null ||
                PageExpectationMatcher.match(expectation, screen) == PageExpectationMatch.Matched
            val fingerprint = screen.toFingerprint()
            if (matches && fingerprint == previousFingerprint) {
                return@withTimeoutOrNull PageStabilityResult.Stable(screen)
            }
            previousFingerprint = fingerprint.takeIf { matches }
            delay(pollIntervalMillis)
        }
        @Suppress("UNREACHABLE_CODE")
        PageStabilityResult.TimedOut
    } ?: PageStabilityResult.TimedOut
}

private data class PageFingerprint(
    val packageName: String,
    val activityName: String?,
    val nodes: List<ScreenNode>,
)
```

`toFingerprint()` 不读取 `screenshotBase64` 或 `screenshotPolicy`。

- [x] **Step 4：运行测试确认 GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.PageStabilityWaiterTest"
```

Expected: 稳定、超时、取消测试全部通过；取消不是 `TimedOut`。

## Task 4：受限计划执行器 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/AgentPlanExecutorTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/AgentPlanExecutor.kt`

- [x] **Step 1：写成功顺序和页面变化失败测试**

期望 API：

```kotlin
sealed interface AgentPlanExecutionResult {
    data class Completed(val completedStepIds: List<String>) : AgentPlanExecutionResult
    data class Stopped(
        val completedStepIds: List<String>,
        val failedStepId: String?,
        val reason: String,
    ) : AgentPlanExecutionResult
    data class ConfirmationRequired(
        val completedStepIds: List<String>,
        val step: AgentPlanStep,
        val sourceScreen: ScreenContext,
    ) : AgentPlanExecutionResult
    data class Invalid(val reason: String) : AgentPlanExecutionResult
}
```

成功测试创建两步计划：`ACTION(GLOBAL_BACK)` 后 `VALIDATE_PAGE`；fake provider 依次返回动作前页面、动作后瞬态页面、两次相同稳定页面、校验页面，断言动作只执行一次、sourceScreen 是动作前页面、结果包含两个 step ID。

页面变化测试让第一步 precondition 包名不匹配，断言 `Stopped`、`ActionRunner` 零调用且后续步骤未执行。

- [x] **Step 2：写等待、失败、高风险、确认和取消失败测试**

分别覆盖：

- `WAIT_FOR_UI` 在条件匹配且稳定后完成。
- 单步超时返回 `Stopped(..., "等待页面稳定超时，已停止后续操作。")`。
- `ActionResult(success = false)` 返回其消息并停止。
- `RiskClassifier.shouldRejectActionsInContext` 命中支付/密码/验证码上下文时停止。
- step 自带 `requiresConfirmation` 或本地 `RiskClassifier.requiresConfirmation` 命中时返回 `ConfirmationRequired`，动作零调用。
- 执行期间外部取消时抛出 `CancellationException`，后续动作零调用。

- [x] **Step 3：运行测试确认 RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AgentPlanExecutorTest"
```

Expected: 编译失败，原因是执行器与结果类型不存在。

- [x] **Step 4：实现最小执行器**

`AgentPlanExecutor` 构造参数只包含 `ScreenContextProvider`、`ActionRunner` 和默认复用同一 provider 的 `PageStabilityWaiter`。`execute(plan)` 先调用 `AgentPlanValidator.validate`，再用 `withTimeoutOrNull(plan.maxDurationMillis)` 包裹 step 循环；每一步再用 `withTimeoutOrNull(step.timeoutMillis)`。

步骤语义：

```kotlin
private suspend fun executeStep(plan: AgentPlan, step: AgentPlanStep): StepResult = when (step.kind) {
    "ACTION" -> executeAction(plan, step)
    "WAIT_FOR_UI" -> when (stabilityWaiter.awaitStable(step.precondition, step.timeoutMillis)) {
        is PageStabilityResult.Stable -> StepResult.Completed
        PageStabilityResult.TimedOut -> StepResult.Stopped("等待页面稳定超时，已停止后续操作。")
    }
    "VALIDATE_PAGE" -> {
        val screen = screenContextProvider.collectForValidation()
        when (val match = PageExpectationMatcher.match(step.precondition, screen)) {
            PageExpectationMatch.Matched -> StepResult.Completed
            is PageExpectationMatch.Mismatch -> StepResult.Stopped(match.reason)
        }
    }
    else -> StepResult.Stopped("不支持的计划步骤。")
}
```

`executeAction` 的固定顺序：采集验证快照 → 匹配 precondition → 上下文拒绝 → 二次确认判定 → `actionRunner.execute(listOf(action), false, sourceScreen)` → 检查失败结果 → `awaitStable(null, step.timeoutMillis)`。任何终止结果都携带已完成 step ID，不重试动作。

- [x] **Step 5：运行测试确认 GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AgentPlanExecutorTest" --tests "com.sightsync.assistant.accessibility.PageStabilityWaiterTest" --tests "com.sightsync.assistant.accessibility.PageExpectationMatcherTest"
```

Expected: 执行器和页面稳定目标测试全部通过。

## Task 5：回归、构建和 Android 模拟器验证

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [x] **Step 1：运行后端回归**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 后端全量测试通过；本小片没有后端生产改动。

- [x] **Step 2：运行 Android 全量单元测试**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android 全量单元测试通过。

- [x] **Step 3：构建和差异检查**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: debug APK 构建成功，差异检查无错误。

- [x] **Step 4：模拟器覆盖安装和冷启动**

复用已配置的 `SightSyncApi35` AVD；只执行覆盖安装、force-stop、冷启动和日志检查，不卸载、不清数据、不改权限：

```powershell
$adb='D:\AndroidDev\Sdk\platform-tools\adb.exe'
$serial='emulator-5554'
& $adb -s $serial install -r D:\project\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial shell am force-stop com.sightsync.assistant
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n com.sightsync.assistant/.MainActivity
```

验证 App 进程存在、`MainActivity` 为 `mResumed=true`，最近日志无 `FATAL EXCEPTION`、SightSync `Process:` 或 ANR。由于执行器尚未接入会话，本小片不在模拟器伪造或执行多步任务。

- [x] **Step 5：更新长期计划和计划勾选**

验证全部完成后，在 `SIGHTSYNC_LONG_TERM_PLAN.md` 当前进度追加：

```markdown
- Phase 4 小片 2 已完成：新增无截图页面验证采集、页面条件匹配、连续两次指纹一致的稳定等待和可取消的受限计划执行器；页面变化、等待超时、动作失败、高风险或待确认步骤会停止后续执行。执行器尚未接入 SessionManager，等待小片 3 以明确浏览器搜索场景启用。
```

将本计划所有已执行步骤改为 `[x]`；不进入小片 3。

---

## 计划自检

- 规格覆盖：实现已批准的内部等待、页面稳定、执行前后校验、超时和取消基础；不提前实现浏览器搜索、候选消歧、微信草稿或 Phase 4 验收。
- 隐私检查：验证轮询不截图，不上传新数据；页面指纹忽略截图。
- 安全检查：动作不自动重试；本地上下文风控和逐步确认优先于执行；执行器未接入会话。
- 兼容检查：所有 `ScreenContextProvider` 实现必须显式选择普通采集或无截图验证采集，避免隐式截图；常规 `collect()` 的截图策略保持不变。
- 完整性检查：所有新类型、方法、命令和预期结果已明确，模拟器序列号来自已运行的本机 AVD。
- 类型一致性：计划继续使用小片 1 的 `AgentPlanStep.kind`、`PageExpectation`、`timeoutMillis` 和 `requiresConfirmation`，不修改线上 JSON。
