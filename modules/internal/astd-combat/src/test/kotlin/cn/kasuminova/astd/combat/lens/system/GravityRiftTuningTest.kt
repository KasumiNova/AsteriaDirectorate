package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力裂隙发生器数值规格（purple/20-production.md §2）：裂隙数量随开火距离递减
 * （(900 − dist) / 200 + 1，钳制 [1, 5]）、单裂隙伤害按难度三锚点区间随序位线性插值
 * （仅 1 个时取上限）、伤害→地雷乘区换算（/ 1000）。
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
    fun `裂隙数量随距离递减并钳制边界`() {
        // 贴脸与 100su 边界：(900 − 100) / 200 + 1 = 5（上限）。
        assertEquals(5, GravityRiftTuning.riftCount(0f))
        assertEquals(5, GravityRiftTuning.riftCount(100f))
        // 越过 100su 即掉档。
        assertEquals(4, GravityRiftTuning.riftCount(101f))
        assertEquals(2, GravityRiftTuning.riftCount(700f))
        // 满射程与超程均为下限 1。
        assertEquals(1, GravityRiftTuning.riftCount(900f))
        assertEquals(1, GravityRiftTuning.riftCount(1100f))
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
}
