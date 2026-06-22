# Phase 3 小片 2：节点树提取增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强 Android 端上传的屏幕节点树，让后端摘要和 AI prompt 能看到层级、区域、可操作项、滚动容器和输入框上下文。

**Architecture:** 在现有 `ScreenNode` 上追加带默认值的元数据字段，保持旧构造点和后端校验兼容。`ScreenNodeTreeExtractor` 仍输出扁平列表，但每个节点带父节点、深度、屏幕区域、可操作类型、最近滚动容器和输入框上下文；后端 `screen_summary_v2` 只读取这些可选字段增强摘要，不改变 `AssistResponse` 动作协议。

**Tech Stack:** Android Kotlin、kotlinx.serialization、JUnit4、Node.js ESM、`node:test`。

---

## 文件范围

- 修改：`app/src/main/java/com/sightsync/assistant/core/ScreenModels.kt`
  - 给 `ScreenNode` 追加 `parentNodeId`、`depth`、`childIndex`、`region`、`actionableType`、`scrollContainerNodeId`、`inputContext` 默认字段。
- 修改：`app/src/main/java/com/sightsync/assistant/core/ScreenNodeTreeExtractor.kt`
  - 遍历时携带层级、父节点、兄弟标签、最近滚动容器和根高度。
  - 对每个有用节点填充新增字段。
- 修改：`app/src/test/java/com/sightsync/assistant/core/ScreenNodeTreeExtractorTest.kt`
  - 新增 RED/GREEN 测试覆盖层级、区域、滚动容器、输入框上下文。
- 修改：`backend/src/screen-summary.js`
  - 将 Android 新字段保留到 `actionableItems`，让 prompt 能消费区域和上下文。
- 修改：`backend/test/screen-summary.test.js`
  - 新增 RED/GREEN 测试覆盖后端摘要对新增字段的消费。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 记录 Phase 3 小片 2 完成。

## 安全边界

- 不新增、删除或放宽 `actions.type`。
- 不修改动作执行逻辑，不引入坐标点击或新手势。
- 不修改截图附带策略。
- 不上传第三方 AI provider key，不改 AI provider/model。
- 输入框上下文只来自已脱敏后的本地节点文字或 contentDescription；密码/验证码/银行卡等敏感内容仍由现有 `SensitiveTextRedactor` 先处理。

## Task 1：Android 节点模型和提取器增强

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenModels.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenNodeTreeExtractor.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/core/ScreenNodeTreeExtractorTest.kt`

- [x] **Step 1: 写失败测试**

在 `ScreenNodeTreeExtractorTest` 中新增测试：

```kotlin
@Test
fun extractsHierarchyRegionActionableTypeScrollContainerAndInputContext() {
    val root = ScreenNodeSnapshot(
        className = "android.widget.FrameLayout",
        bounds = NodeBounds(0, 0, 1080, 2400),
        children = listOf(
            ScreenNodeSnapshot(
                text = "网络设置",
                className = "android.widget.TextView",
                bounds = NodeBounds(0, 0, 1080, 120),
            ),
            ScreenNodeSnapshot(
                className = "android.widget.ScrollView",
                bounds = NodeBounds(0, 120, 1080, 2200),
                scrollable = true,
                children = listOf(
                    ScreenNodeSnapshot(
                        text = "WLAN",
                        className = "android.widget.Button",
                        bounds = NodeBounds(0, 150, 1080, 260),
                        clickable = true,
                    ),
                    ScreenNodeSnapshot(
                        text = "网络名称",
                        className = "android.widget.TextView",
                        bounds = NodeBounds(0, 300, 1080, 360),
                    ),
                    ScreenNodeSnapshot(
                        contentDescription = "请输入网络名称",
                        className = "android.widget.EditText",
                        bounds = NodeBounds(0, 380, 1080, 480),
                        editable = true,
                    ),
                ),
            ),
        ),
    )

    val nodes = ScreenNodeTreeExtractor().extract(root)

    val title = nodes.single { it.text == "网络设置" }
    val scrollContainer = nodes.single { it.role == "ScrollView" }
    val wlan = nodes.single { it.text == "WLAN" }
    val input = nodes.single { it.editable }

    assertEquals(null, title.parentNodeId)
    assertEquals(1, title.depth)
    assertEquals("top", title.region)
    assertEquals(null, title.actionableType)

    assertEquals("scroll", scrollContainer.actionableType)
    assertEquals(null, scrollContainer.scrollContainerNodeId)

    assertEquals(scrollContainer.nodeId, wlan.parentNodeId)
    assertEquals(2, wlan.depth)
    assertEquals("click", wlan.actionableType)
    assertEquals(scrollContainer.nodeId, wlan.scrollContainerNodeId)

    assertEquals("input", input.actionableType)
    assertEquals("网络名称", input.inputContext)
    assertEquals(scrollContainer.nodeId, input.scrollContainerNodeId)
}
```

- [x] **Step 2: 跑测试确认失败**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenNodeTreeExtractorTest
```

Expected: 编译失败，原因是 `ScreenNode` 尚无 `parentNodeId`、`depth`、`region`、`actionableType`、`scrollContainerNodeId`、`inputContext` 字段。

- [x] **Step 3: 最小实现模型字段**

在 `ScreenModels.kt` 的 `ScreenNode` 末尾追加默认字段：

```kotlin
    val parentNodeId: String? = null,
    val depth: Int = 0,
    val childIndex: Int = 0,
    val region: String? = null,
    val actionableType: String? = null,
    val scrollContainerNodeId: String? = null,
    val inputContext: String? = null,
```

- [x] **Step 4: 最小实现提取逻辑**

在 `ScreenNodeTreeExtractor.extract()` 内把 `visit(snapshot)` 改为携带上下文：

```kotlin
val rootHeight = root.bounds.height().takeIf { it > 0 } ?: DEFAULT_SCREEN_HEIGHT

fun visit(
    snapshot: ScreenNodeSource,
    depth: Int,
    childIndex: Int,
    parentNodeId: String?,
    nearestScrollContainerId: String?,
    previousSiblingLabel: String?,
) : String? {
    if (nodes.size >= maxNodes) return null

    val role = snapshot.className?.substringAfterLast('.')?.ifBlank { null } ?: "Unknown"
    val text = SensitiveTextRedactor.redact(snapshot.text.trimToNull(), role = role, isPassword = snapshot.password)
    val description = SensitiveTextRedactor.redact(snapshot.contentDescription.trimToNull(), role = role, isPassword = snapshot.password)
    val hasUsefulContent = !text.isNullOrBlank() ||
        !description.isNullOrBlank() ||
        snapshot.clickable ||
        snapshot.editable ||
        snapshot.scrollable

    var currentNodeId: String? = null
    var currentScrollContainerId = nearestScrollContainerId
    if (hasUsefulContent) {
        currentNodeId = "node_${nodes.size}"
        val actionableType = when {
            snapshot.editable -> "input"
            snapshot.scrollable -> "scroll"
            snapshot.clickable -> "click"
            else -> null
        }
        nodes += ScreenNode(
            nodeId = currentNodeId,
            text = text,
            contentDescription = description,
            role = role,
            bounds = snapshot.bounds,
            clickable = snapshot.clickable,
            editable = snapshot.editable,
            scrollable = snapshot.scrollable,
            parentNodeId = parentNodeId,
            depth = depth,
            childIndex = childIndex,
            region = regionFor(snapshot.bounds, rootHeight),
            actionableType = actionableType,
            scrollContainerNodeId = if (snapshot.scrollable) nearestScrollContainerId else nearestScrollContainerId,
            inputContext = if (snapshot.editable) previousSiblingLabel ?: description ?: text else null,
        )
        if (snapshot.scrollable) currentScrollContainerId = currentNodeId
    }

    var previousLabel: String? = null
    for (index in 0 until snapshot.childCount) {
        if (nodes.size >= maxNodes) return listOf(text, description).firstOrNull { !it.isNullOrBlank() }
        val child = snapshot.childAt(index) ?: continue
        val childLabel = visit(
            snapshot = child,
            depth = depth + 1,
            childIndex = index,
            parentNodeId = currentNodeId ?: parentNodeId,
            nearestScrollContainerId = currentScrollContainerId,
            previousSiblingLabel = previousLabel,
        )
        previousLabel = childLabel ?: previousLabel
    }
    return listOf(text, description).firstOrNull { !it.isNullOrBlank() }
}

visit(root, depth = 0, childIndex = 0, parentNodeId = null, nearestScrollContainerId = null, previousSiblingLabel = null)
```

并添加 helper：

```kotlin
private fun regionFor(bounds: NodeBounds, rootHeight: Int): String? {
    if (bounds.bottom <= bounds.top || rootHeight <= 0) return null
    val centerY = (bounds.top + bounds.bottom) / 2
    return when {
        centerY < rootHeight / 3 -> "top"
        centerY < rootHeight * 2 / 3 -> "middle"
        else -> "bottom"
    }
}

private fun NodeBounds.height(): Int = bottom - top

private companion object {
    const val DEFAULT_SCREEN_HEIGHT = 2400
}
```

如果 `scrollContainerNodeId = if (snapshot.scrollable) nearestScrollContainerId else nearestScrollContainerId` 显得重复，直接写 `scrollContainerNodeId = nearestScrollContainerId`，并保留滚动节点自身无父滚动容器的行为。

- [x] **Step 5: 跑测试确认通过**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.core.ScreenNodeTreeExtractorTest
```

Expected: `ScreenNodeTreeExtractorTest` 通过。

## Task 2：后端摘要消费 Android 新元数据

**Files:**
- Modify: `backend/src/screen-summary.js`
- Modify: `backend/test/screen-summary.test.js`

- [x] **Step 1: 写失败测试**

在 `backend/test/screen-summary.test.js` 的第一条测试中把 `search` 节点改为包含元数据：

```js
{
  nodeId: 'search',
  text: '',
  contentDescription: '搜索设置',
  role: 'EditText',
  editable: true,
  actionableType: 'input',
  region: 'top',
  inputContext: '设置搜索框',
  scrollContainerNodeId: 'settings_list'
}
```

并增加断言：

```js
const searchItem = summary.actionableItems.find((item) => item.nodeId === 'search');
assert.equal(searchItem.type, 'input');
assert.equal(searchItem.region, 'top');
assert.equal(searchItem.inputContext, '设置搜索框');
assert.equal(searchItem.scrollContainerNodeId, 'settings_list');
```

- [x] **Step 2: 跑测试确认失败**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js
```

Expected: 失败原因是 `actionableItems` 尚未保留 `region`、`inputContext` 或 `scrollContainerNodeId`。

- [x] **Step 3: 最小实现后端保留元数据**

在 `backend/src/screen-summary.js` 的 actionable item map 中追加字段：

```js
      region: node.region || null,
      inputContext: node.inputContext || null,
      scrollContainerNodeId: node.scrollContainerNodeId || null
```

并把 type 生成改为优先使用 Android 字段：

```js
      type: node.actionableType || (node.editable === true ? 'input' : node.scrollable === true ? 'scroll' : 'click'),
```

- [x] **Step 4: 跑测试确认通过**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js
```

Expected: `screen-summary.test.js` 通过。

## Task 3：回归、构建、Android 验证和进度记录

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [x] **Step 1: 跑后端全量测试**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 后端全部测试通过，测试进程不依赖真实 provider key。

- [x] **Step 2: 跑 Android 单元测试**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android 单元测试通过。

- [x] **Step 3: 构建 debug APK**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
```

Expected: debug APK 构建成功。

- [x] **Step 4: Android 启动验证**

若 `adb devices` 有且只有一台授权设备，安全启动 App，不清数据、不改权限、不执行脚本输入：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\codex\.codex\skills\sightsync-real-device-acceptance\scripts\sightsync_acceptance.ps1 -ProjectRoot D:\project -AdbPath D:\platform-tools\adb.exe -Serial 10AC7Z0290001EF -ProxyPort 0 -Launch
```

若无设备，记录 `adb devices` 输出为空作为阻塞原因。

- [x] **Step 5: 更新长期计划**

在 `SIGHTSYNC_LONG_TERM_PLAN.md` 当前进度中追加：

```markdown
- Phase 3 小片 2 已完成：Android 节点树提取新增层级、父节点、区域、可操作类型、滚动容器和输入框上下文元数据，后端 `screen_summary_v2` 保留这些字段用于读屏摘要和 prompt。
```

---

## 自检

- 规格覆盖：覆盖 Phase 3 小片 2 的层级、区域、可操作项、滚动容器、输入框上下文；不覆盖读屏模式 UI、截图策略或阶段验收集。
- 占位符扫描：无 TODO/TBD。
- 类型一致性：Android `ScreenNode` 新字段均有默认值；旧构造点继续编译，后端把字段视为可选。
