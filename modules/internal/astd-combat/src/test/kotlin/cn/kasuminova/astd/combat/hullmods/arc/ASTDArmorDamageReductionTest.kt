package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.DamageType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDArmorDamageReductionTest {

    @Test
    fun `armor reduction follows vanilla hit strength over hit strength plus effective armor formula`() {
        val result = ASTDArmorDamageReduction.compute(
            damageAmount = 300f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 300f, isBeam = false),
            armorValue = 900f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )

        // Vanilla-style armor damage reduction uses hitStrength / (hitStrength + effectiveArmor).
        // With 300 hit strength into 900 effective armor, the final damage multiplier is 0.25.
        assertApprox(0.25f, result.damageMultiplier)
        assertApprox(75f, result.damageAfterArmor)
        assertApprox(225f, result.preventedDamage)
    }

    @Test
    fun `armor reduction is capped by max armor damage reduction stat`() {
        val result = ASTDArmorDamageReduction.compute(
            damageAmount = 300f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 300f, isBeam = false),
            armorValue = 9900f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )

        // 300 / (300 + 9900) would be about 2.94%, but vanilla caps armor mitigation at 85%.
        assertApprox(0.15f, result.damageMultiplier)
        assertApprox(45f, result.damageAfterArmor)
        assertApprox(255f, result.preventedDamage)
    }

    @Test
    fun `max armor damage reduction stat can raise the reduction cap`() {
        val result = ASTDArmorDamageReduction.compute(
            damageAmount = 300f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 300f, isBeam = false),
            armorValue = 9900f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.90f,
        )

        assertApprox(0.10f, result.damageMultiplier)
        assertApprox(30f, result.damageAfterArmor)
        assertApprox(270f, result.preventedDamage)
    }

    @Test
    fun `damage type armor multiplier affects both hit strength and applied armor damage`() {
        val kinetic = ASTDArmorDamageReduction.compute(
            damageAmount = 150f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.KINETIC, baseDamage = 300f, isBeam = false),
            armorValue = 900f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )
        val highExplosive = ASTDArmorDamageReduction.compute(
            damageAmount = 600f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.HIGH_EXPLOSIVE, baseDamage = 300f, isBeam = false),
            armorValue = 900f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )

        // Kinetic uses 50% armor effectiveness: damage amount and hit strength are both scaled to 150.
        assertApprox(0.15f, kinetic.damageMultiplier)
        assertApprox(22.5f, kinetic.damageAfterArmor)

        // High explosive uses 200% armor effectiveness: damage amount and hit strength are both scaled to 600.
        assertApprox(0.40f, highExplosive.damageMultiplier)
        assertApprox(240f, highExplosive.damageAfterArmor)
    }

    @Test
    fun `beam hit strength uses half dps while damage amount stays frame scaled`() {
        val result = ASTDArmorDamageReduction.compute(
            damageAmount = 100f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 400f, isBeam = true),
            armorValue = 800f,
            minArmorValue = 0f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )

        assertApprox(200f, ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 400f, isBeam = true))
        assertApprox(0.20f, result.damageMultiplier)
        assertApprox(20f, result.damageAfterArmor)
    }

    @Test
    fun `minimum armor fraction provides a lower armor floor`() {
        val result = ASTDArmorDamageReduction.compute(
            damageAmount = 300f,
            hitStrength = ASTDArmorDamageReduction.hitStrength(DamageType.ENERGY, baseDamage = 300f, isBeam = false),
            armorValue = 10f,
            minArmorValue = 300f,
            effectiveArmorMult = 1f,
            maxArmorDamageReduction = 0.85f,
        )

        assertApprox(0.50f, result.damageMultiplier)
        assertApprox(150f, result.damageAfterArmor)
    }

    private fun assertApprox(expected: Float, actual: Float) {
        assertEquals(expected, actual, absoluteTolerance = 0.0001f)
    }
}
