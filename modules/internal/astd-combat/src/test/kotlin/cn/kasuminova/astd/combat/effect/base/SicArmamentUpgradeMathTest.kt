package cn.kasuminova.astd.combat.effect.base

import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseTuning
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueStackMath
import cn.kasuminova.astd.combat.effect.generic.gravitycollapse.GravityCollapseDifficulty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SIC「武备升级」（docs/design/skills/10-sic-skills.md 阶段二）7 条武器行为改造的纯逻辑验证：
 * 每条覆盖激活/未激活双分支，未激活分支钉死「现状逐字保留」语义。全部调用真实逻辑，禁止源码 contain。
 */
class SicArmamentUpgradeMathTest {

    @Test
    fun `引力坍缩炮 未激活按当前船体值 激活按最大船体值`() {
        assertEquals(500f, GravityCollapseDifficulty.hullDamageBasis(500f, 2000f, armamentUpgrade = false), 1e-6f)
        assertEquals(2000f, GravityCollapseDifficulty.hullDamageBasis(500f, 2000f, armamentUpgrade = true), 1e-6f)
        // 负值防线：两分支均钳到 0
        assertEquals(0f, GravityCollapseDifficulty.hullDamageBasis(-10f, 2000f, armamentUpgrade = false), 1e-6f)
        assertEquals(0f, GravityCollapseDifficulty.hullDamageBasis(500f, -10f, armamentUpgrade = true), 1e-6f)
    }

    @Test
    fun `电荷针刺 未激活关盾不产出软辐能 激活无视护盾状态产出`() {
        val stacks = 5
        val flatPerStack = ChargeNeedleTuning.FLAT_FLUX_CRUISER.v2
        // 未激活：护盾开启产出、关闭不产出（现状）
        assertEquals(
            stacks * flatPerStack,
            ChargeNeedleTuning.flatFluxPerSec(stacks, flatPerStack, shieldOn = true, fluxRegardlessOfShield = false),
            1e-6f,
        )
        assertEquals(
            0f,
            ChargeNeedleTuning.flatFluxPerSec(stacks, flatPerStack, shieldOn = false, fluxRegardlessOfShield = false),
            1e-6f,
        )
        // 激活：关盾同样产出，与开盾等额
        assertEquals(
            stacks * flatPerStack,
            ChargeNeedleTuning.flatFluxPerSec(stacks, flatPerStack, shieldOn = false, fluxRegardlessOfShield = true),
            1e-6f,
        )
        // 零层恒 0（两分支）
        assertEquals(0f, ChargeNeedleTuning.flatFluxPerSec(0, flatPerStack, shieldOn = true, fluxRegardlessOfShield = true), 1e-6f)
    }

    @Test
    fun `彗星冲击波 未激活全量 EMP 位移 激活超 100 部分按 5 比 1 转能量易伤`() {
        // 未激活：全部作用于 EMP 位移，无能量易伤（现状隐藏机制）
        val (empOff, vulnOff) = HeavyIonPulseTuning.splitEmpShift(1.6f, armamentUpgrade = false)
        assertEquals(1.6f, empOff, 1e-6f)
        assertEquals(0f, vulnOff, 1e-6f)

        // 激活未超 100%：无分流
        val (empUnder, vulnUnder) = HeavyIonPulseTuning.splitEmpShift(0.8f, armamentUpgrade = true)
        assertEquals(0.8f, empUnder, 1e-6f)
        assertEquals(0f, vulnUnder, 1e-6f)

        // 激活超 100%：EMP 位移封顶 1.0，超出 0.6 按 5:1 转换
        val (empOver, vulnOver) = HeavyIonPulseTuning.splitEmpShift(1.6f, armamentUpgrade = true)
        assertEquals(1f, empOver, 1e-6f)
        assertEquals(0.6f / HeavyIonPulseTuning.ENERGY_VULN_DIVISOR, vulnOver, 1e-6f)

        // 边界恰 100%：不产生易伤
        val (empAt, vulnAt) = HeavyIonPulseTuning.splitEmpShift(1f, armamentUpgrade = true)
        assertEquals(1f, empAt, 1e-6f)
        assertEquals(0f, vulnAt, 1e-6f)

        // 负位移钳到 0（两分支）
        assertEquals(0f, HeavyIonPulseTuning.splitEmpShift(-0.5f, armamentUpgrade = true).first, 1e-6f)
        assertEquals(0f, HeavyIonPulseTuning.splitEmpShift(-0.5f, armamentUpgrade = false).first, 1e-6f)
    }

    @Test
    fun `穷距 未激活上限取定稿常量 激活提升至 SIC 上限`() {
        assertEquals(QiongjuePhaseRailgunDifficulty.MAX_STACKS, QiongjueStackMath.maxStacks(armamentUpgrade = false))
        assertEquals(QiongjuePhaseRailgunDifficulty.SIC_UPGRADE_MAX_STACKS, QiongjueStackMath.maxStacks(armamentUpgrade = true))
        // 叠层路径钳制跟随提升后的上限：同目标连命中可超过原上限
        val upgradedMax = QiongjueStackMath.maxStacks(armamentUpgrade = true)
        assertEquals(
            upgradedMax,
            QiongjueStackMath.stacksAfterHit(upgradedMax, oldTargetValid = true, sameTarget = true, retainPct = 0.5f, maxStacks = upgradedMax),
        )
        assertTrue(upgradedMax > QiongjueStackMath.maxStacks(armamentUpgrade = false))
    }

    @Test
    fun `正电子冲击波 未激活不追加高爆 激活按结算伤害乘倍率追加`() {
        assertEquals(0f, PositronShockwaveDifficulty.fighterExtraHeDamage(150f, armamentUpgrade = false), 1e-6f)
        assertEquals(
            150f * PositronShockwaveDifficulty.SIC_FIGHTER_EXTRA_HE_MULT,
            PositronShockwaveDifficulty.fighterExtraHeDamage(150f, armamentUpgrade = true),
            1e-6f,
        )
        // 负结算伤害钳到 0
        assertEquals(0f, PositronShockwaveDifficulty.fighterExtraHeDamage(-150f, armamentUpgrade = true), 1e-6f)
    }

    @Test
    fun `双子星 穿盾概率斜坡 起点下为 0 满载点上为最大 中点线性`() {
        val start = GeminiDemDifficulty.ARC_PIERCE_RAMP_START
        val full = GeminiDemDifficulty.ARC_PIERCE_RAMP_FULL
        val max = GeminiDemDifficulty.ARC_PIERCE_MAX_CHANCE

        assertEquals(0f, GeminiDemDifficulty.arcShieldPierceChance(start), 1e-6f)
        assertEquals(0f, GeminiDemDifficulty.arcShieldPierceChance(start / 2f), 1e-6f)
        assertEquals(max, GeminiDemDifficulty.arcShieldPierceChance(full), 1e-6f)
        assertEquals(max, GeminiDemDifficulty.arcShieldPierceChance(full * 2f), 1e-6f)
        assertEquals(max / 2f, GeminiDemDifficulty.arcShieldPierceChance((start + full) / 2f), 1e-6f)
        // 斜坡区间内单调
        assertTrue(
            GeminiDemDifficulty.arcShieldPierceChance(start + (full - start) / 4f) <
                    GeminiDemDifficulty.arcShieldPierceChance(start + (full - start) * 3f / 4f),
        )
    }

    @Test
    fun `双子星 硬辐能占比防 0 除`() {
        assertEquals(0f, GeminiDemDifficulty.hardFluxFraction(hardFlux = 100f, maxFlux = 0f), 1e-6f)
        assertEquals(0f, GeminiDemDifficulty.hardFluxFraction(hardFlux = 100f, maxFlux = -5f), 1e-6f)
        assertEquals(0.25f, GeminiDemDifficulty.hardFluxFraction(hardFlux = 250f, maxFlux = 1000f), 1e-6f)
    }

    @Test
    fun `电驱加速炮 未激活走随机序列 激活固定取上限且不消耗随机`() {
        val maxPct = ElectricDriveAcceleratorDifficulty.CHARGE_MAX_PCT.v2
        // 未激活：roll 结果被采用（现状随机口径）
        assertEquals(
            12.5f,
            ElectricDriveAcceleratorDifficulty.chargeRollPct(maxPct, armamentUpgrade = false) { 12.5f },
            1e-6f,
        )
        // 激活：固定取上限，roll 不应被调用（不消耗确定性随机序列）
        assertEquals(
            maxPct,
            ElectricDriveAcceleratorDifficulty.chargeRollPct(maxPct, armamentUpgrade = true) {
                throw IllegalStateException("激活分支不得消耗随机序列")
            },
            1e-6f,
        )
        assertFailsWith<IllegalStateException> {
            ElectricDriveAcceleratorDifficulty.chargeRollPct(maxPct, armamentUpgrade = false) {
                throw IllegalStateException("未激活分支必须消费随机序列")
            }
        }
    }
}
