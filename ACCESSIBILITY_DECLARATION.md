# Android Accessibility disclosure / Play Console notes

UniversalRemote Companion is not presented as an accessibility tool for people with disabilities. Its optional AccessibilityService is used only to invoke Android's global Home, Back, and Recents actions after an authenticated command from a previously paired UniversalRemote controller.

## In-app prominent disclosure

Before Android Accessibility settings are opened, v0.9.7 shows a dedicated dialog explaining that:

- Accessibility is used only for Home, Back, and Recents;
- commands come from a previously paired controller;
- the service does not read text/window content;
- it does not record taps or perform gesture injection;
- screen contents are not sent to the developer;
- volume/media features remain available without Accessibility;
- the permission can be disabled in Android settings at any time.

The user must affirmatively press `Я понимаю — открыть настройки`; Cancel does not open the settings page.

## Play Console declaration

Before production publication, the developer must complete Google's AccessibilityService declaration using the behavior of the final binary and should attach/demonstrate the disclosure flow if the console requests evidence. Do not describe UniversalRemote Companion as a disability-focused accessibility tool unless the product is actually redesigned and qualified for that purpose.

## Technical configuration

The service should remain `exported=false`, protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`, configured with `canRetrieveWindowContent=false`, and without gesture capability. Any future change to those properties requires a new privacy/security review and updated store declaration.
