@echo off
chcp 65001 >nul
cd /d "%~dp0.."
rmdir /s /q "malware\unpacked\apk_extracted" 2>nul
rmdir /s /q "malware\unpacked\apk_unpacked" 2>nul
echo Papki malware\unpacked\apk_extracted i malware\unpacked\apk_unpacked udaleny.
pause
