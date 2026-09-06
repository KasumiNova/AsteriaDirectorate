package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 报酬计算：单票区间、难度系数缩放（封顶 5×）、批次/线/章结清奖金、报价锁定。
 */
class MainBountyRewardTest {

    private fun def(key: String) = assertNotNull(MainBounties.byKey(key), key)

    @Test
    fun `整单报价在区间内且同种子确定`() {
        for (d in MainBounties.all) {
            repeat(8) { i ->
                val seed = 1000L + i
                val a = MainlineProgression.quoteOrderReward(d, 1f, seed)
                val b = MainlineProgression.quoteOrderReward(d, 1f, seed)
                assertEquals(a, b, "同种子报价应确定：${d.key}")
                assertTrue(a in d.rewardMin..d.rewardMax, "${d.key} k_s=1 报价 $a 超出区间 [${d.rewardMin}, ${d.rewardMax}]")
            }
        }
    }

    @Test
    fun `难度系数线性缩放且 k_s5 恰为 k_s1 的五倍`() {
        val d = def(MainBounties.KEY_ZX_0002)
        repeat(8) { i ->
            val seed = 5000L + i
            val base = MainlineProgression.quoteOrderReward(d, 1f, seed)
            val scaled = MainlineProgression.quoteOrderReward(d, 5f, seed)
            assertEquals(base * 5, scaled, "k_s=5 应为基值 5 倍")
        }
    }

    @Test
    fun `缩放封顶五倍且低于一按一计`() {
        val d = def(MainBounties.KEY_ZQ_0001)
        val seed = 7777L
        val atFive = MainlineProgression.quoteOrderReward(d, 5f, seed)
        assertEquals(atFive, MainlineProgression.quoteOrderReward(d, 7f, seed), "超出 5 应按 5 封顶")
        assertEquals(atFive, MainlineProgression.quoteOrderReward(d, 100f, seed))
        val atOne = MainlineProgression.quoteOrderReward(d, 1f, seed)
        assertEquals(atOne, MainlineProgression.quoteOrderReward(d, 0.2f, seed), "低于 1 应按 1 计")

        assertEquals(1_500_000, MainlineProgression.scaledBonus(300_000, 5f))
        assertEquals(1_500_000, MainlineProgression.scaledBonus(300_000, 6f), "结清奖金同样封顶 5×")
        assertEquals(300_000, MainlineProgression.scaledBonus(300_000, 1f))
    }

    @Test
    fun `报价锁定按整单一份且多阶段不放大`() {
        // 多阶段工单（ZW 四阶段 / ZQ 三阶段）：整单只锁一份报价（L5：阶段不打折不放大）
        for (key in listOf(MainBounties.KEY_ZW_0309, MainBounties.KEY_ZQ_0001)) {
            val d = def(key)
            val quote = MainlineProgression.quoteOrderReward(d, 2f, 42L)
            assertTrue(
                quote in (d.rewardMin * 2)..(d.rewardMax * 2),
                "${d.key} 整单报价 $quote 超出 k_s=2 区间 [${d.rewardMin * 2}, ${d.rewardMax * 2}]",
            )
            // 同种子重报一致（失败重挂沿用首次报价，putIfAbsent 语义由桥接层执行，此处验证取值稳定性）
            assertEquals(quote, MainlineProgression.quoteOrderReward(d, 2f, 42L))
            // 报价存储键为整单一键，不带阶段后缀
            assertEquals(d.key, MainlineProgression.quoteKey(d.key))
        }
    }

    @Test
    fun `结清组奖金基数覆盖批次线与章三档`() {
        // 批次 30/50/75 万、线 100 万、章 150/200 万（k_s=1 口径）
        val expect = mapOf(
            MainBounties.GROUP_CH1_BATCH1 to 300_000,
            MainBounties.GROUP_CH1_BATCH2 to 500_000,
            MainBounties.GROUP_CH1_BATCH3 to 750_000,
            MainBounties.GROUP_CH2_XC to 1_000_000,
            MainBounties.GROUP_CH2_ZW to 1_000_000,
            MainBounties.GROUP_CH3 to 1_500_000,
            MainBounties.GROUP_CH4 to 2_000_000,
        )
        for ((groupId, base) in expect) {
            val group = assertNotNull(MainBounties.group(groupId))
            assertEquals(base, MainlineProgression.scaledBonus(group.bonusBase, 1f), groupId)
            assertEquals(base * 5, MainlineProgression.scaledBonus(group.bonusBase, 5f), groupId)
        }
    }
}
