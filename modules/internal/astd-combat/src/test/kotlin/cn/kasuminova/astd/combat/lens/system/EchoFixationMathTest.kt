package cn.kasuminova.astd.combat.lens.system

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class EchoFixationMathTest {
    private fun approx(e: Float, a: Float, eps: Float = 1e-3f) =
        assertTrue(abs(e - a) <= eps, "expected ~$e but was $a")

    @Test fun `cognitive tear base damage taken scales with difficulty`() {
        approx(0.50f, EchoFixationMath.cognitiveTearBonus(difficultyFactor = 1f))   // m=1 -> +50%
        approx(1.00f, EchoFixationMath.cognitiveTearBonus(difficultyFactor = 2f))   // m=2 -> +100%
    }

    @Test fun `standstill bonus is max at overlap and decays to floor at edge`() {
        approx(2.00f, EchoFixationMath.standstillBonus(distFromPast = 0f, baseRange = 500f, systemRangeMult = 1f))
        approx(0.25f, EchoFixationMath.standstillBonus(distFromPast = 500f, baseRange = 500f, systemRangeMult = 1f))
        approx(1.125f, EchoFixationMath.standstillBonus(distFromPast = 250f, baseRange = 500f, systemRangeMult = 1f))
    }

    @Test fun `system range mult scales the standstill range`() {
        approx(2.00f, EchoFixationMath.standstillBonus(distFromPast = 0f, baseRange = 500f, systemRangeMult = 2f))
        approx(1.125f, EchoFixationMath.standstillBonus(distFromPast = 500f, baseRange = 500f, systemRangeMult = 2f))
    }

    @Test fun `field radius scales with system range`() {
        approx(700f, EchoFixationMath.fieldRadius(baseRadius = 700f, systemRangeMult = 1f))
        approx(1050f, EchoFixationMath.fieldRadius(baseRadius = 700f, systemRangeMult = 1.5f))
    }
}
