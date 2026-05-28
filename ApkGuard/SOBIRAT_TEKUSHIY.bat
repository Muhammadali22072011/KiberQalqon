@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   СБОРКА ТЕКУЩЕЙ ВЕРСИИ (БЕЗ НОВЫХ ЭКРАНОВ)
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0"

echo Сборка с Java 21...
echo.

set "JAVA_HOME=C:\Java\jdk-21\jdk-21.0.10+7"
set "PATH=C:\Java\jdk-21\jdk-21.0.10+7\bin;%PATH%"

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
echo APK файл: app\build\outputs\apk\debug\app-debug.apk
echo.
echo ПРИМЕЧАНИЕ:
echo Новые экраны (Dashboard, Welcome, Terms и т.д.) созданы,
echo но требуют Activity классов для работы.
echo.
echo Текущая сборка включает:
echo • Автоматическое сканирование
echo • Фоновая защита
echo • Современный дизайн AutoScanActivity
echo • Узбекский язык
echo.
pause
