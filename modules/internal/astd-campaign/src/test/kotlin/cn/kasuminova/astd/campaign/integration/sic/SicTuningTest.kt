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

/**
 * SIC 联动「菀星智能」数值规格（[SicTuning]）的纯逻辑校验：
 * 学说侵略度→AI 核心性格映射（含越界）、菀星核心倍率乘区因子（含 max(1,·) 钳制边界与
 * 难度完整映射链路）、硬辐能耗散斜坡（起点/满载/中点插值与 0 除防护）、
 * 狼群按舰级选择加成 entry 与部署点提升（含不适用舰级）、
 * 天幕解限等级加成合成（各自取整后相加、难度分轨查表）与幂等高水位补差（只补差、不回扣）、
 * 智能联结改造按钮可见性/可点击判定（含边界值）。
 */
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
        // 学说侵略度契约区间为 1~5，越界（含 0 与负值）一律回退稳健
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(0))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(6))
        assertEquals(Personalities.STEADY, SicTuning.doctrinePersonality(-1))
    }

    @Test
    fun `核心倍率乘区因子等于新倍率比原倍率 且新倍率钳制不低于 1`() {
        val tierMult = StandardCores.Tier.A.autoPointsMult

        // 未触及钳制：新倍率 = tierMult - reduction
        val reduction = SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v2
        val factor = SicTuning.coreMultFactor(tierMult, reduction)
        assertEquals(tierMult - reduction, tierMult * factor, 1e-6f)

        // 钳制边界：降低量恰好等于档位倍率 → 新倍率恰为 1
        val atClamp = SicTuning.coreMultFactor(tierMult, tierMult)
        assertEquals(1f, tierMult * atClamp, 1e-6f)

        // 越过钳制：降低量大于档位倍率 → 新倍率仍为 1 而不继续下探
        val beyondClamp = SicTuning.coreMultFactor(tierMult, tierMult + SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v5)
        assertEquals(1f, tierMult * beyondClamp, 1e-6f)
        assertEquals(atClamp, beyondClamp, 1e-6f)
    }

    @Test
    fun `核心倍率降低量经难度链路解析 敌我两侧分轨取值`() {
        val tierMult = StandardCores.Tier.G.autoPointsMult

        // 敌方 k_s=5：降低量取 v5 锚点
        DifficultyTuningImpl.installScaleForTests(5f)
        val enemyReduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, false)
        assertEquals(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION.v5, enemyReduction, 1e-6f)
        // G 档倍率 2，v5 降低 2.5 → 越过钳制，新倍率为 1
        assertEquals(1f, tierMult * SicTuning.coreMultFactor(tierMult, enemyReduction), 1e-6f)

        // 玩家侧不受敌方系数影响：我方 k_s=1 → 降低量取 v1 锚点
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
        // 小数锚点经玩家侧（砺刃 2.0 → v2）解析后按 roundToInt 取整
        val entry = ScalingEntry(10f, 120.5f, 240f)
        DifficultyTuningImpl.installPlayerScaleForTests(2f)
        assertEquals(121, SicShared.autoPointsFor(entry, isPlayer = true))
        // 设计声明的三锚点均为整数，解析结果不丢失精度
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
        // 斜坡中点（起点 + 半宽）进度为 0.5
        assertEquals(0.5f, SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN / 2f), 1e-6f)
        // 斜坡区间内单调
        assertTrue(SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN / 4f) < SicTuning.hardFluxRampT(start + SicTuning.FROST_RAMP_SPAN * 3f / 4f))
    }

    @Test
    fun `硬辐能占比防 0 除 正常取硬辐能与最大辐能之比`() {
        assertEquals(0f, SicTuning.hardFluxFraction(hardFlux = 100f, maxFlux = 0f))
        assertEquals(0f, SicTuning.hardFluxFraction(hardFlux = 100f, maxFlux = -5f))
        assertEquals(0.25f, SicTuning.hardFluxFraction(hardFlux = 250f, maxFlux = 1000f), 1e-6f)
        // 超界钳制（硬辐能理论上不超过最大辐能，防御性钳到 [0,1]）
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

        // 常态：基础值未触及上限，原样生效
        assertEquals(base, SicTuning.dimArmorBonus(base, ventingOrOverloaded = false, maxArmor), 1e-6f)
        // 排辐/过载：效果翻倍
        assertEquals(
            base * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(base, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )

        // 上限钳制：基础值超过 25% 最大装甲时按上限生效
        val cap = maxArmor * SicTuning.DIM_ARMOR_CAP_FRACTION
        assertEquals(cap, SicTuning.dimArmorBonus(cap * 2f, ventingOrOverloaded = false, maxArmor), 1e-6f)
        // 翻倍状态下上限同样翻倍
        assertEquals(
            cap * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(cap * 2f, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )
        // 翻倍后的效果仍受翻倍上限钳制（不会无限放大）
        assertEquals(
            cap * SicTuning.DIM_VENT_OVERLOAD_MULT,
            SicTuning.dimArmorBonus(cap * SicTuning.DIM_VENT_OVERLOAD_MULT * 2f, ventingOrOverloaded = true, maxArmor),
            1e-6f,
        )

        // 0 值防线：最大装甲非正或基础值非正恒 0
        assertEquals(0f, SicTuning.dimArmorBonus(base, ventingOrOverloaded = false, maxArmor = 0f), 1e-6f)
        assertEquals(0f, SicTuning.dimArmorBonus(-base, ventingOrOverloaded = false, maxArmor), 1e-6f)
    }

    @Test
    fun `维度分流能量吸收额度 按比例减免 负伤害与越界比例钳制`() {
        val absorb = SicTuning.DIM_ENERGY_ABSORB.v2
        assertEquals(1000f * absorb, SicTuning.dimEnergyAbsorbed(1000f, absorb), 1e-6f)
        assertEquals(0f, SicTuning.dimEnergyAbsorbed(-1000f, absorb), 1e-6f)
        assertEquals(0f, SicTuning.dimEnergyAbsorbed(1000f, 0f), 1e-6f)
        // 比例越界钳制：>1 按 1、<0 按 0
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
        // 整档值：制式核心 = 基础 + 追加，非制式核心 = 基础
        assertEquals(
            SicTuning.UNBOUND_LEVEL_BONUS.v2.toInt() + SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2.toInt(),
            SicTuning.unboundTargetBonus(SicTuning.UNBOUND_LEVEL_BONUS.v2, SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2, standardCore = true),
        )
        assertEquals(
            SicTuning.UNBOUND_LEVEL_BONUS.v2.toInt(),
            SicTuning.unboundTargetBonus(SicTuning.UNBOUND_LEVEL_BONUS.v2, SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v2, standardCore = false),
        )
        // 取整在相加之前：两个 0.6 档值不得凑出额外 1 级
        assertEquals(2, SicTuning.unboundTargetBonus(0.6f, 0.6f, standardCore = true))
        assertEquals(1, SicTuning.unboundTargetBonus(0.6f, 0.6f, standardCore = false))
        // 追加为 0 档值时制式核心与基础一致
        assertEquals(1, SicTuning.unboundTargetBonus(0.6f, 0.4f, standardCore = true))
    }

    @Test
    fun `天幕解限等级加成经难度链路查表 玩家与敌方分轨取档`() {
        // 敌方 k_s=5：基础与追加均取 v5 档，制式核心合成为两者之和
        DifficultyTuningImpl.installScaleForTests(5f)
        val enemyBase = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, false)
        val enemyExtra = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_STANDARD_CORE_EXTRA, false)
        assertEquals(SicTuning.UNBOUND_LEVEL_BONUS.v5, enemyBase, 1e-6f)
        assertEquals(
            (SicTuning.UNBOUND_LEVEL_BONUS.v5 + SicTuning.UNBOUND_STANDARD_CORE_EXTRA.v5).toInt(),
            SicTuning.unboundTargetBonus(enemyBase, enemyExtra, standardCore = true),
        )

        // 玩家侧不受敌方系数影响：我方 k_s=1 → 均取 v1 档
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
        // 难度下调后目标低于已发放：不回扣，差值为 0
        assertEquals(0, SicTuning.unboundPendingDelta(target = 1, granted = 3))
        // 再次上调只补相对高水位的差：已发放 3、目标 4 → 只补 1（不重复发放前 3 级）
        assertEquals(1, SicTuning.unboundPendingDelta(target = 4, granted = 3))
    }

    @Test
    fun `智能联结改造按钮可见性 天赋生效且未改造且非战机`() {
        assertTrue(SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = false, fighter = false))
        // 任一条件不满足即隐藏
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = false, hasMarker = false, fighter = false))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = true, fighter = false))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = true, hasMarker = false, fighter = true))
        assertTrue(!SicTuning.intelLinkButtonVisible(skillActive = false, hasMarker = true, fighter = true))
    }

    @Test
    fun `智能联结改造按钮可点击 核心与故事点均不低于消耗`() {
        assertTrue(SicTuning.intelLinkButtonClickable(cores = 1f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST))
        assertTrue(SicTuning.intelLinkButtonClickable(cores = 3f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST + 4))
        // 边界不足：核心不足 1、故事点低于消耗均不可点击
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 0f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST))
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 0.5f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST + 4))
        assertTrue(!SicTuning.intelLinkButtonClickable(cores = 1f, storyPoints = SicTuning.INTEL_LINK_STORY_POINT_COST - 1))
    }
}
