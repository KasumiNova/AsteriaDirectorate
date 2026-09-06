package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalDataMapperTest {

    private fun order(
        key: String,
        lifecycle: OrderLifecycle,
        groupId: String = "g_$key",
        stageIndex: Int = 0,
        stageCount: Int = 1,
        nodeDrivenStages: Int = 0,
        quotedReward: Int? = 1000,
        requiredItemId: String? = "deliverable",
        hasRequiredItem: Boolean = true,
        chapterCleared: Int? = null,
    ) = OrderSnapshot(
        key = key,
        serial = "SN-$key",
        i18nId = "i18n_$key",
        summary = "summary_$key",
        groupId = groupId,
        chapter = 1,
        threatTier = 3,
        dangerOmitted = false,
        clauseCount = 0,
        stageIndex = stageIndex,
        stageCount = stageCount,
        nodeDrivenStages = nodeDrivenStages,
        lifecycle = lifecycle,
        quotedReward = quotedReward,
        requiredItemId = requiredItemId,
        hasRequiredItem = hasRequiredItem,
        chapterCleared = chapterCleared,
    )

    private fun archive(id: String, layer: Int, unlocked: Boolean) = ArchiveSnapshot(id, layer, unlocked)

    private fun snapshot(
        orders: List<OrderSnapshot> = emptyList(),
        groupTotals: Map<String, Int> = emptyMap(),
        archives: List<ArchiveSnapshot> = emptyList(),
        ledger: List<LedgerSnapshot> = emptyList(),
        chapter: Int = 3,
    ) = TerminalSnapshot(
        orders = orders,
        groupTotals = groupTotals,
        chapter = chapter,
        currentDate = "c206.03.01",
        liquidationProgress = 60f,
        contractorLevel = 2,
        settledCount = ledger.count { it.kind == LedgerKind.ORDER },
        ledger = ledger,
        archives = archives,
    )

    @Test
    fun `生命周期四态映射为状态章`() {
        assertEquals(OrderStatus.AVAILABLE, TerminalDataMapper.statusOf(OrderLifecycle.POSTABLE))
        assertEquals(OrderStatus.ACTIVE, TerminalDataMapper.statusOf(OrderLifecycle.POSTED))
        assertEquals(OrderStatus.AWAITING_SETTLEMENT, TerminalDataMapper.statusOf(OrderLifecycle.DESTROYED))
        assertEquals(OrderStatus.SETTLED, TerminalDataMapper.statusOf(OrderLifecycle.SETTLED))
    }

    @Test
    fun `批次按首现顺序分组且结清进度分母取注册组内总数`() {
        val snap = snapshot(
            orders = listOf(
                order("a1", OrderLifecycle.POSTABLE, groupId = "ga"),
                order("b1", OrderLifecycle.POSTED, groupId = "gb"),
                order("a2", OrderLifecycle.SETTLED, groupId = "ga"),
                order("c1", OrderLifecycle.SETTLED, groupId = "gc"),
            ),
            groupTotals = mapOf("ga" to 3, "gb" to 1, "gc" to 1),
        )

        val batches = TerminalDataMapper.mapOrders(snap)

        assertEquals(listOf("ga", "gb", "gc"), batches.map { it.groupId })
        val ga = batches[0]
        assertEquals(listOf("a1", "a2"), ga.orders.map { it.key })
        assertEquals(1, ga.settled)
        assertEquals(3, ga.total)
        assertFalse(ga.settled == ga.total)
        assertTrue(batches[2].settled == batches[2].total)
    }

    @Test
    fun `主按钮动作随状态与交割物切换`() {
        fun actionOf(lifecycle: OrderLifecycle, hasItem: Boolean = true): TerminalAction =
            TerminalDataMapper.primaryActionOf(
                TerminalDataMapper.mapOrders(
                    snapshot(listOf(order("k", lifecycle, hasRequiredItem = hasItem))),
                ).single().orders.single(),
            )

        assertEquals(TerminalAction.ACCEPT, actionOf(OrderLifecycle.POSTABLE))
        assertEquals(TerminalAction.TRACK, actionOf(OrderLifecycle.POSTED))
        assertEquals(TerminalAction.SETTLE, actionOf(OrderLifecycle.DESTROYED, hasItem = true))
        // D9 自愈：缺交割物的待核销单仍可核销（受理时按底档补发）
        assertEquals(TerminalAction.SETTLE, actionOf(OrderLifecycle.DESTROYED, hasItem = false))
        assertEquals(TerminalAction.NONE, actionOf(OrderLifecycle.SETTLED))
        assertEquals(TerminalAction.NONE, TerminalDataMapper.primaryActionOf(null))
    }

    @Test
    fun `引力节点线合并为单卡并外露阶段进度`() {
        val view = TerminalDataMapper.mapOrders(
            snapshot(listOf(order("zw1", OrderLifecycle.POSTED, stageIndex = 1, stageCount = 4, nodeDrivenStages = 3))),
        ).single().orders.single()

        assertTrue(view.multiStage)
        assertEquals(1, view.stageIndex)
        assertEquals(4, view.stageCount)
        assertEquals(3, view.nodeDrivenStages)

        val single = TerminalDataMapper.mapOrders(
            snapshot(listOf(order("p1", OrderLifecycle.POSTABLE))),
        ).single().orders.single()
        assertFalse(single.multiStage)
    }

    @Test
    fun `档案按层升序分组且存目条目保持锁定`() {
        val layers = TerminalDataMapper.mapArchives(
            snapshot(
                archives = listOf(
                    archive("a11", 1, unlocked = true),
                    archive("a31", 3, unlocked = true),
                    archive("a12", 1, unlocked = false),
                ),
            ),
        )

        assertEquals(listOf(1, 3), layers.map { it.layer })
        assertEquals(listOf("a11", "a12"), layers[0].entries.map { it.id })
        assertEquals(1, layers[0].unlocked)
        assertEquals(2, layers[0].total)
        assertFalse(layers[0].entries[1].unlocked)
        assertEquals(2, layers[0].entries[1].indexInLayer)
    }

    @Test
    fun `整体映射选中项越界或锁定时回落`() {
        val snap = snapshot(
            orders = listOf(
                order("o1", OrderLifecycle.SETTLED, groupId = "g"),
                order("o2", OrderLifecycle.POSTABLE, groupId = "g"),
            ),
            groupTotals = mapOf("g" to 2),
            archives = listOf(
                archive("a1", 1, unlocked = false),
                archive("a2", 1, unlocked = true),
            ),
        )

        val fallback = TerminalDataMapper.map(snap, TerminalTab.ORDERS, "missing", "missing")
        assertEquals("o1", fallback.selectedOrder?.key)
        assertEquals("a2", fallback.selectedArchive?.id)
        assertEquals(TerminalTab.ORDERS, fallback.tab)
        assertTrue(fallback.showLiquidationTopbar)

        val lockedPick = TerminalDataMapper.map(snap, TerminalTab.ARCHIVES, "o2", "a1")
        assertEquals("o2", lockedPick.selectedOrder?.key)
        assertEquals("a2", lockedPick.selectedArchive?.id)
    }

    @Test
    fun `账户流水原样映射并合计贷方`() {
        val account = TerminalDataMapper.mapAccount(
            snapshot(
                ledger = listOf(
                    LedgerSnapshot("SN-1", 1000, LedgerKind.ORDER),
                    LedgerSnapshot("ga", 2000, LedgerKind.GROUP_BONUS),
                ),
            ),
        )

        assertEquals(2, account.ledger.size)
        assertEquals(3000, account.totalPayout)
        assertEquals(LedgerKind.GROUP_BONUS, account.ledger[1].kind)
        assertEquals(2, account.contractorLevel)
        assertEquals(1, account.settledCount)

        assertTrue(TerminalDataMapper.mapAccount(snapshot()).ledger.isEmpty())
        assertEquals(0, TerminalDataMapper.mapAccount(snapshot()).totalPayout)
    }

    @Test
    fun `清算进度顶栏于第三章起常驻至结局后`() {
        assertFalse(TerminalDataMapper.showLiquidationTopbar(snapshot(chapter = 2)))
        assertTrue(TerminalDataMapper.showLiquidationTopbar(snapshot(chapter = 3)))
        assertTrue(TerminalDataMapper.showLiquidationTopbar(snapshot(chapter = 4)))
        // 四章结清后 currentChapter=5（第五章「归档」结局流程），读数仍常驻
        assertTrue(TerminalDataMapper.showLiquidationTopbar(snapshot(chapter = 5)))
        assertFalse(
            TerminalDataMapper.showLiquidationTopbar(snapshot(chapter = 6)),
            "超出主线章节口径（0..5）的值不显示顶栏",
        )
    }

    @Test
    fun `工单视图透传列表摘要与章末结清记录`() {
        val orders = TerminalDataMapper.mapOrders(
            snapshot(
                listOf(
                    order("o1", OrderLifecycle.SETTLED, chapterCleared = 1),
                    order("o2", OrderLifecycle.POSTABLE),
                ),
            ),
        ).flatMap { it.orders }

        assertEquals("summary_o1", orders[0].summary)
        assertEquals(1, orders[0].chapterCleared)
        assertEquals("summary_o2", orders[1].summary)
        assertNull(orders[1].chapterCleared)
    }

    @Test
    fun `glitch 由结清章节决定`() {
        assertNull(TerminalDataMapper.glitchForChapter(null))
        assertNull(TerminalDataMapper.glitchForChapter(0))
        assertEquals(GlitchSpec.TARGET_STATUS, TerminalDataMapper.glitchForChapter(1))
        assertNull(TerminalDataMapper.glitchForChapter(2))
        assertEquals(GlitchSpec.HALF_LINE, TerminalDataMapper.glitchForChapter(3))
        assertNull(TerminalDataMapper.glitchForChapter(4))
    }
}
