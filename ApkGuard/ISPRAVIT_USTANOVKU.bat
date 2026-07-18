@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════════
echo   🔧 ИСПРАВЛЕНИЕ ПРОБЛЕМЫ УСТАНОВКИ
echo ═══════════════════════════════════════════════════════════════
echo.

echo 1. Удаляю старый APK...
del /F /Q "app\build\outputs\apk\debug\app-debug.apk" 2>nul

echo 2. Очищаю кэш сборки...
rmdir /S /Q "app\build\intermediates" 2>nul
rmdir /S /Q "app\build\tmp" 2>nul

echo 3. Пересобираю проект...
echo.
set JAVA_HOME=C:\Java\jdk-21\jdk-21.0.10+7
set PATH=C:\Java\jdk-21\jdk-21.0.10+7\bin;%PATH%
call gradlew.bat clean assembleDebug --no-daemon

echo.
echo ═══════════════════════════════════════════════════════════════
echo   ✅ ГОТОВО!
echo ═══════════════════════════════════════════════════════════════
echo.
echo Новый APK: app\build\outputs\apk\debug\app-debug.apk
echo.
echo ВАЖНО:
echo 1. Удали старую версию UzGuard с телефона
echo 2. Скопируй новый APK на телефон
echo 3. Установи
echo.
pause
