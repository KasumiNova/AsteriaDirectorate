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

class SicArmamentUpgradeMathTest {

    @Test
    fun `引力坍缩炮 未激活按当前船体值 激活按最大船体值`() {
        assertEquals(500f, GravityCollapseDifficulty.hullDamageBasis(500f, 2000f, armamentUpgrade = false), 1e-6f)
        assertEquals(2000f, GravityCollapseDifficulty.hullDamageBasis(500f, 2000f, armamentUpgrade = true), 1e-6f)
        assertEquals(0f, GravityCollapseDifficulty.hullDamageBasis(-10f, 2000f, armamentUpgrade = false), 1e-6f)
        assertEquals(0f, GravityCollapseDifficulty.hullDamageBasis(500f, -10f, armamentUpgrade = true), 1e-6f)
    }

    @Test
    fun `电荷针刺 未激活关盾不产出软辐能 激活无视护盾状态产出`() {
        val stacks = 5
        val flatPerStack = ChargeNeedleTuning.FLAT_FLUX_CRUISER.v2
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
        assertEquals(
            stacks * flatPerStack,
            ChargeNeedleTuning.flatFluxPerSec(stacks, flatPerStack, shieldOn = false, fluxRegardlessOfShield = true),
            1e-6f,
        )
        assertEquals(0f, ChargeNeedleTuning.flatFluxPerSec(0, flatPerStack, shieldOn = true, fluxRegardlessOfShield = true), 1e-6f)
    }

    @Test
    fun `彗星冲击波 未激活全量 EMP 位移 激活超 100 部分按 5 比 1 转能量易伤`() {
        val (empOff, vulnOff) = HeavyIonPulseTuning.splitEmpShift(1.6f, armamentUpgrade = false)
        assertEquals(1.6f, empOff, 1e-6f)
        assertEquals(0f, vulnOff, 1e-6f)

        val (empUnder, vulnUnder) = HeavyIonPulseTuning.splitEmpShift(0.8f, armamentUpgrade = true)
        assertEquals(0.8f, empUnder, 1e-6f)
        assertEquals(0f, vulnUnder, 1e-6f)

        val (empOver, vulnOver) = HeavyIonPulseTuning.splitEmpShift(1.6f, armamentUpgrade = true)
        assertEquals(1f, empOver, 1e-6f)
        assertEquals(0.6f / HeavyIonPulseTuning.ENERGY_VULN_DIVISOR, vulnOver, 1e-6f)

        val (empAt, vulnAt) = HeavyIonPulseTuning.splitEmpShift(1f, armamentUpgrade = true)
        assertEquals(1f, empAt, 1e-6f)
        assertEquals(0f, vulnAt, 1e-6f)

        assertEquals(0f, HeavyIonPulseTuning.splitEmpShift(-0.5f, armamentUpgrade = true).first, 1e-6f)
        assertEquals(0f, HeavyIonPulseTuning.splitEmpShift(-0.5f, armamentUpgrade = false).first, 1e-6f)
    }

    @Test
    fun `穷距 未激活上限取定稿常量 激活提升至 SIC 上限`() {
        assertEquals(QiongjuePhaseRailgunDifficulty.MAX_STACKS, QiongjueStackMath.maxStacks(armamentUpgrade = false))
        assertEquals(QiongjuePhaseRailgunDifficulty.SIC_UPGRADE_MAX_STACKS, QiongjueStackMath.maxStacks(armamentUpgrade = true))
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
        assertEquals(
            12.5f,
            ElectricDriveAcceleratorDifficulty.chargeRollPct(maxPct, armamentUpgrade = false) { 12.5f },
            1e-6f,
        )
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
