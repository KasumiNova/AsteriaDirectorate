package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDPlasmaShieldGridStateTest {

    @Test
    fun `maps relative hit angles into twelve thirty-degree sectors`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 1600f)

        assertEquals(0, grid.sectorForHitAngle(0f))
        assertEquals(0, grid.sectorForHitAngle(29.99f))
        assertEquals(1, grid.sectorForHitAngle(30f))
        assertEquals(11, grid.sectorForHitAngle(359.99f))
        assertEquals(11, grid.sectorForHitAngle(-0.01f))
        assertEquals(0, grid.sectorForHitAngle(720f))
    }

    @Test
    fun `sector integrity is capped from final max armor and bottoms at five percent effectiveness`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 2000f)
        val sector = grid.sectorForHitAngle(75f)

        grid.applyAbsorbedDamage(sector, 3000f)

        assertEquals(0.05f, grid.effectiveArmorFraction(sector), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `absorbed damage reduces only the impacted sector`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 1200f)

        grid.applyAbsorbedDamage(2, 225f)

        assertEquals(0.75f, grid.effectiveArmorFraction(2), absoluteTolerance = 0.0001f)
        assertEquals(1f, grid.effectiveArmorFraction(3), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `recently hit sectors recover at one percent per second`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 1000f)
        grid.applyAbsorbedDamage(4, 375f)

        grid.advance(5f)

        assertEquals(0.55f, grid.effectiveArmorFraction(4), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `sectors recover at five percent per second after ten seconds without hits`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 1000f)
        grid.applyAbsorbedDamage(7, 375f)

        grid.advance(10f)
        grid.advance(4f)

        assertEquals(0.80f, grid.effectiveArmorFraction(7), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `recovery cannot exceed full integrity`() {
        val grid = ASTDPlasmaShieldGridState(finalMaxArmor = 1000f)
        grid.applyAbsorbedDamage(9, 75f)

        grid.advance(30f)

        assertEquals(1f, grid.effectiveArmorFraction(9), absoluteTolerance = 0.0001f)
    }
}
