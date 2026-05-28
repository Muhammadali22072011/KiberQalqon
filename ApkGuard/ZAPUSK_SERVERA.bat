@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ЗАПУСК СЕРВЕРА ДЛЯ ПРИЁМА APK
echo ═══════════════════════════════════════════════════════════
echo.

cd /d "%~dp0\server"

echo [1/3] Проверка Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Python не найден!
    echo    Скачать: https://www.python.org/downloads/
    pause
    exit /b 1
)
echo ✓ Python найден
echo.

echo [2/3] Установка зависимостей...
if not exist "requirements.txt" (
    echo Создаём requirements.txt...
    echo Flask==3.0.0 > requirements.txt
    echo Werkzeug==3.0.1 >> requirements.txt
)
pip install -r requirements.txt
if errorlevel 1 (
    echo ⚠️  Ошибка установки зависимостей
    pause
)
echo.

echo [3/3] Запуск сервера...
echo.
echo ═══════════════════════════════════════════════════════════
echo   СЕРВЕР ЗАПУЩЕН!
echo ═══════════════════════════════════════════════════════════
echo.
echo Адрес сервера: http://localhost:5000
echo.
echo В приложении KiberQalqon укажите в настройках:
echo   - Если телефон в той же сети: http://ВАШ_IP:5000
echo   - Узнать ваш IP: ipconfig (найдите IPv4 адрес)
echo.
echo Для остановки сервера нажмите Ctrl+C
echo.
python app.py
pause
