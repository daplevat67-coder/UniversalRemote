# LAN control security update

Update both UniversalRemote and the Android/iOS Companion. Existing Companion grants
are revoked once when the updated Companion starts; pair again using its displayed code.
New grants last 24 hours rather than 30 days. Accessibility still requires the owner's
explicit Android setting and only exposes Home, Back and Recents.

## Authenticated Companion responses

Successful pairing and authenticated command/revoke responses include
`X-UR-Response-Signature` (base64 HMAC-SHA256). The response key is
HMAC-SHA256(session key, UTF-8 `UniversalRemote Companion response key v1`).
The signed text is `response-v1\n{binding}\n{HTTP status}\n{exact UTF-8 body}`.
The binding is `pair:{pair proof}` or `request:{X-UR-Signature}`. Each request already
includes fresh nonces; a response cannot be reused for a different request.

The controller verifies before saving pairing state, displaying success, or forgetting
a revoked grant. Missing/invalid signatures (including legacy Companion replies) fail
closed. An unsigned 401 never deletes a local key. Discovery/challenge and authentication
failures remain unsigned and must not be treated as proof of device identity or revocation.
If revocation's response is lost, verify/revoke the controller in the Companion UI;
the controller deliberately does not claim success without confirmation.
Responses are authenticated, not encrypted. They contain status messages, not command secrets.

## Local addresses and LG controls

Endpoint policy now accepts only canonical dotted IPv4 literals, never DNS names or IPv6.
An advertised URL must contain exactly the discovered private IPv4; userinfo, fragments,
invalid ports and unsupported schemes are rejected. This eliminates a second DNS lookup
and mixed-address answers from the validation/connection path. Devices advertising only
hostname URLs will be rejected rather than silently contacted through DNS.

LG pointer commands use WSS when offered. A `ws://` pointer is blocked until the user
explicitly approves that single button press. Approval is not stored or reused. Other
legacy protocols (for example UPnP/PJLink) still inherit device transport limitations;
this update does not make a hostile LAN safe or solve first-use TLS identity verification.

## Validation

Android JUnit covers response tampering, replay binding, missing signatures and endpoint
rejection. Dependency-free Java checks and a Swift executable share an independently
calculated UTF-8 HMAC vector. CI runs Android lint/tests/build and iOS compile checks.
Real-device checks: pair both Companions, send a command, revoke, reject a modified/missing
response signature, cancel/approve an LG plaintext button, and confirm old grants are gone.
