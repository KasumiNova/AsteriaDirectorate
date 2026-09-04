package cn.kasuminova.astd.campaign.bounty

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 轨二战力评估纯计算层验证：边界（空舰队/纯后勤/全 s-mod 旗舰队）、
 * 品级系数封顶、sqrt 压缩单调性与封顶、EMA 平滑。
 */
class FleetStrengthMathTest {

    private fun ship(
        dp: Float,
        sMods: Int = 0,
        dMods: Int = 0,
        phaseOrAuto: Boolean = false,
        civilian: Boolean = false,
    ) = FleetStrengthMath.ShipInput(dp, sMods, dMods, phaseOrAuto, civilian)

    @Test
    fun `空舰队得分为零且 p 取下限`() {
        val inputs = FleetStrengthMath.Inputs(emptyList(), emptyList(), 0)
        assertEquals(0f, inputs.rawScore, 1e-6f)
        val snapshot = FleetStrengthMath.buildSnapshot(inputs, 0f, 200f)
        assertEquals(FleetStrengthMath.P_MIN, snapshot.p, 1e-6f)
        assertEquals(0f, snapshot.k, 1e-6f)
    }

    @Test
    fun `纯后勤舰队按民用减半计分`() {
        val inputs = FleetStrengthMath.Inputs(listOf(ship(dp = 10f, civilian = true), ship(dp = 20f, civilian = true)), emptyList(), 0)
        assertEquals(15f, inputs.rawScore, 1e-6f)
    }

    @Test
    fun `全 s-mod 旗舰队品级系数封到 1_5`() {
        // 1 + 3×0.08 + 0.10(相位) = 1.34，再加 2 个 s-mod 应被 1.5 封顶
        assertEquals(1.34f, FleetStrengthMath.qualityCoefficient(ship(30f, sMods = 3, phaseOrAuto = true)), 1e-6f)
        assertEquals(1.5f, FleetStrengthMath.qualityCoefficient(ship(30f, sMods = 5, phaseOrAuto = true)), 1e-6f)
    }

    @Test
    fun `大量 d-mod 品级系数封到 0_5`() {
        // 1 - 4×0.06 = 0.76；6 个 d-mod = 0.64；仍高于 0.5，封底下限验证用 10 个
        assertEquals(0.64f, FleetStrengthMath.qualityCoefficient(ship(30f, dMods = 6)), 1e-6f)
        assertEquals(0.5f, FleetStrengthMath.qualityCoefficient(ship(30f, dMods = 10)), 1e-6f)
    }

    @Test
    fun `军官与技能按权重计分`() {
        val inputs = FleetStrengthMath.Inputs(emptyList(), listOf(5, 3), 4)
        assertEquals(8 * 3f + 4 * 4f, inputs.rawScore, 1e-6f)
    }

    @Test
    fun `sqrt 压缩单调且边际递减`() {
        val ref = 1000f
        // 等差步进且全部落在封顶区间内：sqrt 的二阶导为负，增量应逐段缩小
        val scores = listOf(750f, 1000f, 1250f, 1500f, 1750f)
        val ps = scores.map { FleetStrengthMath.compress(it, ref) }
        // 单调递增
        ps.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "p 应随分数单调递增：$ps") }
        // 边际递减：等差分数带来的 p 增量逐段缩小
        val deltas = ps.zipWithNext { a, b -> b - a }
        deltas.zipWithNext().forEach { (a, b) -> assertTrue(b < a, "p 增量应边际递减：$deltas") }
        // 基准分 = 参照 FP 时 p = 1
        assertEquals(1f, FleetStrengthMath.compress(ref, ref), 1e-6f)
        assertEquals(sqrt(0.81f), FleetStrengthMath.compress(810f, ref), 1e-6f)
    }

    @Test
    fun `压缩结果封顶在 0_85 到 2_2`() {
        assertEquals(0.85f, FleetStrengthMath.compress(0f, 200f), 1e-6f)
        assertEquals(2.2f, FleetStrengthMath.compress(100000f, 200f), 1e-6f)
        // 参照 FP 异常小也不除零
        assertEquals(2.2f, FleetStrengthMath.compress(100f, 0f), 1e-6f)
    }

    @Test
    fun `k_p 由 p 线性归一化`() {
        val inputs = FleetStrengthMath.Inputs(listOf(ship(dp = 200f)), emptyList(), 0)
        // S = 200, ref = 200 → p = 1 → k = (1 - 0.85) / 1.35
        val snapshot = FleetStrengthMath.buildSnapshot(inputs, inputs.rawScore, 200f)
        assertEquals(1f, snapshot.p, 1e-6f)
        assertEquals(0.15f / 1.35f, snapshot.k, 1e-6f)
        // p 封顶 2.2 → k = 1
        val capped = FleetStrengthMath.buildSnapshot(inputs, 1e6f, 1f)
        assertEquals(1f, capped.k, 1e-6f)
    }

    @Test
    fun `EMA 平滑首次取原值后续按权重混合`() {
        assertEquals(100f, FleetStrengthMath.smooth(100f, null), 1e-6f)
        // prev=100, raw=200 → 100 + 0.3×100 = 130
        assertEquals(130f, FleetStrengthMath.smooth(200f, 100f), 1e-5f)
    }

    @Test
    fun `快照分解覆盖舰船军官技能三项`() {
        val inputs = FleetStrengthMath.Inputs(listOf(ship(dp = 40f, sMods = 1)), listOf(2), 1)
        val snapshot = FleetStrengthMath.buildSnapshot(inputs, inputs.rawScore, 100f)
        val byLabel = snapshot.breakdown.associate { it.label to it.value }
        assertEquals(40f * 1.08f, byLabel.getValue("ships"), 1e-4f)
        assertEquals(6f, byLabel.getValue("officers"), 1e-6f)
        assertEquals(4f, byLabel.getValue("skills"), 1e-6f)
        assertEquals(inputs.rawScore, byLabel.getValue("raw"), 1e-4f)
    }
}
