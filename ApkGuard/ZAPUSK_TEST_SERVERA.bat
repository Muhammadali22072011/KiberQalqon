@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ЗАПУСК ТЕСТОВОГО СЕРВЕРА APK
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0\test_server"

echo [1/4] Проверка Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Python не найден!
    pause
    exit /b 1
)
echo ✓ Python найден
echo.

echo [2/4] Установка зависимостей...
pip install -r requirements.txt >nul 2>&1
echo ✓ Зависимости установлены
echo.

echo [3/4] Создание тестовых APK файлов...
python create_test_apk.py
if errorlevel 1 (
    echo.
    echo ❌ Ошибка создания тестовых файлов!
    echo    Сначала соберите APK: запустите ZAPUSK_S_JAVA21.bat
    pause
    exit /b 1
)
echo.

echo [4/4] Запуск тестового сервера...
echo.
echo ═══════════════════════════════════════════════════════════
echo   СЕРВЕР ЗАПУЩЕН!
echo ═══════════════════════════════════════════════════════════
echo.
echo 📱 Откройте на эмуляторе Android:
echo    http://10.0.2.2:8000
echo.
echo 💻 Или на компьютере:
echo    http://localhost:8000
echo.
echo 🛡️ Скачайте тестовые APK и проверьте работу UzGuard
echo.
echo Для остановки нажмите Ctrl+C
echo.
python test_apk_server.py
pause
