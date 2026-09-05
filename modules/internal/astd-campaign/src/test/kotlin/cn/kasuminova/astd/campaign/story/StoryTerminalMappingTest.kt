package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.ui.WorkOrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工单终端可见状态映射（[StoryTerminalMapping]）验证：
 * 结清优先于执行中优先于门槛判定；门槛未满足且未结清的单不挂出（null）。
 */
class StoryTerminalMappingTest {

    private val prologueDef = MainBounties.defsByKey.getValue(MainBounties.KEY_PROLOGUE)

    @Test
    fun `门槛未满足且未结清时不挂出`() {
        val status = StoryTerminalMapping.visibleStatus(
            prologueDef,
            succeeded = emptySet(),
            activeAccepted = emptySet(),
            gating = { false },
        )
        assertNull(status)
    }

    @Test
    fun `门槛满足后为可接取`() {
        val status = StoryTerminalMapping.visibleStatus(
            prologueDef,
            succeeded = emptySet(),
            activeAccepted = emptySet(),
            gating = { it == BountyKeys.MEM_PROLOGUE_DOC_RECEIVED },
        )
        assertEquals(WorkOrderStatus.AVAILABLE, status)
    }

    @Test
    fun `执行中优先于门槛判定`() {
        // 即使门槛读数异常（memory 丢失），已接取的单仍应显示执行中
        val status = StoryTerminalMapping.visibleStatus(
            prologueDef,
            succeeded = emptySet(),
            activeAccepted = setOf(MainBounties.KEY_PROLOGUE),
            gating = { false },
        )
        assertEquals(WorkOrderStatus.ACTIVE, status)
    }

    @Test
    fun `已结清优先于一切状态`() {
        val status = StoryTerminalMapping.visibleStatus(
            prologueDef,
            succeeded = setOf(MainBounties.KEY_PROLOGUE),
            activeAccepted = setOf(MainBounties.KEY_PROLOGUE),
            gating = { false },
        )
        assertEquals(WorkOrderStatus.SETTLED, status)
    }

    @Test
    fun `多门槛单要求全部满足`() {
        val def = MainBounties.defsByKey.getValue("astd_main_c1_b2_a")
        val succeededB1 = setOf("\$astd_main_c1_b1_a", "\$astd_main_c1_b1_b")
        assertNull(
            StoryTerminalMapping.visibleStatus(def, emptySet(), emptySet()) { it in succeededB1 && it == "\$astd_main_c1_b1_a" },
            "批次一只完成一单时批次二不应挂出",
        )
        assertEquals(
            WorkOrderStatus.AVAILABLE,
            StoryTerminalMapping.visibleStatus(def, emptySet(), emptySet()) { it in succeededB1 },
        )
    }

    @Test
    fun `批次可见性：组内至少一单可见才挂出`() {
        val group = MainBounties.groupsById.getValue("c1_b1")
        assertFalse(StoryTerminalMapping.batchVisible(group) { null })
        assertTrue(
            StoryTerminalMapping.batchVisible(group) { key ->
                if (key == "astd_main_c1_b1_a") WorkOrderStatus.AVAILABLE else null
            },
        )
    }

    @Test
    fun `批次可见性：全部结清的批次仍可见`() {
        val group = MainBounties.groupsById.getValue("c4")
        assertTrue(StoryTerminalMapping.batchVisible(group) { WorkOrderStatus.SETTLED })
    }

    @Test
    fun `紫菀合并批次：阶段推进全程批次可见且恰一单处于可执行位`() {
        val group = MainBounties.groupsById.getValue("c2_zw")
        val keys = MainBounties.groupMembers.getValue("c2_zw")
        assertEquals(4, keys.size, "紫菀合并单应拆 4 个内部阶段")

        var succeeded = emptySet<String>()
        var delivered = emptySet<String>()
        fun statusOf(key: String) = StoryTerminalMapping.visibleStatus(
            MainBounties.defsByKey.getValue(key), succeeded, emptySet(),
        ) { it in delivered }

        // 第一章加急件未交付：四阶段全不挂出，批次不可见
        assertEquals(listOf(null, null, null, null), keys.map(::statusOf))
        assertFalse(StoryTerminalMapping.batchVisible(group, ::statusOf))

        // 加急件交付：仅阶段一挂出
        delivered = delivered + "\$astd_main_c1_b3"
        assertEquals(
            listOf(WorkOrderStatus.AVAILABLE, null, null, null),
            keys.map(::statusOf),
        )
        assertTrue(StoryTerminalMapping.batchVisible(group, ::statusOf))

        // 逐阶段结清：已结清段显示 SETTLED，紧邻下一段转为可接取，其余不挂出
        for (i in keys.indices) {
            succeeded = succeeded + keys[i]
            delivered = delivered + "\$${keys[i]}"
            val expected = keys.indices.map { j ->
                when {
                    j <= i -> WorkOrderStatus.SETTLED
                    j == i + 1 -> WorkOrderStatus.AVAILABLE
                    else -> null
                }
            }
            assertEquals(expected, keys.map(::statusOf), "阶段 ${keys[i]} 结清后可见性不符")
            assertTrue(StoryTerminalMapping.batchVisible(group, ::statusOf), "合并批次全程应可见")
        }
    }
}
