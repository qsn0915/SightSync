# Phase 3 小片 1：屏幕摘要协议和后端 Prompt 调整实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将后端从“列出节点文字”推进到“按页面主题、主要内容、可操作项和风险提示组织读屏摘要”，并把同一摘要协议提供给 Qwen prompt。

**Architecture:** Android 端暂不改变采集结构，继续上传现有 `ScreenContext.nodes` 与按既有策略附带的截图。后端新增纯函数把节点树压缩成 `screen_summary_v2` 摘要对象，本地 fallback 读屏和 Qwen prompt 共用该摘要，避免重复拼装与 prompt 中直接塞完整截图 base64。

**Tech Stack:** Node.js ESM 后端、`node:test`、现有 Android Kotlin 客户端协议、现有 SightSync V1 白名单动作协议。

---

## 文件范围

- 新增：`backend/src/screen-summary.js`
  - 从现有 `request.screen` 派生结构化摘要对象。
  - 生成本地读屏话术。
  - 不保存、不输出 app token、AI key、截图 base64 或敏感字段原文以外的新数据。
- 新增：`backend/test/screen-summary.test.js`
  - 覆盖摘要结构、可操作项、风险提示和本地话术。
- 修改：`backend/src/protocol.js`
  - `createScreenReadingResponse()` 改为使用屏幕摘要话术。
  - 不改变动作白名单和响应 JSON 协议。
- 修改：`backend/src/qwen.js`
  - system prompt 增加 Phase 3 读屏输出要求。
  - user prompt 增加 `screen_summary_v2` 摘要 JSON，并继续保留必要节点 JSON。
- 修改：`backend/test/protocol.test.js`
  - 更新读屏 fallback 断言，要求包含主要内容/可操作项等结构化话术。
- 修改：`backend/test/qwen.test.js`
  - 先写失败断言，要求 prompt 包含摘要协议和读屏结构要求，且不包含截图 base64。
- 修改：`SIGHTSYNC_LONG_TERM_PLAN.md`
  - 小片完成后记录 Phase 3 小片 1 已完成，同时保留 Phase 2 小片 5 被用户暂停/跳过验收的事实。

## 安全边界

- 不新增或变更 `actions.type`。
- 不让后端 AI 直接决定安全策略；Android 端既有协议校验和风险判断不变。
- 不在 Android App 内写入 AI provider key。
- 不改变截图附带策略；本小片只调整摘要和 prompt，截图仍按既有 `screenshotBase64` 输入走 image_url。
- 不做 Phase 3 小片 2 的节点树增强，不做小片 3 的读屏模式 UI/语音命令。

## Task 1：新增后端屏幕摘要协议

**Files:**
- Create: `backend/src/screen-summary.js`
- Create: `backend/test/screen-summary.test.js`
- Modify: `backend/src/protocol.js`
- Modify: `backend/test/protocol.test.js`

- [ ] **Step 1: 写失败测试**

在 `backend/test/screen-summary.test.js` 添加测试：

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildScreenSummary,
  buildScreenSummarySpoken
} from '../src/screen-summary.js';

test('buildScreenSummary groups page content, actionable items, and risk hints', () => {
  const summary = buildScreenSummary({
    packageName: 'com.android.settings',
    activityName: 'Settings',
    nodes: [
      { nodeId: 'title', text: '设置', role: 'TextView', clickable: false },
      { nodeId: 'wlan', text: 'WLAN', role: 'Button', clickable: true },
      { nodeId: 'pay', text: '付款设置', role: 'Button', clickable: true },
      { nodeId: 'search', text: '', contentDescription: '搜索设置', role: 'EditText', editable: true }
    ],
    screenshotBase64: 'raw-image-data'
  });

  assert.equal(summary.protocol, 'screen_summary_v2');
  assert.equal(summary.page.packageName, 'com.android.settings');
  assert.equal(summary.page.screenshotAttached, true);
  assert.deepEqual(summary.mainContent, ['设置', 'WLAN', '付款设置', '搜索设置']);
  assert.deepEqual(summary.actionableItems.map((item) => item.nodeId), ['wlan', 'pay', 'search']);
  assert.deepEqual(summary.riskHints, ['付款']);
});

test('buildScreenSummarySpoken describes content before actions without executing anything', () => {
  const spoken = buildScreenSummarySpoken({
    protocol: 'screen_summary_v2',
    page: { packageName: 'com.android.settings', activityName: 'Settings', screenshotAttached: false },
    nodeStats: { total: 3, labeled: 3, clickable: 2, editable: 0, scrollable: 0 },
    mainContent: ['设置', 'WLAN', '蓝牙'],
    actionableItems: [
      { nodeId: 'wlan', label: 'WLAN', role: 'Button', type: 'click' },
      { nodeId: 'bt', label: '蓝牙', role: 'Button', type: 'click' }
    ],
    riskHints: []
  });

  assert.match(spoken, /主要内容/);
  assert.match(spoken, /可操作项/);
  assert.match(spoken, /WLAN/);
  assert.doesNotMatch(spoken, /我会点击/);
});
```

在 `backend/test/protocol.test.js` 的本地读屏测试中增加：

```js
assert.match(response.spoken, /主要内容/);
assert.match(response.spoken, /可操作项/);
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js protocol.test.js
```

Expected: 失败原因是 `../src/screen-summary.js` 不存在或 `response.spoken` 尚未包含结构化读屏话术。

- [ ] **Step 3: 最小实现摘要协议**

实现 `backend/src/screen-summary.js`：

```js
const MAX_MAIN_CONTENT = 12;
const MAX_ACTIONABLE_ITEMS = 10;
const RISK_KEYWORDS = ['支付', '付款', '转账', '订单', '删除', '发送', '提交', '验证码', '银行卡', '密码'];

export function buildScreenSummary(screen = {}) {
  const nodes = Array.isArray(screen.nodes) ? screen.nodes : [];
  const labeled = nodes
    .map(nodeLabel)
    .filter(Boolean);
  const mainContent = unique(labeled).slice(0, MAX_MAIN_CONTENT);
  const actionableItems = nodes
    .filter(isActionable)
    .map((node) => ({
      nodeId: node.nodeId,
      label: nodeLabel(node) || node.role || '未命名控件',
      role: node.role || 'Unknown',
      type: node.editable === true ? 'input' : node.scrollable === true ? 'scroll' : 'click'
    }))
    .filter((item) => item.nodeId && item.label)
    .slice(0, MAX_ACTIONABLE_ITEMS);
  const riskHints = RISK_KEYWORDS.filter((keyword) =>
    `${screen.packageName ?? ''} ${screen.activityName ?? ''} ${labeled.join(' ')}`.includes(keyword)
  );

  return {
    protocol: 'screen_summary_v2',
    page: {
      packageName: screen.packageName || '',
      activityName: screen.activityName || null,
      screenshotAttached: typeof screen.screenshotBase64 === 'string' && screen.screenshotBase64.trim().length > 0
    },
    nodeStats: {
      total: nodes.length,
      labeled: labeled.length,
      clickable: nodes.filter((node) => node.clickable === true).length,
      editable: nodes.filter((node) => node.editable === true).length,
      scrollable: nodes.filter((node) => node.scrollable === true).length
    },
    mainContent,
    actionableItems,
    riskHints: unique(riskHints)
  };
}

export function buildScreenSummarySpoken(summary) {
  if (!summary.mainContent?.length) {
    return '我暂时没有读取到当前页面的主要文字。';
  }

  const parts = [`当前页面主要内容有：${summary.mainContent.join('，')}。`];
  if (summary.actionableItems?.length) {
    parts.push(`可操作项有：${summary.actionableItems.map((item) => item.label).join('，')}。`);
  }
  if (summary.riskHints?.length) {
    parts.push(`我注意到页面可能涉及${summary.riskHints.join('、')}，相关操作需要谨慎确认。`);
  }
  return parts.join('');
}

function isActionable(node) {
  return node?.clickable === true || node?.editable === true || node?.scrollable === true;
}

function nodeLabel(node) {
  return [node?.text, node?.contentDescription]
    .find((value) => typeof value === 'string' && value.trim().length > 0)
    ?.trim();
}

function unique(values) {
  return [...new Set(values)];
}
```

在 `backend/src/protocol.js` 中导入并使用：

```js
import { buildScreenSummary, buildScreenSummarySpoken } from './screen-summary.js';
```

并将 `createScreenReadingResponse()` 的 spoken 生成替换为：

```js
const summary = buildScreenSummary(request?.screen);
return {
  spoken: buildScreenSummarySpoken(summary),
  requiresConfirmation: false,
  actions: []
};
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```powershell
cd D:\project\backend
npm test -- screen-summary.test.js protocol.test.js
```

Expected: 目标测试通过。

## Task 2：调整 Qwen prompt 使用摘要协议

**Files:**
- Modify: `backend/src/qwen.js`
- Modify: `backend/test/qwen.test.js`

- [ ] **Step 1: 写失败测试**

在 `backend/test/qwen.test.js` 的 `buildQwenChatCompletionRequest asks for strict JSON and disables thinking` 测试中增加断言：

```js
assert.match(payload.messages[0].content, /页面主题/);
assert.match(payload.messages[0].content, /主要内容/);
assert.match(payload.messages[0].content, /可操作项/);
assert.match(payload.messages[0].content, /风险提示/);
assert.match(payload.messages[1].content, /screen_summary_v2/);
assert.match(payload.messages[1].content, /屏幕摘要 JSON/);
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
cd D:\project\backend
npm test -- qwen.test.js
```

Expected: 失败原因是 prompt 尚未包含 `screen_summary_v2` 和结构化读屏要求。

- [ ] **Step 3: 最小实现 prompt 调整**

在 `backend/src/qwen.js` 导入摘要构建器：

```js
import { buildScreenSummary } from './screen-summary.js';
```

在 system prompt 规则中加入：

```text
- 用户问“这里有什么”“读一下当前页面”等理解类问题时，只朗读 spoken，actions 必须为空；spoken 必须按“页面主题、主要内容、可操作项、风险提示”组织，无法确定时要说明不确定，不要编造。
```

在 `buildQwenChatCompletionRequest()` 中先构建摘要：

```js
const screenSummary = buildScreenSummary(request?.screen);
```

并将 userText 中节点行之前加入：

```js
`屏幕摘要 JSON：${JSON.stringify(screenSummary)}`,
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```powershell
cd D:\project\backend
npm test -- qwen.test.js screen-summary.test.js protocol.test.js
```

Expected: 目标测试通过。

## Task 3：回归、构建和进度记录

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [ ] **Step 1: 跑后端全量测试**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: 全部后端测试通过，且测试进程不依赖真实 provider key。

- [ ] **Step 2: 跑 Android 单元测试**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android 单元测试通过。此小片不修改 Android App 代码，如失败需先判断是否由已有未提交改动引起。

- [ ] **Step 3: 构建 debug APK**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
```

Expected: debug APK 构建成功。

- [ ] **Step 4: Android 验证**

本小片不改 Android App 层代码；若有可用设备，只做安全启动验证，不执行高风险动作、不清数据、不改权限：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\codex\.codex\skills\sightsync-real-device-acceptance\scripts\sightsync_acceptance.ps1 -ProjectRoot D:\project -AdbPath D:\platform-tools\adb.exe -Serial 10AC7Z0290001EF -ProxyPort 0 -Launch
```

Expected: App 可启动。若设备不可用，记录阻塞原因。

- [ ] **Step 5: 更新长期计划**

在 `SIGHTSYNC_LONG_TERM_PLAN.md` 当前进度中追加：

```markdown
- Phase 2 小片 5 已按用户指示暂停，未作为 Phase 2 最终验收通过证据。
- Phase 3 小片 1 已完成：新增后端 `screen_summary_v2` 摘要协议，本地读屏 fallback 和 Qwen prompt 改为围绕页面主题、主要内容、可操作项和风险提示组织回答。
```

---

## 自检

- 规格覆盖：覆盖 Phase 3 小片 1 的摘要协议和后端 prompt 调整；不覆盖小片 2 节点增强、小片 3 读屏模式、小片 4 截图策略。
- 占位符扫描：无 TODO/TBD。
- 类型一致性：`screen_summary_v2` 是后端内部 prompt/fallback 协议，不改变 Android `AssistRequest` / `AssistResponse` 线上 JSON 结构。
