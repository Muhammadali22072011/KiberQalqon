# Проверка: все 9 файлов озвучки на месте перед сборкой видео
$dir = Join-Path $PSScriptRoot "voice3_human"
$required = @(
    "m0_hook", "m1_tg", "m2_danger", "m3_hero", "m4_detect",
    "m5_virus", "m7_result", "m6_feat", "m8_outro"
)

Write-Host "Voice folder: $dir"
Write-Host ""

$missing = @()
foreach ($name in $required) {
    $mp3 = Join-Path $dir "$name.mp3"
    $wav = Join-Path $dir "$name.wav"
    if (Test-Path $mp3) {
        $size = (Get-Item $mp3).Length
        Write-Host "[OK]  $name.mp3 ($size bytes)"
    } elseif (Test-Path $wav) {
        Write-Host "[WAV] $name.wav — convert to MP3 or run import_voice_human.ps1"
        $missing += $name
    } else {
        Write-Host "[!!]  $name — MISSING"
        $missing += $name
    }
}

Write-Host ""
if ($missing.Count -eq 0) {
    Write-Host "All voice files ready. Run: bash mix_v3.sh"
    exit 0
}

Write-Host "Missing or WAV-only: $($missing -join ', ')"
Write-Host "Send voice_script_uzguard.txt to a voice actor, then save files here."
exit 1
