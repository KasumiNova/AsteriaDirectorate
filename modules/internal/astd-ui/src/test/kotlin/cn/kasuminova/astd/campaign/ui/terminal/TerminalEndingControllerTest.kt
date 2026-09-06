package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 第五章结局签署/签发/任命的控制器事件序列（stub 后端；不动效装配层）。
 */
class TerminalEndingControllerTest {

    private class StubBackend(var snap: TerminalSnapshot) : BranchTerminalBackend {
        var signOutcome: NarrativeOutcome? = null
        var issueOutcome: NarrativeOutcome? = null
        var assignResult = true
        var appointResult = true
        val signed = mutableListOf<Pair<ArchivalChoice, String?>>()
        val issued = mutableListOf<ExecutorSpec>()

        override fun snapshot(): TerminalSnapshot = snap
        override fun postOrder(key: String): Boolean = false
        override fun trackOrder(key: String): Boolean = false
        override fun settleOrder(key: String): SettleOutcome = SettleOutcome(false, key, rejectReason = "unset")
        override fun signArchival(choice: ArchivalChoice, tradeFactionId: String?): NarrativeOutcome? {
            signed += choice to tradeFactionId
            return signOutcome
        }

        override fun issueExecutor(spec: ExecutorSpec): NarrativeOutcome? {
            issued += spec
            return issueOutcome
        }

        override fun assignCommandShip(memberId: String): Boolean = assignResult
        override fun appointAdmin(marketId: String): Boolean = appointResult
    }

    private fun snap(ending: EndingSnapshot) = TerminalSnapshot(
        orders = emptyList(),
        groupTotals = emptyMap(),
        chapter = 5,
        currentDate = "c209.01.01",
        liquidationProgress = 100f,
        contractorLevel = 5,
        settledCount = 14,
        ledger = emptyList(),
        archives = emptyList(),
        ending = ending,
    )

    private val pages = listOf(
        NarrativeReceiptView("t1", listOf(NarrativeLine("l1"))),
        NarrativeReceiptView("t2", listOf(NarrativeLine("l2")), amount = 100, amountKey = "a"),
    )

    @Test
    fun `未选文书或交易未选对象时签署被拒`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalPending = true)))
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)

        var effects = controller.confirmSign()
        assertEquals(1, effects.size)
        assertEquals(TerminalSound.REJECTED, (effects.single() as TerminalEffect.PlaySound).sound)
        assertTrue(backend.signed.isEmpty(), "未选文书不得回注后端")

        controller.selectArchivalDoc(ArchivalChoice.TRADE)
        effects = controller.confirmSign()
        assertEquals(TerminalSound.REJECTED, (effects.single() as TerminalEffect.PlaySound).sound)
        assertTrue(backend.signed.isEmpty(), "交易选未选对象不得回注后端")
    }

    @Test
    fun `签署成功序列为盖章加叙事回执链且清空选中态`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalPending = true)))
        backend.signOutcome = NarrativeOutcome(pages)
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)

        controller.selectArchivalDoc(ArchivalChoice.TRADE)
        controller.selectTradeFaction("hegemony")
        val effects = controller.confirmSign()

        assertEquals(1, backend.signed.size)
        assertEquals(ArchivalChoice.TRADE, backend.signed.single().first)
        assertEquals("hegemony", backend.signed.single().second)
        assertEquals(null, controller.archivalDoc as ArchivalChoice?)
        assertEquals(null, controller.selectedTradeFactionId as String?)

        val show = effects.filterIsInstance<TerminalEffect.ShowNarrative>().single()
        assertEquals(pages, show.pages)
        assertTrue(show.delaySeconds > 0f, "回执链在章面落稳后弹入")
        assertTrue(effects.any { it is TerminalEffect.StampSlam })
        assertTrue(effects.any { it is TerminalEffect.RefreshTopbar })
    }

    @Test
    fun `后端拒绝签署时只回拒音`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalPending = true)))
        backend.signOutcome = null
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)
        controller.selectArchivalDoc(ArchivalChoice.SEAL)
        val effects = controller.confirmSign()
        assertEquals(TerminalSound.REJECTED, (effects.single() as TerminalEffect.PlaySound).sound)
    }

    @Test
    fun `签发成功序列为盖章加两页回执链`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalChoice = ArchivalChoice.PUBLISH)))
        backend.issueOutcome = NarrativeOutcome(pages)
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)

        val effects = controller.chooseExecutorSpec(ExecutorSpec.COMBAT)
        assertEquals(listOf(ExecutorSpec.COMBAT), backend.issued)
        val show = effects.filterIsInstance<TerminalEffect.ShowNarrative>().single()
        assertEquals(2, show.pages.size)
    }

    @Test
    fun `签发被拒时只回拒音`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalChoice = ArchivalChoice.PUBLISH)))
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)
        val effects = controller.chooseExecutorSpec(ExecutorSpec.ADMIN)
        assertEquals(TerminalSound.REJECTED, (effects.single() as TerminalEffect.PlaySound).sound)
        assertTrue(effects.none { it is TerminalEffect.ShowNarrative }, "签发被拒不得弹出回执链")
    }

    @Test
    fun `任命动作成功盖章失败拒音`() {
        val backend = StubBackend(snap(EndingSnapshot()))
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)

        val ok = controller.assignShip("m1")
        assertTrue(ok.any { it is TerminalEffect.PlaySound && it.sound == TerminalSound.STAMP })
        assertIs<TerminalEffect.ReprintDetail>(ok.last())

        backend.assignResult = false
        val denied = controller.assignShip("m1")
        assertEquals(TerminalSound.REJECTED, (denied.single() as TerminalEffect.PlaySound).sound)

        val ok2 = controller.appointMarket("market1")
        assertTrue(ok2.any { it is TerminalEffect.PlaySound && it.sound == TerminalSound.STAMP })

        backend.appointResult = false
        val denied2 = controller.appointMarket("market1")
        assertEquals(TerminalSound.REJECTED, (denied2.single() as TerminalEffect.PlaySound).sound)
    }

    @Test
    fun `切换文书时清空交易候选选中态`() {
        val backend = StubBackend(snap(EndingSnapshot(archivalPending = true)))
        val controller = TerminalController(backend, TerminalTab.ACCOUNT)
        controller.selectArchivalDoc(ArchivalChoice.TRADE)
        controller.selectTradeFaction("hegemony")
        assertEquals("hegemony", controller.selectedTradeFactionId)
        controller.selectArchivalDoc(ArchivalChoice.SEAL)
        assertEquals(null, controller.selectedTradeFactionId as String?, "离开 TRADE 文书应清空候选选中态")
    }
}
