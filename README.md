
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
