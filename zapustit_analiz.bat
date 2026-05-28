@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   ANALIZ APK (tolko chtenie, bez ustanovki)
echo ============================================
echo.
echo Skript tolko raspakovyvayet i chitayet fayly.
echo Kod virusa NE vypolnyaetsya. Zapusk bezopasen.
echo.
python apk_analyzer.py
if errorlevel 1 echo.
if errorlevel 1 echo [!] Analizator zavershilsya s oshibkoy.
echo.
pause
