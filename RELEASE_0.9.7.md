# UniversalRemote v0.9.7 — Store Release Candidate

v0.9.7 prepares UniversalRemote for Google Play and App Store testing without pretending that unsigned or placeholder-identity artifacts are production releases.

## Android

- compileSdk/targetSdk raised to API 36; versionCode 20.
- Google Play AAB + GitHub APK pipeline added.
- Signed store artifacts are produced only when permanent package IDs and upload-key secrets are configured; `com.example.*` is rejected for the store path.
- Companion now shows a prominent Accessibility disclosure and requires affirmative consent before opening Android Accessibility settings.
- Physical-LAN trust boundary tightened; discovery-controlled URLs must use the literal discovery-source IPv4.
- Samsung, Hue and Google Cast credentialed/TLS connections are bound to the selected physical Android Network where available.
- Signing files are ignored/checked and Companion crypto tests are included in CI.

## iOS / iPadOS

- Version 0.9.7 / build 20; deployment target remains iOS 16.0, including iOS 17 devices.
- CI requires Xcode 26+ and iPhoneOS SDK 26+.
- Privacy manifest baseline is bundled and checked.
- Companion crypto/tamper unit tests run on the simulator.
- Real IPA/TestFlight export remains gated on valid Apple Distribution signing secrets.

## Store review preparation

- Added `PRIVACY_POLICY.md`, `ACCESSIBILITY_DECLARATION.md`, and `STORE_RELEASE_CHECKLIST.md`.
- Added weekly Dependabot monitoring for Gradle and GitHub Actions.

This is an RC. Google Play production AAB and App Store/TestFlight IPA must be generated from the final green commit after permanent application identifiers, signing credentials, store declarations, public privacy URL, and final real-device tests are complete.
