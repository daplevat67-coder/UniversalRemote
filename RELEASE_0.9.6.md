# UniversalRemote v0.9.6

This release hardens Companion pairing and LAN trust boundaries, fixes Android/iOS build reliability, adds Wi-Fi listener rebinding on Android Companion, rate-limits pairing challenges, adds an mDNS resolve watchdog, verifies PJLink candidates before marking them controllable, bounds neighbor-process output, aligns the Python lab scanner with supported control ports, and upgrades Android/iOS CI artifact handling.

Artifacts are produced only after CI quality gates pass. A signed iOS IPA is produced only when the Apple signing secrets are configured in the repository.
