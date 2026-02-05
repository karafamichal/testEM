@echo off
REM QR Daemon Build Wrapper - Automatically selects Java 17+

setlocal enabledelayedexpansion

REM Force Java 17 to the front of PATH
if exist "C:\Program Files\Java\jdk-17\bin" (
    set "PATH=C:\Program Files\Java\jdk-17\bin;%PATH%"
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
) else if exist "C:\Program Files\Java\jdk-25.0.2\bin" (
    set "PATH=C:\Program Files\Java\jdk-25.0.2\bin;%PATH%"
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.2"
) else (
    echo ERROR: Neither JDK 17 nor JDK 25.0.2 found!
    exit /b 1
)

REM Verify Java version
echo.
java -version
echo.

REM Set memory options
if not defined GRADLE_OPTS (
    set "GRADLE_OPTS=-Xmx1024m"
)

REM Run gradle
cd /d "%~dp0"
call gradlew.bat %*
goto end

:fail
exit /b 1

:end
