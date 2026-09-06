package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalControllerTest {

    private class StubBackend(var snap: TerminalSnapshot) : BranchTerminalBackend {
        var postResult = true
        var trackResult = true
        var settleOutcome: SettleOutcome = SettleOutcome(false, "", rejectReason = "unset")
        val posted = mutableListOf<String>()
        val tracked = mutableListOf<String>()
        val settled = mutableListOf<String>()

        override fun snapshot(): TerminalSnapshot = snap
        override fun postOrder(key: String): Boolean {
            posted += key
            return postResult
        }

        override fun trackOrder(key: String): Boolean {
            tracked += key
            return trackResult
        }

        override fun settleOrder(key: String): SettleOutcome {
            settled += key
            return settleOutcome
        }

        var signOutcome: NarrativeOutcome? = null
        var issueOutcome: NarrativeOutcome? = null
        val signed = mutableListOf<Pair<ArchivalChoice, String?>>()
        val issued = mutableListOf<ExecutorSpec>()
        val assignedShips = mutableListOf<String>()
        val appointedMarkets = mutableListOf<String>()

        override fun signArchival(choice: ArchivalChoice, tradeFactionId: String?): NarrativeOutcome? {
            signed += choice to tradeFactionId
            return signOutcome
        }

        override fun issueExecutor(spec: ExecutorSpec): NarrativeOutcome? {
            issued += spec
            return issueOutcome
        }

        override fun assignCommandShip(memberId: String): Boolean {
            assignedShips += memberId
            return true
        }

        override fun appointAdmin(marketId: String): Boolean {
            appointedMarkets += marketId
            return true
        }
    }

    private fun order(
        key: String,
        lifecycle: OrderLifecycle,
        groupId: String = "g1",
        quotedReward: Int? = 8000,
        hasRequiredItem: Boolean = true,
        chapterCleared: Int? = null,
    ) = OrderSnapshot(
        key = key,
        serial = "SN-$key",
        i18nId = "i18n_$key",
        summary = "summary_$key",
        groupId = groupId,
        chapter = 1,
        threatTier = 2,
        dangerOmitted = false,
        clauseCount = 0,
        stageIndex = 0,
        stageCount = 1,
        nodeDrivenStages = 0,
        lifecycle = lifecycle,
        quotedReward = quotedReward,
        requiredItemId = "deliverable",
        hasRequiredItem = hasRequiredItem,
        chapterCleared = chapterCleared,
    )

    private fun snapshotOf(
        vararg orders: OrderSnapshot,
        ledger: List<LedgerSnapshot> = emptyList(),
    ) = TerminalSnapshot(
        orders = orders.toList(),
        groupTotals = orders.groupingBy { it.groupId }.eachCount(),
        chapter = 3,
        currentDate = "c206.03.01",
        liquidationProgress = 60f,
        contractorLevel = 2,
        settledCount = 0,
        ledger = ledger,
        archives = emptyList(),
    )

    private fun <T : TerminalEffect> List<TerminalEffect>.singleOf(type: Class<T>): T =
        filterIsInstance(type).also { assertEquals(1, it.size, "期望唯一 ${type.simpleName}：$this") }.single()

    private inline fun <reified T : TerminalEffect> List<TerminalEffect>.singleOf(): T =
        singleOf(T::class.java)

    @Test
    fun `切换 tab 刷新顶栏列表详情且同 tab 为空操作`() {
        val controller = TerminalController(StubBackend(snapshotOf()))
        assertTrue(controller.selectTab(TerminalTab.ORDERS).isEmpty())

        val effects = controller.selectTab(TerminalTab.ARCHIVES)
        assertEquals(TerminalTab.ARCHIVES, controller.tab)
        assertEquals(
            listOf(
                TerminalEffect.PlaySound(TerminalSound.TAB),
                TerminalEffect.RefreshTopbar(),
                TerminalEffect.RebuildList(),
                TerminalEffect.ReprintDetail(),
            ),
            effects,
        )
        assertTrue(effects.all { it.delaySeconds == 0f })
    }

    @Test
    fun `选中工单触发列表重建与详情重印`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.POSTABLE)))
        val controller = TerminalController(backend)

        val effects = controller.selectOrder("o1")
        assertEquals("o1", controller.selectedOrderKey)
        assertEquals(listOf(TerminalEffect.RebuildList(), TerminalEffect.ReprintDetail()), effects)
        assertTrue(controller.selectOrder("o1").isEmpty())
    }

    @Test
    fun `接取工单触发受理章并在章面落定后刷新`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.POSTABLE)))
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertEquals(listOf("o1"), backend.posted)
        assertEquals(TerminalSound.STAMP, effects.singleOf<TerminalEffect.PlaySound>().sound)
        assertEquals(StampKind.ACCEPTED, effects.singleOf<TerminalEffect.StampSlam>().kind)
        assertEquals(0f, effects.singleOf<TerminalEffect.StampSlam>().delaySeconds)
        assertEquals(StampTimeline.SETTLE, effects.singleOf<TerminalEffect.RebuildList>().delaySeconds)
        assertEquals(StampTimeline.SETTLE, effects.singleOf<TerminalEffect.ReprintDetail>().delaySeconds)
        assertEquals(StampTimeline.SETTLE, effects.singleOf<TerminalEffect.RefreshTopbar>().delaySeconds)
    }

    @Test
    fun `接取失败仅播放拒绝音`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.POSTABLE)))
        backend.postResult = false
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertEquals(listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED)), effects)
    }

    @Test
    fun `追踪目标成功播放标记音`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.POSTED)))
        val controller = TerminalController(backend)

        assertEquals(listOf(TerminalEffect.PlaySound(TerminalSound.TRACK)), controller.pressPrimary())
        assertEquals(listOf("o1"), backend.tracked)

        backend.trackResult = false
        assertEquals(listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED)), controller.pressPrimary())
    }

    @Test
    fun `核销成功按序触发盖章刷新回执与一章末 glitch`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(
            success = true,
            orderKey = "o1",
            payout = 8000,
            chapterCleared = 1,
            liquidationProgress = 20f,
        )
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertEquals(listOf("o1"), backend.settled)
        assertEquals(StampKind.SETTLED, effects.singleOf<TerminalEffect.StampSlam>().kind)
        assertEquals(StampTimeline.SETTLE, effects.singleOf<TerminalEffect.RebuildList>().delaySeconds)

        val receipt = effects.singleOf<TerminalEffect.ShowReceipt>()
        assertEquals(StampTimeline.SETTLE + 0.1f, receipt.delaySeconds)
        assertNull(receipt.glitchSpec)
        assertEquals(8000, receipt.receipt.payout)
        assertEquals(1, receipt.receipt.chapterCleared)
        assertEquals("c206.03.01", receipt.receipt.date)

        val glitch = effects.singleOf<TerminalEffect.GlitchFx>()
        assertEquals(GlitchSpec.TARGET_STATUS, glitch.spec)
        // 瞬替在列表重建（SETTLE）完成之后触发，目标行即重建后的已核销行
        assertEquals(StampTimeline.SETTLE + 0.3f, glitch.delaySeconds)
        assertTrue(glitch.delaySeconds > effects.singleOf<TerminalEffect.RebuildList>().delaySeconds)

        val sounds = effects.filterIsInstance<TerminalEffect.PlaySound>()
        assertEquals(
            listOf(TerminalSound.STAMP, TerminalSound.RECEIPT_OPEN, TerminalSound.GLITCH),
            sounds.map { it.sound },
        )
    }

    @Test
    fun `一章末 glitch 瞬替目标为本批已核销行而非正在核销的单`() {
        // 快照模拟核销完成后的状态：o1 已核销（本批首条已核销行），o2 为同批已核销存量
        val backend = StubBackend(
            snapshotOf(
                order("o2", OrderLifecycle.SETTLED),
                order("o1", OrderLifecycle.DESTROYED),
            ),
        )
        backend.settleOutcome = SettleOutcome(
            success = true,
            orderKey = "o1",
            payout = 8000,
            chapterCleared = 1,
            liquidationProgress = 20f,
        )
        val controller = TerminalController(backend)
        controller.selectOrder("o1")

        val effects = controller.pressPrimary()

        val glitch = effects.singleOf<TerminalEffect.GlitchFx>()
        assertEquals(GlitchSpec.TARGET_STATUS, glitch.spec)
        // 目标取本批首条已核销（灰章）行：doc 05「已结清的工单列表里某一份」
        assertEquals("o2", glitch.targetOrderKey)
    }

    @Test
    fun `一章末 glitch 本批无已核销行时兜底全表已核销行`() {
        val backend = StubBackend(
            snapshotOf(
                order("other", OrderLifecycle.SETTLED, groupId = "g0"),
                order("o1", OrderLifecycle.DESTROYED),
            ),
        )
        backend.settleOutcome = SettleOutcome(true, "o1", payout = 8000, chapterCleared = 1)
        val controller = TerminalController(backend)
        controller.selectOrder("o1")

        val glitch = controller.pressPrimary().singleOf<TerminalEffect.GlitchFx>()
        assertEquals("other", glitch.targetOrderKey)
    }

    @Test
    fun `一章末 glitch 全表无已核销行时目标为空`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(true, "o1", payout = 8000, chapterCleared = 1)
        val controller = TerminalController(backend)

        val glitch = controller.pressPrimary().singleOf<TerminalEffect.GlitchFx>()
        assertNull(glitch.targetOrderKey)
    }

    @Test
    fun `三章末核销将卡死行注入回执打印队列`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(
            success = true,
            orderKey = "o1",
            payout = 8000,
            chapterCleared = 3,
            liquidationProgress = 60f,
        )
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertEquals(GlitchSpec.HALF_LINE, effects.singleOf<TerminalEffect.ShowReceipt>().glitchSpec)
        assertEquals(GlitchSpec.HALF_LINE, effects.singleOf<TerminalEffect.GlitchFx>().spec)
    }

    @Test
    fun `非钩章节核销无 glitch`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(success = true, orderKey = "o1", payout = 8000, chapterCleared = 2)
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertTrue(effects.filterIsInstance<TerminalEffect.GlitchFx>().isEmpty())
        assertNull(effects.singleOf<TerminalEffect.ShowReceipt>().glitchSpec)
    }

    @Test
    fun `核销失败仅播放拒绝音`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(false, "o1", rejectReason = "missing_item")
        val controller = TerminalController(backend)

        assertEquals(listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED)), controller.pressPrimary())
    }

    @Test
    fun `缺交割物的待核销单仍可核销`() {
        // D9 自愈：主操作保持核销入口，受理时后端按底档补发交割物
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED, hasRequiredItem = false)))
        backend.settleOutcome = SettleOutcome(success = true, orderKey = "o1", payout = 8000)
        val controller = TerminalController(backend)

        val effects = controller.pressPrimary()

        assertEquals(listOf("o1"), backend.settled)
        assertTrue(effects.filterIsInstance<TerminalEffect.PlaySound>()
            .none { it.sound == TerminalSound.REJECTED })
    }

    @Test
    fun `重打回执以锁定报价回放并补全结清奖金与章末确认行`() {
        val backend = StubBackend(
            snapshotOf(
                order("o1", OrderLifecycle.SETTLED, quotedReward = 8000, chapterCleared = 1),
                ledger = listOf(
                    LedgerSnapshot("SN-o1", 8000, LedgerKind.ORDER),
                    LedgerSnapshot("g1", 20000, LedgerKind.GROUP_BONUS),
                ),
            ),
        )
        val controller = TerminalController(backend)

        val effects = controller.pressReplayReceipt()

        assertEquals(TerminalSound.RECEIPT_OPEN, effects.singleOf<TerminalEffect.PlaySound>().sound)
        val receipt = effects.singleOf<TerminalEffect.ShowReceipt>()
        assertNull(receipt.glitchSpec)
        assertEquals(8000, receipt.receipt.payout)
        assertEquals("g1", receipt.receipt.groupBonusId)
        assertEquals(20000, receipt.receipt.groupBonusAmount)
        assertEquals(1, receipt.receipt.chapterCleared, "触发章末的单重打回执须与原结算回执一致")
    }

    @Test
    fun `未触发章末的已核销单重打回执无章末确认行`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.SETTLED, quotedReward = 8000)))
        val controller = TerminalController(backend)

        val receipt = controller.pressReplayReceipt().singleOf<TerminalEffect.ShowReceipt>()
        assertNull(receipt.receipt.chapterCleared)
    }

    @Test
    fun `结算回执与叙事回执在弹出前屏蔽终端关闭`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.DESTROYED)))
        backend.settleOutcome = SettleOutcome(true, "o1", payout = 8000, chapterCleared = 2)
        val controller = TerminalController(backend)

        val settleEffects = controller.pressPrimary()
        val receipt = settleEffects.singleOf<TerminalEffect.ShowReceipt>()
        assertTrue(receipt.blocksClose, "回执弹出前排队的 ShowReceipt 须屏蔽关闭")
        assertTrue(receipt.delaySeconds > 0f, "回执在盖章落定后弹出，屏蔽窗口真实存在")
        settleEffects.filterNot { it == receipt }.forEach {
            assertFalse(it.blocksClose, "盖章/刷新系特效不屏蔽关闭：$it")
        }

        // 非回执系操作（切 tab / 接取）不产生屏蔽窗口
        assertTrue(controller.selectTab(TerminalTab.ARCHIVES).none { it.blocksClose })

        backend.snap = snapshotOf(order("o2", OrderLifecycle.POSTABLE))
        assertTrue(controller.pressPrimary().none { it.blocksClose })
    }

    @Test
    fun `未核销单不可重打回执`() {
        val backend = StubBackend(snapshotOf(order("o1", OrderLifecycle.POSTABLE)))
        val controller = TerminalController(backend)

        assertTrue(controller.pressReplayReceipt().isEmpty())
    }
}
