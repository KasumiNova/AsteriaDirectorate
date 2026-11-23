package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.EXPLOSION_BASE_RADIUS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.FINAL_STACK_DAMAGE_BONUS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.RESONANCE_MAX_STACKS
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.canFire
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.explosionDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.explosionRadius
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.finalShotBonusDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.finalShotDamage
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning.isFinalShot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallEchoTuningTest {
    @Test
    fun `爆炸范围随消耗的层数线性增大`() {
        assertEquals(EXPLOSION_BASE_RADIUS, explosionRadius(0))
        assertEquals(EXPLOSION_BASE_RADIUS * 2, explosionRadius(1))
        assertEquals(EXPLOSION_BASE_RADIUS * (RESONANCE_MAX_STACKS + 1), explosionRadius(RESONANCE_MAX_STACKS))
    }

    @Test
    fun `每层消耗的谐振同时增加第五发总伤害和直击补伤`() {
        val panel = 1500f
        assertEquals(panel, finalShotDamage(panel, 0))
        assertEquals(panel * (1f + FINAL_STACK_DAMAGE_BONUS * 4), finalShotDamage(panel, 4))
        assertEquals(0f, finalShotBonusDamage(panel, 0))
        assertEquals(finalShotDamage(panel, 4) - panel, finalShotBonusDamage(panel, 4))
    }

    @Test
    fun `爆炸对包含谐振加成的尾发伤害再乘难度倍率`() {
        val panel = 1500f
        assertEquals(panel, explosionDamage(panel, 0, 1f))
        assertEquals(finalShotDamage(panel, 1), explosionDamage(panel, 1, 1f))
        assertEquals(finalShotDamage(panel, 4) * 2f, explosionDamage(panel, 4, 2f))
    }

    @Test
    fun `弹匣不足五发时只允许继续已开始的齐射`() {
        assertFalse(canFire(ammo = 4, inBurst = false))
        assertTrue(canFire(ammo = 4, inBurst = true))
        assertTrue(canFire(ammo = 5, inBurst = false))
    }

    @Test
    fun `连续三轮齐射只有每轮第五发触发强化`() {
        val burst = listOf(0.4f, 0.3f, 0.2f, 0.1f, 0f)
        val finals = (burst + burst + burst).mapIndexedNotNull { index, remaining ->
            (index + 1).takeIf { isFinalShot(inBurst = true, burstTimeRemaining = remaining) }
        }
        assertEquals(listOf(5, 10, 15), finals)
    }

    @Test
    fun `中断的齐射与补弹后的新一轮各自判定尾发`() {
        val shots = listOf(0.4f, 0.3f, 0.2f, 0.4f, 0.3f, 0.2f, 0.1f, 0f)
        assertFalse(isFinalShot(inBurst = false, burstTimeRemaining = 0f))
        assertEquals(listOf(8), shots.mapIndexedNotNull { index, remaining ->
            (index + 1).takeIf { isFinalShot(inBurst = true, burstTimeRemaining = remaining) }
        })
    }
}
