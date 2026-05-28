@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ПЕРЕСБОРКА ПРОЕКТА С НОВЫМИ ФУНКЦИЯМИ
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0"

echo Очистка старой сборки...
call gradlew.bat clean >nul 2>&1

echo.
echo Запуск сборки с Java 21...
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
echo НОВЫЕ ФУНКЦИИ:
echo • Автоматическое полноэкранное окно при обнаружении APK
echo • Красивый дизайн результатов сканирования
echo • Автоматическое удаление опасных файлов
echo • Уведомления со статистикой
echo.
pause
