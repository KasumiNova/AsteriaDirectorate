package cn.kasuminova.astd.combat.hullmods.lens

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LensEcmContributionTest {

    private fun approx(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    @Test
    fun `each hull size contributes its rated ecm per ally`() {
        approx(
            0.05f,
            LensEcmContribution.totalEcmFraction(frigates = 1, destroyers = 1, cruisers = 1, capitals = 1),
        )
    }

    @Test
    fun `more small ships give more ecm than the same count of capitals`() {
        val small = LensEcmContribution.totalEcmFraction(frigates = 4, destroyers = 0, cruisers = 0, capitals = 0)
        val big = LensEcmContribution.totalEcmFraction(frigates = 0, destroyers = 0, cruisers = 0, capitals = 4)
        approx(0.08f, small)
        approx(0.02f, big)
        assertTrue(small > big)
    }

    @Test
    fun `zero allies gives zero`() {
        approx(0f, LensEcmContribution.totalEcmFraction(0, 0, 0, 0))
    }
}
