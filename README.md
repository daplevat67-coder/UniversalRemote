# Universal Remote 0.9.2 — APK + signed IPA from GitHub Actions

- Android workflow now publishes clearly named installable APK artifacts for the main remote and Android Companion.
- iOS workflow still performs an unsigned Simulator compile-check on every relevant push.
- When Apple signing secrets are configured, the same workflow creates `UniversalRemote-iOS-Companion-v0.9.2.ipa`.
- A manual iOS workflow run can upload the signed IPA to TestFlight using an App Store Connect API key.
- Full no-PC/iPad instructions are in `BUILD_FROM_IPAD_NO_PC.md`.

---

# Universal Remote 0.9.1 — iPhone / iPad support without fake “full control”

## v0.9.1 — TV power + remote detection fix

- TV-пульты Samsung/LG/Roku получили отдельную заметную кнопку `⏻ ВЫКЛЮЧИТЬ TV`; Android TV получил отдельную кнопку питания с предупреждением, что на части моделей POWER является toggle.
- Google Cast больше не перехватывает карточку только из-за mDNS: если TCP 8009 не отвечает, Cast-кандидат отклоняется и интерфейс показывает результат проверки других API.
- Ошибка TLS/handshake Cast теперь возвращается в общий результат автоопределения вместо зависания на «Получаю TLS fingerprint Cast…».
- Wake-on-LAN не показывается в карточке, если MAC-адрес неизвестен.
- Чистый Google Cast v2 не получает фиктивную кнопку выключения: универсальной команды power-off в Cast receiver protocol нет.


## iOS / iPadOS в v0.9.0

- Добавлено отдельное обнаружение Apple mobile services через Bonjour: `_companion-link._tcp`, `_apple-mobdev2._tcp`, `_device-info._tcp` и имя/бренд устройства. iPhone/iPad больше не должны маскироваться под generic network device, когда Apple публикует доступный сервис.
- **Без приложения на iPhone/iPad** UniversalRemote показывает доступные Apple/Bonjour признаки и честно отделяет их от управления. PIN/код блокировки iPadOS не является сетевым credential. Для административных команд без Companion нужен штатный MDM enrollment и MDM-сервер.
- Добавлен исходный проект **`ios-companion/`** для iPhone/iPad. Он использует тот же добровольный Companion v2 pairing: 5-минутный код, HMAC challenge-response, AES-GCM команды, 30-дневные отзываемые сессии.
- iOS Companion объявляет `_uremote._tcp`, поэтому основной Android APK автоматически находит его и открывает iOS-режим пульта.
- Реально доступные команды iOS Companion в этой версии: `Ping`, `Find/identify` (звук/отклик) и изменение яркости экрана, пока Companion активен. iOS sandbox **не разрешает** стороннему приложению эмулировать системные Home/Back/касания или управлять чужими приложениями по PIN экрана.
- Локальный listener iOS может быть приостановлен, когда приложение уходит в фон. Проект не злоупотребляет background modes, чтобы притворяться постоянно работающим системным сервисом.
- Для установки iOS Companion на физический iPhone/iPad нужна подпись Apple Developer Team/provisioning profile. Новый workflow `Check iOS Companion` на macOS делает unsigned Simulator compile-check, но не публикует неподписанный IPA как устанавливаемый файл.
- Android Companion остаётся без изменений по модели доступа: громкость/media и Home/Back/Recents только после явного Accessibility разрешения.

## Практический режим

1. **iPad/iPhone без Companion:** запустить общий поиск; Apple Bonjour/MDM-capable признаки отображаются в отдельной карточке. Для полноценного fleet/admin управления используйте ваш MDM.
2. **iPad/iPhone с Companion:** собрать/sign `ios-companion`, запустить его на iOS, затем в основном UniversalRemote открыть найденный iOS Companion и ввести одноразовый код.
3. **Android:** использовать Android Companion APK или штатный протокол конкретного устройства.

---

# Universal Remote 0.8.1 — hardening Companion, discovery boundaries and first-use TLS

## Главное в v0.8.1

- Companion pairing-код теперь действует **5 минут**; challenge имеет отдельный короткий TTL.
- Companion session живёт максимум **30 дней** и может быть отозвана на управляемом телефоне: по одному пульту или кнопкой **«Отозвать все»**. Основной пульт при «Отозвать pairing» сначала отзывает ключ на Companion, затем удаляет локальную копию.
- Companion server вынес accept-loop в отдельный thread, использует bounded worker queue/backlog и общий deadline запроса. Медленные/массовые LAN-подключения больше не накапливаются в бесконечной очереди.
- Companion привязывается к текущему Wi-Fi IPv4-интерфейсу и принимает только peers из того же link-prefix; RFC1918 адрес через VPN/другой private interface больше сам по себе не считается доверенным LAN.
- Команды Companion v2 шифруются **AES-GCM** ключом, выведенным из pairing session key, и дополнительно подписываются HMAC с timestamp + nonce. HTTP transport остаётся локальным legacy transport, но action payload больше не виден пассивному наблюдателю LAN.
- Стабильный Companion deviceId больше не рекламируется через mDNS: до успешного proof используется ephemeral advertisementId; stable ID выдаётся только после корректного pairing proof.
- `ResponseBody.string().take(...)` заменён на bounded stream-read; Yeelight и PJLink больше не используют неограниченный `readLine()`.
- Yeelight `Location:` обязан указывать на IP фактического UDP sender; mDNS TXT `location` и UPnP descriptor/control URL жёстко привязаны к IP исходного discovery record.
- DeviceAnalyzer и ServiceHealthProbe отказываются работать вне **текущей Wi-Fi подсети**. Опция «Сканировать TCP-порты» теперь действительно отключает подробный 64-port анализ.
- mDNS service discovery запускается staggered, resolve остаётся сериализованным, а ошибки start/resolve больше не игнорируются молча.
- Samsung/LG/Hue/Cast больше не делают silent first-use TOFU: unknown TLS certificate не допускается в credentialed session. Сначала выполняется отдельный TLS handshake без token/client-key/application-key, показывается SHA-256 fingerprint и требуется явное подтверждение пользователя; только после этого сертификат pin’ится.
- LG registration теперь сообщает версию `0.8.1`.
- Карточки одного IPv4, найденные несколькими network protocols, объединяются агрессивнее, чтобы уменьшить protocol+protocol дубли.
- CI теперь запускает Android Lint + unit tests перед сборкой. Debug APK остаются для разработки; если настроены приватные GitHub Secrets для keystore, workflow дополнительно собирает подписанные release APK.

### Важное ограничение первого TLS-доверия

Явное подтверждение fingerprint **не превращает self-signed протокол производителя в PKI**. Если Samsung/LG/Hue/Cast не показывают тот же fingerprint в независимом доверенном интерфейсе, пользователь не может криптографически доказать, что первое наблюдение не прошло через уже активный MITM. v0.8.1 закрывает silent trust и не отправляет credentials до pin, но это всё ещё честно обозначенный TOFU bootstrap.

### Signed release в GitHub Actions

Для стабильных обновлений поверх установленного release APK добавьте repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`. Keystore должен оставаться приватным. Если secrets не настроены, workflow собирает только debug APK; приватный signing key **не хранится в репозитории**.

---

# Universal Remote 0.8.0 — real Android phone control via Companion

## Главное в v0.8.0

- Добавлен второй APK **UniversalRemote Companion** для Android-телефона/планшета, которым вы хотите управлять.
- Companion рекламирует `_uremote._tcp.` через mDNS, поэтому телефон с установленным Companion становится явно обнаружимым даже когда у обычного Android нет открытых сервисов.
- Сопряжение выполняется одноразовым 12-символьным кодом с экрана управляемого телефона. PIN/пароль блокировки телефона не используется и не передаётся.
- Pairing использует challenge-response HMAC; долговременный session key выводится на обеих сторонах и не передаётся по сети. Каждая команда подписывается HMAC, имеет timestamp и nonce против replay.
- Без Accessibility доступны громкость и media-команды. Home/Back/Recents становятся доступны только после того, как владелец управляемого телефона вручную включил Accessibility для Companion. Сервис настроен без чтения содержимого экрана.
- Companion принимает только локальные соединения, ограничивает pairing attempts и никогда не обходит lockscreen/PIN.
- GitHub Actions теперь собирает **два APK**: основной UniversalRemote и UniversalRemote Companion.

## Как управлять вторым Android-телефоном

1. Установите `companion-debug.apk` на телефон, которым хотите управлять.
2. Откройте Companion и нажмите «Запустить Companion».
3. На основном телефоне запустите поиск UniversalRemote: появится карточка `Телефон / планшет (Companion)`.
4. Откройте её, нажмите pairing и введите одноразовый код с экрана второго телефона.
5. После сопряжения доступны громкость, mute, Play/Pause, Next/Previous. Для Home/Back/Recents вручную включите Accessibility в Companion.

## Что это НЕ делает

Companion не принимает пароль/PIN блокировки телефона, не снимает lockscreen, не делает скрытое управление и не читает содержимое экрана. Для обычного телефона без Companion/штатного ADB pairing универсального безопасного remote API у Android нет.

---

# Universal Remote 0.7.1 — discovery + security hardening

## Исправлено в v0.7.1

- LAN discovery больше не отбрасывает устройство до проверки управляющих endpoints. Быстрый список включает Android TV 6466/6467, Roku 8060, Samsung 8001/8002, LG webOS 3000/3001, Google Cast 8009, Yeelight 55443 и PJLink 4352.
- Общий LAN-поиск разделён на две фазы: UDP neighbor sweep + один снимок neighbor table, затем быстрые характерные TCP-порты. Полный 64-port анализ выполняется только для выбранного устройства.
- Убраны сотни/тысячи запусков `ip neigh` и отдельные UDP sockets на каждый IP. Для больших подсетей отображается полный CIDR, а активное окно проб ограничивается явно, без притворного изменения маски сети.
- mDNS resolve выполняется через очередь, чтобы не запускать множество `resolveService()` одновременно. Добавлены объявления Android ADB TLS и Apple Companion Link как признаки телефонов/планшетов.
- Статус управления разделён в UI на **кандидат** и **API подтверждён**. Открытый порт сам по себе больше не обещает, что команда сработает.
- Ручной IP разрешён только для private IPv4 текущей Wi‑Fi подсети.
- SSDP `LOCATION`, UPnP `controlURL` и LG `socketPath` не могут увести запрос на другой/публичный хост; HTTP redirects отключены.
- Samsung/LG/Hue credentials шифруются AES-GCM ключом Android Keystore. Backup/Device Transfer для SharedPreferences отключены. Старые v0.7.0 tokens/client-key/application-key без TLS binding не переиспользуются: потребуется одно штатное повторное pairing/approval.
- Samsung token больше не отправляется через `ws://8001`; LG client-key не откатывается на `ws://3000`; Hue application key не используется по HTTP. Для self-signed LAN TLS применяется TOFU fingerprint после успешного штатного protocol/pairing.
- Cast также использует TOFU fingerprint; Android TV remote socket получил read timeout, pairing session автоматически закрывается через 90 секунд.
- HTTP/XML/JSON ответы ограничены по размеру, XML parser настроен fail-closed против DTD/external entities, thread pools ограничены.
- Yeelight теперь использует порт, рекламируемый discovery, а не всегда жёстко `55443`. PJLink password больше не конвертируется в долгоживущий `String` перед вычислением digest.

## Что v0.7.1 всё ещё не может гарантировать

Обычное Android-приложение не получает универсальный список клиентов точки доступа. Телефон без открытых портов/mDNS/BLE может быть обнаружен через ARP/neighbor sweep, но это зависит от прошивки Android, сна устройства и AP/client isolation. Для действительно полного списка клиентов нужен штатно авторизованный адаптер к конкретному роутеру либо companion-служба на клиентах.

Некоторые legacy Roku/WLED/UPnP устройства работают только по local HTTP. Поэтому platform-level cleartext пока остаётся разрешён для приложения, но контроллеры v0.7.1 ограничивают такие URL private same-host LAN адресами и запрещают redirects. Полное отключение `usesCleartextTraffic` потребует отдельной транспортной реализации для legacy HTTP протоколов.

---

# Universal Remote 0.7 — control reliability + real API verification

## Новое в v0.7.0

- Перед открытием пульта приложение выполняет целевую проверку реального локального API, а не доверяет только имени устройства или открытому TCP-порту.
- В карточке каждого LAN-устройства появилась кнопка **«ОПРЕДЕЛИТЬ РЕАЛЬНЫЙ ПУЛЬТ»**. Она повторно проверяет управляющие порты и затем безопасно определяет Roku, Samsung, Hue, WLED, Yeelight, Cast, Android TV, LG webOS, PJLink или UPnP MediaRenderer.
- Добавлен **ручной IP / проверка управления**: можно ввести IPv4 своего устройства, если discovery нашёл его под неправильным именем или вообще не классифицировал.
- Wi‑Fi SSID/BSSID больше не трактуется как доказательство управляемого устройства: видимый радиосигнал явно отделён от доступного LAN API.
- Samsung Tizen: токен разрешения сохраняется между запусками. Если TV сбросил/заменил токен, старый токен удаляется и выполняется одна штатная повторная авторизация через экран TV.
- Roku: HTTP 401/403 теперь объясняется настройкой **Control by mobile apps**, вместо общего сообщения «не ответил».
- Philips Hue: добавлена пассивная идентификация Bridge через config endpoint до показа Hue-пульта.
- PJLink: добавлена пассивная проверка приветствия сервиса до открытия проекторного пульта.
- Ошибки автоопределения собираются в одно диагностическое окно с понятными подсказками по LAN/Guest isolation/авторизации.

## Важное ограничение

Объект, который виден в Wi‑Fi/Bluetooth эфире, не обязательно доступен для управления. Команды возможны только когда телефон имеет сетевой/радиопротокольный доступ к устройству и устройство само предоставляет поддерживаемый API или прошло штатное pairing/разрешение. v0.7 не отправляет команды наугад на неподтверждённые службы.

# Universal Remote 0.6 — more real local remotes

## Новое в v0.6.0

- LG webOS TV: SSAP WebSocket remote, secure port 3001 with fallback to 3000, on-screen authorization, persistent client key, D-pad/Home/Back, volume/mute, media and power-off. Power-on remains Wake-on-LAN when the TV/MAC supports it.
- Google Cast / Chromecast: minimal local Cast v2 controller on TLS port 8009 for the currently active receiver session: volume, mute/unmute and Play/Pause/Stop. It does not inject arbitrary media URLs or bypass Cast session rules.
- Philips Hue Bridge: physical-link-button authorization, stored application key, V2 light listing and local power/brightness/basic XY color control. HTTPS certificate fingerprint is remembered after first pairing when available.
- Yeelight: dedicated UDP multicast discovery on 239.255.255.250:1982 plus LAN Control commands on TCP 55443 for power, brightness and RGB. Some bulbs require LAN Control to be enabled in the official Yeelight app.
- Discovery understands LG webOS, Google Cast, Hue and Yeelight as controllable protocols instead of showing generic network-device cards.
- LAN analyzer now includes ports 3000/3001 (LG webOS) and 55443 (Yeelight); 8009 remains Google Cast.
- GitHub Actions moved to Node.js 24-capable actions: checkout@v5, setup-java@v5, setup-gradle@v6 and upload-artifact@v6.

## Что всё ещё не реализовано

- Matter/HomeKit commissioning and control;
- Tuya/Smart Life local/cloud authorization;
- universal Bluetooth AVRCP control of arbitrary speakers/headphones (not exposed to ordinary third-party Android apps as a general remote-controller API);
- full Sonos/AirPlay/Spotify Connect adapters;
- full PC mouse/keyboard/application control without a companion agent installed on the PC;
- IR control on phones with an infrared blaster.

## Безопасность v0.6

Universal Remote only uses local protocols that the target device exposes. LG and Samsung require approval on the TV, Android TV requires its pairing code, Hue requires physical access to the Bridge button, PJLink uses the supplied password, and no controller bypasses device authentication.

# Universal Remote 0.5 — discovery + real local remotes

## Новое в v0.5.0

- Android TV / Google TV Remote Service v2: TLS pairing по 6-значному HEX-коду с экрана, D-pad, Home/Back, питание, громкость, mute, media, settings и input. PIN не сохраняется; после pairing хранится только криптографическая идентичность пульта и SHA-256 fingerprint сертификата TV.
- Samsung Tizen TV: штатный LAN WebSocket remote на 8002/8001. При первом подключении подтверждение делается на экране телевизора. Токен держится только в памяти процесса.
- Roku: официальный ECP на 8060 — D-pad, Home/Back, громкость, mute, media, power и HDMI inputs. Перед открытием пульта API проверяется.
- WLED: локальный JSON API — питание, яркость и базовые RGB-пресеты. Добавлено mDNS-обнаружение `_wled._tcp.`.
- Сохранены UPnP MediaRenderer, PJLink и Wake-on-LAN.
- LAN-анализ знает порты Android TV 6466/6467, Samsung 8001/8002, Roku 8060 и PJLink 4352.
- Повторное обнаружение больше не теряет `controllable`, capabilities, brand и description URL: данные разных discovery-источников объединяются.
- Неподдерживаемые Hue/Matter/HomeKit/Tuya не получают фальшивых универсальных команд: интерфейс сообщает, что нужна штатная авторизация/адаптер протокола.

## Безопасность управления

Universal Remote не обходит PIN, пароль или подтверждение владельца. Android TV требует код с экрана, Samsung — разрешение на TV, PJLink использует введённый пароль только для текущей команды.

## История v0.4.x

## v0.4.3 nearby-scan fixes

- Wi-Fi radio scan now lists nearby access points (SSID/BSSID/RSSI) separately from LAN clients.
- Previously paired Bluetooth Classic devices are no longer shown unless they are actually discovered during the current scan.
- LAN discovery prefers the connected Wi-Fi network instead of accidentally deriving the range from mobile data.
- Scan-source diagnostics now distinguish Wi-Fi radio results from LAN devices.

# Universal Remote 0.4 — Android Network Lab + Remote

Версия 0.4 расширяет Universal Remote сетевым анализом для устройств в локальной IPv4-сети.

## Новое в 0.4

- поиск активных IPv4-хостов не только через reachability, но и через TCP probes;
- настраиваемый диапазон: `auto`, CIDR или `IP-IP`;
- TCP port scan до 64 выбранных портов;
- настраиваемые connect/banner timeouts, параллелизм и длительность поиска;
- MAC из ARP/neighbor table, когда Android предоставляет эти данные;
- локальный OUI-справочник популярных производителей;
- reverse-DNS hostname;
- banners для SSH/FTP/Telnet/HTTP и других banner-first служб;
- эвристическая подсказка ОС/класса устройства;
- безопасные security observations по Telnet/FTP/HTTP/MQTT/SMB/RDP;
- автоматический подробный анализ при нажатии на сетевое устройство;
- Wake-on-LAN;
- низкочастотная проверка стабильности службы: 3 обычных TCP-подключения без malformed payload/flood;
- объединение LAN-данных с mDNS/SSDP/PJLink карточкой вместо дублей;
- поиск по IP, MAC, порту, баннеру, производителю и ОС;
- сохранены BLE, mDNS/Bonjour, SSDP/UPnP и PJLink;
- сохранены UPnP MediaRenderer и PJLink controls;
- RecyclerView + DiffUtil для 100+ устройств.

## Ограничения платформы

Android не гарантирует доступ приложения к MAC соседних устройств: `/proc/net/arp` или `ip neigh` могут быть ограничены конкретной версией/прошивкой. В таком случае IP и службы всё равно отображаются, а MAC остаётся `—`.

Активный хост невозможно гарантированно обнаружить, если он одновременно не отвечает на ICMP/reachability и не принимает ни одно из проверяемых TCP-соединений. Поэтому «ALL» в интерфейсе означает все устройства, которые реально отвечают через доступные телефону BLE/Wi-Fi/LAN протоколы.

## Сетевые границы

LAN scanner принимает только private/link-local IPv4 и максимум 1024 адреса за запуск. Это защищает UI от случайного огромного диапазона и поддерживает предсказуемую нагрузку.

## Python reference

В `tools/network_lab_scanner.py` лежит отдельная socket-реализация для лабораторного запуска на ПК/Linux. APK использует Kotlin networking API и не зависит от Python.

## Сборка

GitHub Actions:

```text
gradle :app:assembleDebug
```

APK после CI:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Подробная схема: `ARCHITECTURE_NETWORK_LAB.md`.


## v0.4.2 — исправление поиска 0 устройств
- BLE LOW_LATENCY без batch-delay и с отображением SCAN_FAILED причин.
- Runtime permissions для Nearby devices + coarse/fine location, так как расстояние оценивается по RSSI.
- Bluetooth Classic discovery и немедленное отображение уже сопряжённых устройств.
- LAN neighbor-touch: хост больше не обязан иметь открытый TCP-порт, чтобы быть замеченным.
- Маршрутизатор/DNS добавляются как известные узлы локальной сети.
- Диагностика источников показывается вместо молчаливого `0`.
