package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.WeaponOPCostModifier
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.loading.WeaponSpecAPI
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDArcAdvancedTargetingSystemHullModTest {

    private val hullmod = ASTDArcAdvancedTargetingSystemHullMod()

    @kotlin.test.AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun stubStats(variant: ShipVariantAPI): Pair<MutableShipStatsAPI, StatMap> {
        val stats = mock(MutableShipStatsAPI::class.java)
        val map = StatMap()
        `when`(stats.variant).thenReturn(variant)
        `when`(stats.ballisticWeaponRangeBonus).thenReturn(map.ballisticRange)
        `when`(stats.energyWeaponRangeBonus).thenReturn(map.energyRange)
        `when`(stats.beamWeaponRangeBonus).thenReturn(map.beamRange)
        `when`(stats.ballisticProjectileSpeedMult).thenReturn(map.ballisticProjSpeed)
        `when`(stats.energyProjectileSpeedMult).thenReturn(map.energyProjSpeed)
        `when`(stats.ballisticWeaponFluxCostMod).thenReturn(map.ballisticFluxCost)
        `when`(stats.energyWeaponFluxCostMod).thenReturn(map.energyFluxCost)
        `when`(stats.beamWeaponFluxCostMult).thenReturn(map.beamFluxCost)
        `when`(stats.missileWeaponFluxCostMod).thenReturn(map.missileFluxCost)
        return stats to map
    }

    private class StatMap {
        val ballisticRange = StatBonus()
        val energyRange = StatBonus()
        val beamRange = StatBonus()
        val ballisticProjSpeed = MutableStat(100f)
        val energyProjSpeed = MutableStat(100f)
        val ballisticFluxCost = StatBonus()
        val energyFluxCost = StatBonus()
        val beamFluxCost = MutableStat(100f)
        val missileFluxCost = StatBonus()
    }

    private fun cleanVariant(): ShipVariantAPI {
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.sMods).thenReturn(linkedSetOf())
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf())
        `when`(variant.suppressedMods).thenReturn(mutableSetOf())
        return variant
    }

    @Test
    fun `创建前不写入难度缩放乘区 仅注册OP减免监听器`() {
        val (stats, map) = stubStats(cleanVariant())

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.DESTROYER, stats, "test")

        assertEquals(1000f, map.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(100f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(100f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(stats).addListener(captor.capture())
        val listener = captor.value as WeaponOPCostModifier
        val small = mock(WeaponSpecAPI::class.java)
        `when`(small.size).thenReturn(WeaponAPI.WeaponSize.SMALL)
        assertEquals(4, listener.getWeaponOPCost(stats, small, 6), "小型 6 OP → (6-1)×0.8")
        val medium = mock(WeaponSpecAPI::class.java)
        `when`(medium.size).thenReturn(WeaponAPI.WeaponSize.MEDIUM)
        assertEquals(8, listener.getWeaponOPCost(stats, medium, 12), "中型 12 OP → (12-2)×0.8")
        val large = mock(WeaponSpecAPI::class.java)
        `when`(large.size).thenReturn(WeaponAPI.WeaponSize.LARGE)
        assertEquals(20, listener.getWeaponOPCost(stats, large, 20), "大型武器不动")
    }

    @Test
    fun `战斗内按 owner 结算难度缩放乘区 玩家侧默认砺刃 v2`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val (stats, map) = stubStats(cleanVariant())
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isHulk).thenReturn(false)
        `when`(ship.mutableStats).thenReturn(stats)

        hullmod.advanceInCombat(ship, 0.016f)

        assertEquals(1200f, map.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(1200f, map.energyRange.computeEffective(1000f), 1e-3f)
        assertEquals(1200f, map.beamRange.computeEffective(1000f), 1e-3f)
        assertEquals(120f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(120f, map.energyProjSpeed.modifiedValue, 1e-3f)
        assertEquals(80f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(80f, map.energyFluxCost.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `战斗内敌方按 k_s 映射 迟暮 v1 锚点`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val (stats, map) = stubStats(cleanVariant())
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(1)
        `when`(ship.isHulk).thenReturn(false)
        `when`(ship.mutableStats).thenReturn(stats)

        hullmod.advanceInCombat(ship, 0.016f)

        assertEquals(1150f, map.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(115f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(85f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `OP减免纯函数 小型减1再八折 中型减2再八折 下限0 大型与未知槽位不动`() {
        assertEquals(4, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.SMALL, 6))
        assertEquals(0, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.SMALL, 1), "1 OP 小型减 1 后归零不负数")
        assertEquals(8, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.MEDIUM, 12))
        assertEquals(0, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.MEDIUM, 2))
        assertEquals(20, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.LARGE, 20))
        assertEquals(7, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(null, 7))
    }

    @Test
    fun `短射程补偿 按档位阈值与增益上限结算`() {
        assertEquals(200f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(300f, 700f, 200f), "补偿 capped 在 +200")
        assertEquals(200f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(500f, 700f, 200f), "700-500=200 恰好封顶")
        assertEquals(50f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(650f, 700f, 200f), "700-650=50 按差额补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(700f, 700f, 200f), "达到阈值不补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(900f, 700f, 200f), "超阈值不补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(0f, 700f, 200f), "零/负基础射程防线")
        assertEquals(150f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(300f, 600f, 150f))
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(650f, 600f, 150f), "v1 阈值 600，650 不补")
        assertEquals(350f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(500f, 1000f, 350f))
        assertEquals(150f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(850f, 1000f, 350f), "1000-850=150 按差额补")
    }

    @Test
    fun `互斥清理 创建前只检测入队 drain后五路全清`() {
        IncompatibleHullmodStripper.drainPending()
        val sMods = linkedSetOf("targetingunit")
        val sModdedBuiltIns = linkedSetOf("integratedtargetingunit")
        val suppressed = mutableSetOf("dedicated_targeting_core")
        val variant = cleanVariant()
        `when`(variant.sMods).thenReturn(sMods)
        `when`(variant.sModdedBuiltIns).thenReturn(sModdedBuiltIns)
        `when`(variant.suppressedMods).thenReturn(suppressed)
        `when`(variant.hasHullMod("advancedoptics")).thenReturn(true)
        val (stats, _) = stubStats(variant)

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.DESTROYER, stats, "test")

        ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS.forEach { forbiddenId ->
            verify(variant, never()).removeMod(forbiddenId)
            verify(variant, never()).removePermaMod(forbiddenId)
        }
        assertTrue(sMods.contains("targetingunit"), "创建前只入队，S-mod 集合不变")

        IncompatibleHullmodStripper.drainPending()

        ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS.forEach { forbiddenId ->
            verify(variant).removeMod(forbiddenId)
            verify(variant).removePermaMod(forbiddenId)
            verify(variant).removeSuppressedMod(forbiddenId)
        }
        assertTrue(sMods.isEmpty(), "S-mod 集合中的冲突船插被清空")
        assertTrue(sModdedBuiltIns.isEmpty(), "S-modded built-in 集合中的冲突船插被清空")
    }

    @Test
    fun `互斥清理 创建后入队并立即drain`() {
        IncompatibleHullmodStripper.drainPending()
        val sMods = linkedSetOf(HullMods.DISTRIBUTED_FIRE_CONTROL)
        val variant = cleanVariant()
        `when`(variant.sMods).thenReturn(sMods)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.variant).thenReturn(variant)

        hullmod.applyEffectsAfterShipCreation(ship, "test")

        ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS.forEach { forbiddenId ->
            verify(variant).removeMod(forbiddenId)
            verify(variant).removePermaMod(forbiddenId)
            verify(variant).removeSuppressedMod(forbiddenId)
        }
        assertTrue(sMods.isEmpty())
    }

    @Test
    fun `禁止列表集中定义 覆盖原版全部目标定位系统`() {
        assertTrue(
            ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS.containsAll(
                setOf(
                    "targetingunit",
                    "integratedtargetingunit",
                    "dedicated_targeting_core",
                    "advancedoptics",
                    HullMods.DISTRIBUTED_FIRE_CONTROL,
                )
            ),
            "原版目标定位单元/整合定位单元/目标定位核心/先进光学/分布式火控必须全部在禁止列表中",
        )
    }

    @Test
    fun `声明影响装配点 否则statsForOpCosts不创建导致OP监听器不生效`() {
        assertTrue(
            hullmod.affectsOPCosts(),
            "必须声明 affectsOPCosts=true，否则装配界面 OP 结算不回调本船插（原版 HullVariantSpec.updateStatsForOpCosts 口径）",
        )
    }

    @Test
    fun `软提示 装有冲突目标定位系统时不可安装`() {
        val conflictVariant = cleanVariant()
        `when`(conflictVariant.hasHullMod("advancedoptics")).thenReturn(true)
        val conflictShip = mock(ShipAPI::class.java)
        `when`(conflictShip.variant).thenReturn(conflictVariant)
        assertFalse(hullmod.isApplicableToShip(conflictShip))

        val cleanVariant = cleanVariant()
        val cleanShip = mock(ShipAPI::class.java)
        `when`(cleanShip.variant).thenReturn(cleanVariant)
        assertTrue(hullmod.isApplicableToShip(cleanShip))
    }
}
