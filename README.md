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
