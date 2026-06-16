package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [decideVerdict] — [ApkScanner.scan] yakuniy qarori (toza, ajratilgan). Bu yerda eng muhim
 * invariantlar qotiriladi:
 *   • Qat'iy signal reputatsiyani (VERIFIED) BOSADI → DANGER (invariant #5).
 *   • Qat'iy signal yo'q + VERIFIED → SAFE.
 *   • Signal yo'q + past score → SAFE; chegaralar to'g'ri ishlaydi.
 */
class ApkScannerVerdictTest {

    /** "Hammasi toza" tayanch — har bir test faqat kerakli maydonni o'zgartiradi. */
    private fun base(
        iconImpersonation: Boolean = false,
        hiddenApkOrDex: Boolean = false,
        hiddenElfOrDroppedSo: Boolean = false,
        encryptedPayloadWithSignal: Boolean = false,
        deviceAdminWithCombo: Boolean = false,
        obfuscatedSignature: Boolean = false,
        strongCombo: Boolean = false,
        evasionCount: Int = 0,
        verifiedTrusted: Boolean = false,
        trustedInstalledApp: Boolean = false,
        totalScore: Int = 0,
        dangerThreshold: Int = 55,
        suspiciousThreshold: Int = 28,
        randomPkg: Boolean = false,
        filenameScore: Int = 0,
        randomPkgFilenameMin: Int = 50,
        dangerousPermCount: Int = 0,
        randomPkgDangerousPermsMin: Int = 2,
        sensitivity: String = "medium",
    ) = VerdictSignals(
        iconImpersonation, hiddenApkOrDex, hiddenElfOrDroppedSo, encryptedPayloadWithSignal,
        deviceAdminWithCombo, obfuscatedSignature, strongCombo, evasionCount, verifiedTrusted,
        trustedInstalledApp,
        totalScore, dangerThreshold, suspiciousThreshold, randomPkg, filenameScore,
        randomPkgFilenameMin, dangerousPermCount, randomPkgDangerousPermsMin, sensitivity,
    )

    private val DANGER = ScanResult.Verdict.DANGER
    private val SUSPICIOUS = ScanResult.Verdict.SUSPICIOUS
    private val SAFE = ScanResult.Verdict.SAFE

    @Test fun allClear_isSafe() {
        assertEquals(SAFE, decideVerdict(base()))
    }

    @Test fun verifiedTrusted_withNoHardSignal_isSafe() {
        // Yumshoq score yuqori bo'lsa ham VERIFIED uni bosadi (verifiedTrusted score'dan OLDIN).
        assertEquals(SAFE, decideVerdict(base(verifiedTrusted = true, totalScore = 999)))
    }

    @Test fun trustedInstalledApp_suppressesSoftScore_isSafe() {
        // O'rnatilgan + ishonchli stor/tizim ilova: yuqori score'da ham SAFE (false-DANGER tuzatildi).
        assertEquals(SAFE, decideVerdict(base(trustedInstalledApp = true, totalScore = 999)))
        assertEquals(SAFE, decideVerdict(base(trustedInstalledApp = true, randomPkg = true, dangerousPermCount = 5)))
    }

    @Test fun trustedInstalledApp_doesNotOverrideHardSignals_isDanger() {
        // Qat'iy signal (yashirin dropper / blacklist / ikonka...) o'rnatilgan ilovada ham DANGER beradi.
        assertEquals(DANGER, decideVerdict(base(trustedInstalledApp = true, hiddenApkOrDex = true)))
        assertEquals(DANGER, decideVerdict(base(trustedInstalledApp = true, obfuscatedSignature = true)))
        assertEquals(DANGER, decideVerdict(base(trustedInstalledApp = true, iconImpersonation = true)))
    }

    @Test fun hiddenApkOrDex_overridesVerified_isDanger() {
        assertEquals(DANGER, decideVerdict(base(verifiedTrusted = true, hiddenApkOrDex = true)))
    }

    @Test fun hiddenElfOrDroppedSo_overridesVerified_isDanger_DET02() {
        assertEquals(DANGER, decideVerdict(base(verifiedTrusted = true, hiddenElfOrDroppedSo = true)))
    }

    @Test fun iconImpersonation_overridesVerified_isDanger() {
        assertEquals(DANGER, decideVerdict(base(verifiedTrusted = true, iconImpersonation = true)))
    }

    @Test fun obfuscatedSignature_overridesVerified_isDanger() {
        assertEquals(DANGER, decideVerdict(base(verifiedTrusted = true, obfuscatedSignature = true)))
    }

    @Test fun encryptedPayloadWithSignal_overridesVerified_isDanger() {
        assertEquals(DANGER, decideVerdict(base(verifiedTrusted = true, encryptedPayloadWithSignal = true)))
    }

    @Test fun twoEvasions_isDanger_oneEvasion_isSuspicious() {
        assertEquals(DANGER, decideVerdict(base(evasionCount = 2)))
        assertEquals(SUSPICIOUS, decideVerdict(base(evasionCount = 1)))
    }

    @Test fun scoreThresholds_mapToBands() {
        assertEquals(DANGER, decideVerdict(base(totalScore = 55)))      // == dangerThreshold
        assertEquals(SUSPICIOUS, decideVerdict(base(totalScore = 28)))  // == suspiciousThreshold
        assertEquals(SUSPICIOUS, decideVerdict(base(totalScore = 54)))  // danger ostida, suspicious ustida
        assertEquals(SAFE, decideVerdict(base(totalScore = 27)))        // suspicious ostida
    }

    @Test fun randomPkgPlusLureFilename_isDanger() {
        assertEquals(DANGER, decideVerdict(base(randomPkg = true, filenameScore = 50, randomPkgFilenameMin = 50)))
    }

    @Test fun randomPkgPlusManyDangerousPerms_isDanger() {
        assertEquals(DANGER, decideVerdict(base(randomPkg = true, dangerousPermCount = 2, randomPkgDangerousPermsMin = 2)))
    }

    @Test fun randomPkgAlone_isSuspicious_exceptLowSensitivity() {
        assertEquals(SUSPICIOUS, decideVerdict(base(randomPkg = true, sensitivity = "medium")))
        assertEquals(SAFE, decideVerdict(base(randomPkg = true, sensitivity = "low")))
    }

    @Test fun manyDangerousPerms_onlyHighSensitivity_isSuspicious() {
        assertEquals(SUSPICIOUS, decideVerdict(base(dangerousPermCount = 4, sensitivity = "high")))
        assertEquals(SAFE, decideVerdict(base(dangerousPermCount = 4, sensitivity = "medium")))
    }
}
