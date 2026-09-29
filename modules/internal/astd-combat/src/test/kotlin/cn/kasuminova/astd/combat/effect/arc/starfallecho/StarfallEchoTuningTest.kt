package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.EXPLOSION_BASE_RADIUS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.FINAL_STACK_DAMAGE_BONUS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.RESONANCE_MAX_STACKS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.canFire
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.explosionDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.explosionRadius
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.finalShotBonusDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.finalShotDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.nextBurstOrdinal
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 坠星残响机制数值（blue/10-signature.md）的契约测试：
 * 爆炸半径/伤害纯函数映射（恒爆炸：半径 = 基础 ×(层数+1)，每层消耗 +50% 第 5 发伤害）、
 * 弹匣禁射闸、连射序数推进、谐振易伤与爆炸倍率的五档查表解析。
 * 难度系数经 [DifficultyTuningImpl.installScaleForTests] 注入走完整查表链路（对齐 JointTuningTest 先例）。
 */
class StarfallEchoTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    /** 注入难度系数并返回单例读取面（测后经 [clearOverride] 复位）。 */
    private fun tuning(scale: Float): DifficultyTuningImpl {
        DifficultyTuningImpl.installScaleForTests(scale)
        return DifficultyTuningImpl
    }

    @Test
    fun `爆炸半径 基础半径乘层数加一 零层恒基础半径`() {
        assertEquals(EXPLOSION_BASE_RADIUS, explosionRadius(0), "0 层恒爆炸：150su 基础规模爆炸")
        assertEquals(EXPLOSION_BASE_RADIUS * 2, explosionRadius(1), "1 层 = 基础 150 + 每层 150")
        assertEquals(
            EXPLOSION_BASE_RADIUS * (RESONANCE_MAX_STACKS + 1), explosionRadius(RESONANCE_MAX_STACKS),
            "4 层封顶 = 150 × 5 = 750su",
        )
    }

    @Test
    fun `第5发伤害 每层被消耗谐振提升五成`() {
        val panel = 750f * StarfallEchoTuning.FINAL_DAMAGE_MULT // 第 5 发面板 = 单发 750 ×200%
        assertEquals(panel, finalShotDamage(panel, 0), "0 层 = 面板原值")
        assertEquals(panel * (1f + FINAL_STACK_DAMAGE_BONUS * 4), finalShotDamage(panel, 4), "4 层 = 面板 ×(1+0.5×4)")
        assertEquals(0f, finalShotBonusDamage(panel, 0), "0 层无直击补伤")
        assertEquals(panel * FINAL_STACK_DAMAGE_BONUS * 4, finalShotBonusDamage(panel, 4), "直击补伤 = 面板 ×0.5×层数")
    }

    @Test
    fun `爆炸伤害 谐振层数加成口径 第5发总伤乘难度倍率`() {
        val panel = 750f * StarfallEchoTuning.FINAL_DAMAGE_MULT // 第 5 发面板 = 单发 750 ×200%
        assertEquals(panel * 3f, explosionDamage(panel, 4, 1f), "4 层 = 面板 ×（1 + 50%×4）= 面板 ×300%（与直击总伤同口径）")
        assertEquals(panel * 1.5f, explosionDamage(panel, 1, 1f), "1 层 = 面板 ×150%")
        assertEquals(panel, explosionDamage(panel, 0, 1f), "0 层无谐振加成 = 面板原值")
        assertEquals(explosionDamage(panel, 4, 1f) * 2f, explosionDamage(panel, 4, 2f), "难度倍率线性作用于结算")
    }

    @Test
    fun `弹匣禁射闸 低弹药非连射禁止起射 连射中放行`() {
        assertFalse(canFire(ammo = 4, inBurst = false), "弹药低于 5 且不在连射中：禁止起射新一轮")
        assertTrue(canFire(ammo = 4, inBurst = true), "连射进行中放行（禁射闸不切断已起射的 burst）")
        assertTrue(canFire(ammo = 5, inBurst = false), "弹药恰好 5 发允许起射一整轮")
        assertTrue(canFire(ammo = 10, inBurst = false))
    }

    @Test
    fun `连射序数 超时重置新一轮 否则递增`() {
        assertEquals(1, nextBurstOrdinal(prevOrdinal = 0, lastFireTime = -10f, now = 0f), "首发自然归 1")
        assertEquals(2, nextBurstOrdinal(prevOrdinal = 1, lastFireTime = 0f, now = 0.2f), "连射间隔（0.2s）内递增")
        assertEquals(5, nextBurstOrdinal(prevOrdinal = 4, lastFireTime = 0f, now = 0.2f))
        assertEquals(1, nextBurstOrdinal(prevOrdinal = 5, lastFireTime = 0f, now = 1f), "距上一发超 0.4s 视为新一轮")
        assertEquals(2, nextBurstOrdinal(prevOrdinal = 1, lastFireTime = 0f, now = 0.4f), "恰好 0.4s 不重置（> 才重置）")
    }

    @Test
    fun `谐振易伤与爆炸倍率五档查表 玩家固定砺刃档`() {
        // 玩家来源（owner == 0）固定按我方档位（默认砺刃 k2 = 设计基准）取值，与敌方档位无关
        val player = StarfallEchoTuning.resolve(tuning(5f), isPlayer = true)
        assertEquals(0.10f, player.vulnPerStack, 1e-6f)
        assertEquals(1.00f, player.explosionDamageMult, 1e-6f)

        // 敌方阵营按轨一 k_s 逐档查表
        assertEquals(0.05f, StarfallEchoTuning.resolve(tuning(1f), isPlayer = false).vulnPerStack, 1e-6f)
        assertEquals(0.125f, StarfallEchoTuning.resolve(tuning(3f), isPlayer = false).vulnPerStack, 1e-6f)
        assertEquals(0.20f, StarfallEchoTuning.resolve(tuning(5f), isPlayer = false).vulnPerStack, 1e-6f)
        assertEquals(0.75f, StarfallEchoTuning.resolve(tuning(1f), isPlayer = false).explosionDamageMult, 1e-6f)
        assertEquals(2.00f, StarfallEchoTuning.resolve(tuning(5f), isPlayer = false).explosionDamageMult, 1e-6f)
    }
}
