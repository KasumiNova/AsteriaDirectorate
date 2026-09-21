package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 无限赏金生成纯逻辑：3 槽常驻初始化幂等、FP 档位表、R 型词缀开放表、
 * 报价锁定区间、文书编号格式、核销换代刷新。
 */
class InfiniteBountyGeneratorTest {

    @Test
    fun `三槽初始化与幂等补齐`() {
        val state = BountyState()
        assertTrue(
            InfiniteBountyGenerator.ensureSlots(state, 1f, 42L).isEmpty(),
            "未获无限期承包商认证不生成槽位",
        )

        state.indefiniteContractor = true
        val created = InfiniteBountyGenerator.ensureSlots(state, 1f, 42L)
        assertEquals(3, created.size)
        assertEquals(listOf(0, 1, 2), state.infiniteSlots.map { it.index })
        assertEquals(listOf(1, 2, 3), state.infiniteSlots.map { it.generation }, "换代序号从 1 起全局单调")
        assertTrue(state.infiniteSlots.all { it.lifecycle == "" }, "初始为待接取")

        assertTrue(InfiniteBountyGenerator.ensureSlots(state, 1f, 42L).isEmpty(), "已补齐重复调用无副作用")
        assertEquals(3, state.infiniteGeneration)
    }

    @Test
    fun `危险级驱动FP档位与R词缀开放表`() {
        assertEquals(listOf(800, 1300, 1800, 2300, 2800), (1..5).map { InfiniteBountyGenerator.fpForDanger(it) })
        assertEquals(listOf(0, 0, 1, 1, 2), (1..5).map { InfiniteBountyGenerator.rCountForDanger(it) })
    }

    @Test
    fun `报价锁定区间确定性与难度缩放`() {
        val q1 = InfiniteBountyGenerator.quoteReward(1000, 1f, 7L)
        assertEquals(q1, InfiniteBountyGenerator.quoteReward(1000, 1f, 7L), "同种子报价恒定")
        assertTrue(q1 in 1000 * 250..1000 * 500, "报价 = FP × 每 FP 单价（250~500）")
        assertEquals(q1 * 5, InfiniteBountyGenerator.quoteReward(1000, 5f, 7L))
        assertEquals(q1 * 5, InfiniteBountyGenerator.quoteReward(1000, 9f, 7L), "难度缩放封顶 5×")
    }

    @Test
    fun `槽位重滚锁定词缀与目标势力`() {
        val slot = InfiniteBountyGenerator.rollSlot(0, 1, 1234L, 5f)
        assertEquals(
            InfiniteSlotStateCopy(slot),
            InfiniteSlotStateCopy(InfiniteBountyGenerator.rollSlot(0, 1, 1234L, 5f)),
            "同种子整代结果确定",
        )
        assertTrue(slot.danger in 1..5)
        assertEquals(InfiniteBountyGenerator.fpForDanger(slot.danger), slot.fp)
        assertTrue(slot.targetFactionId in InfiniteBountyGenerator.TARGET_FACTIONS)
        assertTrue(slot.quotedReward > 0)
        // 词缀已锁定且 R 条数符合开放表
        val rCount = slot.affixIds.count {
            cn.kasuminova.astd.combat.affix.AffixRegistry.getById(it)?.type ==
                    cn.kasuminova.astd.combat.affix.AffixRegistry.AffixType.R
        }
        assertEquals(InfiniteBountyGenerator.rCountForDanger(slot.danger), rCount)
    }

    @Test
    fun `文书编号与工单key格式`() {
        assertEquals("WG-c209-801／清除-0001", InfiniteBountyGenerator.serialOf(0, 1))
        assertEquals("WG-c209-812／清除-0047", InfiniteBountyGenerator.serialOf(11, 47))
        assertEquals("astd_infinite_2_3", InfiniteBountyGenerator.keyOf(2, 3))
    }

    @Test
    fun `核销换代全字段刷新且序号单调`() {
        val state = BountyState()
        state.indefiniteContractor = true
        InfiniteBountyGenerator.ensureSlots(state, 2f, 99L)
        val old = state.infiniteSlots[1]
        val oldKey = InfiniteBountyGenerator.keyOf(old.index, old.generation)

        val next = InfiniteBountyGenerator.regenerateSlot(state, 1, 2f, 99L)
        assertNotEquals(null, next)
        next!!
        assertEquals(1, next.index)
        assertEquals(4, next.generation, "换代序号为全部槽位共享的单调计数（1,2,3 → 换代至 4）")
        assertTrue(next.generation > old.generation)
        assertEquals("", next.lifecycle, "换代归待接取")
        assertNotEquals(oldKey, InfiniteBountyGenerator.keyOf(next.index, next.generation))
        assertTrue(state.infiniteSlots[1] === next, "槽位表原位替换")

        assertEquals(null, InfiniteBountyGenerator.regenerateSlot(state, 9, 2f, 99L), "越界槽位返回 null")
    }

    @Test
    fun `失败换代与连续失败重滚`() {
        // 与 InfiniteBountyBridge.onInfiniteFailed 同路径：失败终态 → regenerateSlot 换代重滚
        val state = BountyState()
        state.indefiniteContractor = true
        InfiniteBountyGenerator.ensureSlots(state, 1f, 7L)
        val slot = state.infiniteSlots[0]
        slot.lifecycle = "POSTED"
        val firstKey = InfiniteBountyGenerator.keyOf(slot.index, slot.generation)

        // 首次失败：换代（新 key/新种子），归待接取
        val gen1 = InfiniteBountyGenerator.regenerateSlot(state, 0, 1f, 7L)!!
        assertEquals("", gen1.lifecycle, "失败换代归待接取")
        assertNotEquals(firstKey, InfiniteBountyGenerator.keyOf(gen1.index, gen1.generation))
        assertNotEquals(slot.seed, gen1.seed, "换代后目标/报价/词缀按新种子重滚")

        // 同槽位连续失败：换代序号单调递增，key 不复用（防呆）
        gen1.lifecycle = "POSTED"
        val gen2 = InfiniteBountyGenerator.regenerateSlot(state, 0, 1f, 7L)!!
        assertTrue(gen2.generation > gen1.generation)
        assertNotEquals(
            InfiniteBountyGenerator.keyOf(gen1.index, gen1.generation),
            InfiniteBountyGenerator.keyOf(gen2.index, gen2.generation),
        )
        assertTrue(state.infiniteSlots[0] === gen2, "槽位表原位替换")
    }

    /** 逐字段快照比对（InfiniteSlotState 为 XStream 普通类，无 equals）。 */
    private data class InfiniteSlotStateCopy(
        val index: Int,
        val generation: Int,
        val danger: Int,
        val fp: Int,
        val seed: Long,
        val quotedReward: Int,
        val targetFactionId: String,
        val affixIds: List<String>,
    ) {
        constructor(s: cn.kasuminova.astd.campaign.bounty.InfiniteSlotState) : this(
            s.index, s.generation, s.danger, s.fp, s.seed, s.quotedReward, s.targetFactionId, s.affixIds.toList(),
        )
    }
}
