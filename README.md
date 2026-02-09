# testEM

Android app that logs into the SADZV portal, polls for QR tokens, and renders them as a scannable vCard QR code. Includes security features, UI customization, and account detail display.

## Features
- Email/password login with SNR support and account details
- Real-time QR token polling and display (hex + base64)
- NFC UID view with copy action and QR/NFC mode switch
- App lock with PIN + optional biometric unlock and lock timeout
- Theme presets and layout reorder/hide controls
- English and Slovak localization

## Requirements
- Android Studio (latest stable recommended)
- JDK 17+ for builds
- Android SDK (configured by Android Studio)
- An active SADZV account

## Project Details
- Package: `com.ksjd.testem`
- Compile/Target SDK: 34
- Min SDK: 24
- Version: 1.4
- AGP: 7.4.2
- Kotlin: 1.8.22
- Compose compiler: 1.4.8

## Quick Start
1) Open the testEM folder in Android Studio.
2) Let Gradle sync.
3) Run the app on a device or emulator.

## Configuration
- Credentials are entered in the app and stored locally (SharedPreferences).
- Polling and endpoints are defined in [app/src/main/java/com/ksjd/testem/QRDaemonConfig.kt](app/src/main/java/com/ksjd/testem/QRDaemonConfig.kt).
- Release signing uses `keystore.properties` and a local keystore file (not committed).

## Build APK (Debug)
Android Studio:
- Build > Build APK(s)

Terminal:
```
cd testEM
./gradlew assembleDebug
```

## Build APK (Release)
Create a `keystore.properties` file in the project root with your signing config, then:

```
cd testEM
./gradlew assembleRelease
```

Artifacts:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Notes
- Token polling interval is 25 seconds.
- If QR updates stall, verify network connectivity and login status.
- This app does not guarantee that a vehicle inspector will see your ticket as valid in their system.

## Documentation
- [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md)
- [QUICK_BUILD.md](QUICK_BUILD.md)
- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)

## License
Proprietary - All Rights Reserved. See LICENSE.
