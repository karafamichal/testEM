# testEM

A cross-platform (Android & iOS) mobile application that logs into the SADZV portal, polls for real-time QR tokens, and renders them as scannable validation codes. The app includes robust security features, personal account details, advanced UI customization, and complete ticket history.

## Features
- **Ticket**: rotating QR code on a ticket stub with a freshness bar, a clear offline warning when the code gets old, full-screen mode at maximum brightness, and a card switcher for accounts with several cards.
- **Live departures** (Android): stop search, nearby stops, favourite stops, a live departure board with delays, and each trip's stop list. Uses the operator's public real-time data; no sign-in needed.
- **Follow a bus** (Android): an ongoing notification counts down to departure, then shows the current and next stop. On Android 16 it is a Live Update (Samsung One UI 8 shows it in the Now Bar). Optional alerts when the bus is 2 minutes away and before your stop.
- **Planner**: cp.sk connections with platform numbers, saved routes, recent searches, swapping from/to, sharing, and a link to live departures.
- **History and insights**: ticket and top-up history, monthly spend chart, average fare, trips left on your credit, most-used stops, and CSV export.
- **Reminders** (Android): notifications for low credit and for a ticket, card or discount that is about to expire.
- **App security**: PIN lock with optional biometrics and an adjustable lock timeout.
- **Customisation**: colour themes, pure-black dark mode, English and Slovak.

## Requirements
### Android
- Android Studio (latest stable recommended)
- JDK 17+ for builds
- Android SDK 24+ (builds with compileSdk 36)
### iOS
- macOS with Xcode 15+
- iOS SDK & appropriate Simulator/Device
### General
- An active SADZV account

## Build & Run

### Android
1) Open the `testEM` folder in Android Studio.
2) Let Gradle sync.
3) Run the app on a device or emulator.

*To build an APK via Terminal:*
- **Debug**: `./gradlew assembleDebug` (outputs to `app/build/outputs/apk/debug/app-debug.apk`)
- **Release**: Create `keystore.properties` in the project root with your signing config, then `./gradlew assembleRelease`

### iOS
1) Open `testEM/iosApp/iosApp.xcodeproj` in Xcode.
2) Select your target device (iPhone) or Simulator.
3) Run the application (Cmd + R).

## Configuration
- Credentials are entered locally within the app and securely stored on the device.
- Token polling interval operates on a 25-second cadence.

## Important Notes
- **Field Testing (Inspectors):** This application has been successfully tested in the real world with official ticket inspectors, and the generated QR codes were scanned and validated perfectly. **However, we still do not guarantee that it will always work or be accepted by their systems. Use it at your own risk.**
- **Network Sync:** If QR updates stall, verify your active internet connectivity and session login status.
- **iOS Distribution:** As this app is not published on the public App Store, iOS users will need to sideload the application onto their devices using tools like [AltStore](https://altstore.io/), Sideloadly, or Signulous.
- **Disclaimer:** This is an unofficial client for `sadzv.qrbus.me`, which is owned by EMtest. This app is not affiliated with, authorized, or endorsed by EMtest in any way.

## License
Proprietary - All Rights Reserved. See LICENSE.
