# UniversalRemote v0.9.9

## BLE GATT + official pairing

- BLE advertisements are no longer treated only as passive radio records.
- Added Android BLE GATT connection and `discoverServices()` inspection.
- Standard Bluetooth SIG service/characteristic UUIDs are named in the UI; unknown vendor UUIDs remain visible as custom/vendor entries.
- The BLE panel shows bond state, GATT services, characteristics, and READ/WRITE/NOTIFY/INDICATE capabilities.
- Added official Android Bluetooth bonding/pairing. Any PIN/code confirmation is handled by Android and the target device.
- BLE devices can open the connection panel without needing an IPv4 address.
- Unknown vendor characteristics are not written to blindly. Actual control still requires the known command format for that device family.

## Version

- Android app: 0.9.9, versionCode 22.
- Android Companion: 0.9.9, versionCode 22.
