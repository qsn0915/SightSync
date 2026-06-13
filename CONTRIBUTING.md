# Contributing to SightSync

SightSync is an accessibility and safety-sensitive Android project. Small,
well-tested changes are preferred over broad rewrites.

## Before You Start

Read these files first:

- [AGENTS.md](AGENTS.md)
- [V1 product and safety baseline](纲领.md)
- [V2 speech stability design](docs/superpowers/specs/2026-05-18-v2-speech-stability-design.md)
- [V2 boundary update design](docs/superpowers/specs/2026-05-28-v2-boundary-update-design.md)
- [Long-term development plan](SIGHTSYNC_LONG_TERM_PLAN.md)

## Project Boundaries

Do not submit changes that:

- Replace the existing SightSync Android app with a new app or module.
- Add hidden hotword wakeup, hidden background recording, or invisible
  microphone use.
- Store real cloud AI provider API keys in the Android app.
- Add a new AI provider or model choice without maintainer approval.
- Let AI return scripts, shell commands, arbitrary code, or non-allowlisted
  actions.
- Bypass high-risk confirmation for payment, deletion, sending, submission,
  logout, verification-code, password, bank-card, or identity-number flows.
- Upload the full installed-app list or raw device logs by default.

## Development Workflow

1. Open or reference an issue that describes the user scenario and risk level.
2. Keep the change scoped to one phase slice or one bug.
3. Add or update tests before changing behavior when practical.
4. Preserve the existing safety model: local validation before execution,
   structured protocol responses, allowlisted actions, and clear cancellation.
5. Update docs when the user-visible behavior, safety boundary, or verification
   process changes.

## Verification

Run the smallest relevant checks for your change. For Android app behavior,
the normal baseline is:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

For backend behavior:

```powershell
cd backend
npm test
```

For Android app-layer changes, also validate on a local emulator or real device
when available. If device validation is blocked, document the reason in the PR.

## Pull Requests

Every PR should explain:

- What changed.
- Which safety boundary or user scenario it touches.
- Which tests and Android/emulator checks were run.
- Any remaining risk or manual validation still needed.

Do not attach raw screenshots, voice recordings, device logs, or provider keys
unless a maintainer explicitly asks for a redacted artifact.
