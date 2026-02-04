# QR Daemon Android App

This Android application implements the QR token daemon functionality from `qr-daemon-android.js` as a native Android app using Kotlin and Jetpack Compose.

## Features

- 📝 **In-app login** - Enter credentials directly in the app
- 🔐 Automatic authentication with credentials
- 🔄 Continuous QR token polling (25-second intervals)
- 📱 Real-time QR code display
- 💾 Credentials stored locally on device (encrypted via SharedPreferences)
- 🎯 Session management with automatic re-authentication
- 📊 Token hex and base64 display
- 🔌 OkHttp3 for reliable networking with retries
- 🎨 Material Design 3 UI with Compose
- 🔓 Easy logout and credential switching

## Project Structure

```
src/main/java/com/example/testem/
├── MainActivity.kt              # Login + QR display screens
├── QRDaemonService.kt          # Network service for token polling
├── QRDaemonViewModel.kt        # State management (app + QR)
├── QRCodeGenerator.kt          # QR code generation utility
├── QRDaemonConfig.kt           # Configuration constants
└── CredentialsManager.kt       # Local credential storage
```

## Quick Setup - 3 Steps!

**1. Install App:**
```bash
./gradlew installDebug
```

**2. Open App & Enter Credentials:**
- Email
- Password
- Serial Number

**3. Tap "Connect & Start Polling"**

Done! QR codes appear automatically and update every 25 seconds.

## How It Works

```
User opens app
    ↓
Login Screen appears
    ↓
User enters: Email, Password, Serial
    ↓
Tap "Connect & Start Polling"
    ↓
Credentials saved locally
    ↓
QRDaemonService authenticates
    ↓
Continuous token polling starts
    ↓
QR codes display & update every 25 seconds
    ↓
User can tap logout to switch accounts
```

## API Endpoints

- `GET /account` - Access authenticated account page
- `POST /account/login` - Login with credentials
- `POST /cardapi/getQrToken` - Fetch QR token (requires authentication)

## Token Format

- **Expected length:** 57 bytes
- **Response format:** JSON with `success` and `data` (base64-encoded)
- **Polling interval:** 25 seconds
- **Rotation:** Token rotates periodically

## Dependencies

- Jetpack Compose for UI
- OkHttp3 for HTTP networking
- ZXing (Zebra Crossing) for QR code generation
- Gson for JSON parsing
- Coroutines for async operations
- ViewModel and Lifecycle for state management

## Error Handling

- **401 Unauthorized:** Automatically re-authenticates
- **Network errors:** Retries with exponential backoff
- **Invalid tokens:** Skips and retries
- **Bad length tokens:** Validates 57-byte requirement

## Logging

Enable logs in Android Studio's Logcat:
```
adb logcat | grep QRDaemon
```

## Security Considerations

- Never commit credentials to version control
- Add `src/main/assets/qr-demo.env` to `.gitignore` after populating
- Use Android Keystore for production credential storage
- Implement SSL certificate pinning for HTTPS connections
- Add ProGuard rules for obfuscation in release builds
- Use secure storage for sensitive configuration

## Future Enhancements

- [ ] Secure credential storage using Keystore
- [ ] SSL certificate pinning
- [ ] Token history/logging
- [ ] Background service implementation
- [ ] Wear OS support
- [ ] Notification on token updates
- [ ] Export token data functionality
