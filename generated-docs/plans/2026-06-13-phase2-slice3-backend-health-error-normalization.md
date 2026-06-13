# Phase 2 Slice 3 Backend Health And Error Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add an authenticated backend health-check endpoint and normalize backend proxy error responses so the Android app can later distinguish success, authorization failure, proxy reachability, and provider availability.

**Architecture:** Keep this slice focused on `backend/`; do not change the Android runtime networking entry point yet. Extend `backend/src/server.js` with `GET /v1/health`, centralized `sendError()`, and stable error codes while preserving existing assist/transcribe success payloads.

**Tech Stack:** Node.js built-in `http`, Node test runner, existing backend test helpers.

---

### Task 1: Add Backend Health-Check Contract Tests

**Files:**
- Modify: `backend/test/server.test.js`

- [x] **Step 1: Write failing tests for health success, authorization failure, and provider unavailable**

Add these tests near the top of `backend/test/server.test.js`, after the imports:

```js
test('GET /v1/health requires bearer token', async () => {
  const { server, baseUrl } = await listen();
  try {
    const response = await fetch(`${baseUrl}/v1/health`);
    const body = await response.json();

    assert.equal(response.status, 401);
    assert.deepEqual(body.error, {
      code: 'authorization_failed',
      message: 'unauthorized'
    });
  } finally {
    await close(server);
  }
});

test('GET /v1/health returns provider unavailable when Qwen is not configured', async () => {
  const { server, baseUrl } = await listen();
  try {
    const response = await fetch(`${baseUrl}/v1/health`, {
      headers: { 'Authorization': 'Bearer dev-token' }
    });
    const body = await response.json();

    assert.equal(response.status, 503);
    assert.equal(body.status, 'unavailable');
    assert.deepEqual(body.error, {
      code: 'provider_unavailable',
      message: 'ai provider not configured'
    });
  } finally {
    await close(server);
  }
});

test('GET /v1/health returns ok when provider is configured', async () => {
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus',
      asrModel: 'qwen3-asr-flash'
    }
  });
  try {
    const response = await fetch(`${baseUrl}/v1/health`, {
      headers: { 'Authorization': 'Bearer dev-token' }
    });
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.deepEqual(body, {
      status: 'ok',
      provider: 'configured',
      asrProvider: 'configured'
    });
  } finally {
    await close(server);
  }
});
```

- [x] **Step 2: Run tests to verify RED**

Run: `cd backend; npm test -- --test-name-pattern "GET /v1/health"`

Expected: FAIL because `GET /v1/health` currently returns `404`.

### Task 2: Normalize Existing Error Contract Tests

**Files:**
- Modify: `backend/test/server.test.js`

- [x] **Step 1: Update existing error assertions to expect `error.code/message`**

Change the unsupported action assertion from:

```js
assert.equal(body.error, 'ai response invalid');
assert.match(body.detail, /unsupported action type: RUN_SCRIPT/);
```

to:

```js
assert.equal(body.error.code, 'provider_response_invalid');
assert.equal(body.error.message, 'ai response invalid');
assert.match(body.error.detail, /unsupported action type: RUN_SCRIPT/);
```

Change the ASR timeout assertion from:

```js
assert.equal(result.body.error, 'ai provider timeout');
```

to:

```js
assert.deepEqual(result.body.error, {
  code: 'provider_timeout',
  message: 'ai provider timeout',
  detail: 'provider request timed out'
});
```

- [x] **Step 2: Add a transcribe provider-unavailable normalized error test**

Add this test after the existing ASR success test:

```js
test('POST /v1/transcribe returns normalized provider unavailable error when ASR is not configured', async () => {
  const { server, baseUrl } = await listen();
  try {
    const response = await fetch(`${baseUrl}/v1/transcribe`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        locale: 'zh-CN',
        mimeType: 'audio/mp4',
        audioBase64: 'AAAA'
      })
    });
    const body = await response.json();

    assert.equal(response.status, 503);
    assert.deepEqual(body.error, {
      code: 'provider_unavailable',
      message: 'asr provider not configured'
    });
  } finally {
    await close(server);
  }
});
```

- [x] **Step 3: Run tests to verify RED**

Run: `cd backend; npm test -- --test-name-pattern "provider unavailable|unsupported action|ASR stalls"`

Expected: FAIL because `server.js` still returns string errors and top-level `detail`.

### Task 3: Implement Health Endpoint And Normalized Errors

**Files:**
- Modify: `backend/src/server.js`

- [x] **Step 1: Route `GET /v1/health` before JSON body parsing**

In `createServer()`, replace the first routing block with logic equivalent to:

```js
if (req.method === 'GET' && req.url === '/v1/health') {
  if (!isAuthorized(req)) {
    sendError(res, 401, 'authorization_failed', 'unauthorized');
    return;
  }
  sendHealth(res, getQwenConfig());
  return;
}

if (req.method !== 'POST' || !['/v1/assist', '/v1/transcribe'].includes(req.url)) {
  sendError(res, 404, 'not_found', 'not found');
  return;
}
```

- [x] **Step 2: Replace direct backend error responses with `sendError()`**

Use these mappings:

```js
sendError(res, 401, 'authorization_failed', 'unauthorized');
sendError(res, 400, 'invalid_json', 'invalid json');
sendError(res, 400, 'invalid_request', requestValidation.reason);
sendError(res, 503, 'provider_unavailable', 'asr provider not configured');
sendError(res, 504, 'provider_timeout', 'ai provider timeout', error.message);
sendError(res, 502, 'provider_response_invalid', 'ai response invalid', error instanceof Error ? error.message : String(error));
```

- [x] **Step 3: Add helper functions**

Add helpers near `sendJson()`:

```js
function sendHealth(res, qwenConfig) {
  if (!isQwenConfigured(qwenConfig)) {
    sendJson(res, 503, {
      status: 'unavailable',
      error: {
        code: 'provider_unavailable',
        message: 'ai provider not configured'
      }
    });
    return;
  }

  sendJson(res, 200, {
    status: 'ok',
    provider: 'configured',
    asrProvider: 'configured'
  });
}

function sendError(res, statusCode, code, message, detail) {
  const error = { code, message };
  if (detail) error.detail = detail;
  sendJson(res, statusCode, { error });
}
```

- [x] **Step 4: Run targeted backend tests**

Run: `cd backend; npm test -- --test-name-pattern "GET /v1/health|provider unavailable|unsupported action|ASR stalls"`

Expected: PASS.

### Task 4: Run Regression And Android Smoke Validation

**Files:**
- Modify: `SIGHTSYNC_LONG_TERM_PLAN.md`

- [x] **Step 1: Run full backend tests**

Run: `cd backend; npm test`

Expected: PASS.

- [x] **Step 2: Run App checks because the workspace contains App changes from Phase 2 slices 1-2**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

Run: `.\gradlew.bat :app:assembleDebug`

Expected: PASS.

- [x] **Step 3: Install and launch App on Android emulator**

Run:

```powershell
$adb='D:\AndroidDev\Sdk\platform-tools\adb.exe'
& $adb install -r 'D:\project\app\build\outputs\apk\debug\app-debug.apk'
& $adb shell logcat -c
& $adb shell am force-stop com.sightsync.assistant
& $adb shell am start -n com.sightsync.assistant/.MainActivity
Start-Sleep -Seconds 5
& $adb shell pidof com.sightsync.assistant
& $adb shell logcat -d -t 500 | Select-String -Pattern 'FATAL EXCEPTION.*com\.sightsync\.assistant|Process: com\.sightsync\.assistant|E AndroidRuntime.*com\.sightsync\.assistant'
```

Expected: install succeeds, process id is printed, no SightSync crash lines are printed.

- [x] **Step 4: Update long-term plan progress**

Append this bullet under current progress in `SIGHTSYNC_LONG_TERM_PLAN.md`:

```markdown
- Phase 2 灏忕墖 3 宸插畬鎴愶細鍚庣浠ｇ悊鏂板閴存潈鍋ュ悍妫€鏌ョ鐐癸紝骞跺皢閴存潈銆佽姹傛牎楠屻€乸rovider 涓嶅彲鐢ㄣ€乸rovider 瓒呮椂鍜?provider 鍝嶅簲寮傚父褰掍竴鍖栦负绋冲畾閿欒鐮併€?```

## Self-Review

- Spec coverage: The plan covers backend health check, bearer-token authorization, provider availability, and normalized error bodies. It intentionally does not wire Android dynamic proxy use; that belongs to Phase 2 slice 4.
- Placeholder scan: No TBD/TODO placeholders remain.
- Type consistency: Error envelope is consistently `{ error: { code, message, detail? } }`; health success is `{ status, provider, asrProvider }`.

