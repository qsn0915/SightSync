# Phase 4 小片 4：App 内导航与候选消歧实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 SightSync Android App 中实现当前页面内的低风险确定性导航：只响应明确控件命令，唯一候选才点击；找不到或候选不唯一时语音追问一次，并在原应用和原候选快照范围内安全消歧。

**Architecture:** `InAppNavigationTargetResolver` 作为纯 Kotlin 节点定位器，把可见标签映射到最近可点击父节点，按精确、前缀、包含三级评分选出最高分唯一候选。`InAppNavigationCoordinator` 负责明确命令解析、无截图页面采集、一次有界追问和陈旧快照复核，并在唯一目标时产生两步 `AgentPlan`；`AssistantSessionManager` 在普通打开应用和远端 AI 之前处理该结果，先 TTS 播报意图，再用共享 `AgentPlanExecutor` 执行或进入现有二次确认流程。

**Tech Stack:** Kotlin、kotlinx.coroutines、JUnit 4、kotlinx-coroutines-test、现有 `ScreenContextProvider`、`NodeMatcher`、`AgentPlanExecutor`、`RiskClassifier`、`ConfirmationManager` 和 `VoiceTurnCoordinator`。

---

## 文件范围

- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/InAppNavigationTargetResolver.kt`
  - 纯 Kotlin 候选生成、评分、唯一选择和可区分候选标签。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/InAppNavigationTargetResolverTest.kt`
  - 覆盖直接可点击节点、标签子节点上溯、评分优先级、候选去重、歧义和找不到。
- 新增：`app/src/main/java/com/sightsync/assistant/accessibility/InAppNavigationCoordinator.kt`
  - 解析明确当前页面命令，管理一次追问状态，重新采集并验证包名/候选快照，生成受限两步计划。
- 新增：`app/src/test/java/com/sightsync/assistant/accessibility/InAppNavigationCoordinatorTest.kt`
  - 覆盖明确命令、非命令、缺失追问、歧义消歧、包名变化、候选失效、第二次仍不明确和清理状态。
- 修改：`app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
  - 在浏览器搜索、打开应用和远端 AI 之前处理当前页面导航；先播报再执行；高风险步骤进入现有确认；所有取消/停止/销毁路径清空待消歧状态。
- 修改：`app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
  - 复用同一 `ScreenContextCollector`、`ActionRunner` 和 `AgentPlanExecutor` 装配导航 coordinator。
- 修改：`app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
  - 覆盖本地导航成功、追问/取消、高风险确认和不抢占普通打开应用。
- 修改：`app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
  - 覆盖共享生产依赖装配。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 全部验证完成后记录小片 4；不进入小片 5。

## 行为和安全边界

- 本小片只导航当前可见页面，不打开目标 App 后自主探索，不跨 App 搜索路径，不启用远端 `AssistResponse.plan`。
- 初始命令只接受两类明确语法：
  - 控件动作：`点击/点一下/点开/选择/按下 <目标>`；
  - 明确当前页面：`在当前页面/当前应用中 打开/进入/前往/找到 <目标>` 或 `打开/进入当前页面的 <目标>`。
- 普通 `打开微信`、`进入设置` 仍由既有 `OpenAppCommandResolver` 处理；普通环境语音不进入本地导航。
- 目标去除首尾空白和句末标点后必须非空，最多 100 个 Unicode code point；空目标给出一次明确追问，超长目标直接停止。
- 标签来源仅为 `text`、`contentDescription`、`inputContext`；非可点击标签可沿 `parentNodeId` 上溯到最近可点击且非 editable 的父节点，出现父链循环或缺失即停止上溯。
- 候选按目标与标签的归一化关系评分：完全相等 100、标签以目标开头或目标以标签开头 80、互相包含 60；只保留全局最高分候选，并按最终可点击 `nodeId` 去重。
- 唯一最高分候选才生成点击计划。零候选时追问一次更具体名称；多个最高分候选且标签可区分时播报候选并保存快照；多个同名候选不能安全语音消歧，直接说明无法确定，不保存待办。
- 待追问状态最多消费下一条语音一次：
  - 原结果为零候选时，在原包名重新采集并用整条答复作为新目标；
  - 原结果为多候选时，只按保存的候选标签消歧，再用 `NodeMatcher.matchesSnapshot` 验证相同 node ID、bounds、role、clickable 和标签仍成立；
  - 包名变化、候选丢失、仍无唯一结果时清空状态并停止，不连续追问。
- 每个已解析导航只生成 `CLICK_NODE + VALIDATE_PAGE` 两步计划：动作前置条件包含原包名和 node ID，ACTION 自身等待页面稳定，第二步校验仍在原包名；最大 10 秒、首次失败停止、不重试点击。
- Session 必须在执行前通过 `speakResult` 播报“我会点击…”，执行后播报结果；下一轮录音只能在任务和 TTS 收束后开始。
- `AgentPlanExecutionResult.ConfirmationRequired` 必须转换为现有 `ConfirmationManager` 待确认响应，确认前不点击；取消确认或停止连续聆听会同时清空导航追问。
- 浮窗、通知停止、一次唤起取消、`dispose()`、连续停止词和确认成功路径都清空待消歧状态；外部协程取消继续向上传播。
- 所有导航采集只调用 `collectForValidation()`，不截图、不上传安装列表、不调用后端 AI。

## Task 1：当前页面目标定位器 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/InAppNavigationTargetResolverTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/InAppNavigationTargetResolver.kt`

- [x] **Step 1：写失败测试**

期望 API：

```kotlin
data class InAppNavigationCandidate(
    val node: ScreenNode,
    val labelNode: ScreenNode,
    val label: String,
    val score: Int,
)

sealed interface InAppNavigationLookup {
    data class Found(val candidate: InAppNavigationCandidate) : InAppNavigationLookup
    data class Ambiguous(val candidates: List<InAppNavigationCandidate>) : InAppNavigationLookup
    data object NotFound : InAppNavigationLookup
}

object InAppNavigationTargetResolver {
    fun find(screen: ScreenContext, target: String): InAppNavigationLookup
}
```

测试覆盖：

```kotlin
assertEquals(
    InAppNavigationLookup.Found(InAppNavigationCandidate(wlanNode, wlanNode, "WLAN", 100)),
    resolver.find(screen(wlanNode), "WLAN"),
)
```

另加独立测试：文本子节点“WLAN”上溯到可点击父节点；精确候选优先于包含候选；同一可点击父节点由多个子标签命中时只保留一个；两个同分不同节点返回 `Ambiguous`；普通不可点击文本且无可点击祖先返回 `NotFound`；editable 节点或 editable 祖先不能作为导航点击目标。

- [x] **Step 2：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.InAppNavigationTargetResolverTest"
```

Expected: 编译失败，原因是 resolver、candidate 和 lookup 类型不存在。

- [x] **Step 3：实现最小纯 Kotlin resolver**

实现要点：

```kotlin
object InAppNavigationTargetResolver {
    fun find(screen: ScreenContext, target: String): InAppNavigationLookup {
        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return InAppNavigationLookup.NotFound
        val nodesById = screen.nodes.associateBy(ScreenNode::nodeId)
        val candidates = screen.nodes.flatMap { labelNode ->
            labelNode.semanticValues().mapNotNull { label ->
                val score = matchScore(normalize(label), normalizedTarget) ?: return@mapNotNull null
                val clickable = labelNode.closestClickable(nodesById) ?: return@mapNotNull null
                InAppNavigationCandidate(clickable, labelNode, label.trim(), score)
            }
        }
        val bestPerNode = candidates
            .groupBy { it.node.nodeId }
            .map { (_, values) -> values.sortedWith(candidateOrder).first() }
        val bestScore = bestPerNode.maxOfOrNull(InAppNavigationCandidate::score)
            ?: return InAppNavigationLookup.NotFound
        val best = bestPerNode.filter { it.score == bestScore }.sortedBy { it.label.lowercase() }
        return if (best.size == 1) InAppNavigationLookup.Found(best.single())
        else InAppNavigationLookup.Ambiguous(best)
    }
}
```

`closestClickable` 使用 visited node ID 集避免父链循环；只接受 `clickable && !editable`。归一化只保留 Unicode 字母数字。

- [x] **Step 4：运行测试确认 GREEN**

运行 Task 1 目标测试，Expected: 全部通过。

## Task 2：明确命令、一次追问与计划生成 RED/GREEN

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/InAppNavigationCoordinatorTest.kt`
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/InAppNavigationCoordinator.kt`

- [x] **Step 1：写明确命令和非命令失败测试**

期望接口：

```kotlin
interface InAppNavigationResolver {
    val hasPendingClarification: Boolean
    suspend fun resolve(utterance: String): InAppNavigationResolution
    fun clear()
}

sealed interface InAppNavigationResolution {
    data object NotCommand : InAppNavigationResolution
    data class AskClarification(val spoken: String) : InAppNavigationResolution
    data class Ready(val targetLabel: String, val plan: AgentPlan) : InAppNavigationResolution
    data class Stopped(val spoken: String) : InAppNavigationResolution
}

class InAppNavigationCoordinator(
    private val screenContextProvider: ScreenContextProvider,
) : InAppNavigationResolver
```

覆盖“点击 WLAN”“在当前页面进入网络设置”“打开当前页面的蓝牙”生成 `Ready`，且计划仅含 `CLICK_NODE` 和 `VALIDATE_PAGE`；“打开微信”“进入设置”“今天天气不错”返回 `NotCommand` 且不采集页面；空目标返回 `AskClarification`；超过 100 code point 返回 `Stopped`。

- [x] **Step 2：写零候选追问失败测试**

第一次明确命令在原包名找不到时返回 `AskClarification` 且 `hasPendingClarification=true`。下一条裸答复“无线网络”重新采集同一包名：唯一时返回 `Ready` 并清空 pending；仍找不到或包名改变时返回 `Stopped` 且不再追问。

- [x] **Step 3：写多候选消歧和快照失效失败测试**

初次两个最高分不同标签候选返回包含候选名称的 `AskClarification`。下一轮：

- 裸答复唯一匹配一个保存标签且当前节点通过 `NodeMatcher.matchesSnapshot` 时返回 `Ready`；
- 标签仍匹配多个、候选节点丢失或快照 bounds/role/label 改变时返回 `Stopped`；
- 两个同名最高分候选第一次就返回 `Stopped`，不设置 pending；
- `clear()` 立即清空 pending，之后裸答复返回 `NotCommand`。

- [x] **Step 4：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.InAppNavigationCoordinatorTest"
```

Expected: 编译失败，原因是 coordinator、resolver 和 resolution 类型不存在。

- [x] **Step 5：实现有界 coordinator**

明确命令正则只包含计划批准的两类语法；`resolve()` 若有 pending，先取出并清空再处理本轮，因此不会产生第二次追问。多候选 pending 保存：

```kotlin
private data class PendingCandidates(
    val packageName: String,
    val candidates: List<PendingCandidate>,
) : PendingClarification

private data class PendingCandidate(
    val expectedNode: ScreenNode,
    val expectedLabelNode: ScreenNode,
    val label: String,
)
```

消歧先按保存标签使用与 Task 1 相同的三级评分，只接受唯一最高分；随后从新快照分别按 node ID 找到点击节点和命中标签节点，并对两者调用 `NodeMatcher.matchesSnapshot(current, expected)`。这样可点击父节点没有文字时，子标签变化仍会使候选失效。零候选 pending 只保存原包名。

计划固定为：

```kotlin
AgentPlan(
    goal = utterance,
    maxDurationMillis = 10_000,
    maxConsecutiveFailures = 1,
    steps = listOf(
        AgentPlanStep(
            id = "navigate_click",
            kind = "ACTION",
            action = AssistantAction(type = "CLICK_NODE", nodeId = candidate.node.nodeId),
            precondition = PageExpectation(
                packageName = screen.packageName,
                requiredNodeIds = listOf(candidate.node.nodeId),
            ),
            timeoutMillis = 8_000,
            requiresConfirmation = false,
        ),
        AgentPlanStep(
            id = "validate_navigation",
            kind = "VALIDATE_PAGE",
            precondition = PageExpectation(packageName = screen.packageName),
            timeoutMillis = 2_000,
            requiresConfirmation = false,
        ),
    ),
)
```

- [x] **Step 6：运行测试确认 GREEN**

运行 Task 2 目标测试和 Task 1 resolver 测试，Expected: 全部通过。

## Task 3：Session 路由、确认和状态清理 RED/GREEN

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`

- [x] **Step 1：写唯一导航成功失败测试**

使用真实 `InAppNavigationCoordinator`、真实 `AgentPlanExecutor`、队列式验证快照和现有 fake `ActionRunner`。说“点击 WLAN”时断言：

- TTS 在动作执行前出现“我会点击 WLAN。”；
- 只执行一个 `CLICK_NODE`；
- 不调用普通 `collect()` 和 AI；
- 成功后播报“已完成页面导航。”。

- [x] **Step 2：写追问、取消和打开应用兼容失败测试**

覆盖：

- 多候选时第一轮只播报追问、零动作；下一轮裸答复唯一后点击；
- pending 状态下说“取消”只清空追问并播报“已取消。”，不采集、AI 或执行；
- `cancelActiveRequest()`、`stopContinuousListening()` 和 `dispose()` 会调用 resolver `clear()`；
- 同时注入导航与打开应用 resolver 时，“打开微信”仍执行 `OPEN_APP`，导航 resolver 返回 `NotCommand`。

- [x] **Step 3：写高风险确认失败测试**

当前页面唯一候选“删除账号”时，首次导航由 `AgentPlanExecutor` 返回 `ConfirmationRequired`，动作零调用；Session 存储现有确认请求并播报二次确认。下一轮说“确认执行”后只执行一次原 `CLICK_NODE`；说“取消”则不执行并清空导航状态。

- [x] **Step 4：运行测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test"
```

Expected: 编译或断言失败，因为 Session 尚未注入导航 resolver/plan executor，也没有本地导航 handler 和清理逻辑。

- [x] **Step 5：实现 Session 最小接入**

构造参数新增可空 `inAppNavigationResolver` 和可空 `navigationPlanExecutor`。`runAssistantTurn` 在浏览器搜索和打开应用前调用 `handleLocalInAppNavigation`；结果映射：

```kotlin
when (val resolution = resolver.resolve(utterance)) {
    InAppNavigationResolution.NotCommand -> false
    is InAppNavigationResolution.AskClarification -> speak(resolution.spoken)
    is InAppNavigationResolution.Stopped -> speak(resolution.spoken)
    is InAppNavigationResolution.Ready -> {
        speak("我会点击${resolution.targetLabel}。")
        voiceState = VoiceInteractionState.Acting
        when (val execution = executor.execute(resolution.plan)) {
            is AgentPlanExecutionResult.Completed -> speak("已完成页面导航。")
            is AgentPlanExecutionResult.Stopped -> speak(execution.reason)
            is AgentPlanExecutionResult.Invalid -> speak("页面导航计划无效，已停止执行。")
            is AgentPlanExecutionResult.ConfirmationRequired -> {
                val action = checkNotNull(execution.step.action)
                confirmationManager.store(
                    AssistResponse(
                        spoken = "我准备点击${resolution.targetLabel}。",
                        requiresConfirmation = true,
                        actions = listOf(action),
                    ),
                    execution.sourceScreen,
                )
                speak("这是高风险操作，如需继续，请再次唤起并说确认执行。")
            }
        }
    }
}
```

在现有清理 `pendingOpenAppCandidatePackages` 的停止/取消/销毁/确认分支同步调用 `inAppNavigationResolver?.clear()`；pending 导航取消在普通导航 handler 前处理。

- [x] **Step 6：运行测试确认 GREEN**

运行 `AssistantSessionManagerPhase2Test`、`InAppNavigationCoordinatorTest` 和既有 `AgentPlanExecutorTest`，Expected: 新旧会话场景全部通过。

## Task 4：生产装配、全量验证和 Android 模拟器

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`
- Modify: `generated-docs/plans/2026-06-22-phase4-slice4-in-app-navigation-disambiguation.md`

- [x] **Step 1：写生产装配失败测试**

源码测试要求服务创建一次：

```kotlin
val agentPlanExecutor = AgentPlanExecutor(screenContextProvider, actionRunner)
val inAppNavigationResolver = InAppNavigationCoordinator(screenContextProvider)
```

同一 `agentPlanExecutor` 同时传给 `BrowserSearchTaskRunner` 和 Session 的 `navigationPlanExecutor`；同一 `screenContextProvider` 传给 coordinator 和 Session。

- [x] **Step 2：运行装配测试确认 RED**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantAccessibilityServiceSourceTest"
```

Expected: 源码断言失败，因为服务尚未创建共享计划执行器和导航 coordinator。

- [x] **Step 3：实现生产装配并确认 GREEN**

修改服务局部依赖装配，保留现有对象生命周期，不新增 DI 框架。重跑源码测试，Expected: 全部通过。

- [x] **Step 4：运行后端回归**

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 后端全量测试通过；本小片没有后端生产改动。

- [x] **Step 5：运行 Android 全量测试、构建和差异检查**

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: Android 全量单元测试和 debug APK 构建通过，差异检查无错误。

- [x] **Step 6：模拟器覆盖安装和冷启动**

```powershell
$adb='D:\AndroidDev\Sdk\platform-tools\adb.exe'
$serial='emulator-5554'
& $adb -s $serial install -r D:\project\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial shell am force-stop com.sightsync.assistant
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n com.sightsync.assistant/.MainActivity
```

验证进程存在、`MainActivity` 为 top resumed、最近日志无 `FATAL EXCEPTION`、SightSync ANR。由于生产 App 没有调试命令注入入口，禁止为验收新增绕过语音/权限的后门；使用系统设置页面的 `uiautomator dump` 仅确认存在可重复的可点击候选，实际语音导航留到 Phase 4 阶段验收或可控音频环境补验并记录风险。

- [x] **Step 7：更新进度和计划勾选**

仅在目标测试、全量测试、构建、差异检查和 Android 验证完成后，在长期计划追加小片 4 记录，注明实际语音端到端是否完成。将本计划实际步骤勾为 `[x]`；不开始小片 5。

---

## 计划自检

- 规格覆盖：找不到和多候选均有一次语音追问；唯一候选才执行；页面/包名/候选变化停止；高风险进入二次确认；用户停止后不继续。
- 范围检查：只处理当前页面，不做打开 App 后自主探索、通用路径规划、微信流程、远端 plan 或 Phase 4 验收。
- 动作检查：只生成一个 `CLICK_NODE`，配一个批准的 `VALIDATE_PAGE`；不新增动作白名单、坐标、手势、键盘或 Intent。
- 隐私检查：所有定位和追问复核只走 `collectForValidation()`；不截图、不调用后端、不上传安装列表。
- 有界检查：最多一次追问、一次点击、10 秒计划、首次失败停止；没有循环重试。
- 语音检查：Session 在执行前播报意图，结果/追问/失败均 TTS；整个处理留在同一 turn 和同一可取消协程。
- 类型一致性：计划继续使用现有 `AgentPlan`、`AgentPlanStep`、`PageExpectation`、`AgentPlanExecutor`、`NodeMatcher` 和 `AssistResponse`；远端协议不变。
- TDD 检查：每个新生产组件和 Session/装配行为都有先失败后最小实现的步骤，最终运行全量回归。
- 工作树检查：当前分支包含小片 1–3 未提交依赖；不创建隔离 worktree、不提交或回滚既有改动。
