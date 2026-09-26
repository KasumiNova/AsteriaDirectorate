package cn.kasuminova.astd.combat.effect.arc.cuifeng

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CuifengTorpedoMath] 纯计算面全量驱动：二段式速度曲线分段点 / 辐能自适应线性区间
 * 边界 / 舰体自适应正向差值语义 / 0 值防线返回值。
 */
class CuifengTorpedoMathTest {

    @Test
    fun `二段式速度曲线：慢速段恒半速、加速段线性、满速段恒满速`() {
        assertEquals(0.5f, CuifengTorpedoMath.speedFactor(0f), 1e-6f, "航程 0 → 慢速段 50%")
        assertEquals(0.5f, CuifengTorpedoMath.speedFactor(0.125f), 1e-6f, "航程 12.5% → 慢速段 50%")
        assertEquals(0.5f, CuifengTorpedoMath.speedFactor(0.25f), 1e-6f, "航程 25% 分界 → 仍为 50%（加速段起点）")
        assertEquals(0.75f, CuifengTorpedoMath.speedFactor(0.375f), 1e-6f, "航程 37.5% → 加速段中点 75%")
        assertEquals(1f, CuifengTorpedoMath.speedFactor(0.5f), 1e-6f, "航程 50% 分界 → 满速")
        assertEquals(1f, CuifengTorpedoMath.speedFactor(1f), 1e-6f, "航程 100% → 满速")
    }

    @Test
    fun `进度推导：正常比值与 maxRange 非法防线`() {
        assertEquals(0.25f, CuifengTorpedoMath.progressOf(400f, 1600f), 1e-6f)
        assertEquals(0f, CuifengTorpedoMath.progressOf(0f, 1600f), 1e-6f)
        assertEquals(0f, CuifengTorpedoMath.progressOf(-10f, 1600f), 1e-6f, "负航程 clamp 到 0")
        assertEquals(1f, CuifengTorpedoMath.progressOf(100f, 0f), 1e-6f, "maxRange=0 → 按满速段处理")
        assertEquals(1f, CuifengTorpedoMath.progressOf(100f, -5f), 1e-6f, "maxRange<0 → 按满速段处理")
        assertEquals(1f, CuifengTorpedoMath.progressOf(100f, Float.NaN), 1e-6f, "maxRange=NaN → 按满速段处理")
    }

    @Test
    fun `辐能自适应系数：40 起算 90 满值线性，越界 clamp`() {
        assertEquals(0f, CuifengTorpedoMath.fluxAdaptiveFactor(0.3f), 1e-6f, "辐能 30% < 40% 起算点 → 0")
        assertEquals(0f, CuifengTorpedoMath.fluxAdaptiveFactor(0.4f), 1e-6f, "辐能 40% 起算点 → 0")
        assertEquals(0.5f, CuifengTorpedoMath.fluxAdaptiveFactor(0.65f), 1e-6f, "辐能 65% → 线性中点 0.5")
        assertEquals(1f, CuifengTorpedoMath.fluxAdaptiveFactor(0.9f), 1e-6f, "辐能 90% 满值点 → 1")
        assertEquals(1f, CuifengTorpedoMath.fluxAdaptiveFactor(1.2f), 1e-6f, "辐能 120% 越界 → clamp 1")
        assertEquals(0f, CuifengTorpedoMath.fluxAdaptiveFactor(Float.NaN), 1e-6f, "NaN → 0")
    }

    @Test
    fun `辐能自适应增伤：面板乘难度倍率乘进度系数`() {
        // 砺刃 v2 x=0.5、辐能 65%（系数 0.5）：1500 × 0.5 × 0.5 = 375
        assertEquals(375f, CuifengTorpedoMath.fluxAdaptiveBonus(1500f, 0.5f, 0.65f), 1e-4f)
        // 辐能 30%（系数 0）→ 无增伤
        assertEquals(0f, CuifengTorpedoMath.fluxAdaptiveBonus(1500f, 0.5f, 0.3f), 1e-6f)
    }

    @Test
    fun `舰体自适应增伤：档位加正向部署点差值，负差值不扣减`() {
        // 巡洋舰档位 40% + 超出基准 10 点 × 4%：1500 × (0.4 + 0.4) = 1200
        assertEquals(1200f, CuifengTorpedoMath.hullAdaptiveBonus(1500f, 0.4f, 30f, 20f, 0.04f), 1e-2f)
        // 等于基准：仅档位增伤 1500 × 0.4 = 600
        assertEquals(600f, CuifengTorpedoMath.hullAdaptiveBonus(1500f, 0.4f, 20f, 20f, 0.04f), 1e-4f)
        // 低于基准：差值不计（设计案「差值增伤」只取正向）→ 仍仅档位 600
        assertEquals(600f, CuifengTorpedoMath.hullAdaptiveBonus(1500f, 0.4f, 15f, 20f, 0.04f), 1e-4f)
    }
}
