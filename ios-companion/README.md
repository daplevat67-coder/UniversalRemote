# UniversalRemote iOS/iPadOS Companion 0.9.0

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
