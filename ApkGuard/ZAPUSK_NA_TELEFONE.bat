@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ЗАПУСК НА ТЕЛЕФОНЕ БЕЗ ANDROID STUDIO
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0"

echo [1/4] Проверка Java...
java -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 (
    echo ❌ Java не найдена! Установите JDK 17 или выше.
    echo.
    echo Скачать JDK 17:
    echo https://adoptium.net/temurin/releases/?version=17
    echo.
    pause
    exit /b 1
)

echo Версия Java:
java -version 2>&1 | findstr /i "version"
echo.

java -version 2>&1 | findstr /i "version \"1\.[0-8]\." >nul
if not errorlevel 1 (
    echo ❌ У вас Java 8 или старше, нужна Java 11+
    echo Скачайте JDK 17: https://adoptium.net/temurin/releases/?version=17
    pause
    exit /b 1
)

echo ✓ Java версия подходит
echo.

echo [2/4] Проверка ADB (Android Debug Bridge)...
adb version >nul 2>&1
if errorlevel 1 (
    echo ⚠️  ADB не найден в PATH
    echo    Нужно установить Android SDK Platform Tools
    echo    Скачать: https://developer.android.com/tools/releases/platform-tools
    pause
    exit /b 1
)
echo ✓ ADB найден
echo.

echo [3/4] Проверка подключенных устройств...
adb devices
echo.
echo ⚠️  Убедитесь, что:
echo    - Телефон подключен по USB
echo    - Включена "Отладка по USB" в настройках разработчика
echo    - Устройство показано в списке выше (не "unauthorized")
echo.
pause

echo [4/4] Сборка и установка на устройство...
echo Это может занять несколько минут...
echo.
call gradlew.bat installDebug
if errorlevel 1 (
    echo.
    echo ❌ Ошибка установки!
    pause
    exit /b 1
)

echo.
echo ═══════════════════════════════════════════════════════════
echo   ✓ ПРИЛОЖЕНИЕ УСТАНОВЛЕНО НА ТЕЛЕФОН!
echo ═══════════════════════════════════════════════════════════
echo.
echo Откройте приложение "KiberQalqon" на телефоне
echo.
pause
