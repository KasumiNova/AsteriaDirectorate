package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力空间复制器数值规格：复制体伤害比例三锚点折算（v1 35% / v2 50% / v5 100%）、
 * 复制辐能比例（v1 60% / v2 50% / v5 20%，玩家固定 v2）、复制时序（0.5s/1.0s 两发）、
 * 激活辐能（基础最大辐能容量 10% 软辐能）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 GravPhaseDeckTuningTest 先例）。
 */
class GravReplicatorTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): GravReplicatorTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravReplicatorTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        val v1 = resolveAt(1f)
        assertEquals(0.35f, v1.damageRatio, 1e-6f)
        assertEquals(0.60f, v1.fluxRatio, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(0.50f, v2.damageRatio, 1e-6f)
        assertEquals(0.50f, v2.fluxRatio, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(1.00f, v5.damageRatio, 1e-6f)
        assertEquals(0.20f, v5.fluxRatio, 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        val v = resolveAt(5f, isPlayer = true)
        assertEquals(0.50f, v.damageRatio, 1e-6f)
        assertEquals(0.50f, v.fluxRatio, 1e-6f)
    }

    @Test
    fun `复制时序为发射后 0_5s 与 1_0s 两发递增`() {
        assertEquals(0.5f, GravReplicatorTuning.copyDueTime(0), 1e-6f)
        assertEquals(1.0f, GravReplicatorTuning.copyDueTime(1), 1e-6f)
    }

    @Test
    fun `复制体伤害为原弹体伤害乘难度比例`() {
        assertEquals(100f, GravReplicatorTuning.replicaDamage(200f, 0.5f), 1e-3f)
        assertEquals(200f, GravReplicatorTuning.replicaDamage(200f, 1f), 1e-3f)
    }

    @Test
    fun `单发复制附加软辐能为武器单发辐能乘难度比例`() {
        assertEquals(30f, GravReplicatorTuning.replicaFlux(60f, 0.5f), 1e-3f)
        assertEquals(12f, GravReplicatorTuning.replicaFlux(60f, 0.2f), 1e-3f)
    }
}
