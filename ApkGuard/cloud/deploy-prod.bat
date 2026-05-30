@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ====================================================
echo  KiberQalqon - PROD deploy (Vercel)
echo  Akkaunt: muhammadali22072011
echo ====================================================
echo.
npx --yes vercel@latest deploy --prod --yes
echo.
echo ====================================================
echo  Tugadi. Yuqorida "Production: https://..." satrini
echo  ko'rsang - tayyor. Oyna ochiq qoladi.
echo ====================================================
pause
