# Phase 3 Screen Understanding Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute Phase 3 acceptance for SightSync screen understanding V2 and record whether the phase can be accepted before any Phase 4 work begins.

**Architecture:** Acceptance is evidence-based and split into automated checks, deterministic backend fixture checks, Android build/unit verification, and device runtime validation. If no Android device or emulator is online, record Phase 3 as blocked for final acceptance instead of treating automated checks as full acceptance.

**Tech Stack:** Node.js ESM, Android Gradle, Kotlin/JUnit unit tests, adb, generated Markdown acceptance records.

---

## File Scope

- Create: `generated-docs/acceptance/2026-06-15-phase3-screen-understanding-acceptance.md`
  - Final Phase 3 acceptance record with evidence, pass/block status, risks, and required follow-up.
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`
  - Record Phase 3 acceptance outcome.
- Create only if adb has no online device: `generated-docs/logs/2026-06-15-phase3-acceptance-android-validation-blocker.md`
  - Local-only blocker record.

## Safety Boundaries

- Do not enter Phase 4 during this acceptance slice.
- Do not add, remove, or relax any action type.
- Do not script Android taps, text input, permission changes, accessibility setting writes, uninstall, clear data, or destructive device commands.
- Do not upload raw logs or screenshots.
- Do not include real API keys, app tokens, full screenshot base64, or raw user logs in generated docs.

---

## Task 1: Run Deterministic Screen Understanding Fixture Checks

**Files:**
- No file changes in this task.

- [x] **Step 1: Run backend fixture command**

Run:

```powershell
cd D:\project\backend
@'
import { buildScreenSummary, buildScreenSummarySpoken } from './src/screen-summary.js';
import { createFallbackAssistResponse } from './src/protocol.js';

const fixtures = [
  {
    name: 'settings-rich',
    command: '详细读一下当前页面',
    screen: {
      packageName: 'com.android.settings',
      activityName: 'Settings',
      nodes: [
        { nodeId: 'title', text: '设置', role: 'TextView' },
        { nodeId: 'wifi', text: 'WLAN', role: 'Button', clickable: true, actionableType: 'click', region: 'top' },
        { nodeId: 'bt', text: '蓝牙', role: 'Button', clickable: true, actionableType: 'click', region: 'top' },
        { nodeId: 'display', text: '显示与亮度', role: 'Button', clickable: true, actionableType: 'click', region: 'middle' }
      ],
      screenshotBase64: null,
      screenshotPolicy: { attachScreenshot: false, reason: 'node_tree_sufficient', privacyBlocked: false }
    }
  },
  {
    name: 'browser-sparse-webview',
    command: '这里有什么',
    screen: {
      packageName: 'com.android.browser',
      activityName: 'Browser',
      nodes: [
        { nodeId: 'web', text: null, contentDescription: null, role: 'WebView', scrollable: true, actionableType: 'scroll' }
      ],
      screenshotBase64: 'redacted-test-image',
      screenshotPolicy: { attachScreenshot: true, reason: 'sparse_node_tree', privacyBlocked: false }
    }
  },
  {
    name: 'chat-list',
    command: '简短读屏',
    screen: {
      packageName: 'com.tencent.mm',
      activityName: 'Chats',
      nodes: [
        { nodeId: 'title', text: '微信', role: 'TextView' },
        { nodeId: 'chat_1', text: '妈妈 早上好', role: 'Button', clickable: true, actionableType: 'click', region: 'middle' },
        { nodeId: 'chat_2', text: '同事 项目更新', role: 'Button', clickable: true, actionableType: 'click', region: 'middle' },
        { nodeId: 'search', contentDescription: '搜索', role: 'Button', clickable: true, actionableType: 'click', region: 'top' }
      ],
      screenshotBase64: null,
      screenshotPolicy: { attachScreenshot: false, reason: 'node_tree_sufficient', privacyBlocked: false }
    }
  },
  {
    name: 'input-page',
    command: '只说明可操作项',
    screen: {
      packageName: 'com.example.form',
      activityName: 'Edit',
      nodes: [
        { nodeId: 'title', text: '编辑资料', role: 'TextView' },
        { nodeId: 'label_name', text: '昵称', role: 'TextView' },
        { nodeId: 'input_name', contentDescription: '请输入昵称', role: 'EditText', editable: true, actionableType: 'input', inputContext: '昵称', region: 'middle' },
        { nodeId: 'save', text: '保存', role: 'Button', clickable: true, actionableType: 'click', region: 'bottom' }
      ],
      screenshotBase64: null,
      screenshotPolicy: { attachScreenshot: false, reason: 'node_tree_sufficient', privacyBlocked: false }
    }
  },
  {
    name: 'sensitive-payment',
    command: '详细读一下当前页面',
    screen: {
      packageName: 'com.example.pay',
      activityName: 'Payment',
      nodes: [
        { nodeId: 'title', text: '付款', role: 'TextView', privacySensitive: false },
        { nodeId: 'code', text: '[验证码已隐藏]', role: 'TextView', privacySensitive: true },
        { nodeId: 'submit', text: '提交', role: 'Button', clickable: true, actionableType: 'click', region: 'bottom' }
      ],
      screenshotBase64: null,
      screenshotPolicy: { attachScreenshot: false, reason: 'privacy_sensitive_content', privacyBlocked: true }
    }
  }
];

for (const fixture of fixtures) {
  const summary = buildScreenSummary(fixture.screen);
  const local = createFallbackAssistResponse({ utterance: fixture.command, screen: fixture.screen });
  console.log(JSON.stringify({
    name: fixture.name,
    title: summary.page.title,
    screenshotAttached: summary.page.screenshotAttached,
    screenshotPolicy: summary.page.screenshotPolicy,
    riskHints: summary.riskHints,
    actions: local.actions,
    spoken: local.spoken
  }));
}
'@ | node --input-type=module
```

Expected:

- Five JSON lines are printed.
- `settings-rich`, `chat-list`, and `input-page` have `screenshotAttached: false` and `screenshotPolicy.reason: "node_tree_sufficient"`.
- `browser-sparse-webview` has `screenshotAttached: true` and `screenshotPolicy.reason: "sparse_node_tree"`.
- `sensitive-payment` has `screenshotAttached: false`, `screenshotPolicy.reason: "privacy_sensitive_content"`, and risk hints include payment/submission or sensitive keywords.
- All local fallback responses have `actions: []`.

---

## Task 2: Run Regression Tests And Build

**Files:**
- No file changes in this task.

- [x] **Step 1: Run backend full tests without provider keys**

Run:

```powershell
cd D:\project\backend
Remove-Item Env:QWEN_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
npm test
```

Expected: all backend tests pass.

- [x] **Step 2: Run Android unit tests**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:testDebugUnitTest
```

Expected: Android debug unit tests pass.

- [x] **Step 3: Build debug APK**

Run:

```powershell
cd D:\project
.\gradlew.bat :app:assembleDebug
```

Expected: debug APK build succeeds.

- [x] **Step 4: Check diff whitespace**

Run:

```powershell
cd D:\project
git diff --check
```

Expected: no output.

---

## Task 3: Android Runtime Validation Or Blocker

**Files:**
- Create only if blocked: `generated-docs/logs/2026-06-15-phase3-acceptance-android-validation-blocker.md`

- [x] **Step 1: Check device availability**

Run:

```powershell
cd D:\project
D:\platform-tools\adb.exe devices
```

Expected: if exactly one authorized online device is attached, proceed to Step 2. If no authorized online device is attached, create blocker note and mark device runtime validation blocked.

- [x] **Step 2: If a device is online, rebuild, install, and launch safely**

Run only when exactly one online device is available:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\codex\.codex\skills\sightsync-real-device-acceptance\scripts\sightsync_acceptance.ps1 -ProjectRoot D:\project -AdbPath D:\platform-tools\adb.exe -ProxyPort 8787 -Build -Install -Launch
```

Expected: the current workspace debug APK is rebuilt, installed with `adb install -r`, and launched with `adb reverse tcp:8787 tcp:8787` for the computer backend proxy, without clearing data, changing permissions, or scripting user input.

- [x] **Step 3: If blocked, create local blocker note**

Note: the initial `unauthorized` blocker was later resolved after the user enabled USB debugging. Runtime validation then proceeded with the computer backend proxy, rebuilt and installed the current app, and failed because real provider calls and the voice loop were not stable enough for acceptance. The final result is recorded in `generated-docs/acceptance/2026-06-15-phase3-screen-understanding-acceptance.md` and `generated-docs/handoffs/2026-06-15-phase3-runtime-ai-voice-blocker.md`.

Create:

```markdown
# Phase 3 阶段验收 Android 运行验证阻塞记录

日期：2026-06-15

阶段：Phase 3 屏幕理解 V2 阶段验收。

阻塞原因：

- 执行 `D:\platform-tools\adb.exe devices` 后未发现已授权在线 Android 虚拟机或真机，或设备处于 `unauthorized` 状态。
- 因没有可用设备，本次无法安装、启动或进行真实页面读屏运行验收。

影响：

- 自动化测试、后端 fixture 和 debug 构建可以完成。
- Phase 3 最终验收不能判定为通过，必须等待设备在线后补跑真实设置页、浏览器网页、聊天列表、输入页面和敏感页面读屏验收。
```

---

## Task 4: Write Acceptance Record And Update Progress

**Files:**
- Create: `generated-docs/acceptance/2026-06-15-phase3-screen-understanding-acceptance.md`
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [x] **Step 1: Write acceptance record**

Create an acceptance record containing:

- Branch and git status summary.
- Automated command results from Task 2.
- Fixture results from Task 1.
- Device runtime validation result from Task 3.
- Phase 3 acceptance gate table:
  - common settings/browser/chat/input pages structural reading;
  - page title, main content, actionable items, risk hints;
  - node-sufficient no screenshot;
  - sparse nodes screenshot policy;
  - local sensitive redaction and privacy screenshot blocking;
  - no fabrication when information is insufficient.
- Final decision:
  - `通过` only if automated, fixture, build, and device runtime validation all pass.
  - `阻塞` if device runtime validation is unavailable.

- [x] **Step 2: Update long-term progress**

Append or update current progress with a concrete Phase 3 acceptance line:

```markdown
- Phase 3 小片 5 已执行阶段验收：自动化测试、后端 fixture 和 debug 构建通过；Android 运行验收因当前无在线设备阻塞，Phase 3 尚不能判定为最终通过。
```

- [x] **Step 3: Final status check**

Run:

```powershell
cd D:\project
git status --short --branch
```

Expected: generated acceptance documents are visible; local logs remain untracked under `generated-docs/logs/`.

---

## Self-Review

- Spec coverage: covers Phase 3 acceptance gates and explicitly blocks Phase 4 until runtime validation passes.
- Placeholder scan: no TODO/TBD placeholders.
- Safety: no device-destructive commands, no scripted input, no raw screenshots, no API keys.
