@echo off
cd /d "%~dp0"
rmdir /s /q apk_extracted 2>nul
rmdir /s /q apk_unpacked 2>nul
echo Папки apk_extracted и apk_unpacked удалены.
pause
