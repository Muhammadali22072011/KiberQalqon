#!/usr/bin/env bash
# ============================================================================
# UzGuard 8.7 / versionCode 87 — self-update rollout (bir buyruq).
# APK allaqachon public/kq-update-87.apk ga qo'yilgan (Vercel deploy uni root'ga
# ko'chiradi → https://kiberqalqon-cloud.vercel.app/kq-update-87.apk).
#
# ISHLATISH (ApkGuard/cloud/ ichidan):
#   1) Bir marta:  vercel login            (yoki:  export VERCEL_TOKEN=xxxxx)
#   2)             bash deploy_87.sh
#
# Skript: 4 ta ENV'ni Production'ga qo'yadi + prod'ga deploy qiladi + /api/config'ni tekshiradi.
# ENV qiymatlari maxfiy EMAS (versionCode / sha / url / config-version).
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"                       # → cloud/
V="${VERCEL_TOKEN:+--token=$VERCEL_TOKEN}"

setenv() {   # $1=name  $2=value
  vercel env rm "$1" production -y $V 2>/dev/null || true
  printf '%s' "$2" | vercel env add "$1" production $V
}

echo "→ ENV (Production)…"
setenv UPDATE_VERSION_CODE 87
setenv UPDATE_APK_SHA256   e95bad74f9e072bc7ce12dfb801b4fc3c6a5bbf59b28daa9116f3f4af8c049a6
setenv UPDATE_APK_URL      https://kiberqalqon-cloud.vercel.app/kq-update-87.apk
setenv CONFIG_VERSION      7

echo "→ deploy --prod…"
vercel --prod $V

echo "→ tekshiruv (bir necha soniyadan keyin):"
echo "   APK:    curl -sI https://kiberqalqon-cloud.vercel.app/kq-update-87.apk | head -1"
echo "   config: /api/config → v=7, versionCode=87, sha=e95bad74…"
echo "✅ tamom — telefonlar keyingi ochilishda 'Yangi versiya' bildirishnomasini oladi."
