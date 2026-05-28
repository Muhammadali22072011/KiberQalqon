@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   УСТАНОВКА JAVA 21 ИЗ ZIP АРХИВА
echo ═══════════════════════════════════════════════════════════
echo.

echo [1] Укажите путь к скачанному ZIP файлу Java 21
echo.
echo Например:
echo C:\Users\Muhammadali\Downloads\OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip
echo.
set /p "JAVA_ZIP=Путь к ZIP файлу: "

if not exist "%JAVA_ZIP%" (
    echo.
    echo ❌ Файл не найден: %JAVA_ZIP%
    pause
    exit /b 1
)

echo.
echo [2] Распаковка Java 21...
echo.

set "INSTALL_DIR=C:\Java\jdk-21"

if exist "%INSTALL_DIR%" (
    echo Папка %INSTALL_DIR% уже существует.
    set /p "OVERWRITE=Удалить и создать заново? (y/n): "
    if /i "%OVERWRITE%"=="y" (
        rmdir /s /q "%INSTALL_DIR%"
    ) else (
        echo Используем существующую папку
    )
)

mkdir "%INSTALL_DIR%" 2>nul

echo Распаковка в %INSTALL_DIR%...
echo Это может занять минуту...
powershell -Command "Expand-Archive -Path '%JAVA_ZIP%' -DestinationPath '%INSTALL_DIR%' -Force"

if errorlevel 1 (
    echo ❌ Ошибка распаковки!
    pause
    exit /b 1
)

echo ✓ Распаковка завершена
echo.

REM Найти папку с JDK внутри распакованного архива
for /d %%i in ("%INSTALL_DIR%\jdk-*") do (
    set "JDK_PATH=%%i"
    goto :found
)

set "JDK_PATH=%INSTALL_DIR%"

:found
echo [3] Проверка Java...
echo.
"%JDK_PATH%\bin\java.exe" -version
echo.

if errorlevel 1 (
    echo ❌ Java не работает!
    pause
    exit /b 1
)

echo ✓ Java 21 работает!
echo.

echo [4] Настройка переменных окружения...
echo.
echo Путь к Java: %JDK_PATH%
echo.

echo Устанавливаем JAVA_HOME...
setx JAVA_HOME "%JDK_PATH%" /M >nul 2>&1
if errorlevel 1 (
    echo ⚠️  Не удалось установить системную переменную (нужны права администратора)
    echo    Устанавливаем для текущего пользователя...
    setx JAVA_HOME "%JDK_PATH%" >nul
)

echo ✓ JAVA_HOME установлена
echo.

echo ═══════════════════════════════════════════════════════════
echo   ✓ JAVA 21 УСТАНОВЛЕНА!
echo ═══════════════════════════════════════════════════════════
echo.
echo Путь: %JDK_PATH%
echo.
echo ВАЖНО: Теперь запустите:
echo   ZAPUSK_S_JAVA21.bat
echo.
echo Он автоматически найдёт эту Java и соберёт APK
echo.
pause
