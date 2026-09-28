package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 压制模式数值规格（[SuppressionModeTuning]）：四项难度缩放的三锚点折算
 * （航速机动 40%/50%/80%、武器辐能减免 40%/50%/80%、射程 20%/30%/60%、护盾减伤 20%/30%/60%，
 * 玩家固定我方档位 v2）、恒定射速加成 +30%，以及硬辐能产出曲线
 * （第 0 秒 2%/s 线性爬坡，第 4 秒封顶 6%/s）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 GravEmFieldTuningTest 先例）。
 */
class SuppressionModeTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): SuppressionModeTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return SuppressionModeTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        val v1 = resolveAt(1f)
        assertEquals(0.60f, v1.speedManeuverMult, 1e-6f)
        assertEquals(0.60f, v1.weaponFluxMult, 1e-6f)
        assertEquals(0.20f, v1.rangeBonusPercent, 1e-6f)
        assertEquals(0.80f, v1.shieldDamageTakenMult, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(0.50f, v2.speedManeuverMult, 1e-6f)
        assertEquals(0.50f, v2.weaponFluxMult, 1e-6f)
        assertEquals(0.30f, v2.rangeBonusPercent, 1e-6f)
        assertEquals(0.70f, v2.shieldDamageTakenMult, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(0.20f, v5.speedManeuverMult, 1e-6f)
        assertEquals(0.20f, v5.weaponFluxMult, 1e-6f)
        assertEquals(0.60f, v5.rangeBonusPercent, 1e-6f)
        assertEquals(0.40f, v5.shieldDamageTakenMult, 1e-6f)
    }

    @Test
    fun `玩家按我方档位取值 与敌方 k_s 无关`() {
        assertEquals(0.50f, resolveAt(1f, isPlayer = true).speedManeuverMult, 1e-6f)
        assertEquals(0.30f, resolveAt(5f, isPlayer = true).rangeBonusPercent, 1e-6f)
        assertEquals(0.70f, resolveAt(5f, isPlayer = true).shieldDamageTakenMult, 1e-6f)
    }

    @Test
    fun `射速加成恒定 不随难度缩放`() {
        assertEquals(0.30f, resolveAt(1f).rofBonusPercent, 1e-6f)
        assertEquals(0.30f, resolveAt(2f).rofBonusPercent, 1e-6f)
        assertEquals(0.30f, resolveAt(5f).rofBonusPercent, 1e-6f)
    }

    @Test
    fun `硬辐能产出曲线 2百分比起步线性爬坡 第4秒封顶6百分比`() {
        assertEquals(0.02f, SuppressionModeTuning.hardFluxFractionPerSecond(0f), 1e-6f)
        assertEquals(0.03f, SuppressionModeTuning.hardFluxFractionPerSecond(1f), 1e-6f)
        assertEquals(0.04f, SuppressionModeTuning.hardFluxFractionPerSecond(2f), 1e-6f, "爬坡中点")
        assertEquals(0.06f, SuppressionModeTuning.hardFluxFractionPerSecond(4f), 1e-6f, "第 4 秒达到最高")
        assertEquals(0.06f, SuppressionModeTuning.hardFluxFractionPerSecond(9f), 1e-6f, "持续期后段保持封顶")
        assertEquals(0.02f, SuppressionModeTuning.hardFluxFractionPerSecond(-1f), 1e-6f, "负时长按 0 计")
    }
}
