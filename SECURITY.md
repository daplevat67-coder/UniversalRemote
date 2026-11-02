# UniversalRemote security and consent

## Intended model
UniversalRemote is a diagnostic tool and optional remote controller for devices you own or are authorized to manage. **Finding a device is not permission to control it.** A responsive port or successful API probe is not proof of control rights.

## Discovery vs control
- Automatic IPv4 discovery and network analysis operate on the connected private Wi-Fi network; Bluetooth/mDNS/SSDP discovery is subject to platform and routing limits.
- Manual private IPv4 entry can target other *reachable* private networks only after the user confirms authorization to inspect the device. It does not make devices on unrelated networks discoverable automatically.
- Controller commands for non-pairing legacy protocols require a separate session-only confirmation of prior owner permission. The app never records this statement as proof of device-side consent.

## Device-side consent (where natively supported)
- Android TV: pairing code shown on the TV.
- Android/iOS Companion: time-limited pairing code shown in the Companion app; revocable authenticated session.
- Samsung and LG: manufacturer pairing/approval behavior (model-dependent); TLS fingerprints confirmed on controller.
- Hue: local bridge authorization / physical link button.

## No native consent prompt
Roku ECP, WLED, Yeelight, UPnP, PJLink and Google Cast generally do not offer a standard authenticated consent screen for this app to invoke. For these protocols a controller-side authorization acknowledgment is **not** evidence the owner consented. To enforce device-side approval, use the manufacturer's access controls or install a trusted Companion where the target OS permits. The app must not emulate a native approval that the device cannot supply.

## Network security
- Non-pairing private IPv4 controllers are permitted to address *reachable* private hosts; this is not a security boundary or a substitute for authorization.
- Companion and LG retain their stricter current-Wi-Fi checks because of their existing trusted-network design.
- SSDP/UPnP/LG discovered URLs require a literal IPv4 destination matching the advertising host, preventing a discovery response from sending control traffic to a different host or dynamically rebound hostname.
- Legacy HTTP/plain TCP protocols lack cryptographic confidentiality; an attacker on the network may observe or tamper with traffic. Never send credentials to an unauthenticated HTTP endpoint.
- TLS trust-on-first-use cannot independently defeat an active MITM during initial pairing.

## Limitations
No regular app can force an unrelated TV, IoT device, or phone to display an approval dialog when the target firmware has no matching API. Android Wi-Fi client isolation, segmented networks and protocol support constrain reachability. Static code changes are not a substitute for on-device testing.

## Reporting
Report security issues privately to the project maintainer. Do not post device credentials, tokens, or exploit details in public issues.
