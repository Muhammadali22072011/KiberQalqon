@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   СБОРКА APK С УКАЗАНИЕМ JAVA 21
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0"

echo Поиск Java 21...
echo.

set "JAVA21_PATH="

REM Проверяем типичные места установки Java 21
if exist "C:\Java\jdk-21" (
    for /d %%i in ("C:\Java\jdk-21\jdk-*") do (
        if exist "%%i\bin\java.exe" (
            set "JAVA21_PATH=%%i"
            goto :found
        )
    )
    REM Если внутри нет подпапки, используем саму папку
    if exist "C:\Java\jdk-21\bin\java.exe" (
        set "JAVA21_PATH=C:\Java\jdk-21"
        goto :found
    )
)

if exist "C:\Java\jdk-21*" (
    for /d %%i in ("C:\Java\jdk-21*") do (
        if exist "%%i\bin\java.exe" (
            set "JAVA21_PATH=%%i"
            goto :found
        )
    )
)

if exist "C:\Program Files\Eclipse Adoptium\jdk-21*" (
    for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
        set "JAVA21_PATH=%%i"
        goto :found
    )
)

if exist "C:\Program Files\Java\jdk-21*" (
    for /d %%i in ("C:\Program Files\Java\jdk-21*") do (
        set "JAVA21_PATH=%%i"
        goto :found
    )
)

if exist "C:\Program Files\OpenJDK\jdk-21*" (
    for /d %%i in ("C:\Program Files\OpenJDK\jdk-21*") do (
        set "JAVA21_PATH=%%i"
        goto :found
    )
)

echo ❌ Java 21 не найдена!
echo.
echo Возможные причины:
echo 1. Java 21 ещё не установлена (дождитесь окончания установки)
echo 2. Java 21 установлена в другую папку
echo.
echo Запустите NAJTI_I_NASTROIT_JAVA.bat чтобы найти Java
echo.
pause
exit /b 1

:found
echo ✓ Найдена Java 21: %JAVA21_PATH%
echo.

echo Проверка версии...
"%JAVA21_PATH%\bin\java.exe" -version 2>&1 | findstr "version"
echo.

echo Запуск сборки с Java 21...
echo Это может занять несколько минут при первом запуске...
echo.

set "JAVA_HOME=%JAVA21_PATH%"
set "PATH=%JAVA21_PATH%\bin;%PATH%"

call gradlew.bat assembleDebug

if errorlevel 1 (
    echo.
    echo ❌ Ошибка сборки!
    pause
    exit /b 1
)

echo.
echo ═══════════════════════════════════════════════════════════
echo   ✓ СБОРКА ЗАВЕРШЕНА!
echo ═══════════════════════════════════════════════════════════
echo.
echo APK файл:
echo app\build\outputs\apk\debug\app-debug.apk
echo.
echo Скопируйте его на телефон и установите
echo.
pause
