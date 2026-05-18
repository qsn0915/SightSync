# Continuous Listening Design

## Goal

SightSync should stop requiring a tap before every voice command. After the user has enabled the accessibility service and microphone permission, the assistant enters a visible continuous listening mode, repeatedly accepts short voice commands, and routes each recognized command through the existing screen collection, AI planning, confirmation, and action execution pipeline.

## Scope

This design does not implement hidden or unconditional ambient surveillance. The microphone is active only while SightSync is running its visible assistant service. The user can stop listening from the overlay, notification, or by saying a stop command such as "停止聆听" or "取消".

## Approach

The existing one-shot `AssistantSessionManager.onAssistantRequested()` path remains the execution unit for a single utterance. A new continuous listening state in `AssistantSessionManager` owns a loop:

1. Speak a short startup prompt.
2. Listen for one short utterance.
3. If the utterance is a stop command, end continuous mode.
4. Otherwise process it through the same AI and action code used today.
5. Repeat after the command finishes.

While TTS is speaking or a command is executing, recording is not active. This prevents the assistant from hearing its own voice and avoids overlapping requests.

## Android Visibility

Continuous listening uses a foreground notification from `AssistantAccessibilityService`, with microphone foreground service permissions declared in the manifest. The notification states that SightSync is listening and remains visible while the service is active.

## Controls

The overlay becomes a toggle:

- When continuous mode is active, tapping it stops listening.
- When continuous mode is stopped, tapping it starts listening again.
- Speaking "停止聆听", "停止监听", "暂停助手", "取消", or "退出" stops continuous mode.

## Verification

Unit tests cover the state machine before production code changes:

- Continuous mode processes more than one recognized utterance.
- A stop utterance exits the loop without calling AI.
- Tapping the overlay while active stops listening.
- Manifest declares microphone foreground service permissions and service type.

Manual verification installs the debug app on the local emulator, enables required permissions, confirms the visible overlay/notification, and checks that voice commands can be issued without tapping before each command.
