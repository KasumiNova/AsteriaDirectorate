package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.bounty.MainlineProgression
import cn.kasuminova.astd.campaign.dialog.story.BranchStationDialog.BranchStationPhase
import cn.kasuminova.astd.campaign.ui.terminal.LedgerKind
import cn.kasuminova.astd.campaign.ui.terminal.OrderLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分局终端快照装配（[BranchTerminalData]）：gating 隐藏、生命周期四态、
 * 报价/交割物注入、批次分母口径、档案解锁注入与流水构成。
 */
class BranchTerminalDataTest {

    private fun snapshotOf(
        state: BountyState,
        pulledCount: Int = 0,
        hasItem: (String) -> Boolean = { true },
    ) = BranchTerminalData.snapshot(
        state, pulledCount, hasItem, "c206.03.01",
        orderSummary = { i18nId, vars ->
            if (vars.isEmpty()) "摘要:$i18nId" else "摘要:$i18nId:${vars["faction"]}"
        },
    )

    @Test
    fun `功能分相随序章进度推进`() {
        val state = BountyState()
        assertEquals(BranchStationPhase.LOCKED, BranchTerminalData.phase(state))

        state.postedWorkOrders.add(MainBounties.KEY_PROLOGUE)
        assertEquals(BranchStationPhase.PENDING, BranchTerminalData.phase(state))

        state.postedWorkOrders.remove(MainBounties.KEY_PROLOGUE)
        state.destroyedWorkOrders.add(MainBounties.KEY_PROLOGUE)
        assertEquals(BranchStationPhase.PENDING, BranchTerminalData.phase(state))

        state.settledWorkOrders.add(MainBounties.KEY_PROLOGUE)
        assertEquals(BranchStationPhase.OPEN, BranchTerminalData.phase(state))
    }

    @Test
    fun `初始快照无可挂工单且档案全锁定`() {
        val snap = snapshotOf(BountyState())

        assertTrue(snap.orders.isEmpty(), "序章未接时终端不装配任何工单")
        assertTrue(snap.archives.isNotEmpty(), "存目条目仍应入快照")
        assertTrue(snap.archives.none { it.unlocked })
        assertEquals(0, snap.chapter)
        assertEquals("c206.03.01", snap.currentDate)
        assertTrue(snap.ledger.isEmpty())
    }

    @Test
    fun `快照装配四态生命周期且 gating 未满足工单隐藏`() {
        val state = BountyState()
        // 序章核销后一章批一两单可挂（postable）；未结清批一的其余工单 gating 未满足
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_PROLOGUE)] = 5_000
        state.settledWorkOrders.add(MainBounties.KEY_PROLOGUE)
        state.clearedGroups.add(MainBounties.GROUP_PROLOGUE)
        state.contractorLevel = 1
        state.currentChapter = 1
        // YJ_1102 挂出、YJ_1103 保持可挂、手工置入一个待核销单
        state.postedWorkOrders.add(MainBounties.KEY_YJ_1102)
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_YJ_1198)] = 120_000
        state.destroyedWorkOrders.add(MainBounties.KEY_YJ_1198)

        val snap = snapshotOf(state)
        val byKey = snap.orders.associateBy { it.key }

        assertEquals(OrderLifecycle.SETTLED, byKey[MainBounties.KEY_PROLOGUE]?.lifecycle)
        assertEquals(OrderLifecycle.POSTED, byKey[MainBounties.KEY_YJ_1102]?.lifecycle)
        assertEquals(OrderLifecycle.POSTABLE, byKey[MainBounties.KEY_YJ_1103]?.lifecycle)
        assertEquals(OrderLifecycle.DESTROYED, byKey[MainBounties.KEY_YJ_1198]?.lifecycle)
        assertFalse(MainBounties.KEY_YJ_1201 in byKey, "批二 gating 未满足应隐藏")
        assertEquals(4, snap.orders.size)

        // 批次分母取注册表组内总数（含隐藏单：批二 3 单全部隐藏仍有分母）
        assertEquals(3, snap.groupTotals[MainBounties.GROUP_CH1_BATCH2])
        assertEquals(1, snap.groupTotals[MainBounties.GROUP_PROLOGUE])
    }

    @Test
    fun `锁定报价与交割物持有状态注入快照`() {
        val state = BountyState()
        state.postedWorkOrders.add(MainBounties.KEY_XC_0221)
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_XC_0221)] = 480_000
        state.postedWorkOrders.add(MainBounties.KEY_YJ_1102)

        val holding = snapshotOf(state)
        val xc = holding.orders.single { it.key == MainBounties.KEY_XC_0221 }
        assertEquals(480_000, xc.quotedReward)
        assertEquals(StoryQuestItems.REQUIRED_DELIVERABLE[MainBounties.KEY_XC_0221], xc.requiredItemId)
        assertTrue(xc.hasRequiredItem)

        val yj = holding.orders.single { it.key == MainBounties.KEY_YJ_1102 }
        assertNull(yj.quotedReward, "未锁报价的工单报价为 null")
        assertNull(yj.requiredItemId, "无交割物要求的工单 requiredItemId 为 null")
        assertTrue(yj.hasRequiredItem, "无交割物要求视为已满足")

        val missing = snapshotOf(state) { false }
        assertFalse(
            missing.orders.single { it.key == MainBounties.KEY_XC_0221 }.hasRequiredItem,
            "交割物未持有应注入 false",
        )
    }

    @Test
    fun `多阶段工单阶段下标外露并收敛到合法区间`() {
        val state = BountyState()
        state.postedWorkOrders.add(MainBounties.KEY_ZW_0309)
        state.workOrderStageIndex[MainBounties.KEY_ZW_0309] = 2

        val snap = snapshotOf(state)
        val zw = snap.orders.single { it.key == MainBounties.KEY_ZW_0309 }
        assertEquals(2, zw.stageIndex)
        assertEquals(4, zw.stageCount)
        assertEquals(3, zw.nodeDrivenStages)

        // 脏数据收敛：阶段下标越界压回末阶段
        state.workOrderStageIndex[MainBounties.KEY_ZW_0309] = 99
        assertEquals(3, snapshotOf(state).orders.single { it.key == MainBounties.KEY_ZW_0309 }.stageIndex)
    }

    @Test
    fun `档案解锁判定使用注入的节点拔除数`() {
        val state = BountyState()
        state.clearedGroups.add(MainBounties.GROUP_CH1_BATCH1)

        val none = snapshotOf(state, pulledCount = 0)
        val byIdNone = none.archives.associateBy { it.id }
        assertTrue(byIdNone.getValue("l1_charter").unlocked)
        assertTrue(byIdNone.getValue("l1_councils_memo").unlocked)
        assertFalse(byIdNone.getValue("l2_admin_core_whitepaper").unlocked, "拔除数为 0 不应解锁")
        assertFalse(byIdNone.getValue("l2_ethics_review").unlocked)

        val two = snapshotOf(state, pulledCount = 2)
        val byIdTwo = two.archives.associateBy { it.id }
        assertTrue(byIdTwo.getValue("l2_admin_core_whitepaper").unlocked)
        assertTrue(byIdTwo.getValue("l2_ethics_review").unlocked)
        assertTrue(byIdTwo.getValue("l3_mothball_order").unlocked.not(), "三章档案不受拔除数影响")
    }

    @Test
    fun `流水由已核销单票与结清奖金构成`() {
        val state = BountyState()
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_PROLOGUE)] = 5_000
        state.settledWorkOrders.add(MainBounties.KEY_PROLOGUE)
        state.grantedGroupBonuses[MainBounties.GROUP_PROLOGUE] = 0
        state.grantedGroupBonuses[MainBounties.GROUP_CH1_BATCH1] = 300_000
        // 已核销但缺锁定报价的脏数据不进流水
        state.settledWorkOrders.add(MainBounties.KEY_YJ_1102)

        val snap = snapshotOf(state)

        val prologue = snap.ledger.single { it.kind == LedgerKind.ORDER }
        assertEquals(MainBounties.byKey(MainBounties.KEY_PROLOGUE)?.serial, prologue.title)
        assertEquals(5_000, prologue.amount)

        val bonuses = snap.ledger.filter { it.kind == LedgerKind.GROUP_BONUS }
        assertEquals(2, bonuses.size)
        assertEquals(300_000, bonuses.single { it.title == MainBounties.GROUP_CH1_BATCH1 }.amount)
        assertEquals(
            2,
            snap.settledCount,
            "settledCount 取已核销工单数（含缺报价脏数据），与流水行数口径分离",
        )
    }

    @Test
    fun `列表摘要经注入的解析器按工单 i18nId 装配`() {
        val state = BountyState()
        state.postedWorkOrders.add(MainBounties.KEY_YJ_1102)
        state.postedWorkOrders.add(MainBounties.KEY_XC_0216)

        val snap = snapshotOf(state)
        val byKey = snap.orders.associateBy { it.key }

        assertEquals("摘要:yj_c206_1102", byKey[MainBounties.KEY_YJ_1102]?.summary)
        assertEquals("摘要:xc_c208_0216", byKey[MainBounties.KEY_XC_0216]?.summary)
    }

    @Test
    fun `触发章节结清的工单在快照中携带 chapterCleared`() {
        val state = BountyState()
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_JJ_0007)] = 400_000
        state.quotedRewards[MainlineProgression.quoteKey(MainBounties.KEY_YJ_1102)] = 100_000
        state.settledWorkOrders.add(MainBounties.KEY_JJ_0007)
        state.settledWorkOrders.add(MainBounties.KEY_YJ_1102)
        state.chapterClearingOrders[1] = MainBounties.KEY_JJ_0007

        val snap = snapshotOf(state)
        val byKey = snap.orders.associateBy { it.key }

        assertEquals(1, byKey[MainBounties.KEY_JJ_0007]?.chapterCleared, "触发章末的单携带结清章节")
        assertNull(byKey[MainBounties.KEY_YJ_1102]?.chapterCleared, "普通核销单不携带")
    }
}
