package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 第五章「归档」结局的终端映射：结局事务阶段推导、无限赏金工单视图透传（词缀表/文书变量）。
 */
class TerminalEndingMapperTest {

    private fun snapshot(ending: EndingSnapshot) = TerminalSnapshot(
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

    @Test
    fun `结局事务阶段推导全链`() {
        assertEquals(EndingStage.NONE, TerminalDataMapper.endingStageOf(EndingSnapshot()))
        assertEquals(
            EndingStage.AWAITING_SIGN,
            TerminalDataMapper.endingStageOf(EndingSnapshot(archivalPending = true)),
        )
        assertEquals(
            EndingStage.AWAITING_EXECUTOR_SPEC,
            TerminalDataMapper.endingStageOf(EndingSnapshot(archivalPending = true, archivalChoice = ArchivalChoice.PUBLISH)),
        )
        assertEquals(
            EndingStage.AWAITING_COMMAND_SHIP,
            TerminalDataMapper.endingStageOf(
                EndingSnapshot(
                    archivalChoice = ArchivalChoice.PUBLISH,
                    executorIssued = true,
                    executorSpec = ExecutorSpec.COMBAT,
                ),
            ),
        )
        assertEquals(
            EndingStage.COMPLETE,
            TerminalDataMapper.endingStageOf(
                EndingSnapshot(
                    archivalChoice = ArchivalChoice.PUBLISH,
                    executorIssued = true,
                    executorSpec = ExecutorSpec.COMBAT,
                    commandShipName = "旗舰",
                ),
            ),
        )
        assertEquals(
            EndingStage.AWAITING_ADMIN_MARKET,
            TerminalDataMapper.endingStageOf(
                EndingSnapshot(
                    archivalChoice = ArchivalChoice.SEAL,
                    executorIssued = true,
                    executorSpec = ExecutorSpec.ADMIN,
                ),
            ),
        )
        assertEquals(
            EndingStage.COMPLETE,
            TerminalDataMapper.endingStageOf(
                EndingSnapshot(
                    archivalChoice = ArchivalChoice.SEAL,
                    executorIssued = true,
                    executorSpec = ExecutorSpec.ADMIN,
                    adminMarketName = "殖民地",
                ),
            ),
        )
    }

    @Test
    fun `map透出ending视图与选中态`() {
        val ending = EndingSnapshot(archivalPending = true)
        val view = TerminalDataMapper.map(
            snapshot(ending), TerminalTab.ACCOUNT, null, null,
            endingDoc = ArchivalChoice.TRADE, endingTradeFaction = "hegemony",
        )
        assertEquals(EndingStage.AWAITING_SIGN, view.ending.stage)
        assertEquals(ArchivalChoice.TRADE, view.ending.selectedDoc)
        assertEquals("hegemony", view.ending.selectedTradeFaction)
        assertTrue(view.ending.snapshot.archivalPending)
    }

    @Test
    fun `无限赏金工单视图透传词缀表与文书变量`() {
        val order = OrderSnapshot(
            key = "astd_infinite_0_1",
            serial = "WG-c209-801／清除-0001",
            i18nId = "infinite",
            summary = "清除海盗活动集群",
            groupId = "indefinite_contract",
            chapter = 5,
            threatTier = 4,
            dangerOmitted = false,
            clauseCount = 0,
            stageIndex = 0,
            stageCount = 1,
            nodeDrivenStages = 0,
            lifecycle = OrderLifecycle.POSTABLE,
            quotedReward = 800_000,
            requiredItemId = null,
            hasRequiredItem = true,
            affixIds = listOf("astd_affix_ironclad_plating", "astd_affix_grid_deepening"),
            descVars = mapOf("serial" to "WG-c209-801／清除-0001", "faction" to "海盗", "fp" to "2300"),
        )
        val snap = TerminalSnapshot(
            orders = listOf(order),
            groupTotals = mapOf("indefinite_contract" to 3),
            chapter = 5,
            currentDate = "c209.01.01",
            liquidationProgress = 100f,
            contractorLevel = 5,
            settledCount = 14,
            ledger = emptyList(),
            archives = emptyList(),
        )
        val view = TerminalDataMapper.map(snap, TerminalTab.ORDERS, null, null)
        val batch = view.batches.single()
        assertEquals("indefinite_contract", batch.groupId)
        assertEquals(3, batch.total)
        val row = batch.orders.single()
        assertEquals(OrderStatus.AVAILABLE, row.status)
        assertEquals(listOf("astd_affix_ironclad_plating", "astd_affix_grid_deepening"), row.affixIds)
        assertEquals("海盗", row.descVars["faction"])
        assertEquals(800_000, row.reward)
    }

    @Test
    fun `签收视图动作路由不受影响`() {
        val order = OrderSnapshot(
            key = "astd_infinite_0_1", serial = "s", i18nId = "infinite", summary = "摘要",
            groupId = "indefinite_contract",
            chapter = 5, threatTier = 2, dangerOmitted = false, clauseCount = 0,
            stageIndex = 0, stageCount = 1, nodeDrivenStages = 0,
            lifecycle = OrderLifecycle.DESTROYED, quotedReward = 500_000,
            requiredItemId = null, hasRequiredItem = true,
        )
        val view = TerminalDataMapper.map(
            TerminalSnapshot(
                orders = listOf(order), groupTotals = mapOf("indefinite_contract" to 3), chapter = 5,
                currentDate = "c209.01.01", liquidationProgress = 100f, contractorLevel = 5,
                settledCount = 14, ledger = emptyList(), archives = emptyList(),
            ),
            TerminalTab.ORDERS, null, null,
        )
        val row = assertNotNull(view.selectedOrder)
        assertEquals(OrderStatus.AWAITING_SETTLEMENT, row.status)
        assertEquals(TerminalAction.SETTLE, TerminalDataMapper.primaryActionOf(row), "无限赏金无交割物要求，已击毁即可核销")
        assertFalse(view.ending.snapshot.archivalPending)
    }
}
