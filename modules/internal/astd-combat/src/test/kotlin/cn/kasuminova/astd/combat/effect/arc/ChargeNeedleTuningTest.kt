package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 01 §4.1 用例 1~3（2026-09 机制修订）：五档查表精确取档、玩家固定 v2、
 * 非整数 k_s 就近取档（查表项不插值）与体型固定软辐能分档。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 BountyScalingHullModTest 先例）。
 */
class ChargeNeedleTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(
        scale: Float,
        isPlayer: Boolean = false,
        hullSize: ShipAPI.HullSize? = ShipAPI.HullSize.FRIGATE,
    ): ChargeNeedleTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return ChargeNeedleTuning.resolve(DifficultyTuningImpl, isPlayer, hullSize)
    }

    @Test
    fun `用例1 五档精确取档 k_s 1 2 5`() {
        resolveAt(1f).let { v ->
            assertEquals(0.01f, v.perStack, 1e-6f)
            assertEquals(1f, v.flatFluxPerStack, 1e-6f)
            assertEquals(0.20f, v.dischargeChance, 1e-6f)
            assertEquals(1.00f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(3f, v.flatFluxPerStack, 1e-6f)
            assertEquals(0.30f, v.dischargeChance, 1e-6f)
            assertEquals(1.50f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(0.05f, v.perStack, 1e-6f)
            assertEquals(5f, v.flatFluxPerStack, 1e-6f)
            assertEquals(0.60f, v.dischargeChance, 1e-6f)
            assertEquals(3.00f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例2 玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(3f, v.flatFluxPerStack, 1e-6f)
            assertEquals(0.30f, v.dischargeChance, 1e-6f)
            assertEquals(1.50f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(3f, v.flatFluxPerStack, 1e-6f)
            assertEquals(0.30f, v.dischargeChance, 1e-6f)
            assertEquals(1.50f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例3 查表项非整数 k_s 就近取档不插值`() {
        // k_s=3.4 → 就近 v3：泄放概率/EMP 倍率精确取档；perStack 为 ScalingEntry 仍走 LINEAR 插值。
        resolveAt(3.4f).let { v ->
            assertEquals(0.40f, v.dischargeChance, 1e-6f)
            assertEquals(2.00f, v.dischargeEmpMult, 1e-6f)
            assertEquals(ScalingMap.LINEAR.value(3.4f, 0.01f, 0.02f, 0.05f), v.perStack, 1e-6f)
        }
        // k_s=3.5 → half-up 进位取 v4。
        resolveAt(3.5f).let { v ->
            assertEquals(0.50f, v.dischargeChance, 1e-6f)
            assertEquals(2.50f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例3b 体型固定软辐能分档 v2`() {
        assertEquals(3f, resolveAt(2f, hullSize = ShipAPI.HullSize.FRIGATE).flatFluxPerStack, 1e-6f)
        assertEquals(6f, resolveAt(2f, hullSize = ShipAPI.HullSize.DESTROYER).flatFluxPerStack, 1e-6f)
        assertEquals(9f, resolveAt(2f, hullSize = ShipAPI.HullSize.CRUISER).flatFluxPerStack, 1e-6f)
        assertEquals(12f, resolveAt(2f, hullSize = ShipAPI.HullSize.CAPITAL_SHIP).flatFluxPerStack, 1e-6f)
        // null 体型（配置异常上游遗漏）按护卫舰档兜底。
        assertEquals(3f, resolveAt(2f, hullSize = null).flatFluxPerStack, 1e-6f)
    }
}
