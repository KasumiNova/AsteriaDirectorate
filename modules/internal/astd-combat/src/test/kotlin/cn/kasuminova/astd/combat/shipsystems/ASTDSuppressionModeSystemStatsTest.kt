package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 压制模式 Stats（[ASTDSuppressionModeSystemStats]）行为验证：
 * - apply 在 ACTIVE 满额时把五项难度缩放效果与恒定射速加成写入对应乘区
 *   （真实 MutableStat/StatBonus 对象 + 砺刃 v2 口径）；
 * - effectLevel 渐入半额缩放与 unapply 完全还原；
 * - settleHardFlux 硬辐能结算：基础最大辐能 × 当前每秒比例 × 帧时长 × 渐入系数，
 *   恰为 increaseFlux(amount, hardFlux=true)；零帧长/零渐入不写辐能。
 */
class ASTDSuppressionModeSystemStatsTest {

    private val stats = ASTDSuppressionModeSystemStats()

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    /** 挂满真实乘区对象的 ship stats 桩（砺刃 v2 口径：敌方 k_s = 2）。 */
    private class StatFixture {
        val maxSpeed = MutableStat(100f)
        val acceleration = MutableStat(100f)
        val deceleration = MutableStat(100f)
        val maxTurnRate = MutableStat(30f)
        val turnAcceleration = MutableStat(60f)
        val ballisticFluxCost = StatBonus()
        val energyFluxCost = StatBonus()
        val missileFluxCost = StatBonus()
        val ballisticRange = StatBonus()
        val energyRange = StatBonus()
        val beamRange = StatBonus()
        val missileRange = StatBonus()
        val shieldDamageTaken = MutableStat(1f)
        val ballisticRoF = MutableStat(100f)
        val energyRoF = MutableStat(100f)
        val missileRoF = MutableStat(100f)
        val fluxCapacity = MutableStat(7000f)

        val ship: ShipAPI = mock(ShipAPI::class.java).also { `when`(it.owner).thenReturn(1) }
        val stats: MutableShipStatsAPI = mock(MutableShipStatsAPI::class.java).also { s ->
            `when`(s.entity).thenReturn(ship)
            `when`(s.maxSpeed).thenReturn(maxSpeed)
            `when`(s.acceleration).thenReturn(acceleration)
            `when`(s.deceleration).thenReturn(deceleration)
            `when`(s.maxTurnRate).thenReturn(maxTurnRate)
            `when`(s.turnAcceleration).thenReturn(turnAcceleration)
            `when`(s.ballisticWeaponFluxCostMod).thenReturn(ballisticFluxCost)
            `when`(s.energyWeaponFluxCostMod).thenReturn(energyFluxCost)
            `when`(s.missileWeaponFluxCostMod).thenReturn(missileFluxCost)
            `when`(s.ballisticWeaponRangeBonus).thenReturn(ballisticRange)
            `when`(s.energyWeaponRangeBonus).thenReturn(energyRange)
            `when`(s.beamWeaponRangeBonus).thenReturn(beamRange)
            `when`(s.missileWeaponRangeBonus).thenReturn(missileRange)
            `when`(s.shieldDamageTakenMult).thenReturn(shieldDamageTaken)
            `when`(s.ballisticRoFMult).thenReturn(ballisticRoF)
            `when`(s.energyRoFMult).thenReturn(energyRoF)
            `when`(s.missileRoFMult).thenReturn(missileRoF)
            `when`(s.fluxCapacity).thenReturn(fluxCapacity)
        }
    }

    @Test
    fun `满额激活 五项效果写入对应乘区 砺刃v2口径`() {
        DifficultyTuningImpl.installScaleForTests(2f)
        val fixture = StatFixture()

        stats.apply(fixture.stats, "test", ShipSystemStatsScript.State.ACTIVE, 1f)

        // 航速与机动性 -50%
        assertEquals(50f, fixture.maxSpeed.modifiedValue, 1e-3f)
        assertEquals(50f, fixture.acceleration.modifiedValue, 1e-3f)
        assertEquals(50f, fixture.deceleration.modifiedValue, 1e-3f)
        assertEquals(15f, fixture.maxTurnRate.modifiedValue, 1e-3f)
        assertEquals(30f, fixture.turnAcceleration.modifiedValue, 1e-3f)
        // 武器辐能产出 -50%（弹道/能量/导弹三通道）
        assertEquals(50f, fixture.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(50f, fixture.energyFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(50f, fixture.missileFluxCost.computeEffective(100f), 1e-3f)
        // 武器射程 +30%（四通道）
        assertEquals(1300f, fixture.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(1300f, fixture.energyRange.computeEffective(1000f), 1e-3f)
        assertEquals(1300f, fixture.beamRange.computeEffective(1000f), 1e-3f)
        assertEquals(1300f, fixture.missileRange.computeEffective(1000f), 1e-3f)
        // 护盾承伤 -30%
        assertEquals(0.7f, fixture.shieldDamageTaken.modifiedValue, 1e-6f)
        // 武器射速 +30%（三通道）
        assertEquals(130f, fixture.ballisticRoF.modifiedValue, 1e-3f)
        assertEquals(130f, fixture.energyRoF.modifiedValue, 1e-3f)
        assertEquals(130f, fixture.missileRoF.modifiedValue, 1e-3f)
    }

    @Test
    fun `渐入半额缩放 各项效果按 effectLevel 折半`() {
        DifficultyTuningImpl.installScaleForTests(2f)
        val fixture = StatFixture()

        stats.apply(fixture.stats, "test", ShipSystemStatsScript.State.IN, 0.5f)

        assertEquals(75f, fixture.maxSpeed.modifiedValue, 1e-3f, "渐入一半时航速惩罚减半")
        assertEquals(75f, fixture.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(1150f, fixture.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(0.85f, fixture.shieldDamageTaken.modifiedValue, 1e-6f)
        assertEquals(115f, fixture.ballisticRoF.modifiedValue, 1e-3f)
    }

    @Test
    fun `unapply 完全还原全部乘区`() {
        DifficultyTuningImpl.installScaleForTests(2f)
        val fixture = StatFixture()
        stats.apply(fixture.stats, "test", ShipSystemStatsScript.State.ACTIVE, 1f)

        stats.unapply(fixture.stats, "test")

        assertEquals(100f, fixture.maxSpeed.modifiedValue, 1e-3f)
        assertEquals(100f, fixture.acceleration.modifiedValue, 1e-3f)
        assertEquals(30f, fixture.maxTurnRate.modifiedValue, 1e-3f)
        assertEquals(100f, fixture.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(100f, fixture.missileFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(1000f, fixture.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(1000f, fixture.beamRange.computeEffective(1000f), 1e-3f)
        assertEquals(1f, fixture.shieldDamageTaken.modifiedValue, 1e-6f)
        assertEquals(100f, fixture.ballisticRoF.modifiedValue, 1e-3f)
        assertEquals(100f, fixture.missileRoF.modifiedValue, 1e-3f)
    }

    @Test
    fun `硬辐能结算 产出量等于基础最大辐能乘当前比例乘帧时长乘渐入系数`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(tracker)

        stats.settleHardFlux(ship, 7000f, 4f, 1f, 1f)
        stats.settleHardFlux(ship, 7000f, 0f, 1f, 1f)
        stats.settleHardFlux(ship, 7000f, 2f, 0.5f, 0.5f)

        val captor = ArgumentCaptor.forClass(Float::class.java)
        verify(tracker, times(3)).increaseFlux(captor.capture(), eq(true))
        val amounts = captor.allValues.map { it.toFloat() }
        // 第 4 秒满比例 6%：7000 × 0.06 × 1s × 满额 = 420 硬辐能
        assertEquals(420f, amounts[0], 1e-2f)
        // 激活首秒比例 2%：7000 × 0.02 × 1s = 140
        assertEquals(140f, amounts[1], 1e-2f)
        // 爬坡中点 4% + 渐入半额减半：7000 × 0.04 × 0.5s × 0.5 = 70
        assertEquals(70f, amounts[2], 1e-2f)
    }

    @Test
    fun `硬辐能结算 零帧长零渐入零基础容量不写辐能`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(tracker)

        stats.settleHardFlux(ship, 7000f, 4f, 0f, 1f)
        stats.settleHardFlux(ship, 7000f, 4f, 1f, 0f)
        stats.settleHardFlux(ship, 0f, 4f, 1f, 1f)

        verify(tracker, never()).increaseFlux(anyFloat(), anyBoolean())
    }
}
