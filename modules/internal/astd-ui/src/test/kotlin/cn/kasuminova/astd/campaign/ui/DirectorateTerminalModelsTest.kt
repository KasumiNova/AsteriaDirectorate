package cn.kasuminova.astd.campaign.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分局终端数据模型的纯逻辑测试（批次结清进度、危险等级徽记、档案分层、快照检索、清算进度字段）。
 */
class DirectorateTerminalModelsTest {

    private fun order(id: String, status: WorkOrderStatus, tier: Int = 1) = WorkOrder(
        id = id,
        code = "XW-c206-$id",
        title = "工单 $id",
        threatTier = tier,
        targetSummary = "目标 $id",
        status = status,
        commission = "委托事项 $id",
    )

    @Test
    fun `batch settlement progress counts only settled orders`() {
        val batch = WorkOrderBatch(
            id = "b1",
            chapterTitle = "第一章",
            title = "批次一",
            orders = listOf(
                order("a", WorkOrderStatus.SETTLED),
                order("b", WorkOrderStatus.ACTIVE),
                order("c", WorkOrderStatus.AVAILABLE),
                order("d", WorkOrderStatus.READY_TO_SETTLE),
            ),
        )
        assertEquals(1, batch.settledCount, "可交付（READY_TO_SETTLE）尚未核销，不计入结清进度")
        assertEquals(4, batch.totalCount)
        assertFalse(batch.isCleared)

        val cleared = batch.copy(
            orders = batch.orders.map { it.copy(status = WorkOrderStatus.SETTLED) },
        )
        assertTrue(cleared.isCleared)
        assertEquals(4, cleared.settledCount)

        // 空批次不算结清（没有工单可结清）。
        assertFalse(batch.copy(orders = emptyList()).isCleared)
    }

    @Test
    fun `threat tier badge renders roman numerals up to VI and unrated never clamps to V`() {
        assertEquals("I", threatTierBadge(1))
        assertEquals("II", threatTierBadge(2))
        assertEquals("III", threatTierBadge(3))
        assertEquals("IV", threatTierBadge(4))
        assertEquals("V", threatTierBadge(5))
        assertEquals("VI", threatTierBadge(6), "第三章把等级上限撑到六级，VI 不得钳回 V")
        assertEquals(THREAT_TIER_UNRATED_BADGE, threatTierBadge(THREAT_TIER_UNRATED), "第四章等级从缺，不得显示为 V")
        assertNotEquals("V", threatTierBadge(0))
        assertEquals(THREAT_TIER_UNRATED_BADGE, threatTierBadge(-1), "契约外取值按从缺呈现")
        assertEquals(THREAT_TIER_UNRATED_BADGE, threatTierBadge(7), "超出等级表的取值按从缺呈现")
    }

    @Test
    fun `archive layers are grouped and sorted by layer`() {
        val snapshot = TerminalSnapshot(
            contractorId = "C-001",
            contractorLevel = 1,
            registerCycle = "c+206",
            archives = listOf(
                ArchiveEntry("a3", layer = 2, title = "三", body = listOf("x"), unlocked = false),
                ArchiveEntry("a1", layer = 1, title = "一", body = listOf("x"), unlocked = true),
                ArchiveEntry("a2", layer = 1, title = "二", body = listOf("x"), unlocked = false),
            ),
        )
        val layers = snapshot.archiveLayers
        assertEquals(listOf(1, 2), layers.keys.toList())
        assertEquals(listOf("a1", "a2"), layers.getValue(1).map { it.id })
        assertEquals(listOf("a3"), layers.getValue(2).map { it.id })
    }

    @Test
    fun `snapshot order lookup spans all batches`() {
        val snapshot = TerminalSnapshot(
            contractorId = "C-001",
            contractorLevel = 1,
            registerCycle = "c+206",
            batches = listOf(
                WorkOrderBatch("b1", "第一章", "批次一", listOf(order("a", WorkOrderStatus.SETTLED))),
                WorkOrderBatch("b2", "第一章", "批次二", listOf(order("b", WorkOrderStatus.AVAILABLE, tier = 3))),
            ),
        )
        assertEquals(2, snapshot.allOrders.size)
        assertEquals("XW-c206-b", snapshot.findOrder("b")?.code)
        assertEquals(WorkOrderStatus.SETTLED, snapshot.findOrder("a")?.status)
        assertNull(snapshot.findOrder("missing"))
    }

    @Test
    fun `liquidation progress is script driven and hidden before chapter two ends`() {
        // 第二章末之前：null —— UI 完全不渲染清算行。
        val early = TerminalSnapshot(contractorId = "C-001", contractorLevel = 1, registerCycle = "c+206")
        assertNull(early.liquidationProgress)

        // 之后：显示剧情脚本推进值（如第二章末 97.3%），与工单完成数/总数无关。
        val later = TerminalSnapshot(
            contractorId = "C-001",
            contractorLevel = 1,
            registerCycle = "c+206",
            batches = listOf(
                WorkOrderBatch("b1", "第二章", "批次一", listOf(order("a", WorkOrderStatus.SETTLED))),
            ),
            liquidationProgress = 97.3f,
        )
        assertEquals(97.3f, later.liquidationProgress)
        assertEquals(1, later.batches[0].settledCount, "工单结清计数独立存在，但不充当清算进度")
    }
}
