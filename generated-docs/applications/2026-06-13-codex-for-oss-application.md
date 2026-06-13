# Codex for Open Source Application Draft

Date: 2026-06-13

Official form: https://openai.com/zh-Hans-CN/form/codex-for-oss/

The form asks for short answers. The three free-text fields each have a 500
character limit on the public page. Keep the application honest: SightSync is
early-stage, so do not claim broad adoption, downloads, or a large user base.

## Before Submitting

- Make sure the GitHub profile visibility is public.
- Make sure the repository visibility is public.
- Push the latest public-packaging commit before submitting.
- Re-check public metrics on GitHub before copying the final answer.
- Fill in the ChatGPT account email and OpenAI organization ID manually.

## Form Fields

### Last Name

Fill manually.

### First Name

Fill manually.

### Email

Use the email address associated with the ChatGPT account.

### GitHub Username

```text
qsn0915
```

### GitHub Repository URL

```text
https://github.com/qsn0915/SightSync
```

### Role

Recommended selection:

```text
Primary maintainer
```

Chinese page selection:

```text
主要维护者
```

## Why This Repository Qualifies

Chinese version, under 500 characters:

```text
SightSync 是一个 AGPL-3.0 的公开 Android AI 无障碍助手，目标是为盲人用户探索可审计的语音读屏和受限手机操作。当前公开指标仍早期（约 1 star、0 fork、暂无下载量），但项目对中文 Android 无障碍和安全 AI agent 生态有明确价值：它公开了可见连续聆听、节点优先/截图兜底、敏感字段本地脱敏、结构化白名单动作和高风险二次确认等边界，并已有 V2 规格、测试和模拟器验收记录。
```

English version, under 500 characters:

```text
SightSync is a public AGPL-3.0 Android AI accessibility assistant for blind users. Public adoption is still early (about 1 star, 0 forks, no download metrics), but the project is important to the Android accessibility and safe-agent ecosystem: it documents visible continuous listening, node-first/screenshot-fallback screen understanding, local redaction, structured allowlisted actions, high-risk confirmation, V2 specs, tests, and emulator acceptance.
```

## Interest

Recommended selections:

```text
Project API credits
Codex Security
```

Chinese page selections:

```text
项目的 API 额度
Codex Security
```

Rationale: API credits are the stronger fit for day-to-day OSS maintenance and
acceptance workflows. Codex Security is worth selecting because the repository
touches microphone use, screen data, accessibility actions, and backend proxy
boundaries, but access is conditional.

## OpenAI Organization ID

Fill manually from the OpenAI Platform organization page.

## How You Will Use API Credits

Chinese version, under 500 characters:

```text
我会把 API 额度用于 SightSync 的核心 OSS 维护：辅助 PR review，生成和检查 Android 无障碍、语音状态机、后端协议和风控相关测试；分析失败日志并整理验收记录；验证 AI 响应不能绕过结构化动作协议、敏感字段脱敏、高风险确认和取消语义。额度只用于维护和验证工作，不会把 OpenAI key 硬编码进 Android App。
```

English version, under 500 characters:

```text
I will use API credits for SightSync's core OSS maintenance: PR review, test generation and review for Android accessibility, the voice state machine, backend protocol validation, and risk controls; failure-log analysis; acceptance summaries; and checks that AI responses cannot bypass structured actions, redaction, high-risk confirmation, or cancellation semantics. Credits will not be embedded in the Android app.
```

## Additional Notes

Chinese version, under 500 characters:

```text
我理解该项目目前不是广泛采用项目，因此申请重点不是下载量，而是生态重要性：SightSync 把 AI 手机助手放在无障碍、隐私和安全边界内开源实现，尤其关注中文 Android 场景。若获得支持，我会继续把规格、测试、验收和安全边界公开沉淀，并优先用于维护工作流而非商业闭源用途。
```

English version, under 500 characters:

```text
I understand SightSync is not yet widely adopted; the application is based on ecosystem importance rather than downloads. It open-sources a constrained AI phone-assistant architecture for accessibility, privacy, and safety, with a Chinese Android focus. If supported, I will keep specs, tests, acceptance records, and safety boundaries public, and use benefits for OSS maintenance rather than closed commercial use.
```

## Shorter Backup Answers

Use these if the form rejects length or punctuation.

Why this repo qualifies:

```text
SightSync is a public AGPL-3.0 Android AI accessibility assistant for blind users. It is early-stage, but important for safe mobile-agent and Android accessibility work: visible listening, no hidden hotword, backend-held keys, local redaction, node-first screen understanding, structured allowlisted actions, high-risk confirmation, V2 specs, tests, and emulator acceptance are all developed in the open.
```

API credits use:

```text
API credits will support OSS maintenance: PR review, tests for Android accessibility and backend protocol changes, failure-log analysis, acceptance summaries, and security/safety checks around redaction, action allowlists, high-risk confirmation, and cancellation. Credits will not be placed in the Android app.
```

Additional notes:

```text
SightSync has small public metrics today, so I am applying based on ecosystem importance. The goal is to make AI-assisted phone accessibility auditable and safe for Chinese Android users, with public specs, tests, acceptance records, and maintainer workflows.
```
