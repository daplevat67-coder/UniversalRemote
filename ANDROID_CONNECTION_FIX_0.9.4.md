# UniversalRemote Android v0.9.4 — LAN connection fix

v0.9.3 исправил обнаружение устройств через физический Wi‑Fi при включённом VPN, но контроллеры управления всё ещё использовали обычные `Socket`/`URLConnection`/OkHttp соединения через системный default network. На Android default network часто является VPN, поэтому устройство могло находиться, а подключение к его локальному API — не проходить.

v0.9.4 перед каждым сканированием и перед проверкой/открытием пульта привязывает сетевой процесс UniversalRemote к реальной Wi‑Fi сети через `ConnectivityManager.bindProcessToNetwork`. Из-за этого существующие контроллеры Roku, Samsung, LG webOS, Android TV, Cast, Hue, WLED, Yeelight, PJLink, UPnP и Companion используют Wi‑Fi-маршрут даже при активном VPN.

Добавлено разрешение `CHANGE_NETWORK_STATE`, необходимое для управления выбором сети. В интерфейсе отображается фактический статус LAN-маршрута. При закрытии приложения process binding снимается.

Важно: найденное устройство не всегда имеет API удалённого управления. Телефоны требуют UniversalRemote Companion; телевизоры обычно требуют штатный pairing/разрешение управления; Yeelight требует LAN Control; Hue требует нажатия кнопки на Bridge при первом pairing.

## Установка поверх v0.9.3

```bash
cd ~/projects
unzip -o /sdcard/Download/UniversalRemote-Android-v0.9.4-connection-fix.zip
cd UniversalRemote
git status
```

Добавить изменённые файлы:

```bash
git add -- \
.github/workflows/build-apk.yml \
app/build.gradle.kts \
app/src/main/AndroidManifest.xml \
app/src/main/java/com/example/universalremote/MainActivity.kt \
app/src/main/java/com/example/universalremote/network/WifiNetworkResolver.kt \
app/src/main/java/com/example/universalremote/control/LgWebOsController.kt \
companion/build.gradle.kts \
companion/src/main/java/com/example/universalremote/companion/CompanionMainActivity.kt \
ANDROID_CONNECTION_FIX_0.9.4.md

git commit -m "Fix LAN control connections through Wi-Fi v0.9.4"
git push
```

Сборка:

```bash
RUN_ID=$(gh run list --workflow "Build Android APK" --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status
rm -rf ~/projects/UniversalRemote/apk-v0.9.4
mkdir -p ~/projects/UniversalRemote/apk-v0.9.4
gh run download "$RUN_ID" -n "UniversalRemote-v0.9.4-APKs" --dir ~/projects/UniversalRemote/apk-v0.9.4
cp ~/projects/UniversalRemote/apk-v0.9.4/*.apk /sdcard/Download/
```

После установки v0.9.4 запусти поиск. В верхней строке должно появиться что-то вроде `LAN → Wi-Fi wlan0 • 192.168.x.x`. Если вместо этого показана ошибка маршрута, пришли её текст.
