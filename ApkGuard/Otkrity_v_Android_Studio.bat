@echo off
chcp 65001 >nul
echo Opening KiberQalqon project in Android Studio...

REM Short path (8.3) avoids Cyrillic — Android Studio then accepts the path
set "DIR=%~dp0"
set "SHORTPATH=%DIR%"
for %%I in ("%DIR:~0,-1%") do set "SHORTPATH=%%~sI"

set "STUDIO=C:\Program Files\Android\Android Studio\bin\studio64.exe"
if exist "%STUDIO%" (
    start "" "%STUDIO%" "%SHORTPATH%"
) else (
    echo Android Studio not found at default path.
    echo Open Android Studio manually, then File - Open - select this folder.
    start "" "%DIR%"
)
pause
