# UniversalRemote 0.9.2 — APK + IPA + iPad без ПК

## Что будет собираться в GitHub Actions

После push в `main`:

- `UniversalRemote-v0.9.2.apk` — основной Android-пульт;
- `UniversalRemote-Companion-v0.9.2.apk` — Android Companion;
- iOS workflow всегда делает unsigned Simulator compile-check;
- при настроенной Apple-подписи создаётся `UniversalRemote-iOS-Companion-v0.9.2.ipa`;
- при ручном запуске iOS workflow можно сразу отправить эту IPA в TestFlight.

> Важно: IPA для реального iPad обязательно должна быть подписана Apple. Просто переименовать `.app`/ZIP в `.ipa` недостаточно. Для установки без ПК удобнее всего использовать TestFlight.

---

## Часть 1. APK с Android/iPad через GitHub

1. Открой репозиторий UniversalRemote на GitHub.
2. Открой **Actions** → **Build Android APK**.
3. Нажми **Run workflow**.
4. После зелёной галочки открой запуск.
5. Внизу в **Artifacts** скачай `UniversalRemote-v0.9.2-APKs`.
6. В ZIP будут:
   - `UniversalRemote-v0.9.2.apk`;
   - `UniversalRemote-Companion-v0.9.2.apk`;
   - `SHA256SUMS.txt`.
7. На Android распакуй ZIP и установи APK. Если Android спросит разрешение на установку из браузера/файлового менеджера — разреши только для выбранного приложения.

Debug APK из этого workflow уже подписаны стандартным debug-ключом Android и пригодны для обычной установки на тестовое устройство.

---

## Часть 2. Что один раз нужно для IPA

Нужны:

1. членство в **Apple Developer Program**;
2. зарегистрированный Bundle ID для iOS Companion;
3. Apple Distribution certificate + приватный ключ в `.p12`;
4. App Store provisioning profile для этого Bundle ID;
5. App Store Connect API key `.p8`, если хочешь автоматическую отправку в TestFlight.

Секреты GitHub:

- `IOS_BUNDLE_ID` — например `com.yourname.universalremote.ioscompanion`;
- `IOS_P12_BASE64` — `.p12`, закодированный base64;
- `IOS_P12_PASSWORD` — пароль от `.p12`;
- `IOS_MOBILEPROVISION_BASE64` — App Store provisioning profile в base64;
- `ASC_KEY_ID` — Key ID App Store Connect API;
- `ASC_ISSUER_ID` — Issuer ID;
- `ASC_PRIVATE_KEY_BASE64` — содержимое `.p8` в base64.

Никогда не коммить `.p12`, `.p8`, provisioning profile или пароли в публичный репозиторий. Добавляй их только в **Settings → Secrets and variables → Actions → Repository secrets**.

---

## Часть 3. Как получить Apple Distribution `.p12` без Mac

Это можно сделать с Android/Termux, а Apple Developer/App Store Connect открыть в Safari на iPad.

### 3.1 Создай приватный ключ и CSR в Termux

```sh
pkg update
pkg install openssl
mkdir -p ~/ios-signing
cd ~/ios-signing
openssl genrsa -out ios_distribution.key 2048
openssl req -new -key ios_distribution.key -out ios_distribution.csr
```

Во время `openssl req` можно указать своё имя/e-mail; остальные поля не критичны для GitHub workflow. Файл `ios_distribution.key` — приватный ключ. Никому его не отправляй и не коммить в GitHub.

### 3.2 Выпусти Apple Distribution certificate с iPad

1. Открой Apple Developer → **Certificates, Identifiers & Profiles**.
2. В **Certificates** нажми `+`.
3. Выбери **Apple Distribution**.
4. Загрузи `ios_distribution.csr`.
5. Скачай выданный `distribution.cer`.
6. Передай/сохрани `distribution.cer` на Android в папку `~/ios-signing`.

### 3.3 Собери `.p12` в Termux

Apple обычно выдаёт `.cer` в DER-формате:

```sh
cd ~/ios-signing
openssl x509 -inform DER -in distribution.cer -out distribution.pem
openssl pkcs12 -export \
  -inkey ios_distribution.key \
  -in distribution.pem \
  -out distribution.p12
```

На последней команде задай пароль. Этот же пароль потом положи в GitHub Secret `IOS_P12_PASSWORD`.

### 3.4 Создай App ID и provisioning profile с iPad

На Apple Developer:

1. **Identifiers** → зарегистрируй App ID, например `com.yourname.universalremote.ioscompanion`.
2. Это же значение добавь в GitHub Secret `IOS_BUNDLE_ID`.
3. **Profiles** → `+` → профиль для **App Store Connect**.
4. Выбери этот App ID и созданный Apple Distribution certificate.
5. Скачай `.mobileprovision`.

Для TestFlight профиль должен быть App Store/App Store Connect distribution profile, а не Development profile.

### 3.5 Создай App Store Connect API key с iPad

В App Store Connect открой **Users and Access → Integrations → App Store Connect API** и создай ключ, которому разрешена загрузка build. Сохрани:

- Key ID → `ASC_KEY_ID`;
- Issuer ID → `ASC_ISSUER_ID`;
- скачанный `AuthKey_XXXXXXXXXX.p8` → затем его base64 в `ASC_PRIVATE_KEY_BASE64`.

Файл `.p8` Apple позволяет скачать только ограниченно, поэтому сразу сохрани его в надёжном месте и не добавляй в репозиторий.

---

## Часть 4. Как подготовить base64 без ПК

Если у тебя есть Android с Termux, это можно сделать без компьютера:

```sh
base64 -w 0 distribution.p12 > distribution.p12.b64
base64 -w 0 profile.mobileprovision > profile.mobileprovision.b64
base64 -w 0 AuthKey_XXXXXXXXXX.p8 > AuthKey.p8.b64
```

Открой каждый `.b64`, скопируй одну длинную строку и вставь её в соответствующий GitHub Secret.

Если команда `base64` на твоём Termux не понимает `-w 0`, используй:

```sh
base64 distribution.p12 | tr -d '\n'
```

и аналогично для остальных файлов.

---

## Часть 5. Сборка IPA с iPad — без ПК/Mac дома

После того как секреты уже добавлены:

1. На iPad открой GitHub в Safari или приложение GitHub.
2. Открой репозиторий → **Actions**.
3. Выбери **Build iOS Companion IPA**.
4. Нажми **Run workflow**.
5. Оставь `Upload signed IPA to TestFlight = true`.
6. Запусти workflow.
7. GitHub сам использует macOS runner + Xcode, импортирует сертификат и provisioning profile, собирает Archive, экспортирует signed IPA и загружает её в TestFlight.
8. В этом же запуске появится artifact `UniversalRemote-iOS-Companion-v0.9.2-IPA` — там лежит сам файл `.ipa`.

Твой домашний ПК или Mac для этого не нужен.

---

## Часть 6. Установка на iPad через TestFlight

1. Установи **TestFlight** из App Store.
2. В App Store Connect добавь свой Apple ID как internal tester для приложения UniversalRemote Companion.
3. После загрузки GitHub подожди, пока Apple закончит обработку build.
4. Открой TestFlight на iPad.
5. Выбери UniversalRemote Companion → **Install**.
6. Запусти Companion.
7. Разреши доступ к **Local Network**.
8. Нажми **Запустить Companion**.
9. На Android открой UniversalRemote, найди iOS Companion по Bonjour `_uremote._tcp` и введи одноразовый pairing-код с iPad.

Для internal tester отдельная Beta App Review обычно не нужна. Для external testers Apple может потребовать beta review.

---

## Часть 7. Что реально сможет iPad Companion

В версии 0.9.2 iOS Companion поддерживает:

- Ping;
- Find / identify — звук и haptic-отклик;
- изменение яркости экрана, пока Companion активен;
- pairing-код на 5 минут;
- защищённые сессии и revoke.

iPadOS не позволяет обычному стороннему приложению удалённо эмулировать Home/Back/системные касания или использовать код блокировки экрана как сетевой пароль. Для такого администрирования используется штатный Apple MDM, а не обход PIN/Face ID.

---

## Если iOS workflow зелёный, но IPA не появилась

Это значит, что прошёл только Simulator compile-check. Проверь четыре signing-секрета:

- `IOS_BUNDLE_ID`;
- `IOS_P12_BASE64`;
- `IOS_P12_PASSWORD`;
- `IOS_MOBILEPROVISION_BASE64`.

Если IPA собрана, но TestFlight upload упал — проверь ещё три App Store Connect секрета:

- `ASC_KEY_ID`;
- `ASC_ISSUER_ID`;
- `ASC_PRIVATE_KEY_BASE64`.

Также Bundle ID в provisioning profile должен точно совпадать с `IOS_BUNDLE_ID`.
