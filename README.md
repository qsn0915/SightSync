# SightSync

SightSync 是一个面向盲人用户的 Android AI 无障碍助手原型，包含现有
Android App 和一个小型 Node.js AI proxy。

## Phase 1

The Android app now provides the project skeleton and permission entry screen:

- Android namespace and application id: `com.sightsync.assistant`.
- Accessibility service registration for `AssistantAccessibilityService`.
- Microphone and overlay permission declarations.
- Launch screen checklist for accessibility, microphone, and floating button
  permissions.
- Privacy explanation page for current screen text/screenshot processing by AI
  services.
- Permission state refresh when returning from Android settings.

## Run On Android Emulator

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:installDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Run Backend Tests

```powershell
cd backend
npm test
```

## Run Backend With Qwen

The Android app only talks to the local SightSync proxy. Keep the Qwen key on
the backend side:

```powershell
cd backend
$env:QWEN_API_KEY='<your-qwen-api-key>'
$env:APP_API_TOKEN='dev-token'
npm start
```

Defaults:

- `QWEN_BASE_URL`: `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- `QWEN_MODEL`: `qwen3.6-plus`
- `APP_API_TOKEN`: `dev-token`

## Run Android Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Phase 6 Acceptance

Phase 6 unit, local integration, and manual V1 acceptance steps are documented in
[`docs/phase6-acceptance.md`](docs/phase6-acceptance.md).
