# UniversalRemote v0.9.7 — connection modes

The connection UI now separates automatic connection from explicit authorization. A device being visible on Wi-Fi/Bluetooth/LAN is never treated as authorization to control it.

| Protocol / device family | Normal connection path | Credential handling |
| --- | --- | --- |
| Roku ECP | Direct local API when enabled by the device | No password is invented or bypassed |
| WLED | Direct local JSON API when enabled | No password bypass |
| Yeelight LAN Control | Direct LAN Control when the feature is enabled by the owner | No password bypass |
| UPnP / DLNA MediaRenderer | Direct published UPnP control URL | No password bypass |
| Google Cast | Local Cast v2 with TLS fingerprint confirmation | No generic password; first TLS trust is explicit |
| Android TV / Google TV | Remote Service v2 pairing | 6-character code shown by the TV; code is not stored |
| Samsung Tizen | TV authorization + token | User approves on the TV; token is stored only after TLS trust |
| LG webOS | TV authorization + client key | User approves on the TV; client key is stored only after TLS trust |
| Philips Hue | Physical Bridge button + application key | Requires the owner to press the Bridge button |
| UniversalRemote Android/iOS Companion | Companion pairing | One-time Companion code; session is revocable |
| PJLink projector | PJLink 0/1/2 | Empty password works only when the projector itself requires none; otherwise the supplied PJLink password is used transiently and cleared after the command |
| Wake-on-LAN | Magic packet | Requires the target MAC and device firmware/OS support |

Unsupported or ecosystem-locked protocols such as Matter/HomeKit/Tuya are not controlled by guessing credentials. They require their own supported commissioning, owner authorization, local key, cloud account, or vendor bridge integration before UniversalRemote can legitimately control them.

The app must remain limited to the user's current private Wi-Fi subnet for LAN-control paths. Password guessing, lock-screen PIN bypass, hidden pairing bypass, or arbitrary command attempts against unknown services are intentionally out of scope.