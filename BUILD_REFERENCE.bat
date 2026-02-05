@echo off
REM Quick Build Reference for QR Daemon

echo.
echo ============================================
echo  QR Daemon - Quick Build Reference
echo ============================================
echo.
echo BUILD COMMAND:
echo   call build.bat clean build
echo.
echo WHAT THIS DOES:
echo   1. Uses Java 17 (auto-detected)
echo   2. Runs Gradle 9.1.0
echo   3. Compiles all Kotlin source files
echo   4. Generates APK in app\build\outputs\apk\debug\
echo.
echo BUILD OPTIONS:
echo   call build.bat clean build          (full clean rebuild)
echo   call build.bat build                (incremental build)
echo   call build.bat assembleDebug        (APK only, no tests)
echo   call build.bat --help               (show all gradle tasks)
echo.
echo TROUBLESHOOTING:
echo   If build hangs on "INITIALIZING":
echo     - Wait 2-3 minutes (downloading dependencies)
echo     - Check internet connection
echo     - Run: gradlew.bat --stop
echo.
echo   If build fails:
echo     - Check Java version: java -version (should be 17.x)
echo     - Check network connectivity
echo     - Try: call build.bat clean build --debug
echo.
echo LOCATIONS:
echo   Project: c:\Users\micha\Downloads\EMtest\testEM
echo   Source:  app\src\main\java\com\ksjd\testem\
echo   APK:     app\build\outputs\apk\debug\app-debug.apk
echo   Gradle:  gradle\ (wrapper directory)
echo.
echo CONFIGURATION:
echo   Android Gradle Plugin: 8.1.0
echo   Kotlin:               1.9.20
echo   Gradle:               9.1.0
echo   Java:                 17.0.12
echo   Target Android SDK:   33
echo.
echo ============================================
echo.
pause
