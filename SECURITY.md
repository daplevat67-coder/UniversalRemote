# UniversalRemote security and trust boundaries

## Intended use
Only scan and control devices you own or are explicitly authorized to administer. Discovery identifies *candidates*; a reachable port does not prove that a device can be controlled.

## Scope
All device control requests are restricted to literal private IPv4 addresses inside the currently connected physical Wi-Fi subnet. Control is unavailable when that network cannot be established. IP hostnames and URLs that point to any other host are rejected for UPnP/LG discovery. Controllers are not intended for remote administration over VPN, even if that network is private.

## Authentication
Companion actions require explicit pairing, a short-lived code, authenticated commands, and revocable sessions. The app cannot bypass Android/iOS lock screens. A compatible TV or IoT device may expose its own unauthenticated local-control API; use a trusted home Wi-Fi network and the manufacturer's access controls.

## Cleartext
The app enables Android cleartext network traffic because Roku ECP, some WLED, UPnP, and other legacy LAN devices require plain HTTP/TCP. Android's global cleartext permission does **not** make HTTP secure. These protocols should carry only non-sensitive control commands; never send passwords, tokens, or private data to an HTTP-only endpoint. When supported, paired/TLS protocols use certificates, keys or authenticated encryption.

## Known limitations
- An attacker already present on the same Wi-Fi may tamper with unauthenticated legacy HTTP/TCP control traffic.
- Wi-Fi client isolation and platform restrictions can prevent otherwise valid device discovery.
- A network's subnet prefix is not a user authorization boundary; only control devices with permission.
- TLS trust-on-first-use does not guarantee identity against an attacker active during initial pairing.
- An Android device build and live tests on representative devices remain necessary.

## Reporting
Use a private security report to the maintainer rather than posting exploits, access keys, or customer device details in public issues.
