# Security Policy

SightSync touches accessibility services, microphone input, screen content, and
AI-assisted action execution. Treat privacy and safety issues as security issues.

## Supported Branch

Security review currently focuses on the default `main` branch. The project is
early-stage and does not yet maintain separate long-term support branches.

## What To Report

Please report issues such as:

- Real provider API keys or tokens committed to the repository.
- Hidden or misleading microphone usage.
- Screen content, screenshots, installed app lists, or raw logs being uploaded
  outside the documented data-minimization rules.
- AI responses bypassing the structured action protocol.
- Non-allowlisted actions being executed.
- High-risk actions such as payment, deletion, sending, submission, password, or
  verification-code flows executing without confirmation.
- Bugs that allow a stale node, changed package, or changed page state to be
  acted on.

## Reporting Privately

Do not publish exploit details, real user data, full screenshots, voice
recordings, provider keys, or device logs in public issues.

Use GitHub's private vulnerability reporting flow if it is available for this
repository. If private reporting is not available, open a minimal public issue
that says you have a security or privacy report and wait for a maintainer to
coordinate a private channel.

## Safe Reproduction

When possible, reproduce with:

- Mock backend responses.
- Test fixtures instead of real user content.
- Redacted logs.
- Emulator screenshots that do not contain personal data.

Do not test SightSync against accounts, apps, systems, or repositories that you
do not own or lack permission to review.
