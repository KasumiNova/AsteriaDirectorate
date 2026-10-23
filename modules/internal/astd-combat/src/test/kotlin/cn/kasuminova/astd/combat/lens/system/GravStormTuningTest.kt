package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GravStormTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): GravStormTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravStormTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `电弧数量区间按舰级分档 战机对齐护卫舰档`() {
        assertEquals(2..4, GravStormTuning.arcBaseCountRange(ShipAPI.HullSize.FRIGATE))
        assertEquals(2..4, GravStormTuning.arcBaseCountRange(ShipAPI.HullSize.FIGHTER))
        assertEquals(4..8, GravStormTuning.arcBaseCountRange(ShipAPI.HullSize.DESTROYER))
        assertEquals(8..16, GravStormTuning.arcBaseCountRange(ShipAPI.HullSize.CRUISER))
        assertEquals(12..24, GravStormTuning.arcBaseCountRange(ShipAPI.HullSize.CAPITAL_SHIP))
        assertNull(GravStormTuning.arcBaseCountRange(null))
    }

    @Test
    fun `多目标衰减 每多一个减一成 封顶减半`() {
        assertEquals(1f, GravStormTuning.targetCountMultiplier(1), 1e-6f)
        assertEquals(0.9f, GravStormTuning.targetCountMultiplier(2), 1e-6f)
        assertEquals(0.8f, GravStormTuning.targetCountMultiplier(3), 1e-6f)
        assertEquals(0.5f, GravStormTuning.targetCountMultiplier(6), 1e-6f)
        assertEquals(0.5f, GravStormTuning.targetCountMultiplier(11), 1e-6f)
        assertEquals(1f, GravStormTuning.targetCountMultiplier(0), 1e-6f)
    }

    @Test
    fun `最终电弧数 基础数乘衰减 下限一道`() {
        assertEquals(10, GravStormTuning.finalArcCount(10, 1))
        assertEquals(9, GravStormTuning.finalArcCount(10, 2))
        assertEquals(5, GravStormTuning.finalArcCount(10, 6))
        assertEquals(1, GravStormTuning.finalArcCount(2, 11))
    }

    @Test
    fun `充能占比 两秒为零 四秒为一 越界钳制`() {
        assertEquals(0f, GravStormTuning.chargeNorm(2f), 1e-6f)
        assertEquals(0.5f, GravStormTuning.chargeNorm(3f), 1e-6f)
        assertEquals(1f, GravStormTuning.chargeNorm(4f), 1e-6f)
        assertEquals(0f, GravStormTuning.chargeNorm(0f), 1e-6f)
        assertEquals(1f, GravStormTuning.chargeNorm(99f), 1e-6f)
    }

    @Test
    fun `强制过载时长 按充能占比在锚点半值与锚点间插值`() {
        assertEquals(1f, GravStormTuning.overloadDuration(2f, 0f), 1e-6f)
        assertEquals(1.5f, GravStormTuning.overloadDuration(2f, 0.5f), 1e-6f)
        assertEquals(2f, GravStormTuning.overloadDuration(2f, 1f), 1e-6f)
    }

    @Test
    fun `过载锚点三难度分档 砺刃档全链路取值`() {
        val v1 = resolveAt(1f)
        assertEquals(0.5f, GravStormTuning.overloadAnchor(v1, ShipAPI.HullSize.FRIGATE)!!, 1e-6f)
        assertEquals(1f, GravStormTuning.overloadAnchor(v1, ShipAPI.HullSize.DESTROYER)!!, 1e-6f)
        assertEquals(1f, GravStormTuning.overloadAnchor(v1, ShipAPI.HullSize.CRUISER)!!, 1e-6f)
        assertEquals(2f, GravStormTuning.overloadAnchor(v1, ShipAPI.HullSize.CAPITAL_SHIP)!!, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(1f, GravStormTuning.overloadAnchor(v2, ShipAPI.HullSize.FRIGATE)!!, 1e-6f)
        assertEquals(2f, GravStormTuning.overloadAnchor(v2, ShipAPI.HullSize.DESTROYER)!!, 1e-6f)
        assertEquals(2f, GravStormTuning.overloadAnchor(v2, ShipAPI.HullSize.CRUISER)!!, 1e-6f)
        assertEquals(3f, GravStormTuning.overloadAnchor(v2, ShipAPI.HullSize.CAPITAL_SHIP)!!, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(1.5f, GravStormTuning.overloadAnchor(v5, ShipAPI.HullSize.FRIGATE)!!, 1e-6f)
        assertEquals(3f, GravStormTuning.overloadAnchor(v5, ShipAPI.HullSize.DESTROYER)!!, 1e-6f)
        assertEquals(3f, GravStormTuning.overloadAnchor(v5, ShipAPI.HullSize.CRUISER)!!, 1e-6f)
        assertEquals(6f, GravStormTuning.overloadAnchor(v5, ShipAPI.HullSize.CAPITAL_SHIP)!!, 1e-6f)

        assertEquals(1f, GravStormTuning.overloadAnchor(v2, ShipAPI.HullSize.FIGHTER)!!, 1e-6f)
        assertNull(GravStormTuning.overloadAnchor(v2, null))
    }

    @Test
    fun `硬辐能转软辐能 按舰级分档 随过载时长线性`() {
        assertEquals(0.0025f, GravStormTuning.hardToSoftPerSecond(ShipAPI.HullSize.FIGHTER), 1e-7f)
        assertEquals(0.01f, GravStormTuning.hardToSoftPerSecond(ShipAPI.HullSize.FRIGATE), 1e-7f)
        assertEquals(0.02f, GravStormTuning.hardToSoftPerSecond(ShipAPI.HullSize.DESTROYER), 1e-7f)
        assertEquals(0.03f, GravStormTuning.hardToSoftPerSecond(ShipAPI.HullSize.CRUISER), 1e-7f)
        assertEquals(0.04f, GravStormTuning.hardToSoftPerSecond(ShipAPI.HullSize.CAPITAL_SHIP), 1e-7f)
        assertEquals(0f, GravStormTuning.hardToSoftPerSecond(null), 1e-7f)

        assertEquals(0.06f, GravStormTuning.hardToSoftRatio(ShipAPI.HullSize.CRUISER, 2f), 1e-7f)
        assertEquals(0.0025f, GravStormTuning.hardToSoftRatio(ShipAPI.HullSize.FIGHTER, 1f), 1e-7f)
        assertEquals(0f, GravStormTuning.hardToSoftRatio(null, 2f), 1e-7f)
        assertEquals(0f, GravStormTuning.hardToSoftRatio(ShipAPI.HullSize.CRUISER, 0f), 1e-7f)
    }

    @Test
    fun `充能装饰电弧 数量与间隔随进度渐变`() {
        assertEquals(2, GravStormTuning.chargeArcCount(0f))
        assertEquals(8, GravStormTuning.chargeArcCount(1f))
        assertEquals(5, GravStormTuning.chargeArcCount(0.5f))
        assertEquals(0.5f, GravStormTuning.chargeArcInterval(0f), 1e-6f)
        assertEquals(0.1f, GravStormTuning.chargeArcInterval(1f), 1e-6f)
        assertEquals(0.3f, GravStormTuning.chargeArcInterval(0.5f), 1e-6f)
        assertEquals(2, GravStormTuning.chargeArcCount(-1f))
        assertEquals(0.1f, GravStormTuning.chargeArcInterval(2f), 1e-6f)
    }

    @Test
    fun `电弧伤害与伤害减免三锚点 玩家固定 v2`() {
        val v1 = resolveAt(1f)
        assertEquals(150f, v1.arcEnergyDamage, 1e-6f)
        assertEquals(300f, v1.arcEmpDamage, 1e-6f)
        assertEquals(0.25f, v1.damageTakenReduction, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(600f, v5.arcEnergyDamage, 1e-6f)
        assertEquals(1200f, v5.arcEmpDamage, 1e-6f)
        assertEquals(0.90f, v5.damageTakenReduction, 1e-6f)

        val player = resolveAt(5f, isPlayer = true)
        assertEquals(300f, player.arcEnergyDamage, 1e-6f)
        assertEquals(600f, player.arcEmpDamage, 1e-6f)
        assertEquals(0.50f, player.damageTakenReduction, 1e-6f)
    }

    @Test
    fun `锥状锁定角差口径 六十度锥`() {
        assertTrue(GravStormTuning.isInCone(0f))
        assertTrue(GravStormTuning.isInCone(30f))
        assertFalse(GravStormTuning.isInCone(30.5f))
        assertFalse(GravStormTuning.isInCone(180f))
    }
}
