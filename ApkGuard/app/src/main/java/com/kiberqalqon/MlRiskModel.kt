package com.uzguard

/**
 * Yengil "zero-day" ehtimollik modeli — detektorlar allaqachon hisoblagan signallar ustidan
 * logistik (sigmoid) skoring. Hozir og'irliklar QO'LDA sozlangan (oddiy, lekin haqiqiy:
 * SMS+Accessibility, native+overlay, ZIP-shifrlash kabi signallar yuqori og'irlik oladi).
 * TFLite modeliga almashtirish nuqtasi pastda.
 *
 * MUHIM (golden qoida): bu ADVISORY (maslahat) signal — verdictni O'ZGARTIRMAYDI. Model xato
 * qilsa ham mavjud determinlashgan detektorlar ishlayveradi. ApkScanner uni telemetriya/log
 * uchun ishlatishi mumkin: [riskProbability] ni skan oxirida chaqirib, natijani advisory maydon
 * sifatida qo'shadi (verdict `when` mantig'iga TEGMASDAN). Shu sabab bu modul mustaqil,
 * birorta tashqi kutubxonaga bog'liq emas va sof funksiyalardan iborat (unit-testlanadi).
 */
object MlRiskModel {

    /** Skan davomida yig'iladigan xususiyatlar (detektorlar chiqishidan). */
    data class Features(
        val dangerousPermCount: Int = 0,    // xavfli ruxsatlar soni (ManifestAnalyzer)
        val permComboScore: Int = 0,        // PermissionCombos balli (0..100)
        val dexPatternHits: Int = 0,        // DexPatternAnalyzer mosliklari
        val hasNativeSuspicious: Boolean = false, // NativeLibAnalyzer
        val hasObfuscatedSig: Boolean = false,    // ObfuscatedSignatures
        val evasionTechniques: Int = 0,     // anti-frida/magisk/vpn/debug soni
        val maxAssetEntropy: Double = 0.0,  // 0..8 (Shannon) — DropperDetector
        val hasHiddenPayload: Boolean = false,    // yashirin APK/DEX/ELF
        val filenameSuspicion: Int = 0,     // FilenameHeuristic balli
        val zipEncrypted: Boolean = false,  // ZipEncryptionDetector
        val iconImpersonation: Boolean = false,   // IconImpersonationDetector
    )

    // Qo'lda sozlangan logistik og'irliklar (TFLite o'rniga vaqtinchalik). Musbat = xavf tomon.
    private const val BIAS = -3.2

    private fun logit(f: Features): Double {
        var z = BIAS
        z += 0.35 * f.dangerousPermCount
        z += 0.03 * f.permComboScore
        z += 0.15 * f.dexPatternHits
        z += if (f.hasNativeSuspicious) 1.4 else 0.0
        z += if (f.hasObfuscatedSig) 2.2 else 0.0
        z += 1.1 * f.evasionTechniques
        z += 0.45 * (f.maxAssetEntropy - 6.0).coerceAtLeast(0.0)  // 6.0 dan yuqori entropiya xavfli
        z += if (f.hasHiddenPayload) 2.0 else 0.0
        z += 0.02 * f.filenameSuspicion
        z += if (f.zipEncrypted) 3.0 else 0.0
        z += if (f.iconImpersonation) 3.0 else 0.0
        return z
    }

    /** 0..1 — APK zararli bo'lish ehtimoli (advisory). */
    fun riskProbability(f: Features): Double {
        val z = logit(f)
        return 1.0 / (1.0 + Math.exp(-z))
    }

    /** Inson o'qiy oladigan daraja (telemetriya/UI uchun). */
    fun band(p: Double): String = when {
        p >= 0.85 -> "critical"
        p >= 0.60 -> "high"
        p >= 0.35 -> "medium"
        else -> "low"
    }

    // ===== TFLite swap-in (kelajak) =====
    // 1) app/build.gradle.kts: implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // 2) app/src/main/assets/zeroday_model.tflite — o'qitilgan model (input = Features vektori).
    // 3) Bu yerda Interpreter bilan inference qiling va riskProbability() ni model chiqishiga
    //    ulang. Model assets'da yo'q bo'lsa — yuqoridagi logistik fallback ishlaydi (fail-safe).
}
