#!/usr/bin/env bash
# Снимает РЕАЛЬНЫЕ скриншоты приложения Anor Qalqon v4 с эмулятора.
# Использование: bash capture_screens.sh <путь-к-apk>
set -u
ADB=/c/Android/platform-tools/adb.exe
PKG=com.kiberqalqon.debug
NS=com.kiberqalqon
OUT="/c/Users/User/OneDrive/Desktop/KiberQalqon/design_v4_extracted/shots"
mkdir -p "$OUT"

APK="${1:?apk path required}"
echo "== install =="
"$ADB" install -r -g "$APK" || exit 1

# имя_файла  активити  задержка_сек
CAPS=(
  "01_language    LanguageSelectActivity      4"
  "02_splash      SplashActivity              1"
  "03_onboarding  OnboardingActivity          4"
  "04_dashboard   DashboardNewActivity        6"
  "05_skaner      MainActivity                7"
  "06_stats       ScanHistoryActivity         5"
  "07_settings    SettingsActivity            5"
  "08_quarantine  QuarantineActivity          4"
  "09_protection  ProtectionStatusActivity    4"
  "10_report      ReportProblemActivity       4"
  "11_initialscan InitialScanActivity         3"
)

for line in "${CAPS[@]}"; do
  set -- $line
  name=$1; act=$2; wait=$3
  echo "== $name ($act) =="
  "$ADB" shell am force-stop $PKG
  sleep 1
  "$ADB" shell am start -n "$PKG/$NS.$act"
  sleep "$wait"
  "$ADB" exec-out screencap -p > "$OUT/$name.png"
  ls -la "$OUT/$name.png"
done

# Onboarding: 2-й и 3-й слайды (свайпы)
"$ADB" shell am force-stop $PKG; sleep 1
"$ADB" shell am start -n "$PKG/$NS.OnboardingActivity"; sleep 3
"$ADB" shell input swipe 900 1200 200 1200 300; sleep 1.5
"$ADB" exec-out screencap -p > "$OUT/03b_onboarding2.png"
"$ADB" shell input swipe 900 1200 200 1200 300; sleep 1.5
"$ADB" exec-out screencap -p > "$OUT/03c_onboarding3.png"

# InitialScan: итоговое состояние после прогона
sleep 8
"$ADB" exec-out screencap -p > "$OUT/11b_initialscan_done.png" 2>/dev/null || true

# Тёмная тема: dashboard + settings
"$ADB" shell "cmd uimode night yes"
sleep 2
"$ADB" shell am force-stop $PKG; sleep 1
"$ADB" shell am start -n "$PKG/$NS.DashboardNewActivity"; sleep 6
"$ADB" exec-out screencap -p > "$OUT/12_dashboard_dark.png"
"$ADB" shell am force-stop $PKG; sleep 1
"$ADB" shell am start -n "$PKG/$NS.SettingsActivity"; sleep 5
"$ADB" exec-out screencap -p > "$OUT/13_settings_dark.png"
"$ADB" shell "cmd uimode night no"

echo "== done =="
ls -la "$OUT"
