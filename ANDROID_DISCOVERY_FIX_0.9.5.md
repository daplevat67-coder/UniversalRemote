# UniversalRemote Android v0.9.5 — zero-device discovery recovery

This update targets the case where the UI stays at 0 devices even on a populated home Wi-Fi network.

Changes:
- LAN discovery no longer depends exclusively on ConnectivityManager exposing TRANSPORT_WIFI.
- Added fallbacks through the real wlan/wifi network interface and WifiManager DHCP data.
- LAN scanning can continue with ordinary sockets when an OEM/VPN hides the Wi-Fi Network object.
- SSDP, PJLink and Yeelight discovery no longer abort merely because the Wi-Fi Network object is hidden.
- Removed process-wide Wi-Fi binding from the discovery phase; controller traffic is still bound when control is opened.
- A saved custom IP range that does not contain the phone's current LAN IP is automatically replaced with `auto` for that scan.
- Increased fallback liveness TCP timeout to reduce false negatives on sleeping/slow Wi-Fi clients.
- Added **ДИАГНОСТИКА ПОИСКА** with LAN IP/prefix, gateway, DNS, location and permission state.
- Diagnostics includes **Сбросить сканер** to restore all discovery sources and automatic range.

The app still cannot enumerate radio devices that do not advertise and cannot see clients isolated by the AP/router.
