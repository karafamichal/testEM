# Build Instructions for testEM Android App

## Current Status
✅ All code is complete and ready to build  
✅ Project structure is correctly configured  
✅ .gitignore is set up for safe commits (no hardcoded credentials)  
⚠️ SSL certificate issues prevent command-line Gradle build  

## Recommended Build Method: Android Studio

### Step 1: Open in Android Studio
1. Open Android Studio
2. Click **File** → **Open**
3. Navigate to `C:\Users\micha\Downloads\EMtest\testEM`
4. Click **OK**

### Step 2: Let Android Studio Sync
1. Android Studio will automatically detect the Gradle project
2. It will download the Gradle wrapper and dependencies
3. Wait for "Gradle sync" to complete (status bar at bottom)
4. Android Studio handles SSL certificates automatically

### Step 3: Build the App
1. Click **Build** → **Make Project** (or press `Ctrl+F9`)
2. Wait for build to complete
3. Check **Build** panel at bottom for any errors

### Step 4: Run on Device/Emulator
1. Connect an Android device via USB (with USB debugging enabled)
   OR create an emulator: **Tools** → **AVD Manager** → **Create Virtual Device**
2. Select your device from the device dropdown (top toolbar)
3. Click the green **Run** button (▶️) or press `Shift+F10`
4. App will install and launch automatically

## Alternative: Fix SSL Certificates for Command Line Build

If you must use command-line Gradle, you need to fix Java's SSL trust store:

### Option A: Use Corporate Network SSL Fix
```powershell
# If behind corporate proxy, export certificates and add to Java trust store
# This requires admin access to your Java installation
```

### Option B: Disable SSL Verification (NOT RECOMMENDED FOR PRODUCTION)
Create `gradle.properties` in the project root:
```properties
systemProp.javax.net.ssl.trustStore=NUL
systemProp.javax.net.ssl.trustStorePassword=
```

## Project Structure

```
testEM/
├── app/                           # Android app module
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/testem/
│   │       │   ├── MainActivity.kt              # Entry point + UI screens
│   │       │   ├── QRDaemonViewModel.kt         # State management
│   │       │   ├── QRDaemonService.kt           # Network layer
│   │       │   ├── QRCodeGenerator.kt           # QR generation
│   │       │   ├── CredentialsManager.kt        # Local storage
│   │       │   └── QRDaemonConfig.kt            # Configuration
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts          # App module dependencies
├── build.gradle.kts              # Root project config (minimal)
├── settings.gradle.kts           # Multi-module settings
├── gradle/wrapper/               # Gradle wrapper files
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── gradlew.bat                   # Windows Gradle wrapper script
└── local.properties              # SDK location (auto-generated)
```

## App Features

### Login Screen
- Email input field
- Password input field (with visibility toggle)
- Serial Number input field
- Automatic credential validation
- Saves credentials locally in SharedPreferences

### QR Display Screen
- Auto-starts polling for QR tokens (every 25 seconds)
- Displays QR code bitmap (512x512px)
- Shows token metadata (hex string, timestamp, etc.)
- Logout button to return to login screen
- Auto-reauthenticates on 401 errors

### Technical Details
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 33 (Android 13)
- **Compile SDK**: 33
- **Build Tool**: Gradle 7.6 + Android Gradle Plugin 7.4.2
- **Language**: Kotlin 1.8.0
- **UI Framework**: Jetpack Compose 1.3.3
- **Architecture**: MVVM with StateFlow

## Dependencies

### Core Android
- AndroidX Core KTX 1.9.0
- Lifecycle Runtime KTX 2.5.1
- Activity Compose 1.6.1

### UI
- Compose UI 1.3.3
- Compose Material 3 1.0.1
- Compose ViewModel 2.5.1

### Networking
- OkHttp 4.10.0 (with logging interceptor)
- Gson 2.10

### QR Generation
- ZXing Core 3.5.1

### Async
- Kotlin Coroutines 1.6.4

## Troubleshooting

### "SDK location not found"
Android Studio will auto-create `local.properties` with your SDK path.  
Or manually create it:
```properties
sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
```

### "Unable to resolve dependency"
1. File → Invalidate Caches → Invalidate and Restart
2. Delete `.gradle` and `.idea` folders, then reopen project

### "Gradle sync failed"
1. Check internet connection
2. File → Settings → Build Tools → Gradle → Use Gradle from 'gradle-wrapper.properties'
3. Tools → SDK Manager → ensure Android SDK 33 is installed

### "Error running app: No target device found"
1. For physical device: Enable USB debugging in Developer Options
2. For emulator: Create one in AVD Manager (Tools → AVD Manager)

## Next Steps

1. **Open in Android Studio** (recommended path)
2. **Sync Gradle** (automatic on open)
3. **Build** (Ctrl+F9)
4. **Run** (Shift+F10)

## Support

If you encounter issues:
1. Check the **Build** panel in Android Studio for specific errors
2. Ensure Android SDK 33 is installed
3. Verify Java 11 or later is installed
4. Check that your device/emulator is running API 24+

---

**Note**: This project uses in-app credential entry. No sensitive data is hardcoded. Safe to commit to version control.
