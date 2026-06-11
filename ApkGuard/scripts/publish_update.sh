#!/usr/bin/env bash
# ============================================================================
# KiberQalqon — yangi versiya chiqarish yordamchisi (self-update, SelfUpdate.kt)
#
# Nima qiladi:
#   1. Imzolangan release APK'ni topadi (yoki argumentdan oladi)
#   2. versionCode'ni app/build.gradle.kts'dan o'qiydi
#   3. SHA-256 hisoblaydi
#   4. Vercel'ga qo'yiladigan ENV qiymatlarini va aniq buyruqlarni CHOP ETADI
#
# APK'ni qayerga yuklash: Supabase Storage (public bucket) — repo PRIVATE bo'lgani
# uchun GitHub Releases ishlamaydi (telefon yuklab ololmaydi).
#   Supabase Dashboard → Storage → "updates" bucket (public, yo'q bo'lsa yarating)
#   → faylni sudrab tashlang → "Get URL" → public URL'ni nusxalang.
#
# Ishlatish (git-bash, ApkGuard/ ichidan):
#   bash scripts/publish_update.sh [path/to/app-release.apk]
#
# Mijoz tomonidagi himoya (SelfUpdate.kt): HMAC-imzolangan config + SHA-256 mosligi +
# APK imzo-sertifikati o'zimizniki bo'lishi SHART — noto'g'ri fayl o'rnatilmaydi.
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."   # → ApkGuard/

APK="${1:-}"
if [[ -z "$APK" ]]; then
  # Eng yangi release APK (нет — eng yangi debug emas! faqat release/)
  APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -n1 || true)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "❌ Release APK topilmadi. Avval build qiling:"
  echo "   ./gradlew assembleRelease   (keystore.properties bo'lishi shart — imzosiz APK yaramaydi!)"
  echo "Yoki yo'lni o'zingiz bering: bash scripts/publish_update.sh path/to/app-release.apk"
  exit 1
fi

VC=$(grep -oE 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+' | head -n1)
if [[ -z "$VC" ]]; then
  echo "❌ versionCode'ni app/build.gradle.kts'dan o'qib bo'lmadi"; exit 1
fi

SHA=$(sha256sum "$APK" | awk '{print $1}')
NAME="kiberqalqon-v${VC}.apk"

echo "════════════════════════════════════════════════════════════════"
echo " APK:          $APK"
echo " versionCode:  $VC"
echo " SHA-256:      $SHA"
echo "════════════════════════════════════════════════════════════════"
echo
echo "1) Faylni Supabase Storage'ga yuklang (nomini '$NAME' qiling):"
echo "   Supabase Dashboard → Storage → 'updates' bucket (PUBLIC) → Upload"
echo "   Public URL odatda shunday ko'rinadi:"
echo "   https://<project-ref>.supabase.co/storage/v1/object/public/updates/$NAME"
echo
echo "2) Vercel ENV (kiberqalqon-cloud, Production) — eski qiymatlarni o'chirib, yangisini qo'ying:"
echo "   vercel env rm UPDATE_VERSION_CODE production -y 2>/dev/null"
echo "   printf '$VC' | vercel env add UPDATE_VERSION_CODE production"
echo "   vercel env rm UPDATE_APK_SHA256 production -y 2>/dev/null"
echo "   printf '$SHA' | vercel env add UPDATE_APK_SHA256 production"
echo "   vercel env rm UPDATE_APK_URL production -y 2>/dev/null"
echo "   printf '<SUPABASE_PUBLIC_URL>' | vercel env add UPDATE_APK_URL production"
echo
echo "3) CONFIG_VERSION'ni +1 oshiring (rollback-guard) va redeploy:"
echo "   vercel env rm CONFIG_VERSION production -y 2>/dev/null"
echo "   printf '<ESKI+1>' | vercel env add CONFIG_VERSION production"
echo "   cd cloud && vercel --prod"
echo
echo "Shu bilan tamom — telefonlar keyingi ochilishda 'Yangi versiya chiqdi' bildirishnomasini oladi."
