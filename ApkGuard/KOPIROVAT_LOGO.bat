@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════════
echo   📱 КОПИРОВАНИЕ ЛОГОТИПА В ПРОЕКТ
echo ═══════════════════════════════════════════════════════════════
echo.

REM Проверяем что logo.jpg существует
if not exist "logo.jpg" (
    echo ❌ Файл logo.jpg не найден!
    echo Положи logo.jpg в папку UzGuard
    pause
    exit /b 1
)

echo ✅ Файл logo.jpg найден!
echo.

REM Копируем в все папки mipmap
echo Копирую в mipmap-mdpi...
copy /Y "logo.jpg" "app\src\main\res\mipmap-mdpi\ic_launcher.png"

echo Копирую в mipmap-hdpi...
copy /Y "logo.jpg" "app\src\main\res\mipmap-hdpi\ic_launcher.png"

echo Копирую в mipmap-xhdpi...
copy /Y "logo.jpg" "app\src\main\res\mipmap-xhdpi\ic_launcher.png"

echo Копирую в mipmap-xxhdpi...
copy /Y "logo.jpg" "app\src\main\res\mipmap-xxhdpi\ic_launcher.png"

echo Копирую в mipmap-xxxhdpi...
copy /Y "logo.jpg" "app\src\main\res\mipmap-xxxhdpi\ic_launcher.png"

echo.
echo ═══════════════════════════════════════════════════════════════
echo   ✅ ЛОГОТИП СКОПИРОВАН!
echo ═══════════════════════════════════════════════════════════════
echo.
echo Теперь запусти: ZAPUSK_S_JAVA21.bat
echo.
pause
