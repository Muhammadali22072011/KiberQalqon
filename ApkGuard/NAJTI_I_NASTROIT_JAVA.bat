@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════════════════════════
echo   ПОИСК И НАСТРОЙКА JAVA
echo ═══════════════════════════════════════════════════════════
echo.

echo [1] Текущая версия Java в PATH:
echo.
java -version 2>&1
echo.
echo ───────────────────────────────────────────────────────────
echo.

echo [2] Поиск всех установленных версий Java...
echo.

set "found=0"

if exist "C:\Program Files\Java" (
    echo Найдено в C:\Program Files\Java:
    dir /b "C:\Program Files\Java" 2>nul
    set "found=1"
    echo.
)

if exist "C:\Program Files (x86)\Java" (
    echo Найдено в C:\Program Files (x86)\Java:
    dir /b "C:\Program Files (x86)\Java" 2>nul
    set "found=1"
    echo.
)

if exist "C:\Program Files\Eclipse Adoptium" (
    echo Найдено в C:\Program Files\Eclipse Adoptium:
    dir /b "C:\Program Files\Eclipse Adoptium" 2>nul
    set "found=1"
    echo.
)

if exist "C:\Program Files\Android\Android Studio\jbr" (
    echo Найдено в Android Studio:
    echo C:\Program Files\Android\Android Studio\jbr
    "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -version 2>&1 | findstr "version"
    set "found=1"
    echo.
)

if "%found%"=="0" (
    echo ❌ Java не найдена на компьютере!
    echo.
    echo Установите Java 17 или 21:
    echo https://adoptium.net/temurin/releases/
    echo.
    pause
    exit /b 1
)

echo ───────────────────────────────────────────────────────────
echo.
echo [3] РЕШЕНИЕ:
echo.
echo Если вы только что установили Java 21, но система всё ещё
echo использует Java 8, нужно:
echo.
echo 1. Найти путь к Java 21 выше (например: C:\Program Files\Eclipse Adoptium\jdk-21.x.x)
echo.
echo 2. Установить переменные окружения:
echo    - Нажмите Win + Pause или Пуск → Параметры → Система → О системе
echo    - "Дополнительные параметры системы"
echo    - "Переменные среды"
echo.
echo 3. Создать/изменить JAVA_HOME:
echo    - В "Системные переменные" нажмите "Создать" или "Изменить"
echo    - Имя: JAVA_HOME
echo    - Значение: [путь к Java 21, например C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot]
echo.
echo 4. Изменить PATH:
echo    - Найдите переменную "Path" в "Системные переменные"
echo    - Нажмите "Изменить"
echo    - Найдите строки со старой Java (с jdk1.8 или jre1.8)
echo    - Удалите их или переместите вниз
echo    - Добавьте в начало: %%JAVA_HOME%%\bin
echo.
echo 5. ВАЖНО: Перезапустите командную строку (закройте все окна CMD)
echo.
echo 6. Проверьте: java -version
echo    Должно показать версию 21
echo.
echo ───────────────────────────────────────────────────────────
echo.
echo БЫСТРОЕ РЕШЕНИЕ (если нашли Java 21 выше):
echo.
echo Скопируйте путь к Java 21 и запустите (замените ПУТЬ):
echo.
echo setx JAVA_HOME "ПУТЬ_К_JAVA_21" /M
echo setx PATH "%%JAVA_HOME%%\bin;%%PATH%%" /M
echo.
echo Например:
echo setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot" /M
echo.
echo (Нужны права администратора)
echo.
pause
