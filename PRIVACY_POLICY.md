# UniversalRemote Privacy Policy

_Last updated: 2026-08-23_

UniversalRemote is a local-network remote-control and device-discovery application. This policy describes the behavior of the v0.9.7 Store Release Candidate source tree.

## Data processing

UniversalRemote performs device discovery and supported-device control primarily on the user's local network and nearby-device radios. The current project does not include a developer-operated analytics, advertising, telemetry, or user-account backend.

The main Android application may process local-network addresses, device names, service advertisements, ports, Bluetooth/Wi-Fi discovery metadata, and protocol responses in order to show nearby/local devices and determine whether a supported control protocol is available. These values are used on the device for discovery and control and are not intentionally uploaded to the developer.

## Pairing credentials and secrets

Where a supported device requires pairing or authorization, UniversalRemote stores only the credentials needed for that protocol. Android secrets such as Companion session keys, TV tokens, Hue application keys, and approved TLS certificate fingerprints are stored using application-private storage; sensitive Android credentials use Android Keystore-backed encryption where implemented by the relevant controller. iOS Companion session material is stored in the iOS Keychain.

Pairing codes and device PINs used during supported pairing flows are not intended to be retained after the pairing operation unless the vendor protocol itself returns a long-lived token/key that must be stored for future control.

## Android Companion Accessibility service

UniversalRemote Companion can optionally use Android Accessibility solely to invoke the system Home, Back, and Recents actions after a command from a previously paired UniversalRemote controller.

The Companion Accessibility service is configured not to retrieve window content and does not use gesture injection. UniversalRemote Companion does not use this service to read on-screen text, record taps, capture passwords, or collect screen contents. Accessibility is optional; volume and supported media commands can work without it. The user can disable the service at any time in Android Accessibility settings.

## Local network and nearby-device permissions

Depending on Android/iOS version and enabled discovery sources, the apps may request permissions needed for Bluetooth, nearby Wi-Fi, location-derived Wi-Fi scan results, notifications, and local-network/Bonjour access. These permissions are used for the feature described when the permission is requested.

A visible Wi-Fi access point, Bluetooth advertisement, mDNS/Bonjour service, or LAN host is treated as a discovery signal only. UniversalRemote does not claim that every discovered device is controllable; protocol verification and supported vendor pairing are required before credentialed control.

## Third-party devices and services

UniversalRemote communicates with compatible devices using their local protocols. Those devices and any vendor cloud services they independently use are governed by their own privacy policies. UniversalRemote does not bypass device authentication, lock-screen PINs, or vendor account protections.

## Retention and deletion

Locally stored settings and pairing material remain on the device until they expire, are revoked/forgotten in the app where supported, or the application data is cleared/uninstalled. Companion sessions are time-limited and can be revoked from the Companion application.

## Children, advertising, and sale of data

The current project contains no advertising SDK and does not sell personal data. It is not designed as a service for collecting children's personal information.

## Security

UniversalRemote limits supported LAN control to the current physical local-network scope where the platform exposes that information, uses bounded parsers/timeouts for network input, and uses explicit pairing or certificate approval for protocols that require it. No security measure can guarantee that a local network or third-party device is free from compromise.

## Changes

This policy may be updated when functionality, dependencies, or store-distribution behavior changes. The release date above identifies the policy version associated with this source tree.

## Contact

For privacy or security questions, open an issue in the UniversalRemote GitHub repository. Before production store publication, the store listing must also provide the final public privacy-policy URL and required developer contact information.
