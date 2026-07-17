# Phase 3 Slice 4 Screenshot Privacy Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tighten screenshot attachment so SightSync only sends screenshots when the node tree is insufficient and local privacy checks do not block it, while exposing the decision reason to backend summaries and prompts.

**Architecture:** Android remains the final privacy gate: the screen collector computes a `ScreenshotPolicyDecision`, blocks screenshots when any extracted node is privacy-sensitive, and attaches screenshots only for sparse node trees. The backend treats the Android decision as metadata for `screen_summary_v2` and prompt guidance; it never asks for or reconstructs screenshots, and screenshot base64 stays out of text prompts.

**Tech Stack:** Android Kotlin, kotlinx.serialization, JUnit4, Node.js ESM, `node:test`.

---

## File Scope

- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenModels.kt`
  - Add `privacySensitive` to `ScreenNode`.
  - Add `ScreenshotPolicyDecision`.
  - Add optional `screenshotPolicy` to `ScreenContext`.
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenNodeTreeExtractor.kt`
  - Mark nodes as `privacySensitive` when source text or content description is hidden by local redaction or the source node is a password field.
  - Replace boolean-only screenshot policy with reasoned `ScreenshotPolicyDecision`.
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt`
  - Use `ScreenContextPolicy.decideScreenshot()` and include the decision in `ScreenContext`.
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenNodeTreeExtractorTest.kt`
  - Add tests for privacy-sensitive nodes and screenshot decisions.
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenContextCollectorTest.kt`
  - Add tests for decision metadata and privacy blocking in collection.
- Modify: `backend/src/screen-summary.js`
  - Preserve screenshot policy metadata in `screen_summary_v2.page`.
- Modify: `backend/test/screen-summary.test.js`
  - Test backend summary includes screenshot policy metadata.
- Modify: `backend/src/qwen.js`
  - Add prompt guidance that screenshots are optional, Android-gated, and privacy-blocked screens must not be guessed from absent visuals.
- Modify: `backend/test/qwen.test.js`
  - Test prompt contains screenshot policy metadata and does not put screenshot base64 in text content.
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`
  - Mark Phase 3 slice 4 complete after implementation and verification.

## Safety Boundaries

- Do not add, remove, or relax any `actions.type`.
- Do not change action execution behavior.
- Do not add any AI provider/model or API key handling.
- Do not attach screenshots when local nodes indicate passwords or redacted sensitive content.
- Do not log screenshot base64 or include it in prompt text; screenshots may only be sent through existing `image_url` content when policy allows and a screenshot is actually available.

---

## Task 1: Android Screenshot Policy And Privacy Metadata

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenModels.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenNodeTreeExtractor.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenNodeTreeExtractorTest.kt`

- [x] **Step 1: Write failing tests for privacy sensitivity and policy decisions**

Add this import if needed:

```kotlin
import org.junit.Assert.assertNotNull
```

Add these tests to `ScreenNodeTreeExtractorTest`:

```kotlin
@Test
fun marksNodesPrivacySensitiveWhenLocalRedactionHidesContent() {
    val root = ScreenNodeSnapshot(
        children = listOf(
            ScreenNodeSnapshot(
                text = "验证码 123456",
                className = "android.widget.TextView",
            ),
            ScreenNodeSnapshot(
                text = "secret",
                className = "android.widget.EditText",
                password = true,
                editable = true,
            ),
        ),
    )

    val nodes = ScreenNodeTreeExtractor().extract(root)

    assertEquals("[验证码已隐藏]", nodes[0].text)
    assertTrue(nodes[0].privacySensitive)
    assertEquals("[已隐藏]", nodes[1].text)
    assertTrue(nodes[1].privacySensitive)
}

@Test
fun screenshotPolicyBlocksSensitiveNodesBeforeSparseFallback() {
    val sparseSensitiveNodes = listOf(
        ScreenNode(
            nodeId = "node_0",
            text = "[已隐藏]",
            contentDescription = null,
            role = "EditText",
            bounds = NodeBounds(0, 0, 100, 100),
            clickable = false,
            editable = true,
            scrollable = false,
            privacySensitive = true,
        ),
    )
    val emptyDecision = ScreenContextPolicy.decideScreenshot(emptyList())
    val sparseDecision = ScreenContextPolicy.decideScreenshot(
        listOf(
            ScreenNode(
                nodeId = "node_0",
                text = null,
                contentDescription = null,
                role = "WebView",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = false,
                editable = false,
                scrollable = true,
            ),
        ),
    )
    val sensitiveDecision = ScreenContextPolicy.decideScreenshot(sparseSensitiveNodes)

    assertTrue(emptyDecision.attachScreenshot)
    assertEquals("empty_node_tree", emptyDecision.reason)
    assertFalse(emptyDecision.privacyBlocked)

    assertTrue(sparseDecision.attachScreenshot)
    assertEquals("sparse_node_tree", sparseDecision.reason)
    assertFalse(sparseDecision.privacyBlocked)

    assertFalse(sensitiveDecision.attachScreenshot)
    assertEquals("privacy_sensitive_content", sensitiveDecision.reason)
    assertTrue(sensitiveDecision.privacyBlocked)
}
```

- [x] **Step 2: Run tests to verify RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenNodeTreeExtractorTest
```

Expected: compile failure for missing `privacySensitive`, `ScreenContextPolicy.decideScreenshot`, and `ScreenshotPolicyDecision` behavior.

- [x] **Step 3: Add model fields**

In `ScreenModels.kt`, add:

```kotlin
@Serializable
data class ScreenshotPolicyDecision(
    val attachScreenshot: Boolean,
    val reason: String,
    val privacyBlocked: Boolean = false,
)
```

Add this field to `ScreenNode`:

```kotlin
    val privacySensitive: Boolean = false,
```

Add this field to `ScreenContext`:

```kotlin
    val screenshotPolicy: ScreenshotPolicyDecision? = null,
```

- [x] **Step 4: Mark sensitive nodes and add reasoned policy**

In `ScreenNodeTreeExtractor.extract()`, keep raw trimmed values before redaction and compute sensitivity:

```kotlin
val rawText = snapshot.text.trimToNull()
val rawDescription = snapshot.contentDescription.trimToNull()
val text = SensitiveTextRedactor.redact(
    rawText,
    role = role,
    isPassword = snapshot.password,
)
val description = SensitiveTextRedactor.redact(
    rawDescription,
    role = role,
    isPassword = snapshot.password,
)
val privacySensitive = snapshot.password ||
    wasRedacted(rawText, text) ||
    wasRedacted(rawDescription, description)
```

Pass it into `ScreenNode`:

```kotlin
privacySensitive = privacySensitive,
```

Add helper:

```kotlin
private fun wasRedacted(raw: String?, redacted: String?): Boolean =
    raw != null && redacted != null && raw != redacted
```

Replace `ScreenContextPolicy` with:

```kotlin
object ScreenContextPolicy {
    fun shouldAttachScreenshot(nodes: List<ScreenNode>): Boolean =
        decideScreenshot(nodes).attachScreenshot

    fun decideScreenshot(nodes: List<ScreenNode>): ScreenshotPolicyDecision {
        if (nodes.any { it.privacySensitive }) {
            return ScreenshotPolicyDecision(
                attachScreenshot = false,
                reason = "privacy_sensitive_content",
                privacyBlocked = true,
            )
        }
        if (nodes.isEmpty()) {
            return ScreenshotPolicyDecision(
                attachScreenshot = true,
                reason = "empty_node_tree",
            )
        }

        val labeledNodes = nodes.count { node ->
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        }

        return if (nodes.size < 3 || labeledNodes < 2) {
            ScreenshotPolicyDecision(
                attachScreenshot = true,
                reason = "sparse_node_tree",
            )
        } else {
            ScreenshotPolicyDecision(
                attachScreenshot = false,
                reason = "node_tree_sufficient",
            )
        }
    }
}
```

- [x] **Step 5: Run tests to verify GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenNodeTreeExtractorTest
```

Expected: `ScreenNodeTreeExtractorTest` passes.

---

## Task 2: Android Collector Includes Screenshot Policy Decision

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenContextCollectorTest.kt`

- [x] **Step 1: Write failing collector tests**

Add this test to `ScreenContextCollectorTest`:

```kotlin
@Test
fun collectorIncludesScreenshotPolicyAndBlocksScreenshotForSensitiveContent() = runTest {
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
                text = "secret",
                className = "android.widget.EditText",
                editable = true,
                password = true,
            ),
        ),
    )

    val context = assembler.collectFrom(
        packageName = "com.example.secure",
        activityName = "Password",
        root = root,
    )

    assertNull(context.screenshotBase64)
    assertEquals(0, screenshotCalls)
    assertEquals(false, context.screenshotPolicy?.attachScreenshot)
    assertEquals("privacy_sensitive_content", context.screenshotPolicy?.reason)
    assertEquals(true, context.screenshotPolicy?.privacyBlocked)
}
```

Update existing collector tests to assert policy metadata:

```kotlin
assertEquals(false, context.screenshotPolicy?.attachScreenshot)
assertEquals("node_tree_sufficient", context.screenshotPolicy?.reason)
```

for the rich node tree test, and:

```kotlin
assertEquals(true, context.screenshotPolicy?.attachScreenshot)
assertEquals("sparse_node_tree", context.screenshotPolicy?.reason)
```

for the sparse node tree test.

- [x] **Step 2: Run tests to verify RED**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenContextCollectorTest
```

Expected: failure because `ScreenContextAssembler` does not populate `screenshotPolicy` yet.

- [x] **Step 3: Populate policy in collector**

In `ScreenContextAssembler.collectFrom()`, replace the screenshot boolean with:

```kotlin
val screenshotPolicy = ScreenContextPolicy.decideScreenshot(nodes)
val screenshot = if (screenshotPolicy.attachScreenshot) {
    screenshotProvider.takeScreenshotBase64()
} else {
    null
}
```

Return:

```kotlin
return ScreenContext(
    packageName = packageName,
    activityName = activityName,
    nodes = nodes,
    screenshotBase64 = screenshot,
    screenshotPolicy = screenshotPolicy,
)
```

- [x] **Step 4: Run tests to verify GREEN**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenContextCollectorTest
```

Expected: `ScreenContextCollectorTest` passes.

---

## Task 3: Backend Summary And Prompt Respect Screenshot Policy

**Files:**
- Modify: `backend/src/screen-summary.js`
- Modify: `backend/test/screen-summary.test.js`
- Modify: `backend/src/qwen.js`
- Modify: `backend/test/qwen.test.js`

- [x] **Step 1: Write failing backend tests**

In `backend/test/screen-summary.test.js`, add `screenshotPolicy` to the first `buildScreenSummary` input:

```js
screenshotPolicy: {
  attachScreenshot: true,
  reason: 'sparse_node_tree',
  privacyBlocked: false
}
```

Add assertions:

```js
assert.deepEqual(summary.page.screenshotPolicy, {
  attachScreenshot: true,
  reason: 'sparse_node_tree',
  privacyBlocked: false
});
```

In `backend/test/qwen.test.js`, update `validRequest().screen` to include:

```js
screenshotPolicy: {
  attachScreenshot: false,
  reason: 'privacy_sensitive_content',
  privacyBlocked: true
}
```

Add assertions to `buildQwenChatCompletionRequest asks for strict JSON and disables thinking`:

```js
assert.match(payload.messages[0].content, /截图/);
assert.match(payload.messages[0].content, /隐私/);
assert.match(payload.messages[1].content, /privacy_sensitive_content/);
assert.match(payload.messages[1].content, /screenshotPolicy/);
```

- [x] **Step 2: Run tests to verify RED**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js qwen.test.js
```

Expected: failure because `summary.page.screenshotPolicy` and prompt guidance are missing.

- [x] **Step 3: Preserve screenshot policy in summary**

In `backend/src/screen-summary.js`, add to `page`:

```js
screenshotPolicy: normalizeScreenshotPolicy(screen.screenshotPolicy)
```

Add helper:

```js
function normalizeScreenshotPolicy(policy) {
  if (!policy || typeof policy !== 'object') return null;
  return {
    attachScreenshot: policy.attachScreenshot === true,
    reason: typeof policy.reason === 'string' ? policy.reason : null,
    privacyBlocked: policy.privacyBlocked === true
  };
}
```

- [x] **Step 4: Add prompt guidance**

In `backend/src/qwen.js` system prompt rules, add:

```text
- 截图只作为 Android 端按隐私策略附加的辅助材料；当 screen_summary_v2.page.screenshotPolicy.privacyBlocked 为 true 或截图不存在时，不要要求截图，不要编造视觉内容。
```

The existing `屏幕摘要 JSON` line already serializes `screenshotPolicy` after Task 3 Step 3.

- [x] **Step 5: Run tests to verify GREEN**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js qwen.test.js
```

Expected: backend target tests pass.

---

## Task 4: Progress, Regression, Build, And Android Validation

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`
- Create local log only if device validation is blocked: `generated-docs/logs/2026-06-13-phase3-slice4-android-validation-blocker.md`

- [x] **Step 1: Update long-term progress**

Append under current progress:

```markdown
- Phase 3 小片 4 已完成：截图辅助理解策略改为带原因的 Android 隐私决策；节点树不足时才请求截图，敏感或已脱敏内容会阻断截图，并把截图策略原因传给后端摘要和 prompt。
```

- [x] **Step 2: Run backend full test suite**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: all backend tests pass without real provider keys.

- [x] **Step 3: Run Android unit tests**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android debug unit tests pass.

- [x] **Step 4: Build debug APK**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
```

Expected: debug APK build succeeds.

- [x] **Step 5: Check whitespace and device availability**

Run:

```powershell
cd D:\project
git diff --check
D:\platform-tools\adb.exe devices
```

Expected: `git diff --check` has no output. If no online device appears in `adb devices`, create the local blocker note:

```markdown
# Phase 3 小片 4 Android 验证阻塞记录

日期：2026-06-13

小片：Phase 3 小片 4，截图辅助理解策略和隐私控制。

已完成自动化验证：

- `npm test`：后端测试通过。
- `.\gradlew.bat :app:testDebugUnitTest`：Android debug 单元测试通过。
- `.\gradlew.bat :app:assembleDebug`：debug APK 构建通过。
- `git diff --check`：通过。

App 运行验证状态：阻塞。

阻塞原因：

- 执行 `D:\platform-tools\adb.exe devices` 后未发现在线 Android 虚拟机或真机。
- 因没有可用设备，本次无法安装或启动 debug APK 做运行验证。

后续恢复条件：

- 启动本机 Android 虚拟机，或连接并授权 USB 真机。
- 设备在线后，重新执行 Phase 3 小片 2、3、4 对应的 App 验证流程。
```

If exactly one online device is available, use the SightSync acceptance helper with safe launch-only behavior unless the user explicitly authorizes broader input:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\codex\.codex\skills\sightsync-real-device-acceptance\scripts\sightsync_acceptance.ps1 -ProjectRoot D:\project -AdbPath D:\platform-tools\adb.exe -ProxyPort 0 -Launch
```

---

## Self-Review

- Spec coverage: covers Phase 3 slice 4 screenshot auxiliary understanding and privacy control; does not implement Phase 3 acceptance set or enter Phase 4.
- Placeholder scan: no TODO/TBD placeholders.
- Type consistency: Android uses `ScreenshotPolicyDecision.attachScreenshot/reason/privacyBlocked`; backend preserves the same field names under `screen_summary_v2.page.screenshotPolicy`.
