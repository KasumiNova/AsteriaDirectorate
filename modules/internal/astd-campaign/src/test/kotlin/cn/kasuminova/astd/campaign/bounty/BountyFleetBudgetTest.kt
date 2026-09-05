package cn.kasuminova.astd.campaign.bounty

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BountyFleetBudgetTest {
    @Test
    fun `舰队配置倍率只计一次且不截断最高难度`() {
        val ordinary = DifficultyModel.calculate(1f, 1f, 30, 30)
        assertEquals(1f, ordinary.totalMult)
        val expanded = DifficultyModel.calculate(5f, 3f, 60, 90)
        assertEquals(2f, expanded.playerMult)
        assertEquals(3f, expanded.fleetSizeMult)
        assertEquals(30f, expanded.totalMult)
        assertEquals(1f, expanded.k)
        assertEquals(1f, DifficultyModel.calculate(1f, 1f, 10, 20).fleetSizeMult)
    }

    @Test
    fun `词缀数量只由固有难度而非玩家规模决定`() {
        for (difficulty in 1..5) {
            val weak = DifficultyModel.calculate(difficulty.toFloat(), 0.85f, 30, 30)
            val strong = DifficultyModel.calculate(difficulty.toFloat(), 2f, 90, 90)
            assertEquals(weak.k, strong.k)
            assertEquals(AffixRule.STANDARD.counts(weak.k), AffixRule.STANDARD.counts(strong.k))
        }
    }

    @Test
    fun `大额预算不会因为超过三十艘或六百次填充而丢失`() {
        val costs = mapOf("frigate" to 5, "cruiser" to 20, "capital" to 40)
        val result = FleetComposer.fillBudget(costs, 28_000, Random(71))
        assertTrue(result.size > 600)
        assertEquals(28_000, result.sumOf { costs.getValue(it) })
        assertEquals(result, FleetComposer.fillBudget(costs, 28_000, Random(71)))
    }

    @Test
    fun `不能整除时以最小舰补齐且误差小于一艘舰`() {
        val costs = mapOf("a" to 7, "b" to 19, "c" to 41)
        for (budget in 1..500) {
            val result = FleetComposer.fillBudget(costs, budget, Random(budget.toLong()))
            val actual = result.sumOf { costs.getValue(it) }
            assertTrue(actual >= budget && actual < budget + 7, "$budget -> $actual")
        }
        assertTrue(FleetComposer.fillBudget(emptyMap(), 0, Random(1)).isEmpty())
        assertFailsWith<IllegalArgumentException> { FleetComposer.fillBudget(emptyMap(), 100, Random(1)) }
        assertFailsWith<IllegalArgumentException> { FleetComposer.fillBudget(mapOf("bad" to 0), 100, Random(1)) }
    }
}
