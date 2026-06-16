package com.uzguard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MlRiskModel sof funksiya — Android'siz testlanadi (Robolectric kerak emas).
 * Modelni o'zgartirsang, bu testlar mantiqiy yo'nalishni ushlab turadi.
 */
class MlRiskModelTest {

    @Test
    fun benignAppIsLowRisk() {
        val p = MlRiskModel.riskProbability(MlRiskModel.Features(dangerousPermCount = 1))
        assertTrue("benign should be low risk, was $p", p < 0.2)
    }

    @Test
    fun ajinaBankerLikeIsHighRisk() {
        val f = MlRiskModel.Features(
            dangerousPermCount = 5,
            permComboScore = 90,
            dexPatternHits = 6,
            hasObfuscatedSig = true,
            evasionTechniques = 2,
            hasHiddenPayload = true,
            zipEncrypted = true,
        )
        val p = MlRiskModel.riskProbability(f)
        assertTrue("banker-like should be high risk, was $p", p > 0.9)
        assertTrue(MlRiskModel.band(p) == "critical" || MlRiskModel.band(p) == "high")
    }

    @Test
    fun probabilityAlwaysInRange() {
        val p = MlRiskModel.riskProbability(MlRiskModel.Features(maxAssetEntropy = 8.0, dexPatternHits = 40))
        assertTrue("probability must be in [0,1], was $p", p in 0.0..1.0)
    }
}
