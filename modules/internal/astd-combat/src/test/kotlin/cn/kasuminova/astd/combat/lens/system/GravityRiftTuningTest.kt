package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力裂隙发生器数值规格（purple/20-production.md §2，2026-09-20 二轮重做）：
 * 裂隙数量随光束未用射程递增（unusedRange / 200 + 1，钳制 [1, 5]，对齐原版裂隙洪流发射极）、
 * 单裂隙伤害按难度三锚点区间随序位线性插值（仅 1 个时取上限）、伤害→地雷乘区换算（/ 1000）、
 * 旋涡 alpha 包络（1s 淡入 + 全亮 + 1s 淡出，forceFadeOut 提前收口）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 FighterGravLinkTuningTest 先例）。
 */
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
            assertEquals(600f, v.damageMin, 1e-6f)
            assertEquals(1000f, v.damageMax, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(800f, v.damageMin, 1e-6f)
            assertEquals(1400f, v.damageMax, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(1200f, v.damageMin, 1e-6f)
            assertEquals(2000f, v.damageMax, 1e-6f)
        }
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(800f, v.damageMin, 1e-6f)
            assertEquals(1400f, v.damageMax, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(800f, v.damageMin, 1e-6f)
            assertEquals(1400f, v.damageMax, 1e-6f)
        }
    }

    @Test
    fun `裂隙数量随未用射程递增并钳制边界`() {
        // 未用射程 0（贴脸命中）为下限 1。
        assertEquals(1, GravityRiftTuning.riftCount(0f))
        assertEquals(1, GravityRiftTuning.riftCount(199f))
        // 每余 200su 多 1 枚。
        assertEquals(2, GravityRiftTuning.riftCount(200f))
        assertEquals(4, GravityRiftTuning.riftCount(700f))
        // 未用射程 800 起触顶 5（上限）。
        assertEquals(5, GravityRiftTuning.riftCount(800f))
        assertEquals(5, GravityRiftTuning.riftCount(1000f))
    }

    @Test
    fun `单裂隙伤害按序位插值`() {
        // 仅 1 个时取上限。
        assertEquals(1400f, GravityRiftTuning.riftDamage(800f, 1400f, 0, 1), 1e-6f)
        // 5 个：首取下限、末取上限、中间线性插值。
        assertEquals(800f, GravityRiftTuning.riftDamage(800f, 1400f, 0, 5), 1e-6f)
        assertEquals(1100f, GravityRiftTuning.riftDamage(800f, 1400f, 2, 5), 1e-6f)
        assertEquals(1400f, GravityRiftTuning.riftDamage(800f, 1400f, 4, 5), 1e-6f)
        // 2 个：首末各占区间端点。
        assertEquals(800f, GravityRiftTuning.riftDamage(800f, 1400f, 0, 2), 1e-6f)
        assertEquals(1400f, GravityRiftTuning.riftDamage(800f, 1400f, 1, 2), 1e-6f)
    }

    @Test
    fun `伤害换算地雷乘区`() {
        assertEquals(0.8f, GravityRiftTuning.riftDamageMult(800f), 1e-6f)
        assertEquals(1.4f, GravityRiftTuning.riftDamageMult(1400f), 1e-6f)
        assertEquals(1f, GravityRiftTuning.riftDamageMult(GravityRiftTuning.MINE_BASE_DAMAGE), 1e-6f)
    }

    @Test
    fun `旋涡 alpha 包络淡入全亮淡出`() {
        // 起点与 1s 淡入。
        assertEquals(0f, GravityRiftTuning.vortexAlpha(0f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(0.5f), 1e-6f)
        assertEquals(1f, GravityRiftTuning.vortexAlpha(1f), 1e-6f)
        // 全亮段。
        assertEquals(1f, GravityRiftTuning.vortexAlpha(2f), 1e-6f)
        // 1s 淡出至归零。
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(2.5f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(3f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(4f), 1e-6f)
    }

    @Test
    fun `旋涡 forceFadeOut 自收口时刻起 1s 内压暗到 0`() {
        // 全亮段 1.2s 处强制收口：1s 内压暗到 0。
        assertEquals(1f, GravityRiftTuning.vortexAlpha(1.2f, forceFadeOutAt = 1.2f), 1e-6f)
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(1.7f, forceFadeOutAt = 1.2f), 1e-6f)
        assertEquals(0f, GravityRiftTuning.vortexAlpha(2.2f, forceFadeOutAt = 1.2f), 1e-6f)
        // 收口时刻之前不受 forceFadeOut 影响。
        assertEquals(0.5f, GravityRiftTuning.vortexAlpha(0.5f, forceFadeOutAt = 1.2f), 1e-6f)
    }
}
