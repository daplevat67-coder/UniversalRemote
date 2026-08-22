# UniversalRemote Android v0.9.3 — discovery fix

Это точечное обновление Android-сканера. iOS workflow/исходники не перезаписываются.

## Что исправлено

- LAN/TCP/UDP discovery теперь привязывает сокеты к реальной Wi-Fi сети, а не к default network Android. Это важно при активном VPN: раньше локальные пакеты могли уходить в VPN и приложение показывало 0.
- SSDP/UPnP, Yeelight и PJLink также отправляются именно через Wi-Fi.
- На Android 13+ mDNS/NSD использует overload с конкретным Wi-Fi `Network`.
- LAN больше не зависит только от `/proc/net/arp`, `ip neigh` и ICMP. Короткий TCP probe считает `ECONNREFUSED` доказательством живого IP, поэтому видит больше телефонов/ПК с закрытыми портами.
- Gateway/DNS показываются сразу, чтобы при рабочем Wi-Fi интерфейс не оставался на 0 во время долгого sweep.
- Добавлено runtime-разрешение `NEARBY_WIFI_DEVICES` для Android 13+; точная геолокация всё ещё нужна для Wi-Fi scan results.
- Если в сохранённых настройках случайно выключены вообще все источники discovery, приложение автоматически включает их обратно.
- Android app + Android Companion подняты до v0.9.3; GitHub Actions artifact называется `UniversalRemote-v0.9.3-APKs`.

## Применить с Termux

Положи ZIP в `/sdcard/Download/`, затем:

```bash
cd ~/projects
unzip -o /sdcard/Download/UniversalRemote-Android-v0.9.3-discovery-fix.zip
cd ~/projects/UniversalRemote

git status

git add -- \
.github/workflows/build-apk.yml \
app/build.gradle.kts \
app/src/main/AndroidManifest.xml \
app/src/main/java/com/example/universalremote/MainActivity.kt \
app/src/main/java/com/example/universalremote/control/LgWebOsController.kt \
app/src/main/java/com/example/universalremote/discovery/LanDiscovery.kt \
app/src/main/java/com/example/universalremote/discovery/NsdDiscovery.kt \
app/src/main/java/com/example/universalremote/discovery/PjLinkDiscovery.kt \
app/src/main/java/com/example/universalremote/discovery/SsdpDiscovery.kt \
app/src/main/java/com/example/universalremote/discovery/YeelightDiscovery.kt \
app/src/main/java/com/example/universalremote/network/TcpProbe.kt \
app/src/main/java/com/example/universalremote/network/WifiNetworkResolver.kt \
companion/build.gradle.kts \
companion/src/main/java/com/example/universalremote/companion/CompanionMainActivity.kt \
ANDROID_DISCOVERY_FIX_0.9.3.md

git commit -m "Fix Android discovery on Wi-Fi and VPN v0.9.3"
git push
```

Дождаться Android Actions:

```bash
RUN_ID=$(gh run list --workflow "Build Android APK" --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status
```

Скачать APK:

```bash
rm -rf ~/projects/UniversalRemote/apk-v0.9.3
mkdir -p ~/projects/UniversalRemote/apk-v0.9.3

gh run download "$RUN_ID" \
  -n "UniversalRemote-v0.9.3-APKs" \
  --dir ~/projects/UniversalRemote/apk-v0.9.3

cp ~/projects/UniversalRemote/apk-v0.9.3/*.apk /sdcard/Download/
```

## После установки

1. Разреши **Устройства поблизости / Nearby devices**.
2. Разреши **точное местоположение**, если нужен список Wi-Fi SSID/BSSID.
3. Включи системную **Геолокацию** для Wi-Fi radio scan.
4. Оставь телефон подключённым к той же Wi-Fi сети, что и устройства.
5. VPN теперь не должен перехватывать LAN discovery, но Guest Wi-Fi / AP isolation на роутере всё равно может блокировать клиентов друг от друга.

Ограничение Android остаётся: обычное приложение без root не может гарантированно перечислить абсолютно каждый пассивный LAN-клиент, если тот не отвечает ни на mDNS/SSDP/Bluetooth, ни на TCP, а роутер скрывает таблицу клиентов. v0.9.3 устраняет ситуацию, когда рабочая локальная сеть давала 0 из-за маршрутизации/VPN и ограничений ARP/ICMP.
