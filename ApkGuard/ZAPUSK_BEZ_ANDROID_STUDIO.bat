@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ЗАПУСК СБОРКИ APK БЕЗ ANDROID STUDIO
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0"

echo [1/3] Проверка Java...
java -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 (
    echo ❌ Java не найдена! Установите JDK 17 или выше.
    echo.
    echo Скачать JDK 17:
    echo https://adoptium.net/temurin/releases/?version=17
    echo.
    echo После установки перезапустите этот скрипт.
    pause
    exit /b 1
)

echo Версия Java:
java -version 2>&1 | findstr /i "version"
echo.

echo Проверка версии Java...
java -version 2>&1 | findstr /i "version \"1\.[0-8]\." >nul
if not errorlevel 1 (
    echo.
    echo ❌ У вас Java 8 или старше, нужна Java 11+
    echo.
    echo Скачайте и установите JDK 17:
    echo https://adoptium.net/temurin/releases/?version=17
    echo.
    echo После установки перезапустите этот скрипт.
    pause
    exit /b 1
)

echo ✓ Java версия подходит
echo.

echo [2/3] Сборка Debug APK...
echo Это может занять несколько минут при первом запуске...
echo.
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
echo APK файл находится здесь:
echo app\build\outputs\apk\debug\app-debug.apk
echo.
echo Теперь можно:
echo 1. Скопировать APK на телефон и установить
echo 2. Установить через ADB: adb install app\build\outputs\apk\debug\app-debug.apk
echo.
pause

echo.
echo Теперь можно:
echo 1. Скопировать APK на телефон и установить
echo 2. Установить через ADB: adb install app\build\outputs\apk\debug\app-debug.apk
echo.
pause
