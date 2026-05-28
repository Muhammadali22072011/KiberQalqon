#requires -version 5.0
# ============================================================
#   KiberQalqon local build — PowerShell version.
#   Run:  Right-click → "Run with PowerShell"
#   Or:   pwsh -ExecutionPolicy Bypass -File build-apk.ps1
# ============================================================

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$kiberqalqonDir = Join-Path $scriptDir 'KiberQalqon'

if (-not (Test-Path $kiberqalqonDir)) {
    Write-Host "[X] KiberQalqon papkasi topilmadi: $kiberqalqonDir" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Set-Location $kiberqalqonDir
Write-Host "===============================================" -ForegroundColor Cyan
Write-Host " KiberQalqon build — assembleDebug" -ForegroundColor Cyan
Write-Host "===============================================" -ForegroundColor Cyan
Write-Host "Papka: $kiberqalqonDir"
Write-Host ""

# local.properties tekshiruv
$localProps = Join-Path $kiberqalqonDir 'local.properties'
if (-not (Test-Path $localProps)) {
    Write-Host "[!] local.properties yo'q." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# JAVA_HOME — Android Studio JBR avto-aniqlash
if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) {
        $env:JAVA_HOME = $jbr
        Write-Host "[i] JAVA_HOME = $jbr" -ForegroundColor DarkGray
    }
}

# Build
$gradlew = Join-Path $kiberqalqonDir 'gradlew.bat'
& $gradlew assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[X] BUILD FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "===============================================" -ForegroundColor Green
Write-Host " BUILD SUCCESS" -ForegroundColor Green
Write-Host "===============================================" -ForegroundColor Green

# APK ni topamiz va Desktop ga ko'chiramiz
$apkSrcDir = Join-Path $kiberqalqonDir 'app\build\outputs\apk\debug'
$apkFiles  = Get-ChildItem -Path $apkSrcDir -Filter '*.apk' -ErrorAction SilentlyContinue

if (-not $apkFiles -or $apkFiles.Count -eq 0) {
    Write-Host "[X] APK fayl topilmadi: $apkSrcDir" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

$ts   = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $scriptDir "kiberqalqon-$ts-debug.apk"

# Birinchi (kichik bo'lsa hammasi bir xil) APK ni ko'chiramiz
Copy-Item -Path $apkFiles[0].FullName -Destination $dest -Force
$size = [math]::Round((Get-Item $dest).Length / 1MB, 1)

Write-Host ""
Write-Host "[+] APK: $dest" -ForegroundColor Green
Write-Host "[+] Hajm: $size MB" -ForegroundColor Green
Write-Host ""

# Explorerda ko'rsatamiz
Start-Process explorer.exe -ArgumentList "/select,`"$dest`""

Read-Host "Press Enter to exit"
