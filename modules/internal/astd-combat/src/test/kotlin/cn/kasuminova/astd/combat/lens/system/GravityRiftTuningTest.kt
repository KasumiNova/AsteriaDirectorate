package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravityRiftTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): GravityRiftTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravityRiftTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        resolveAt(1f).let { v ->
            assertEquals(750f, v.damageMin, 1e-6f)
            assertEquals(1250f, v.damageMax, 1e-6f)
            assertEquals(0.50f, v.damageTakenReduction, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(1000f, v.damageMin, 1e-6f)
            assertEquals(1500f, v.damageMax, 1e-6f)
            assertEquals(0.60f, v.damageTakenReduction, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(1750f, v.damageMin, 1e-6f)
            assertEquals(2250f, v.damageMax, 1e-6f)
            assertEquals(0.90f, v.damageTakenReduction, 1e-6f)
        }
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(1000f, v.damageMin, 1e-6f)
            assertEquals(1500f, v.damageMax, 1e-6f)
            assertEquals(0.60f, v.damageTakenReduction, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(1000f, v.damageMin, 1e-6f)
            assertEquals(1500f, v.damageMax, 1e-6f)
            assertEquals(0.60f, v.damageTakenReduction, 1e-6f)
        }
    }

    @Test
    fun `减伤换算承伤乘区并钳制`() {
        assertEquals(0.5f, GravityRiftTuning.damageTakenMult(0.5f), 1e-6f)
        assertEquals(0.4f, GravityRiftTuning.damageTakenMult(0.6f), 1e-6f)
        assertEquals(0.1f, GravityRiftTuning.damageTakenMult(0.9f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.damageTakenMult(1.0f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.damageTakenMult(1.5f), 1e-6f)
    }

    @Test
    fun `相位压制冷却改写逻辑`() {
        val threshold = GravityRiftTuning.PHASE_SUPPRESS_COOLDOWN_REMAINING
        assertEquals(threshold, GravityRiftTuning.phaseSuppressCooldown(0f, inCooldown = true))
        assertEquals(threshold, GravityRiftTuning.phaseSuppressCooldown(0.05f, inCooldown = true))
        assertEquals(null, GravityRiftTuning.phaseSuppressCooldown(0.2f, inCooldown = true))
        assertEquals(threshold, GravityRiftTuning.phaseSuppressCooldown(5f, inCooldown = false))
    }

    @Test
    fun `jitter 强度按状态分支变化且 IN 受充能时长影响`() {
        val dur = 2f
        val active = GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.ACTIVE, 0.5f, dur)
        val inEarly = GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.IN, 0.2f, dur)
        val inLate = GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.IN, 0.8f, dur)
        assertTrue(inEarly.first <= inLate.first)
        assertTrue(inLate.first <= active.first)
        assertTrue(
            GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.IN, 0.5f, 0.5f).first !=
                    GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.IN, 0.5f, 2f).first
        )
        val outHigh = GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.OUT, 0.8f, dur)
        val outLow = GravityRiftTuning.jitterLevelAndRange(ShipSystemStatsScript.State.OUT, 0.2f, dur)
        assertTrue(outLow.first < outHigh.first)
        assertTrue(outHigh.first <= active.first)
        assertTrue(outHigh.second <= active.second)
    }

    @Test
    fun `裂隙数量随未用射程递增并钳制边界`() {
        assertEquals(1, GravityRiftTuning.riftCount(0f))
        assertEquals(1, GravityRiftTuning.riftCount(199f))
        assertEquals(2, GravityRiftTuning.riftCount(200f))
        assertEquals(4, GravityRiftTuning.riftCount(700f))
        assertEquals(5, GravityRiftTuning.riftCount(800f))
        assertEquals(5, GravityRiftTuning.riftCount(1000f))
    }

    @Test
    fun `单裂隙伤害按序位插值`() {
        assertEquals(1500f, GravityRiftTuning.riftDamage(1000f, 1500f, 0, 1), 1e-6f)
        assertEquals(1000f, GravityRiftTuning.riftDamage(1000f, 1500f, 0, 5), 1e-6f)
        assertEquals(1250f, GravityRiftTuning.riftDamage(1000f, 1500f, 2, 5), 1e-6f)
        assertEquals(1500f, GravityRiftTuning.riftDamage(1000f, 1500f, 4, 5), 1e-6f)
        assertEquals(1000f, GravityRiftTuning.riftDamage(1000f, 1500f, 0, 2), 1e-6f)
        assertEquals(1500f, GravityRiftTuning.riftDamage(1000f, 1500f, 1, 2), 1e-6f)
    }

    @Test
    fun `伤害换算地雷乘区`() {
        assertEquals(1.0f, GravityRiftTuning.riftDamageMult(1000f), 1e-6f)
        assertEquals(1.5f, GravityRiftTuning.riftDamageMult(1500f), 1e-6f)
        assertEquals(1f, GravityRiftTuning.riftDamageMult(GravityRiftTuning.MINE_BASE_DAMAGE), 1e-6f)
    }

    @Test
    fun `旋涡 alpha 包络淡入全亮淡出`() {
        assertEquals(0f, GravityRiftTuning.vortexAlpha(0f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(0.5f), 1e-6f)
        assertEquals(1f, GravityRiftTuning.vortexAlpha(1f), 1e-6f)
        assertEquals(1f, GravityRiftTuning.vortexAlpha(2f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(2.5f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(3f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(4f), 1e-6f)
    }

    @Test
    fun `旋涡 forceFadeOut 自收口时刻起 1s 内压暗到 0`() {
        assertEquals(1f, GravityRiftTuning.vortexAlpha(1.2f, forceFadeOutAt = 1.2f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(1.7f, forceFadeOutAt = 1.2f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(2.2f, forceFadeOutAt = 1.2f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(0.5f, forceFadeOutAt = 1.2f), 1e-6f)
    }
}
