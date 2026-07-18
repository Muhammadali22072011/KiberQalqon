package com.uzguard

import android.util.Log

/**
 * YARA-lite qoida dvigateli — [RuleStore.rules] dagi bulut qoidalarini skanlanayotgan APK'ning
 * (kichik harfli) matn "pichanlariga" tatbiq qiladi. Uch xil haystack:
 *   • dex_string → classes*.dex string pool matni ([DexPatternAnalyzer] o'qigan baytlar).
 *   • manifest   → AndroidManifest.xml (best-effort matn).
 *   • path       → APK fayl yo'li (kichik harf).
 *
 * Har qoida uchun needle'lar soni sanaladi; agar >= (minHits>0 ? minHits : needles.size) bo'lsa — hit.
 * Arzon: needle'lar allaqachon kichik harf, haystack'lar ham chaqiruvchi tomonidan bir marta
 * kichik harfga o'tkazilgan. Hech qachon throw QILMAYDI.
 *
 * SKORING [ApkScanner] tomonida: NON-advisory + severity critical/high → qat'iy (hard) DANGER hissa
 * ([ApkScanner]'da obfuscatedSignature bilan bir xil). severity medium/low YOKI istalgan advisory →
 * faqat SUSPICIOUS darajali (score) hissa — hech qachon yakka o'zi DANGER'ga majburlamaydi.
 */
object RuleEngine {

    private const val TAG = "RuleEngine"

    /** Bitta mos qoida. `advisory`/`severity` skoringni [ApkScanner]'da hal qiladi. */
    data class RuleHit(
        val ruleId: String,
        val family: String,
        val severity: String,
        val advisory: Boolean,
    )

    /**
     * @param dexTextLower classes*.dex string pool matni (KICHIK HARF) yoki null.
     * @param manifestTextLower AndroidManifest.xml matni (KICHIK HARF) yoki null.
     * @param pathLower APK fayl yo'li (KICHIK HARF) yoki null.
     */
    fun evaluate(
        dexTextLower: String?,
        manifestTextLower: String?,
        pathLower: String?,
    ): List<RuleHit> {
        val rules = RuleStore.rules()
        if (rules.isEmpty()) return emptyList()
        val out = ArrayList<RuleHit>()
        try {
            for (r in rules) {
                if (r.needles.isEmpty()) continue  // needlesiz qoida — o'tkazib yuboramiz
                val hay = when (r.target) {
                    "dex_string" -> dexTextLower
                    "manifest" -> manifestTextLower
                    "path" -> pathLower
                    else -> null
                } ?: continue
                var count = 0
                for (n in r.needles) {
                    if (n.isNotEmpty() && hay.contains(n)) count++
                }
                val need = if (r.minHits > 0) r.minHits else r.needles.size
                if (count >= need) {
                    out.add(RuleHit(r.id, r.family, r.severity, r.advisory))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "evaluate failed", e)
        }
        return out
    }
}
