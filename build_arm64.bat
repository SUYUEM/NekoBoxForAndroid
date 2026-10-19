@echo off
echo === Building NekoBoxForAndroid (arm64-v8a only) ===

where go >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 'go' is not found in PATH. Please install Go 1.23+ and add it to PATH.
    pause
    exit /b 1
)

where java >nul 2>nul
if %errorlevel% neq 0 (
    if "%JAVA_HOME%"=="" (
        echo [ERROR] 'java' is not found and JAVA_HOME is not set. Please install JDK 17+.
        pause
        exit /b 1
    )
)

if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo [ERROR] ANDROID_HOME is not set. Please configure your Android SDK path.
        pause
        exit /b 1
    )
)

echo [INFO] ANDROID_HOME=%ANDROID_HOME%

echo >> Step 1: Compiling arm64-v8a APK with Gradle...
call gradlew.bat app:assemblePreviewRelease

echo.
echo === Build Finished ===
dir /s /b app\build\outputs\apk\*arm64-v8a*.apk
pause
