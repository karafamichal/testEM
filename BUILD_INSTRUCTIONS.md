# Build Instructions for QR Daemon App

## Prerequisites

1. **Java Development Kit (JDK)**
   - Required: Java 17 or later
   - Recommended: Java 25.0.2
   - Location: `C:\Program Files\Java\jdk-17` or `C:\Program Files\Java\jdk-25.0.2`

2. **Android SDK**
   - Required for compilation
   - Typically configured via Android Studio

## Building the Project

### Windows (Command Prompt)

```batch
cd c:\Users\micha\Downloads\EMtest\testEM
set JAVA_HOME=C:\Program Files\Java\jdk-17
gradlew.bat build
```

If using Java 25.0.2:
```batch
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
gradlew.bat build
```

### Windows (PowerShell)

```powershell
cd c:\Users\micha\Downloads\EMtest\testEM
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
& ".\gradlew.bat" build
```

### macOS/Linux

```bash
cd ~/Downloads/EMtest/testEM
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew build
```

## Common Build Commands

- **Clean build**: `gradlew clean build`
- **Build APK**: `gradlew assembleDebug`
- **Run tests**: `gradlew test`
- **View dependencies**: `gradlew dependencies`
- **Check for updates**: `gradlew dependencyUpdates` (if plugin installed)

## Troubleshooting

### "Gradle requires JVM 17 or later"
- Set JAVA_HOME to Java 17+ installation directory
- Verify with: `java -version`

### "Unable to find method 'DependencyHandler.module()'"
- This was a Java 8 compatibility issue
- Use Java 17 or later (Java 25.0.2 is recommended)

### Gradle daemon issues
- Clear daemon: `gradlew --stop`
- Rebuild: `gradlew clean build --no-daemon`

### Build hangs on "INITIALIZING"
- Try with `--no-daemon` flag
- Check JAVA_HOME is set correctly
- Increase heap memory: `set GRADLE_OPTS=-Xmx2048m`

## Project Structure

```
testEM/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/ksjd/testem/
│   │       │   ├── MainActivity.kt
│   │       │   ├── QRDaemonViewModel.kt
│   │       │   ├── QRDaemonService.kt
│   │       │   ├── api/QrTokenService.kt
│   │       │   └── ...
│   │       ├── res/
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts (root)
├── settings.gradle.kts
├── gradle/ (wrapper)
└── gradlew.bat / gradlew (wrapper scripts)
```

## Configuration

### Default Credentials (in QRDaemonConfig.kt)
- Base URL: `https://sadzv.qrbus.me`
- Update USERNAME, PASSWORD, and SERIAL_NUMBER as needed

### Polling Settings
- Interval: 25 seconds (adjustable in QRDaemonConfig.kt)
- Token length validation: 57 bytes

## Running the App

### Via Android Studio
1. Open project in Android Studio
2. Run > Run 'app' (or press Shift+F10)
3. Select emulator or connected device

### Via Gradle
```bash
gradlew installDebug
adb shell am start -n com.ksjd.testem/.MainActivity
```

### Via Command Line
```bash
gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Build Output

- **APK files**: `app/build/outputs/apk/`
- **Build artifacts**: `app/build/intermediates/`
- **Reports**: `app/build/reports/` (after build)

## Dependencies

Key dependencies are declared in `app/build.gradle.kts`:
- AndroidX Compose for UI
- OkHttp for HTTP requests
- ZXing for QR code generation
- Gson for JSON parsing
- Kotlinx Coroutines for async operations

All are managed by Gradle and downloaded from Maven Central.

## Environment Variables

For automated builds or CI/CD:

```bash
JAVA_HOME=C:\Program Files\Java\jdk-17
ANDROID_HOME=C:\Users\[username]\AppData\Local\Android\Sdk
GRADLE_OPTS=-Xmx2048m
```

## Next Steps After Build

1. Verify successful compilation (no errors)
2. Test on emulator or device
3. Check login functionality
4. Verify QR token polling works
5. Validate QR code display

For issues, check the IMPLEMENTATION_SUMMARY.md file for architecture details and known limitations.
