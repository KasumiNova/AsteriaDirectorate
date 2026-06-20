package cn.kasuminova.astd.combat.lens.marks

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LensMarkMathTest {

    private fun approx(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    @Test
    fun `drift damage-taken multiplier scales linearly per stack at base magnitude`() {
        approx(1.0f, LensMarkMath.driftDamageTakenMult(stacks = 0, magnitudeMult = 1f))
        approx(1.05f, LensMarkMath.driftDamageTakenMult(stacks = 1, magnitudeMult = 1f))
        approx(1.50f, LensMarkMath.driftDamageTakenMult(stacks = 10, magnitudeMult = 1f))
    }

    @Test
    fun `drift per-stack magnitude is clamped between low and high bounds`() {
        approx(0.025f, LensMarkMath.driftPerStackBonus(magnitudeMult = 0f))
        approx(0.05f, LensMarkMath.driftPerStackBonus(magnitudeMult = 1f))
        approx(0.075f, LensMarkMath.driftPerStackBonus(magnitudeMult = 2f))
        approx(0.075f, LensMarkMath.driftPerStackBonus(magnitudeMult = 9f))
        approx(0.025f, LensMarkMath.driftPerStackBonus(magnitudeMult = -3f))
    }

    @Test
    fun `deep water penalties scale linearly per stack`() {
        approx(1.0f, LensMarkMath.deepWaterRangeMult(stacks = 0))
        approx(0.90f, LensMarkMath.deepWaterRangeMult(stacks = 5))   // -2%/层 * 5 = -10%
        approx(0.80f, LensMarkMath.deepWaterRangeMult(stacks = 10))
        approx(0.60f, LensMarkMath.deepWaterAccuracyMult(stacks = 10))
        approx(0.90f, LensMarkMath.deepWaterSpeedMult(stacks = 10))
        approx(0.60f, LensMarkMath.deepWaterVsLensDamageMult(stacks = 10))
    }

    @Test
    fun `stacks beyond max are clamped to ten`() {
        approx(1.50f, LensMarkMath.driftDamageTakenMult(stacks = 25, magnitudeMult = 1f))
        approx(0.80f, LensMarkMath.deepWaterRangeMult(stacks = 25))
    }
}
