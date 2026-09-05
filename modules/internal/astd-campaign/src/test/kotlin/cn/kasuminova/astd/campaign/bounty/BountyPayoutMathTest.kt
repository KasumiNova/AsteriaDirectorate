package cn.kasuminova.astd.campaign.bounty

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 报酬纯计算验证（[BountyRewards]）：单票区间抽取 × k_s、结清奖金基数 × k_s（封顶 5×）。
 */
class BountyPayoutMathTest {

    @Test
    fun `单票报酬在区间内且随 k_s 缩放`() {
        for (seed in 0L..100L) {
            val rnd = Random(seed)
            val base = BountyRewards.computeTicketPayout(300_000, 1_500_000, 1f, rnd)
            assertTrue(base in 300_000..1_500_000, "k_s=1 时应在原区间内：$base")

            val scaled = BountyRewards.computeTicketPayout(300_000, 1_500_000, 5f, Random(seed))
            assertTrue(scaled in 1_500_000..7_500_000, "k_s=5 时应为 5 倍区间：$scaled")
        }
    }

    @Test
    fun `相同种子抽取确定一致`() {
        val a = BountyRewards.computeTicketPayout(200_000, 1_000_000, 2f, Random(7))
        val b = BountyRewards.computeTicketPayout(200_000, 1_000_000, 2f, Random(7))
        assertEquals(a, b)
    }

    @Test
    fun `非法区间返回 0`() {
        assertEquals(0, BountyRewards.computeTicketPayout(0, 0, 2f, Random(1)))
        assertEquals(0, BountyRewards.computeTicketPayout(1_000_000, 500_000, 2f, Random(1)))
    }

    @Test
    fun `结清奖金按 k_s 缩放且封顶 5 倍`() {
        assertEquals(300_000, BountyRewards.computeGroupBonus(300_000, 1f))
        assertEquals(600_000, BountyRewards.computeGroupBonus(300_000, 2f))
        assertEquals(1_500_000, BountyRewards.computeGroupBonus(300_000, 5f))
        // k_s 超界钳制到 5（文档口径“最高 5×”）
        assertEquals(1_500_000, BountyRewards.computeGroupBonus(300_000, 99f))
        // 序章组无结清奖金
        assertEquals(0, BountyRewards.computeGroupBonus(0, 5f))
    }
}
