@echo off
rem chcp ДО любых команд с кириллицей и до cd, чтобы путь с не-ASCII корректно отображался
chcp 65001 >nul
cd /d "%~dp0"
echo Raspakovka APK dlya reversa...
python unpack_apk.py
echo.
echo Gotovo. Papka: malware\unpacked\apk_unpacked
pause
