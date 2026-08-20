# Universal Remote 0.4 — Network Lab architecture

## 1. Поток данных

`MainActivity` запускает независимые источники обнаружения:

- `BleDiscovery` — Bluetooth LE advertisements;
- `NsdDiscovery` — Android NSD / mDNS / Bonjour;
- `SsdpDiscovery` — SSDP/UPnP;
- `PjLinkDiscovery` — PJLink Class 2 broadcast discovery;
- `LanDiscovery` — активные IPv4-хосты и ограниченный TCP service scan.

Все результаты преобразуются в единый `NearbyDevice`. Если один и тот же IPv4 уже найден через более информативный протокол (например, UPnP), данные LAN-анализа не создают дубль, а дополняют существующую карточку.

## 2. LAN discovery

`LanDiscovery` работает в две фазы:

1. **Host discovery**: `InetAddress.isReachable()` + TCP connect probes на небольшой набор распространённых портов. Это позволяет находить устройства, которые не отвечают на ICMP/ping.
2. **Service inspection**: для живого хоста проверяется пользовательский список TCP-портов. По умолчанию это FTP, SSH, Telnet, DNS, HTTP(S), NetBIOS/SMB, RTSP, IPP, MQTT, RDP, PJLink, Cast и JetDirect.

Параллелизм ограничен пулом `4..64` потоков. Список UI обновляется через `RecyclerView + DiffUtil`, поэтому 100+ устройств не требуют полной перерисовки экрана после каждого ответа.

Пользовательский диапазон задаётся как:

- `auto` — текущая IPv4-подсеть, но окно ограничивается максимум /22;
- CIDR: `192.168.1.0/24`;
- диапазон: `192.168.1.10-192.168.1.200`.

Сканер принимает только private/link-local IPv4 и максимум 1024 адреса за один запуск.

## 3. Device Data

При нажатии на сетевое устройство `DeviceAnalyzer` автоматически запускает повторный подробный анализ. Карточка показывает:

- IPv4;
- MAC, если его удалось получить из `/proc/net/arp` или `ip neigh`;
- производителя по встроенному OUI-справочнику для распространённых брендов;
- reverse-DNS hostname;
- открытые TCP-порты;
- название службы по порту;
- ограниченный banner grabbing;
- эвристическую подсказку ОС/класса устройства;
- наблюдения безопасности.

Banner grabbing не отправляет произвольные payload. Для HTTP используется обычный `HEAD / HTTP/1.0`, для banner-first протоколов читается первая строка приветствия.

## 4. Security observations

`DeviceAnalyzer.securityFindings()` отмечает видимые конфигурационные риски без эксплуатации:

- Telnet;
- FTP;
- HTTP без найденного HTTPS;
- MQTT 1883 без найденного MQTT TLS 8883;
- доступный SMB;
- доступный RDP.

Это именно сетевые наблюдения. Они не означают наличие конкретной CVE.

## 5. Control и stability probe

`ServiceHealthProbe` делает три обычных TCP connect с паузами и показывает долю успешных ответов и среднюю задержку. Он используется как неразрушающая проверка доступности/устойчивости вместо malformed payload или flood.

### Wake-on-LAN

`WakeOnLanController` формирует стандартный magic packet:

- 6 байт `FF`;
- MAC повторяется 16 раз;
- UDP broadcast на порты 7 и 9.

Если MAC не удалось определить автоматически, интерфейс предлагает ввести его вручную.

### UPnP MediaRenderer

Сохраняется существующий `UpnpController`:

- volume +/-;
- mute/unmute;
- play/pause/stop.

### PJLink

Сохраняется `PjLinkController`:

- power;
- volume;
- mute;
- штатная PJLink authentication.

Пароль не сохраняется в preferences.

## 6. Settings

Экран «Настройки сканера» содержит:

- включение/выключение BLE, mDNS, SSDP, LAN, PJLink;
- TCP port scan on/off;
- IPv4 range;
- connect timeout;
- banner timeout;
- parallelism;
- общую длительность discovery;
- список TCP-портов (до 64).

## 7. Python reference

`tools/network_lab_scanner.py` — консольный reference implementation на стандартном `socket`. Он повторяет основные идеи Android-модуля: private IPv4 CIDR, bounded concurrency, TCP port discovery, banners, reverse DNS и neighbor-table MAC lookup.

Основная Android-реализация не использует Python/Scapy: она написана на Kotlin/Java networking API, поэтому не требует встраивать Python runtime в APK.
