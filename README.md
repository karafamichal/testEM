# testEM

A cross-platform (Android & iOS) mobile application that logs into the SADZV portal, polls for real-time QR tokens, and renders them as scannable validation codes. The app includes robust security features, personal account details, advanced UI customization, and complete ticket history.

## Features
- **Multi-Platform Native UI**: Built natively with Jetpack Compose for Android and SwiftUI for iOS.
- **Authentication**: Seamless Email/password login with SNR support and full account detailing.
- **Real-Time QR Tokens**: Polling system that dynamically fetches and displays scannable QR codes (hex + base64).
- **Ticket & Payment History**: View past transactions, discounts, and current account balances with pull-to-refresh capabilities.
- **App Security**: Integrated app lock with PIN, optional biometric unlock (Face ID / Touch ID / Android Biometrics), and adjustable lock timeouts.
- **UI Customization**: Flexible layout engine (reorder or hide sections on the fly), AMOLED true-black mode support, and customizable color themes.
- **Localization**: Seamless English and Slovak language integration across both platforms.
- **NFC Support (Alpha)**: View NFC UIDs with a copy action and toggle between QR/NFC modes.

## Requirements
### Android
- Android Studio (latest stable recommended)
- JDK 17+ for builds
- Android SDK 24+
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
- **NFC Status:** The NFC functionality is strictly an early alpha test feature. It still isn't working properly and serves only as an experimental development tool.
- **Network Sync:** If QR updates stall, verify your active internet connectivity and session login status.
- **Disclaimer:** This is an unofficial client for `sadzv.qrbus.me`, which is owned by EMtest. This app is not affiliated with, authorized, or endorsed by EMtest in any way.

## License
Proprietary - All Rights Reserved. See LICENSE.
