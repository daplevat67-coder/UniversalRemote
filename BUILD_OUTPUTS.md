# UniversalRemote 0.9.2 build outputs

Android workflow artifact `UniversalRemote-v0.9.2-APKs` contains:

- `UniversalRemote-v0.9.2.apk`
- `UniversalRemote-Companion-v0.9.2.apk`
- `SHA256SUMS.txt`

iOS workflow artifact `UniversalRemote-iOS-Companion-v0.9.2-IPA` is created only when Apple signing secrets are configured and contains:

- `UniversalRemote-iOS-Companion-v0.9.2.ipa`
- `SHA256SUMS.txt`

The iOS workflow can additionally upload the signed IPA to TestFlight when manually dispatched with `upload_to_testflight=true` and App Store Connect API secrets are present.
