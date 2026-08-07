@echo off
REM ============================================================
REM   KiberQalqon local build — double-click to run.
REM   Builds debug APK and copies it to builds\ with timestamp.
REM ============================================================

setlocal enabledelayedexpansion
chcp 65001 >nul

REM Android modul papkasi: hozircha ApkGuard\, nomi o'zgargach KiberQalqon\
set "APP_DIR="
if exist "%~dp0ApkGuard\gradlew.bat"    set "APP_DIR=%~dp0ApkGuard"
if not defined APP_DIR if exist "%~dp0KiberQalqon\gradlew.bat" set "APP_DIR=%~dp0KiberQalqon"

if not defined APP_DIR (
    echo [X] Android modul papkasi topilmadi ^(ApkGuard\ yoki KiberQalqon\^): %~dp0
    pause
    exit /b 1
)

cd /d "%APP_DIR%"

echo ===============================================
echo  KiberQalqon build — assembleDebug
echo ===============================================
echo Papka: %CD%
echo.

REM Tekshiramiz local.properties bor-yo'qligi
if not exist "local.properties" (
    echo [!] local.properties yo'q. Yaratish kerak.
    pause
    exit /b 1
)

REM Java JDK avto-aniqlash (Android Studio JBR yoki JAVA_HOME)
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
        echo [i] JAVA_HOME = !JAVA_HOME!
    )
)

call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo [X] BUILD FAILED
    pause
    exit /b 1
)

echo.
echo ===============================================
echo  BUILD SUCCESS
echo ===============================================

REM APK papkasini topamiz
set "APK_SRC=%CD%\app\build\outputs\apk\debug"
if not exist "%APK_SRC%" (
    echo [X] APK papkasi topilmadi: %APK_SRC%
    pause
    exit /b 1
)

REM Timestamp ism — kiberqalqon-YYYYMMDD-HHMMSS.apk, builds\ ichiga
if not exist "%~dp0builds" mkdir "%~dp0builds"
for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /value ^| find "="') do set DT=%%a
set "TS=%DT:~0,8%-%DT:~8,6%"
set "DEST=%~dp0builds\kiberqalqon-!TS!-debug.apk"

REM Hamma APK fayllarini ko'chiramiz (oxirgi har doim yangi)
for %%f in ("%APK_SRC%\*.apk") do (
    copy /Y "%%f" "!DEST!" >nul
    echo [+] %%~nxf  -^>  !DEST!
)

echo.
echo APK joylashuvi: !DEST!
echo.
explorer /select,"!DEST!"
pause
