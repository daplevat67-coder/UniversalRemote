# UniversalRemote iOS/iPadOS Companion 0.9.2

Это добровольный Companion для iPhone/iPad. Он **не** обходит PIN/Face ID и не эмулирует системные касания.

Что реализовано:
- Bonjour `_uremote._tcp` в локальной сети;
- тот же challenge-response/HMAC + AES-GCM Companion v2, что у Android;
- 5-минутный pairing-код, 30-дневные сессии, revoke;
- `ping`, `identify` (звук/отклик) и яркость экрана, когда приложение активно.

Ограничение iOS: обычное стороннее приложение может быть приостановлено в фоне. Полное управление интерфейсом iPadOS стороннему Companion не предоставляется. Для корпоративно/лично управляемых iPad без Companion используйте штатный Apple MDM enrollment и MDM-сервер.

## Сборка

Нужен macOS + Xcode. `project.yml` предназначен для XcodeGen:

```sh
brew install xcodegen
cd ios-companion
xcodegen generate
open UniversalRemoteIOSCompanion.xcodeproj
```

Для установки на физический iPhone/iPad проект надо подписать вашим Apple Developer Team / provisioning profile. GitHub Actions в проекте делает unsigned simulator compile-check; он не публикует неподписанный IPA как будто его можно установить на устройство.

## Сборка без локального Xcode

Устанавливать Xcode на ваш собственный Mac не обязательно: `.github/workflows/build-ios-companion.yml` компилирует проект на GitHub-hosted macOS runner, где Xcode уже установлен. Однако iOS SDK и `xcodebuild` всё равно являются частью Xcode toolchain где-то в процессе сборки. Для IPA, устанавливаемого на физический iPhone/iPad, дополнительно нужны Apple signing certificate и provisioning profile (или другой штатный способ подписи Apple).

## GitHub Actions 0.9.2

Workflow `.github/workflows/build-ios-companion.yml` now has two levels:

1. unsigned Simulator compile-check — always;
2. signed App Store Connect IPA + optional TestFlight upload — only when Apple signing secrets are configured.

See `../BUILD_FROM_IPAD_NO_PC.md` for the exact secret names and iPad-only run/install flow.
