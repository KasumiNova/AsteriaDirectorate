package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.util.DynamicStatsAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * v3 词缀 HullMod（affixes.md v3.0，S-01~S-08 / M-09~M-14 / R-15~R-17）逻辑验证：
 * 三锚点端点数值（k_s=1/2/5 全链路走 [DifficultyTuningImpl] 注入）、编队光环上限、
 * 歼灭指令选择、峰值免疫抵偿、排辐 AI 决策矩阵——全部直调真实实现。
 */
class AffixV3HullModsTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun valueAt(scale: Float, entry: ScalingEntry): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return DifficultyTuningImpl.value(entry)
    }

    /** 断言 entry 三锚点精确命中设计文档的 下限/中点/上限。 */
    private fun assertAnchors(entry: ScalingEntry, v1: Float, v2: Float, v5: Float) {
        assertEquals(v1, valueAt(1f, entry), 1e-6f, "k_s=1 下限")
        assertEquals(v2, valueAt(2f, entry), 1e-6f, "k_s=2 设计基准")
        assertEquals(v5, valueAt(5f, entry), 1e-6f, "k_s=5 上限")
    }

    @Test
    fun `S 型词缀数值锚点与设计文档一致`() {
        // S-01 铁甲重装：装甲 15%~45%，机动 -15% 固定
        assertAnchors(AffixIroncladPlatingHullMod.ARMOR_BONUS, 0.15f, 0.30f, 0.45f)
        assertEquals(0.85f, AffixIroncladPlatingHullMod.MOBILITY_MULT)
        assertAnchors(AffixIroncladPlatingHullMod.MIN_ARMOR_FLAT.getValue(ShipAPI.HullSize.FRIGATE), 50f, 75f, 100f)
        assertAnchors(AffixIroncladPlatingHullMod.MIN_ARMOR_FLAT.getValue(ShipAPI.HullSize.DESTROYER), 100f, 150f, 200f)
        assertAnchors(AffixIroncladPlatingHullMod.MIN_ARMOR_FLAT.getValue(ShipAPI.HullSize.CRUISER), 200f, 300f, 400f)
        assertAnchors(AffixIroncladPlatingHullMod.MIN_ARMOR_FLAT.getValue(ShipAPI.HullSize.CAPITAL_SHIP), 300f, 450f, 600f)

        // S-02 六相冰辐能网络
        assertAnchors(AffixCryoFluxNetworkHullMod.FLUX_DISSIPATION_BONUS, 0.10f, 0.15f, 0.20f)
        assertAnchors(AffixCryoFluxNetworkHullMod.VENT_RATE_BONUS, 0.20f, 0.30f, 0.40f)
        assertAnchors(AffixCryoFluxNetworkHullMod.EMP_DAMAGE_TAKEN_REDUCTION, 0.20f, 0.30f, 0.40f)

        // S-03 极限辐能线圈扩容
        assertAnchors(AffixFluxCoilExpansionHullMod.FLUX_CAPACITY_BONUS, 0.20f, 0.30f, 0.40f)
        assertEquals(0.80f, AffixFluxCoilExpansionHullMod.FLUX_DISSIPATION_MULT)

        // S-04 极化护盾发生器
        assertAnchors(AffixPolarizedShieldHullMod.SHIELD_DAMAGE_TAKEN_REDUCTION, 0.25f, 0.375f, 0.50f)
        assertEquals(1.50f, AffixPolarizedShieldHullMod.OVERLOAD_TIME_MULT)

        // S-05 引擎超频
        assertAnchors(AffixEngineOverclockHullMod.MAX_SPEED_BONUS, 0.25f, 0.375f, 0.50f)
        assertEquals(0.75f, AffixEngineOverclockHullMod.MANEUVER_MULT)

        // S-06 维度专长
        assertAnchors(AffixDimensionalSpecialtyHullMod.PEAK_CR_BONUS, 1.0f, 1.5f, 2.0f)
        assertAnchors(AffixDimensionalSpecialtyHullMod.CR_LOSS_REDUCTION, 0.25f, 0.375f, 0.50f)
        assertAnchors(AffixDimensionalSpecialtyHullMod.SYSTEM_TIME_REDUCTION, 0.10f, 0.15f, 0.20f)

        // S-07 相位线圈调谐
        assertAnchors(AffixPhaseCoilTuningHullMod.PHASE_TIME_FLOW_BONUS, 0.50f, 0.75f, 1.0f)
        assertAnchors(AffixPhaseCoilTuningHullMod.PEAK_CR_BONUS, 1.0f, 1.5f, 2.0f)
        assertAnchors(AffixPhaseCoilTuningHullMod.CR_LOSS_REDUCTION, 0.25f, 0.375f, 0.50f)

        // S-08 相位线圈降频
        assertAnchors(AffixPhaseCoilDetuningHullMod.PHASE_TIME_FLOW_REDUCTION, 0.25f, 0.375f, 0.50f)
        assertAnchors(AffixPhaseCoilDetuningHullMod.PHASE_FLUX_REDUCTION, 0.25f, 0.375f, 0.50f)
        assertAnchors(AffixPhaseCoilDetuningHullMod.PHASE_COOLDOWN_REDUCTION, 0.25f, 0.375f, 0.50f)
    }

    @Test
    fun `M 型与 R 型词缀数值锚点与设计文档一致`() {
        // M-09 递归式目标定位系统
        assertAnchors(AffixRecursiveTargetingHullMod.PER_ALLY_BONUS.getValue(ShipAPI.HullSize.FRIGATE), 0.01f, 0.015f, 0.02f)
        assertAnchors(AffixRecursiveTargetingHullMod.PER_ALLY_BONUS.getValue(ShipAPI.HullSize.CAPITAL_SHIP), 0.04f, 0.06f, 0.08f)
        assertAnchors(AffixRecursiveTargetingHullMod.RANGE_CAP.getValue(ShipAPI.HullSize.FRIGATE), 0.15f, 0.225f, 0.30f)
        assertAnchors(AffixRecursiveTargetingHullMod.RANGE_CAP.getValue(ShipAPI.HullSize.CAPITAL_SHIP), 0.40f, 0.60f, 0.80f)
        assertAnchors(AffixRecursiveTargetingHullMod.PROJ_SPEED_CAP, 0.40f, 0.60f, 0.80f)

        // M-10 反应式辐能装甲
        assertAnchors(AffixReactiveFluxArmorHullMod.VENTING_DAMAGE_TAKEN_REDUCTION, 0.75f, 0.825f, 0.90f)
        assertEquals(0.65f, AffixReactiveFluxArmorHullMod.VENT_RATE_MULT)

        // M-11 P空间深潜器
        assertAnchors(AffixPspaceDiverHullMod.SLOWDOWN_IMMUNITY, 0.50f, 0.75f, 1.0f)

        // M-12 引擎辐能网隔离
        assertAnchors(AffixEngineFluxIsolationHullMod.ZERO_FLUX_THRESHOLD_BONUS, 0.20f, 0.30f, 0.40f)
        assertAnchors(AffixEngineFluxIsolationHullMod.ZERO_FLUX_SPEED_BONUS, 0.25f, 0.375f, 0.50f)

        // M-13 蜂群协同网络
        assertAnchors(AffixSwarmCoordinationHullMod.PER_ALLY_BONUS, 0.01f, 0.015f, 0.02f)
        assertAnchors(AffixSwarmCoordinationHullMod.BONUS_CAP, 0.25f, 0.375f, 0.50f)

        // M-14 等离子装甲护盾
        assertAnchors(AffixPlasmaArmorShieldHullMod.SHIELD_ARMOR_FRACTION, 0.20f, 0.30f, 0.40f)

        // R-15 电网深化升级
        assertAnchors(AffixGridDeepeningHullMod.VENT_RATE_BONUS, 0.25f, 0.375f, 0.50f)
        assertAnchors(AffixGridDeepeningHullMod.HARD_FLUX_DISSIPATION_BONUS, 0.10f, 0.15f, 0.20f)

        // R-16 激进式集群作战网络
        assertAnchors(AffixAggressiveSwarmNetworkHullMod.CP_RATE_BONUS, 1.0f, 1.5f, 2.0f)
        assertAnchors(AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DAMAGE_DEALT_BONUS, 0.15f, 0.225f, 0.30f)
        assertAnchors(AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DAMAGE_TAKEN_REDUCTION, 0.15f, 0.225f, 0.30f)
        assertEquals(45f, AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_INTERVAL_SECONDS)
        assertEquals(30f, AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DURATION_SECONDS)

        // R-17 奇点驱动
        assertAnchors(AffixSingularityDriveHullMod.PEAK_CR_BONUS, 3.0f, 6.0f, 9.0f)
        assertAnchors(AffixSingularityDriveHullMod.WEAPON_FLUX_REDUCTION, 0.20f, 0.30f, 0.40f)
        assertAnchors(AffixSingularityDriveHullMod.OVERLOAD_TIME_REDUCTION, 0.25f, 0.50f, 0.75f)
        assertAnchors(AffixSingularityDriveHullMod.SINGULARITY_TIME_FLOW_BONUS, 1.0f, 1.5f, 2.0f)
    }

    @Test
    fun `M-09 射程与弹速按友军数线性累计且不超上限`() {
        val tuning = object : DifficultyTuning {
            override val fixedScale: Float get() = 5f
            override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
        }
        // 主力舰 k_s=5：每艘 8%，射程上限 80%，弹速上限 80%
        val (r0, p0) = AffixRecursiveTargetingHullMod.bonuses(ShipAPI.HullSize.CAPITAL_SHIP, 0, tuning)
        assertEquals(0f, r0); assertEquals(0f, p0)
        val (r5, p5) = AffixRecursiveTargetingHullMod.bonuses(ShipAPI.HullSize.CAPITAL_SHIP, 5, tuning)
        assertEquals(0.40f, r5, 1e-6f); assertEquals(0.40f, p5, 1e-6f)
        val (r20, p20) = AffixRecursiveTargetingHullMod.bonuses(ShipAPI.HullSize.CAPITAL_SHIP, 20, tuning)
        assertEquals(0.80f, r20, 1e-6f, "射程应截断在上限")
        assertEquals(0.80f, p20, 1e-6f, "弹速应截断在上限")
    }

    @Test
    fun `M-13 加成按全自动友军数累计且不超上限`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(0f, AffixSwarmCoordinationHullMod.bonus(0, DifficultyTuningImpl))
        assertEquals(0.10f, AffixSwarmCoordinationHullMod.bonus(5, DifficultyTuningImpl), 1e-6f)
        assertEquals(0.50f, AffixSwarmCoordinationHullMod.bonus(100, DifficultyTuningImpl), 1e-6f, "应截断在上限 50%")
    }

    @Test
    fun `M-11 阈值调制在部分免疫时放大阈值 完全免疫时垫高阈值`() {
        fun applyAt(scale: Float): StatBonus {
            DifficultyTuningImpl.installScaleForTests(scale)
            val thresholdMod = StatBonus()
            val dynamic = mock(DynamicStatsAPI::class.java)
            `when`(dynamic.getMod(AffixPspaceDiverHullMod.FLUX_LEVEL_THRESHOLD_MOD)).thenReturn(thresholdMod)
            val stats = mock(MutableShipStatsAPI::class.java)
            `when`(stats.dynamic).thenReturn(dynamic)
            AffixPspaceDiverHullMod.apply(stats, "test", DifficultyTuningImpl)
            return thresholdMod
        }

        // k_s=1：免疫 50% → 阈值 × 1/(1-0.5) = 2 倍 → 0.5 × 2 = 1.0
        assertEquals(1.0f, applyAt(1f).computeEffective(0.5f), 1e-4f)
        // k_s=5：免疫 100% → 直接垫高到完全免疫量级
        assertTrue(applyAt(5f).computeEffective(0.5f) >= AffixPspaceDiverHullMod.FULL_IMMUNITY_THRESHOLD)
    }

    @Test
    fun `M-14 护盾命中减免随难度与装甲值单调且不重击穿透`() {
        fun multAt(scale: Float, armor: Float, hitStrength: Float): Float {
            DifficultyTuningImpl.installScaleForTests(scale)
            return AffixPlasmaArmorShieldHullMod.shieldHitDamageMult(
                damageAmount = 300f,
                hitStrength = hitStrength,
                effectiveMaxArmor = armor,
                effectiveArmorMult = 1f,
                maxArmorDamageReduction = 0.85f,
                tuning = DifficultyTuningImpl,
            )
        }

        // 装甲公式：减免必须存在（mult < 1）且随难度（装甲系数 20%→40%）单调下降。
        val low = multAt(1f, armor = 1000f, hitStrength = 300f)
        val high = multAt(5f, armor = 1000f, hitStrength = 300f)
        assertTrue(low < 1f, "低难度也应有减免")
        assertTrue(high < low, "高难度（更高装甲系数）减免应更强: low=$low high=$high")
        // 命中强度远高于装甲时减免趋弱；装甲远高于命中强度时受 85% 减免上限约束。
        assertTrue(multAt(5f, armor = 1000f, hitStrength = 3000f) > high, "重击应削弱装甲减免")
        assertTrue(multAt(5f, armor = 10000f, hitStrength = 100f) >= 0.15f - 1e-4f, "不得突破原版最大装甲减免")
    }

    @Test
    fun `R-15 排辐 AI 决策矩阵与设计一致`() {
        // 辐能 < 50%：不主动排辐
        assertFalse(AffixGridDeepeningHullMod.shouldVent(0.30f, Float.MAX_VALUE))
        // 辐能 ≥ 50% 且脱离接触：排辐
        assertTrue(AffixGridDeepeningHullMod.shouldVent(0.55f, 2000f))
        assertTrue(AffixGridDeepeningHullMod.shouldVent(0.55f, Float.MAX_VALUE))
        // 辐能 50%~75% 但敌舰在中距：不主动排辐
        assertFalse(AffixGridDeepeningHullMod.shouldVent(0.55f, 1000f))
        // 辐能 ≥ 75%：激进排辐，但敌舰贴脸（≤700）时仍不排
        assertTrue(AffixGridDeepeningHullMod.shouldVent(0.80f, 1000f))
        assertFalse(AffixGridDeepeningHullMod.shouldVent(0.80f, 500f))
    }

    @Test
    fun `R-16 响应舰船选择满足至少一半且确定`() {
        assertEquals(setOf("a", "b"), AffixAggressiveSwarmNetworkHullMod.selectResponderIds(listOf("b", "a", "d", "c")))
        assertEquals(setOf("a", "b"), AffixAggressiveSwarmNetworkHullMod.selectResponderIds(listOf("c", "a", "b")))
        assertEquals(setOf("a"), AffixAggressiveSwarmNetworkHullMod.selectResponderIds(listOf("a")))
        assertEquals(emptySet(), AffixAggressiveSwarmNetworkHullMod.selectResponderIds(emptyList()))
    }

    @Test
    fun `R-16 歼灭目标为部署点最高且并列取 id 最小`() {
        val target = AffixAggressiveSwarmNetworkHullMod.selectTargetId(
            listOf("ship_c" to 10f, "ship_a" to 30f, "ship_b" to 30f),
        )
        assertEquals("ship_a", target, "并列最高部署点应取 id 字典序最小者")
        assertNull(AffixAggressiveSwarmNetworkHullMod.selectTargetId(emptyList()))
    }

    @Test
    fun `R-17 峰值免疫仅抵偿外部负向修饰`() {
        val own = setOf("astd_affix_singularity_drive", "astd_affix_singularity_drive_peak_immunity")
        // 环境削减（如日冕 ×0.25）+ 外部增益（不抵偿）+ 自身加成（不抵偿）
        val (mult, percent) = AffixSingularityDriveHullMod.immunityCompensation(
            multBonuses = mapOf(
                "corona" to 0.25f,
                "astd_affix_singularity_drive" to 4.0f,
                "some_buff" to 1.5f,
            ),
            percentBonuses = mapOf("nebula" to -50f, "astd_affix_singularity_drive" to 300f),
            ownIds = own,
        )
        assertEquals(0.25f, mult, 1e-6f)
        assertEquals(-50f, percent, 1e-6f)

        // 无环境削减时不产生补偿
        val (cleanMult, cleanPercent) = AffixSingularityDriveHullMod.immunityCompensation(
            multBonuses = mapOf("some_buff" to 1.5f),
            percentBonuses = mapOf("another_buff" to 25f),
            ownIds = own,
        )
        assertEquals(1f, cleanMult)
        assertEquals(0f, cleanPercent)
    }

    @Test
    fun `S-06 系统时间降低换算为速率乘区`() {
        // 时间降低 20% → 速率 × 1/(1-0.2) = 1.25
        assertEquals(1.25f, AffixDimensionalSpecialtyHullMod.timeReductionToRateMult(0.20f), 1e-6f)
        assertEquals(1f / 0.85f, AffixDimensionalSpecialtyHullMod.timeReductionToRateMult(0.15f), 1e-6f)
    }

    @Test
    fun `相位限定 hullmod 仅适用于相位舰`() {
        val phaseHull = mock(com.fs.starfarer.api.combat.ShipHullSpecAPI::class.java)
        `when`(phaseHull.isPhase).thenReturn(true)
        val normalHull = mock(com.fs.starfarer.api.combat.ShipHullSpecAPI::class.java)
        `when`(normalHull.isPhase).thenReturn(false)
        val phaseShip = mock(ShipAPI::class.java)
        `when`(phaseShip.hullSpec).thenReturn(phaseHull)
        val normalShip = mock(ShipAPI::class.java)
        `when`(normalShip.hullSpec).thenReturn(normalHull)

        for (hullmod in listOf(
            AffixPhaseCoilTuningHullMod(),
            AffixPhaseCoilDetuningHullMod(),
            AffixPspaceDiverHullMod(),
        )) {
            assertTrue(hullmod.isApplicableToShip(phaseShip), "${hullmod.javaClass.simpleName} 应适用于相位舰")
            assertFalse(hullmod.isApplicableToShip(normalShip), "${hullmod.javaClass.simpleName} 不应适用于非相位舰")
        }
    }

    @Test
    fun `相位限定口径与注册表 phaseOnly 一致`() {
        // 注册表 phaseOnly 恰为调谐/降频/深潜器（AffixRegistryTest 已覆盖）；
        // 此处验证三个 hullmod 与注册表条目的 id 约定闭环。
        assertEquals("astd_affix_phase_coil_tuning", AffixPhaseCoilTuningHullMod.HULLMOD_ID)
        assertEquals("astd_affix_phase_coil_detuning", AffixPhaseCoilDetuningHullMod.HULLMOD_ID)
        for (id in listOf("phase_coil_tuning", "phase_coil_detuning", "pspace_diver")) {
            val def = AffixRegistry.getById(id)
            assertTrue(def?.phaseOnly == true, "$id 应为相位限定")
            assertEquals("astd_affix_$id", def?.hullModId)
        }
    }
}
