# UniversalRemote v0.9.7 Store Release Checklist

This checklist is intentionally fail-closed: production store artifacts should not be treated as ready until every relevant item is complete.

## Source identity

- [ ] Choose the permanent Google Play application ID for the main app and set repository variable `ANDROID_APPLICATION_ID`.
- [ ] Choose the permanent Google Play application ID for Companion and set `ANDROID_COMPANION_APPLICATION_ID` if Companion will be a separate Play listing.
- [ ] Do not use `com.example.*` for a production listing.
- [ ] Choose/register the permanent Apple Bundle ID and set `IOS_BUNDLE_ID`.
- [ ] Tag the exact green release commit; APK/AAB/IPA must come from that commit.

## Android / Google Play

- [x] compileSdk 36 / targetSdk 36.
- [x] CI builds verified debug APKs after lint/tests.
- [x] CI can build signed release APK + AAB when final package IDs and signing secrets are configured.
- [x] Companion contains a prominent Accessibility disclosure before opening Accessibility settings.
- [ ] Complete the Play Console AccessibilityService declaration accurately.
- [ ] Complete Data Safety based on the final binary/dependencies.
- [ ] Publish a stable HTTPS privacy-policy URL and use it in Play Console and the application UI/store listing.
- [ ] Configure Android upload/release key secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`.
- [ ] Upload `UniversalRemote-v0.9.7-release.aab` first to Internal/Closed testing and run real-device tests on Android 16/API 36.
- [ ] Review all runtime permissions and Play declarations shown for the final AAB.

## iOS / App Store Connect

- [x] Deployment target remains iOS 16.0, so iOS 17 remains supported.
- [x] CI requires Xcode 26+ and iPhoneOS SDK 26+.
- [x] Privacy manifest baseline is bundled and linted in CI.
- [x] iOS crypto unit tests are included in CI.
- [ ] Configure Apple signing secrets: `IOS_P12_BASE64`, `IOS_P12_PASSWORD`, `IOS_MOBILEPROVISION_BASE64`.
- [ ] For TestFlight automation configure `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY_BASE64`.
- [ ] Decide and answer App Store encryption/export-compliance questions for the final binary. The project uses standard CryptoKit HMAC-SHA256 and AES-GCM; CI intentionally does not claim an exemption.
- [ ] Inspect the final Xcode archive privacy report and update `PrivacyInfo.xcprivacy` if Required Reason APIs or collected-data declarations appear through code/dependencies.
- [ ] Complete App Privacy in App Store Connect from the final binary behavior.
- [ ] Upload to TestFlight and test on a real iOS 17 device before production review.

## Security / release hygiene

- [x] Signing-key file types are ignored by Git and checked in CI.
- [x] Discovery-controlled URL hosts are restricted to the literal source IPv4 to prevent DNS rebinding/multi-A TOCTOU.
- [x] Samsung/Hue/Cast credentialed connections are scoped to the current physical LAN and bind sockets to the selected Android Network where available.
- [x] Existing v0.9.6 Companion rate limits, replay protection, encrypted sessions, Wi-Fi rebind, NSD watchdog, bounded neighbor parsing, and PJLink candidate verification are retained.
- [ ] Finish per-controller socket binding for remaining legacy cleartext/device protocols before declaring v1.0 security architecture complete; current process-wide Wi-Fi binding remains as compatibility support for those paths.
- [ ] Review global cleartext allowance before v1.0; legacy local protocols such as Roku/WLED/UPnP may require HTTP, but unrelated future networking must not silently inherit that trust model.
- [ ] Run final Android and iOS PR CI green, then merge and rerun release workflows on `main`.

## Store metadata

- [ ] Final app name/subtitle/descriptions.
- [ ] App icon and store screenshots for required phone/tablet sizes.
- [ ] Support/contact URL and email.
- [ ] Public privacy-policy URL.
- [ ] Accurate statements about supported protocols; never claim that every discovered device can be controlled.
