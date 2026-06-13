## Summary

- Briefly describe the change.

## Safety Scope

- [ ] Does not add hidden hotword wakeup or hidden background recording.
- [ ] Does not store real cloud AI provider keys in the Android app.
- [ ] Does not bypass structured protocol validation or action allowlists.
- [ ] Does not bypass confirmation/refusal for high-risk actions.
- [ ] Does not upload raw screenshots, installed app lists, or device logs by default.

## Verification

- [ ] `.\gradlew.bat :app:testDebugUnitTest`
- [ ] `.\gradlew.bat :app:assembleDebug`
- [ ] `cd backend; npm test`
- [ ] Android emulator or real-device validation, or documented blocker.

## Notes

- Remaining risks, blockers, or follow-up work.
