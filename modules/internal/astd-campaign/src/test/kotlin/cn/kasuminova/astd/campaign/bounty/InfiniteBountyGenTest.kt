package cn.kasuminova.astd.campaign.bounty

import org.magiclib.bounty.ActiveBounty
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 无限赏金生成与核销行为（[InfiniteBounties] 纯逻辑层）验证：
 * 数值口径（危险等级 1~5 / FP 800~2800 / 报酬随难度 / R 型按难度搭配）、
 * key 每代唯一、定义可往返还原、账单有界、终态失败阶段覆盖、核销受理判据与单次结算不重复发奖。
 */
class InfiniteBountyGenTest {

    @Test
    fun `随机口径：危险等级 1 到 5 且 FP 在 800 到 2800`() {
        for (seed in 0L..500L) {
            val slot = InfiniteBounties.rollSlot(0, 1, seed.toInt() + 1, Random(seed), 2f)
            assertTrue(slot.dangerLevel in 1..5, "危险等级越界：${slot.dangerLevel}")
            assertTrue(slot.baselineFP in 800..2800, "FP 越界：${slot.baselineFP}")
        }
    }

    @Test
    fun `R 型词缀按危险等级搭配`() {
        assertEquals(0 to 0, InfiniteBounties.rRangeForDanger(1))
        assertEquals(0 to 1, InfiniteBounties.rRangeForDanger(2))
        assertEquals(1 to 1, InfiniteBounties.rRangeForDanger(3))
        assertEquals(1 to 2, InfiniteBounties.rRangeForDanger(4))
        assertEquals(2 to 2, InfiniteBounties.rRangeForDanger(5))

        for (seed in 0L..200L) {
            val slot = InfiniteBounties.rollSlot(1, 1, 1, Random(seed), 1f)
            val expect = InfiniteBounties.rRangeForDanger(slot.dangerLevel)
            assertEquals(expect.first, slot.affixRMin)
            assertEquals(expect.second, slot.affixRMax)
        }
    }

    @Test
    fun `报酬随难度且报价在锁定区间内`() {
        // 基数区间：随危险等级线性放大
        assertEquals(150_000 to 500_000, InfiniteBounties.rewardRangeForDanger(1))
        assertEquals(750_000 to 2_500_000, InfiniteBounties.rewardRangeForDanger(5))

        for (seed in 0L..200L) {
            // k_s = 1：报价落在基数区间内
            val low = InfiniteBounties.rollSlot(2, 1, 1, Random(seed), 1f)
            assertTrue(
                low.quotedReward in low.rewardMin..low.rewardMax,
                "k_s=1 报价应在基数区间：${low.quotedReward} !in ${low.rewardMin}..${low.rewardMax}",
            )
            // k_s = 5：报价落在 5 倍区间内（展示与实发同用该锁定值）
            val high = InfiniteBounties.rollSlot(2, 1, 1, Random(seed), 5f)
            assertTrue(
                high.quotedReward in high.rewardMin..high.rewardMax * 5,
                "k_s=5 报价应在 5 倍区间：${high.quotedReward}",
            )
        }
    }

    @Test
    fun `key 每代唯一且带模组前缀`() {
        val keys = HashSet<String>()
        for (slot in 0 until InfiniteBounties.SLOT_COUNT) {
            for (gen in 1..50) {
                val key = InfiniteBounties.keyOf(slot, gen)
                assertTrue(key.startsWith(BountyKeys.BOUNTY_KEY_PREFIX), "key 需带 astd_ 前缀：$key")
                assertTrue(keys.add(key), "key 冲突：$key")
            }
        }
        // 与主线单、与序章键永不冲突
        for (def in MainBounties.defs) {
            assertFalse(keys.contains(def.key), "与主线 key 冲突：${def.key}")
            assertFalse(def.key.startsWith(InfiniteBounties.KEY_PREFIX), "主线 key 不应带无限前缀：${def.key}")
        }
    }

    @Test
    fun `槽位内容可完整还原 BountyDef`() {
        val slot = InfiniteBounties.rollSlot(1, 3, 42, Random(7), 3f)
        val def = InfiniteBounties.defOfSlot(slot)

        assertEquals(slot.key, def.key)
        assertEquals(slot.code, def.code)
        assertEquals(slot.dangerLevel, def.dangerLevel)
        assertEquals(slot.baselineFP, def.baselineFP)
        assertEquals(slot.flagshipVariantId, def.flagshipVariantId)
        assertEquals("remnant", def.fleetFactionId)
        assertEquals(AffixRule.withR(slot.affixRMin, slot.affixRMax), def.affixRule)
        assertEquals(slot.rewardMin, def.rewardMin)
        assertEquals(slot.rewardMax, def.rewardMax)
        assertTrue(def.modOnlyComposition, "无限单目标池应全部模组舰船")
        // 无限单不参与主线批次/章节推进
        assertEquals("infinite", def.groupId)
        assertEquals(5, def.chapter)
        assertFalse(def.groupId in MainBounties.groupsById.keys, "无限单不应占用主线结清组")
    }

    @Test
    fun `旗舰池全部沿用主线已验证的模组 variant`() {
        val mainFlagships = MainBounties.defs.map { it.flagshipVariantId }.toSet()
        for (id in InfiniteBounties.FLAGSHIP_POOL) {
            assertTrue(id in mainFlagships, "旗舰池含主线未使用（未验证）的 variant：$id")
        }
    }

    @Test
    fun `同种子抽取确定一致`() {
        val a = InfiniteBounties.rollSlot(0, 1, 9, Random(42), 2.5f)
        val b = InfiniteBounties.rollSlot(0, 1, 9, Random(42), 2.5f)
        assertEquals(InfiniteBounties.defOfSlot(a), InfiniteBounties.defOfSlot(b))
        assertEquals(a.quotedReward, b.quotedReward)
    }

    @Test
    fun `账单只保留最近若干条`() {
        val state = InfiniteBountyState()
        repeat(InfiniteBounties.BILL_CAP + 7) { i ->
            state.addBill(InfiniteBountyBill("WX-c209-%04d".format(i), "c+209", i.toLong(), "续展核销报酬"))
        }
        assertEquals(InfiniteBounties.BILL_CAP, state.bills.size)
        // 最旧的被截掉，保留最后 BILL_CAP 条（时间序）
        assertEquals("WX-c209-0007", state.bills.first().code)
        assertEquals((InfiniteBounties.BILL_CAP + 6).toLong(), state.bills.last().amount)
    }

    @Test
    fun `状态无参构造默认三个在册槽`() {
        val state = InfiniteBountyState()
        assertEquals(InfiniteBounties.SLOT_COUNT, state.slots.size)
        assertTrue(state.slots.all { it.key.isEmpty() && it.generation == 0 })
        assertTrue(state.pendingDelivery.isEmpty())
        assertTrue(state.bills.isEmpty())
    }

    @Test
    fun `终态失败阶段集合覆盖全部非流转阶段`() {
        val inFlight = setOf(
            ActiveBounty.Stage.NotAccepted,
            ActiveBounty.Stage.Accepted,
            ActiveBounty.Stage.Succeeded,
        )
        assertEquals(
            ActiveBounty.Stage.entries.toSet() - inFlight,
            InfiniteBounties.TERMINAL_FAILURE_STAGES,
            "终态失败集合应恰好是全部阶段扣除非终态（挂出/已接取/已取胜）",
        )
    }

    @Test
    fun `核销校验：只在册且待交付的工单被受理`() {
        val state = InfiniteBountyState()
        state.slots[0] = InfiniteBounties.rollSlot(0, 1, 1, Random(3), 2f)
        val key = state.slots[0].key

        // 未击破：在册但无待交付战果
        val (idxNotReady, verdictNotReady) = InfiniteBounties.judgeSettlement(state, key)
        assertEquals(0, idxNotReady)
        assertEquals(InfiniteBounties.SettlementVerdict.NO_PENDING_DELIVERY, verdictNotReady)

        // 非在册 key（未签发过）
        assertEquals(
            -1 to InfiniteBounties.SettlementVerdict.UNKNOWN_KEY,
            InfiniteBounties.judgeSettlement(state, InfiniteBounties.keyOf(1, 99)),
        )
        // 空 key 不算在册
        assertEquals(
            InfiniteBounties.SettlementVerdict.UNKNOWN_KEY,
            InfiniteBounties.judgeSettlement(state, "").second,
        )

        // 击破登记后受理
        state.pendingDelivery.add(key)
        assertEquals(
            0 to InfiniteBounties.SettlementVerdict.ACCEPTABLE,
            InfiniteBounties.judgeSettlement(state, key),
        )
    }

    @Test
    fun `单次结算不重复发奖：落账后同 key 立即失效，换代后旧 key 永久失效`() {
        val state = InfiniteBountyState()
        state.slots[0] = InfiniteBounties.rollSlot(0, 1, 1, Random(11), 2f)
        val oldKey = state.slots[0].key
        state.pendingDelivery.add(oldKey)

        // 第一次核销：受理 → 落账（清除待交付登记）
        assertEquals(
            InfiniteBounties.SettlementVerdict.ACCEPTABLE,
            InfiniteBounties.judgeSettlement(state, oldKey).second,
        )
        InfiniteBounties.markDelivered(state, 0)

        // 重复提交（如 UI 双击/重复事件）：不再是待交付，拒绝
        assertEquals(
            InfiniteBounties.SettlementVerdict.NO_PENDING_DELIVERY,
            InfiniteBounties.judgeSettlement(state, oldKey).second,
        )

        // 换代后：旧 key 彻底不在册，拒绝
        state.slots[0] = InfiniteBounties.rollSlot(0, 2, 2, Random(12), 2f)
        assertEquals(
            InfiniteBounties.SettlementVerdict.UNKNOWN_KEY,
            InfiniteBounties.judgeSettlement(state, oldKey).second,
        )
        // 新一代未击破前同样不受理
        assertEquals(
            InfiniteBounties.SettlementVerdict.NO_PENDING_DELIVERY,
            InfiniteBounties.judgeSettlement(state, state.slots[0].key).second,
        )
        // 落账只清本槽登记，不误伤其它槽
        val otherKey = InfiniteBounties.keyOf(1, 1)
        state.slots[1] = InfiniteBounties.rollSlot(1, 1, 3, Random(13), 2f)
        state.pendingDelivery.add(otherKey)
        InfiniteBounties.markDelivered(state, 0)
        assertTrue(otherKey in state.pendingDelivery, "核销槽 0 不应清掉槽 1 的待交付登记")
    }
}
