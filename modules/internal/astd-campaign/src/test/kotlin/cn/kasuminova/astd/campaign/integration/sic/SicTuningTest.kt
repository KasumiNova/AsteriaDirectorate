package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SicTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
        DifficultyTuningImpl.installPlayerScaleForTests(null)
    }

    @Test
    fun `学说侵略度映射到对应 AI 核心性格 越界按稳健处理`() {
        assertEquals(Personalities.CAUTIOUS, SicTuning.doctrinePersonality(1))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(2))
        assertEquals(Personalities.AGGRESSIVE, SicTuning.doctrinePersonality(3))
        assertEquals(Personalities.AGGRESSIVE, SicTuning.doctrinePersonality(4))
        assertEquals(Personalities.RECKLESS, SicTuning.doctrinePersonality(5))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(0))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(6))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(-1))
    }

    @Test
    fun `核心倍率乘区因子等于新倍率比原倍率 且新倍率钳制不低于 1`() {
        val tierMult = StandardCores.Tier.A.autoPointsMult

        val reduction = SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v2
        val factor = SicTuning.coreMultFactor(tierMult, reduction)
        assertEquals(tierMult - reduction, tierMult * factor, 1e-6f)

        val atClamp = SicTuning.coreMultFactor(tierMult, tierMult)
        assertEquals(1f, tierMult * atClamp, 1e-6f)

        val beyondClamp = SicTuning.coreMultFactor(tierMult, tierMult + SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v5)
        assertEquals(1f, tierMult * beyondClamp, 1e-6f)
        assertEquals(atClamp, beyondClamp, 1e-6f)
    }

    @Test
    fun `核心倍率降低量经难度链路解析 敌我两侧分轨取值`() {
        val tierMult = StandardCores.Tier.G.autoPointsMult

        DifficultyTuningImpl.installScaleForTests(5f)
        val enemyReduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, false)
        assertEquals(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v5, enemyReduction, 1e-6f)
        assertEquals(1f, tierMult * SicTuning.coreMultFactor(tierMult, enemyReduction), 1e-6f)

        DifficultyTuningImpl.installPlayerScaleForTests(1f)
        val playerReduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, true)
        assertEquals(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v1, playerReduction, 1e-6f)
        assertEquals(
            tierMult - SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v1,
            tierMult * SicTuning.coreMultFactor(tierMult, playerReduction),
            1e-6f,
        )
    }

    @Test
    fun `无人舰点数解析后四舍五入为整数`() {
        val entry = ScalingEntry(10f, 120.5f, 240f)
        DifficultyTuningImpl.installPlayerScaleForTests(2f)
        assertEquals(121, SicShared.autoPointsFor(entry, isPlayer = true))
        assertEquals(
            SicTuning.SKYDOME_NETWORK_AUTO_POINTS.v2.toInt(),
            SicShared.autoPointsFor(SicTuning.SKYDOME_NETWORK_AUTO_POINTS, isPlayer = true),
        )
    }

    @Test
    fun `硬辐能斜坡 起点以下为 0 满载点以上为 1 中点线性插值`() {
        val start = SicTuning.FROST_RAMP_START
        val full = SicTuning.FROST_RAMP_START + SicTuning.FROST_RAMP_SPAN

        assertEquals(0f, SicTuning.hardFluxRampT(start))
        assertEquals(0f, SicTuning.hardFluxRampT(start / 2f))
        assertEquals(1f, SicTuning.hardFluxRampT(full))
        assertEquals(1f, SicTuning.hardFluxRampT(full * 2f))
        assertEquals(0.5f, SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN / 2f), 1e-6f)
        assertTrue(SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN / 4f) < SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN * 3f / 4f))
    }

    @Test
    fun `硬辐能占比防 0 除 正常取硬辐能与最大辐能之比`() {
        assertEquals(0f, SicTuning.hardFluxFraction(hardFlux = 100f, maxFlux = 0f))
        assertEquals(0f, SicTuning.hardFluxFraction(hardFlux = 100f, maxFlux = -5f))
        assertEquals(0.25f, SicTuning.hardFluxFraction(hardFlux = 250f, maxFlux = 1000f), 1e-6f)
        assertEquals(1f, SicTuning.hardFluxFraction(hardFlux = 1200f, maxFlux = 1000f))
    }

    @Test
    fun `狼群按舰级选择加成 entry 主力舰与战机不生效`() {        assertSame(SicTuning.WOLFPACK_BONUS_FRIGATE, SicTuning.wolfpackBonusEntry(ShipAPI.HullSize.FRIGATE))
        assertSame(SicTuning.WOLFPACK_BONUS_DESTROYER, SicTuning.wolfpackBonusEntry(ShipAPI.HullSize.DESTROYER))
        assertSame(SicTuning.WOLFPACK_BONUS_CRUISER, SicTuning.wolfpackBonusEntry(ShipAPI.HullSize.CRUISER))
        assertNull(SicTuning.wolfpackBonusEntry(ShipAPI.HullSize.CAPITAL_SHIP))
        assertNull(SicTuning.wolfpackBonusEntry(ShipAPI.HullSize.FIGHTER))
        assertNull(SicTuning.wolfpackBonusEntry(null))

        assertEquals(SicTuning.WOLFPACK_DP_FRIGATE, SicTuning.wolfpackDeploymentBonus(ShipAPI.HullSize.FRIGATE))
        assertEquals(SicTuning.WOLFPACK_DP_DESTROYER, SicTuning.wolfpackDeploymentBonus(ShipAPI.HullSize.DESTROYER))
        assertEquals(SicTuning.WOLFPACK_DP_CRUISER, SicTuning.wolfpackDeploymentBonus(ShipAPI.HullSize.CRUISER))
        assertEquals(0f, SicTuning.wolfpackDeploymentBonus(ShipAPI.HullSize.CAPITAL_SHIP))
        assertEquals(0f, SicTuning.wolfpackDeploymentBonus(ShipAPI.HullSize.FIGHTER))
        assertEquals(0f, SicTuning.wolfpackDeploymentBonus(null))
    }

    @Test
    fun `维度分流装甲计算值 按舰级选 entry 战机与未知按护卫舰档`() {
        assertSame(SicTuning.DIM_ARMOR_FRIGATE, SicTuning.dimArmorEntry(ShipAPI.HullSize.FRIGATE))
        assertSame(SicTuning.DIM_ARMOR_DESTROYER, SicTuning.dimArmorEntry(ShipAPI.HullSize.DESTROYER))
        assertSame(SicTuning.DIM_ARMOR_CRUISER, SicTuning.dimArmorEntry(ShipAPI.HullSize.CRUISER))
        assertSame(SicTuning.DIM_ARMOR_CAPITAL, SicTuning.dimArmorEntry(ShipAPI.HullSize.CAPITAL_SHIP))
        assertSame(SicTuning.DIM_ARMOR_FRIGATE, SicTuning.dimArmorEntry(ShipAPI.HullSize.FIGHTER))
        assertSame(SicTuning.DIM_ARMOR_FRIGATE, SicTuning.dimArmorEntry(null))
    }

    @Test
    fun `维度分流装甲计算值 排辐翻倍与上限钳制 上限同样翻倍`() {
        val base = SicTuning.DIM_ARMOR_CAPITAL.v2
        val maxArmor = 1600f

        assertEquals(base, SicTuning.dimArmorBonus(base, ventingOrOverloaded = false, maxArmor), 1e-6f)
        assertEquals(
            base * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(base, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )

        val cap = maxArmor * SicTuning.DIM_ARMOR_CAP_FRACTION
        assertEquals(cap, SicTuning.dimArmorBonus(cap * 2f, ventingOrOverloaded = false, maxArmor), 1e-6f)
        assertEquals(
            cap * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(cap * 2f, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )
        assertEquals(
            cap * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(cap * SicTuning.DIM_VENT_OVERLOAD_MULT * 2f, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )

        assertEquals(0f, SicTuning.dimArmorBonus(base, ventingOrOverloaded = false, maxArmor = 0f), 1e-6f)
        assertEquals(0f, SicTuning.dimArmorBonus(-base, ventingOrOverloaded = false, maxArmor), 1e-6f)
    }

    @Test
    fun `维度分流能量吸收额度 按比例减免 负伤害与越界比例钳制`() {
        val absorb = SicTuning.DIM_ENERGY_ABSORB.v2
        assertEquals(1000f * absorb, SicTuning.dimEnergyAbsorbed(1000f, absorb), 1e-6f)
        assertEquals(0f, SicTuning.dimEnergyAbsorbed(-1000f, absorb), 1e-6f)
        assertEquals(0f, SicTuning.dimEnergyAbsorbed(1000f, 0f), 1e-6f)
        assertEquals(1000f, SicTuning.dimEnergyAbsorbed(1000f, 1.5f), 1e-6f)
        assertEquals(0f, SicTuning.dimEnergyAbsorbed(1000f, -0.5f), 1e-6f)
    }

    @Test
    fun `菀星设计局制造商判定 星坠与紫菀命中 其余不命中`() {
        assertTrue(SicTuning.isWanxingManufacturer(SicTuning.MANUFACTURER_STARFALL))
        assertTrue(SicTuning.isWanxingManufacturer(SicTuning.MANUFACTURER_ASTER))
        assertTrue(!SicTuning.isWanxingManufacturer("菀星设计局"))
        assertTrue(!SicTuning.isWanxingManufacturer("未知制造商"))
        assertTrue(!SicTuning.isWanxingManufacturer(null))
    }

    @Test
    fun `天幕解限等级加成合成 基础与制式追加各自取整后相加 非制式不追加`() {
        assertEquals(
            SicTuning.UNBOUND_LEVEL_BONUS.v2.toInt() + SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2.toInt(),
            SicTuning.unboundTargetBonus(SicTuning.UNBOUND_LEVEL_BONUS.v2, SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2, standardCore = true),
        )
        assertEquals(
            SicTuning.UNBOUND_LEVEL_BONUS.v2.toInt(),
            SicTuning.unboundTargetBonus(SicTuning.UNBOUND_LEVEL_BONUS.v2, SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2, standardCore = false),
        )
        assertEquals(2, SicTuning.unboundTargetBonus(0.6f, 0.6f, standardCore = true))
        assertEquals(1, SicTuning.unboundTargetBonus(0.6f, 0.6f, standardCore = false))
        assertEquals(1, SicTuning.unboundTargetBonus(0.6f, 0.4f, standardCore = true))
    }

    @Test
    fun `天幕解限等级加成经难度链路查表 玩家与敌方分轨取档`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val enemyBase = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, false)
        val enemyExtra = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_STANDARD_CORE_EXTRA, false)
        assertEquals(SicTuning.UNBOUND_LEVEL_BONUS.v5, enemyBase, 1e-6f)
        assertEquals(
            (SicTuning.UNBOUND_LEVEL_BONUS.v5 + SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v5).toInt(),
            SicTuning.unboundTargetBonus(enemyBase, enemyExtra, standardCore = true),
        )

        DifficultyTuningImpl.installPlayerScaleForTests(1f)
        val playerBase = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, true)
        assertEquals(SicTuning.UNBOUND_LEVEL_BONUS.v1, playerBase, 1e-6f)
        assertEquals(
            SicTuning.UNBOUND_LEVEL_BONUS.v1.toInt(),
            SicTuning.unboundTargetBonus(playerBase, 0f, standardCore = false),
        )
    }

    @Test
    fun `天幕解限幂等补差 目标高于已发放补正差 不高则不回扣`() {
        assertEquals(2, SicTuning.unboundPendingDelta(target = 3, granted = 1))
        assertEquals(3, SicTuning.unboundPendingDelta(target = 3, granted = 0))
        assertEquals(0, SicTuning.unboundPendingDelta(target = 2, granted = 2))
        assertEquals(0, SicTuning.unboundPendingDelta(target = 1, granted = 3))
        assertEquals(1, SicTuning.unboundPendingDelta(target = 4, granted = 3))
    }

    @Test
    fun `智能联结改造按钮可见性 天赋生效且未改造且非战机`() {
        assertTrue(SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = false, fighter = false))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = false, hasMarker = false, fighter = false))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = true, fighter = false))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = false, fighter = true))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = false, hasMarker = true, fighter = true))
    }

    @Test
    fun `智能联结改造按钮可点击 核心与故事点均不低于消耗`() {
        assertTrue(SicTuning.intelLinkButtonClickable(cores = 1f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST))
        assertTrue(SicTuning.intelLinkButtonClickable(cores = 3f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST + 4))
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 0f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST))
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 0.5f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST + 4))
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 1f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST - 1))
    }
}
