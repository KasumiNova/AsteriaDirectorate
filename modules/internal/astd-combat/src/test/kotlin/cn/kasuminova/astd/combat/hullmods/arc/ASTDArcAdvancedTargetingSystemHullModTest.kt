package cn.kasuminova.astd.combat.hullmods.arc

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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 高级舰装集成（[ASTDArcAdvancedTargetingSystemHullMod]）行为验证：
 * - applyEffectsBeforeShipCreation 的射程/弹速/武器辐能乘区写入与 WeaponOPCostModifier 注册；
 * - OP 减免纯函数（小型 -1 再 -20%、中型 -2 再 -20%、下限 0、大型不动）；
 * - 短射程补偿纯函数（基础射程 <700 至多 +200）；
 * - 目标定位系统互斥：创建前后双路径五路清理（普通/permaMod/S-mod/S-modded built-in/suppressed）
 *   与 isApplicableToShip 软提示；禁止列表集中定义断言。
 */
class ASTDArcAdvancedTargetingSystemHullModTest {

    private val hullmod = ASTDArcAdvancedTargetingSystemHullMod()

    /** 挂满真实乘区对象的 stats 桩：variant 为 [variant]，其余乘区为真实 StatBonus/MutableStat。 */
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
    }

    private fun cleanVariant(): ShipVariantAPI {
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.sMods).thenReturn(linkedSetOf())
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf())
        `when`(variant.suppressedMods).thenReturn(mutableSetOf())
        return variant
    }

    @Test
    fun `创建前效果写入 射程弹速武器辐能乘区并注册OP减免监听器`() {
        val (stats, map) = stubStats(cleanVariant())

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.DESTROYER, stats, "test")

        // 舰船武器射程 +20%（弹道/能量/光束三通道）
        assertEquals(1200f, map.ballisticRange.computeEffective(1000f), 1e-3f)
        assertEquals(1200f, map.energyRange.computeEffective(1000f), 1e-3f)
        assertEquals(1200f, map.beamRange.computeEffective(1000f), 1e-3f)
        // 实弹与能量武器射弹飞行速度 +20%
        assertEquals(120f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(120f, map.energyProjSpeed.modifiedValue, 1e-3f)
        // 实弹与能量武器辐能产出 -20%
        assertEquals(80f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(80f, map.energyFluxCost.computeEffective(100f), 1e-3f)

        // OP 减免经 WeaponOPCostModifier 监听器接入装配结算
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
    fun `OP减免纯函数 小型减1再八折 中型减2再八折 下限0 大型与未知槽位不动`() {
        assertEquals(4, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.SMALL, 6))
        assertEquals(0, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.SMALL, 1), "1 OP 小型减 1 后归零不负数")
        assertEquals(8, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.MEDIUM, 12))
        assertEquals(0, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.MEDIUM, 2))
        assertEquals(20, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(WeaponAPI.WeaponSize.LARGE, 20))
        assertEquals(7, ASTDArcAdvancedTargetingSystemHullMod.weaponOpCost(null, 7))
    }

    @Test
    fun `短射程补偿 基础射程低于700的非导弹武器至多补200`() {
        assertEquals(200f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(300f), "补偿 capped 在 +200")
        assertEquals(200f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(500f), "700-500=200 恰好封顶")
        assertEquals(50f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(650f), "700-650=50 按差额补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(700f), "达到阈值不补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(900f), "超阈值不补")
        assertEquals(0f, ASTDArcAdvancedTargetingSystemHullMod.shortRangeFlatBonus(0f), "零/负基础射程防线")
    }

    @Test
    fun `互斥硬清理 创建前五路全清`() {
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

        // 普通 + permaMod + suppressed 走 variant 移除调用，S-mod 与 S-modded built-in 走集合移除
        ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS.forEach { forbiddenId ->
            verify(variant).removeMod(forbiddenId)
            verify(variant).removePermaMod(forbiddenId)
            verify(variant).removeSuppressedMod(forbiddenId)
        }
        assertTrue(sMods.isEmpty(), "S-mod 集合中的冲突船插被清空")
        assertTrue(sModdedBuiltIns.isEmpty(), "S-modded built-in 集合中的冲突船插被清空")
    }

    @Test
    fun `互斥硬清理 创建后同路径生效`() {
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
