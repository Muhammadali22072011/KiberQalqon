@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ПРОСМОТР ЛОГОВ UZGUARD
echo ═══════════════════════════════════════════════════════════
echo.

echo Подключите телефон и нажмите Enter...
pause >nul

echo.
echo Очистка старых логов...
adb logcat -c

echo.
echo Запуск приложения...
adb shell am start -n com.uzguard/.MainActivity

echo.
echo Логи (нажмите Ctrl+C для остановки):
echo.
adb logcat | findstr /i "uzguard AndroidRuntime FATAL"

pause
