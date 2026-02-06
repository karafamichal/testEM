# testEM

An Android app that logs into the SADZV portal, fetches a dynamic QR token, and renders it as a vCard QR code for scanning. The app can auto-detect your card SNR from your account details.

## Features
- Login with email and password
- Auto-detects card SNR from your account
- Polls for a new QR token and displays it
- QR code encoded as vCard for compatibility

## Requirements
- Android Studio (latest stable recommended)
- JDK 17 for builds
- An active SADZV account

## Quick Start
1) Open the testEM folder in Android Studio.
2) Let Gradle sync.
3) Run the app on a device or emulator.

## Build APK (Debug)
In Android Studio:
- Build > Build APK(s)

Or from terminal:
```
cd testEM
./gradlew assembleDebug
```

## Build APK (Release)
This project uses a local keystore and properties file for signing. These files are ignored by git.

```
cd testEM
./gradlew assembleRelease
```

The APK will be in:
- app/build/outputs/apk/release/app-release.apk

## Notes
- The app polls the server roughly every 25 seconds.
- If the QR code is not updating, check network connectivity and login status.

## License
MIT
