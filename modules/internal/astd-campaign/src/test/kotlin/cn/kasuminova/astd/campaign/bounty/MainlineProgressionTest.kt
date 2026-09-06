package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主线推进纯逻辑：批次结清推进、章节 gating 链、等级递升、
 * 清算进度节拍（含 -0.5% 反常跳动）、失败重挂、结算校验。
 */
class MainlineProgressionTest {

    private fun def(key: String) = assertNotNull(MainBounties.byKey(key), key)

    /** 模拟游戏侧完整流程：挂出（锁整单报价）→ 逐阶段击毁 → 终端核销。 */
    private fun playOrder(state: BountyState, key: String, kS: Float = 1f): MainlineProgression.SettleResult {
        val d = def(key)
        state.quotedRewards.putIfAbsent(
            MainlineProgression.quoteKey(key),
            MainlineProgression.quoteOrderReward(d, kS, key.hashCode().toLong()),
        )
        state.postedWorkOrders.add(key)
        repeat(d.stages.size) {
            MainlineProgression.onStageDestroyed(state, key)
        }
        return MainlineProgression.settle(state, key, kS)
    }

    @Test
    fun `初始状态序章不挂牌且无可挂工单`() {
        val state = BountyState()
        assertEquals(0, state.contractorLevel)
        assertEquals(0, state.currentChapter)
        assertEquals(MainlineProgression.LIQUIDATION_BASELINE, state.liquidationProgress, 1e-6f)
        // 序章 boardPosted=false：postableOrders 为空，须走 acceptPrologueWorkOrder 入口
        assertEquals(emptyList(), MainlineProgression.postableOrders(state))
        assertFalse(state.archivalPending)
    }

    @Test
    fun `未击毁不可核销且不可重复结算`() {
        val state = BountyState()
        val d = def(MainBounties.KEY_PROLOGUE)
        state.quotedRewards[MainlineProgression.quoteKey(d.key)] =
            MainlineProgression.quoteOrderReward(d, 1f, 1L)

        val notDestroyed = MainlineProgression.settle(state, d.key, 1f)
        assertFalse(notDestroyed.success)
        assertEquals("not_destroyed:${d.key}", notDestroyed.rejectReason)

        MainlineProgression.onStageDestroyed(state, d.key)
        val first = MainlineProgression.settle(state, d.key, 1f)
        assertTrue(first.success)

        // 幂等：重复结算被拒绝，锁定报价与结清奖金记录不被覆盖、进度不重复推进
        val progressAfterFirst = state.liquidationProgress
        val quotesSnapshot = state.quotedRewards.toMap()
        val bonusesSnapshot = state.grantedGroupBonuses.toMap()
        val second = MainlineProgression.settle(state, d.key, 1f)
        assertFalse(second.success)
        assertEquals("already_settled:${d.key}", second.rejectReason)
        assertEquals(progressAfterFirst, state.liquidationProgress, 1e-6f)
        assertEquals(quotesSnapshot, state.quotedRewards)
        assertEquals(bonusesSnapshot, state.grantedGroupBonuses)
        assertEquals(1, state.settledWorkOrders.size)
    }

    @Test
    fun `缺报价视为数据异常拒绝结算`() {
        val state = BountyState()
        val key = MainBounties.KEY_PROLOGUE
        MainlineProgression.onStageDestroyed(state, key)
        val result = MainlineProgression.settle(state, key, 1f)
        assertFalse(result.success)
        assertEquals("missing_quote:$key", result.rejectReason)
    }

    @Test
    fun `序章核销注册承包商并开放一章批一`() {
        val state = BountyState()
        val result = playOrder(state, MainBounties.KEY_PROLOGUE)
        assertTrue(result.success)
        assertEquals(0, result.chapterCleared)
        assertEquals(1, state.contractorLevel)
        assertEquals(1, state.currentChapter)
        assertTrue(MainlineProgression.isGroupCleared(state, MainBounties.GROUP_PROLOGUE))
        // 序章结清组无奖金
        assertEquals(0, state.grantedGroupBonuses[MainBounties.GROUP_PROLOGUE])

        val postable = MainlineProgression.postableOrders(state).map { it.key }
        assertEquals(
            setOf(MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103),
            postable.toSet(),
            "批一两单应同时挂出（自由顺序）",
        )
    }

    @Test
    fun `批次制同批自由顺序且整批结清才挂下一批`() {
        val state = BountyState()
        playOrder(state, MainBounties.KEY_PROLOGUE)

        // 批一先核销一单：批二不挂（仅剩批一另一单可挂）
        playOrder(state, MainBounties.KEY_YJ_1103)
        assertEquals(listOf(MainBounties.KEY_YJ_1102), MainlineProgression.postableOrders(state).map { it.key })

        // 批一整批结清：批二三单同时挂出
        val r = playOrder(state, MainBounties.KEY_YJ_1102)
        assertEquals(MainBounties.GROUP_CH1_BATCH1 to 300_000, r.groupBonus)
        assertNull(r.chapterCleared, "批一结清不触发章末")
        assertEquals(3, MainlineProgression.postableOrders(state).size)

        // 批二整批结清才挂批三
        playOrder(state, MainBounties.KEY_YJ_1198)
        playOrder(state, MainBounties.KEY_YJ_1204)
        assertEquals(listOf(MainBounties.KEY_YJ_1201), MainlineProgression.postableOrders(state).map { it.key })
        val r2 = playOrder(state, MainBounties.KEY_YJ_1201)
        assertEquals(MainBounties.GROUP_CH1_BATCH2 to 500_000, r2.groupBonus)
        assertEquals(listOf(MainBounties.KEY_JJ_0007), MainlineProgression.postableOrders(state).map { it.key })
    }

    @Test
    fun `章节结清递升承包商等级并记录章末钩子`() {
        val state = BountyState()
        // 一章
        playOrder(state, MainBounties.KEY_PROLOGUE)
        playOrder(state, MainBounties.KEY_YJ_1102)
        playOrder(state, MainBounties.KEY_YJ_1103)
        playOrder(state, MainBounties.KEY_YJ_1198)
        playOrder(state, MainBounties.KEY_YJ_1201)
        playOrder(state, MainBounties.KEY_YJ_1204)
        assertEquals(1, state.contractorLevel, "一章未结清前仍为一级")
        val r1 = playOrder(state, MainBounties.KEY_JJ_0007)
        assertEquals(1, r1.chapterCleared)
        assertEquals(MainlineProgression.HOOK_SEALED_CATEGORIES, r1.newHook, "章末钩子应随本次结算结果携带")
        assertEquals(2, state.contractorLevel)
        assertTrue(MainlineProgression.HOOK_SEALED_CATEGORIES in state.chapterHooks)
        assertEquals(
            MainBounties.KEY_JJ_0007,
            state.chapterClearingOrders[1],
            "一章末单核销须记录为触发章节结清的工单（重打回执章末确认行数据源）",
        )

        // 二章双线并行互不卡：两线工单同时可挂
        val postable = MainlineProgression.postableOrders(state).map { it.key }
        assertEquals(
            setOf(MainBounties.KEY_XC_0216, MainBounties.KEY_ZW_0309),
            postable.toSet(),
        )

        // 先做紫菀线（合并单四阶段）也应放行星坠线
        val zw = def(MainBounties.KEY_ZW_0309)
        state.quotedRewards[MainlineProgression.quoteKey(zw.key)] =
            MainlineProgression.quoteOrderReward(zw, 1f, 7L)
        state.postedWorkOrders.add(zw.key)
        assertEquals(MainlineProgression.StageDestroy.Advance(1), MainlineProgression.onStageDestroyed(state, zw.key))
        assertEquals(MainlineProgression.StageDestroy.Advance(2), MainlineProgression.onStageDestroyed(state, zw.key))
        assertEquals(MainlineProgression.StageDestroy.Advance(3), MainlineProgression.onStageDestroyed(state, zw.key))
        assertEquals(MainlineProgression.StageDestroy.FinalStage, MainlineProgression.onStageDestroyed(state, zw.key))
        // 星坠线线性递进：单 2 在单 1 核销前不挂
        assertEquals(listOf(MainBounties.KEY_XC_0216), MainlineProgression.postableOrders(state).map { it.key })

        val zwSettle = MainlineProgression.settle(state, zw.key, 1f)
        assertTrue(zwSettle.success)
        assertEquals(MainBounties.GROUP_CH2_ZW to 1_000_000, zwSettle.groupBonus)
        assertNull(zwSettle.chapterCleared, "单线结清不触发章末")
        assertFalse(zw.key in state.chapterClearingOrders.values, "未触发章末的单不记录")

        playOrder(state, MainBounties.KEY_XC_0216)
        playOrder(state, MainBounties.KEY_XC_0217)
        val xcEnd = playOrder(state, MainBounties.KEY_XC_0221)
        assertEquals(MainBounties.GROUP_CH2_XC to 1_000_000, xcEnd.groupBonus)
        assertEquals(2, xcEnd.chapterCleared)
        assertEquals(MainBounties.KEY_XC_0221, state.chapterClearingOrders[2])
        assertEquals(3, state.contractorLevel)
        assertEquals(3, state.currentChapter)
        assertTrue(MainlineProgression.HOOK_DUPLICATE_TARGET in state.chapterHooks)
        // 二章末清算进度首现 97.3%（此前读数无变化）
        assertEquals(97.3f, state.liquidationProgress, 1e-3f)
    }

    @Test
    fun `清算进度节拍含三章单2反常回跳与四章封顶`() {
        val state = BountyState()
        // 快进序章+一章+二章
        listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
            MainBounties.KEY_JJ_0007,
            MainBounties.KEY_XC_0216, MainBounties.KEY_XC_0217, MainBounties.KEY_XC_0221,
            MainBounties.KEY_ZW_0309,
        ).forEach { playOrder(state, it) }
        assertEquals(97.3f, state.liquidationProgress, 1e-3f)

        // 三章：97.9 → 97.4（-0.5 回跳）→ 98.8
        val zx1 = playOrder(state, MainBounties.KEY_ZX_1001)
        assertEquals(97.9f, zx1.liquidationProgress, 1e-3f)
        val zx2 = playOrder(state, MainBounties.KEY_ZX_0344)
        assertEquals(97.4f, zx2.liquidationProgress, 1e-3f)
        val zx3 = playOrder(state, MainBounties.KEY_ZX_0002)
        assertEquals(98.8f, zx3.liquidationProgress, 1e-3f)
        assertEquals(3, zx3.chapterCleared)
        assertEquals(4, state.contractorLevel)
        assertTrue(MainlineProgression.HOOK_HALF_LINE_ORDER in state.chapterHooks)
        assertEquals(MainBounties.KEY_ZX_0002, state.chapterClearingOrders[3])

        // 四章：阶段击毁即推进 99.1 → 99.6 → 100.0（核销不再重复推进）
        val zq = def(MainBounties.KEY_ZQ_0001)
        state.quotedRewards[MainlineProgression.quoteKey(zq.key)] =
            MainlineProgression.quoteOrderReward(zq, 1f, 9L)
        state.postedWorkOrders.add(zq.key)
        MainlineProgression.onStageDestroyed(state, zq.key)
        assertEquals(99.1f, state.liquidationProgress, 1e-3f)
        MainlineProgression.onStageDestroyed(state, zq.key)
        assertEquals(99.6f, state.liquidationProgress, 1e-3f)
        MainlineProgression.onStageDestroyed(state, zq.key)
        assertEquals(100f, state.liquidationProgress, 1e-3f)

        val zqSettle = MainlineProgression.settle(state, zq.key, 1f)
        assertTrue(zqSettle.success)
        assertEquals(100f, zqSettle.liquidationProgress, 1e-3f)
        assertEquals(4, zqSettle.chapterCleared)
        assertEquals(MainlineProgression.HOOK_FINAL_RECEIPT, zqSettle.newHook)
        assertTrue(zqSettle.archivalPending, "100% 结算结果应携带归档挂起")
        assertEquals(MainBounties.GROUP_CH4 to 2_000_000, zqSettle.groupBonus)
        assertEquals(5, state.contractorLevel)
        assertEquals(5, state.currentChapter)
        assertTrue(MainlineProgression.HOOK_FINAL_RECEIPT in state.chapterHooks)
        assertTrue(state.archivalPending, "四章末应置归档挂起")
        assertEquals(MainBounties.KEY_ZQ_0001, state.chapterClearingOrders[4])
    }

    @Test
    fun `归档挂起的百分之边界硬校验`() {
        // 未达 100%（99.6）：四章结清也不置 archivalPending
        val below = BountyState()
        listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
            MainBounties.KEY_JJ_0007,
            MainBounties.KEY_XC_0216, MainBounties.KEY_XC_0217, MainBounties.KEY_XC_0221,
            MainBounties.KEY_ZW_0309,
            MainBounties.KEY_ZX_1001, MainBounties.KEY_ZX_0344, MainBounties.KEY_ZX_0002,
        ).forEach { playOrder(below, it) }
        // 压一节拍：ZQ 三阶段推进后读数停在 99.6（98.4 + 0.3 + 0.5 + 0.4）
        below.liquidationProgress = 98.4f
        val zq = def(MainBounties.KEY_ZQ_0001)
        below.quotedRewards[MainlineProgression.quoteKey(zq.key)] =
            MainlineProgression.quoteOrderReward(zq, 1f, 9L)
        below.postedWorkOrders.add(zq.key)
        repeat(zq.stages.size) { MainlineProgression.onStageDestroyed(below, zq.key) }
        assertEquals(99.6f, below.liquidationProgress, 1e-3f)
        val r = MainlineProgression.settle(below, zq.key, 1f)
        assertTrue(r.success)
        assertEquals(4, r.chapterCleared, "四章仍正常结清")
        assertFalse(r.archivalPending, "99.6% 不置归档挂起")
        assertFalse(below.archivalPending)

        // 恰达 100.0：置位
        val exact = BountyState()
        exact.liquidationProgress = MainlineProgression.LIQUIDATION_MAX
        exact.currentChapter = 4
        exact.clearedGroups.addAll(MainBounties.groupsOfChapter(0).map { it.id })
        exact.clearedGroups.addAll(MainBounties.groupsOfChapter(1).map { it.id })
        exact.clearedGroups.addAll(MainBounties.groupsOfChapter(2).map { it.id })
        exact.clearedGroups.add(MainBounties.GROUP_CH3)
        exact.quotedRewards[MainlineProgression.quoteKey(zq.key)] =
            MainlineProgression.quoteOrderReward(zq, 1f, 9L)
        exact.destroyedWorkOrders.add(zq.key)
        val r2 = MainlineProgression.settle(exact, zq.key, 1f)
        assertTrue(r2.success)
        assertTrue(r2.archivalPending, "100.0% 触发归档挂起")
        assertTrue(exact.archivalPending)

        // 浮点容差内（100 - ε/2 量级）同样触发，超出容差（99.5）不触发
        val near = BountyState()
        near.liquidationProgress = MainlineProgression.LIQUIDATION_MAX - MainlineProgression.ARCHIVAL_PROGRESS_EPSILON / 2f
        near.currentChapter = 4
        near.clearedGroups.addAll(MainBounties.groups.filter { it.chapter <= 3 }.map { it.id })
        near.quotedRewards[MainlineProgression.quoteKey(zq.key)] =
            MainlineProgression.quoteOrderReward(zq, 1f, 9L)
        near.destroyedWorkOrders.add(zq.key)
        assertTrue(MainlineProgression.settle(near, zq.key, 1f).archivalPending, "容差内读数应触发")
    }

    @Test
    fun `清算进度越界收敛到 0 至 100`() {
        // 封顶：多阶段工单阶段击毁增量超过 100 的部分被截断（ZQ 阶段一 +0.4）
        val state = BountyState()
        state.liquidationProgress = 99.9f
        state.postedWorkOrders.add(MainBounties.KEY_ZQ_0001)
        MainlineProgression.onStageDestroyed(state, MainBounties.KEY_ZQ_0001)
        assertEquals(100f, state.liquidationProgress, 1e-6f)

        // 托底：单阶段工单的反常跳动在核销时推进，不得把读数压到 0 以下（ZX 单 2 -0.5）
        val floor = BountyState()
        floor.liquidationProgress = 0.2f
        val zx2 = MainBounties.byKey(MainBounties.KEY_ZX_0344)!!
        floor.quotedRewards[MainlineProgression.quoteKey(zx2.key)] =
            MainlineProgression.quoteOrderReward(zx2, 1f, 1L)
        floor.postedWorkOrders.add(zx2.key)
        MainlineProgression.onStageDestroyed(floor, zx2.key)
        val r = MainlineProgression.settle(floor, zx2.key, 1f)
        assertEquals(0f, r.liquidationProgress, 1e-6f)
        assertEquals(0f, floor.liquidationProgress, 1e-6f)
    }

    @Test
    fun `失败终态回到未挂出并可重挂且不回退阶段进度`() {
        val state = BountyState()
        // 快进到二章紫菀线进行中
        listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
            MainBounties.KEY_JJ_0007,
        ).forEach { playOrder(state, it) }

        val zw = def(MainBounties.KEY_ZW_0309)
        state.quotedRewards[MainlineProgression.quoteKey(zw.key)] =
            MainlineProgression.quoteOrderReward(zw, 1f, 7L)
        state.postedWorkOrders.add(zw.key)
        MainlineProgression.onStageDestroyed(state, zw.key)
        assertEquals(1, state.workOrderStageIndex[zw.key])

        // 阶段二失败：移出挂出集，阶段进度保持
        val failed = MainlineProgression.markFailed(state, zw.key)
        assertEquals(zw, failed)
        assertFalse(zw.key in state.postedWorkOrders)
        assertEquals(1, state.workOrderStageIndex[zw.key])
        assertTrue(MainlineProgression.postableOrders(state).any { it.key == zw.key }, "失败后应可重挂")

        // 幂等：同一失败终态重复登记不生效（防无限重置；重挂走 postableOrders + postWorkOrder）
        assertNull(MainlineProgression.markFailed(state, zw.key), "非挂出中的工单不可重复标失败")

        // 已核销/已击毁工单不可标失败
        assertNull(MainlineProgression.markFailed(state, MainBounties.KEY_JJ_0007))
        assertNull(MainlineProgression.markFailed(state, "astd_side_whatever"))
    }

    @Test
    fun `结算金额为整单锁定报价加结清奖金且多阶段不放大`() {
        val state = BountyState()
        val key = MainBounties.KEY_JJ_0007
        // 前置快进
        listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
        ).forEach { playOrder(state, it) }

        val d = def(key)
        val quote = MainlineProgression.quoteOrderReward(d, 1f, 42L)
        state.quotedRewards[MainlineProgression.quoteKey(key)] = quote
        state.postedWorkOrders.add(key)
        MainlineProgression.onStageDestroyed(state, key)
        val result = MainlineProgression.settle(state, key, 1f)
        assertTrue(result.success)
        assertEquals(quote, result.orderPayout)
        assertEquals(MainBounties.GROUP_CH1_BATCH3 to 750_000, result.groupBonus)
        assertEquals(result.orderPayout + 750_000, result.totalPayout)
        assertEquals(750_000, state.grantedGroupBonuses[MainBounties.GROUP_CH1_BATCH3])

        // L5：多阶段工单（ZW 四阶段）整单只发一份锁定报价，不按阶段数累乘
        val zwState = BountyState()
        val zw = def(MainBounties.KEY_ZW_0309)
        val zwQuote = MainlineProgression.quoteOrderReward(zw, 1f, 7L)
        zwState.quotedRewards[MainlineProgression.quoteKey(zw.key)] = zwQuote
        zwState.postedWorkOrders.add(zw.key)
        repeat(zw.stages.size) { MainlineProgression.onStageDestroyed(zwState, zw.key) }
        val zwResult = MainlineProgression.settle(zwState, zw.key, 1f)
        assertTrue(zwResult.success)
        assertEquals(zwQuote, zwResult.orderPayout, "四阶段工单结算应为整单一份报价")
        assertEquals(zwQuote + 1_000_000, zwResult.totalPayout)
    }

    @Test
    fun `待核销查询仅含已击毁未交付工单`() {
        val state = BountyState()
        val key = MainBounties.KEY_PROLOGUE
        val d = def(key)
        state.quotedRewards[MainlineProgression.quoteKey(key)] =
            MainlineProgression.quoteOrderReward(d, 1f, 1L)
        state.postedWorkOrders.add(key)
        assertEquals(emptyList(), MainlineProgression.pendingSettlement(state))
        MainlineProgression.onStageDestroyed(state, key)
        assertEquals(listOf(d), MainlineProgression.pendingSettlement(state))
        MainlineProgression.settle(state, key, 1f)
        assertEquals(emptyList(), MainlineProgression.pendingSettlement(state))
    }

    // ─── ZW 引力节点拔除驱动的阶段同步（07 文档：阶段 0~2 = 拔除 3 座引力节点） ───

    @Test
    fun `ZW 前三个阶段为节点拔除驱动且核心阶段保持战斗`() {
        val zw = def(MainBounties.KEY_ZW_0309)
        assertEquals(4, zw.stages.size)
        assertEquals(3, zw.nodeDrivenStages)
        assertTrue(zw.isNodeDrivenStage(0))
        assertTrue(zw.isNodeDrivenStage(1))
        assertTrue(zw.isNodeDrivenStage(2))
        assertFalse(zw.isNodeDrivenStage(3), "阶段四「核心回收」应保持 MagicBounty 击毁路径")
        // 其余工单不走节点驱动
        assertEquals(0, def(MainBounties.KEY_ZQ_0001).nodeDrivenStages)
        assertEquals(0, def(MainBounties.KEY_XC_0216).nodeDrivenStages)
    }

    @Test
    fun `节点拔除进度与工单阶段取 max 同步且不倒退不死档`() {
        val state = BountyState()
        val key = MainBounties.KEY_ZW_0309

        // 未挂出时拔除也累计进度（挂出时按 max 追平，不死档）：纯同步不要求 posted
        assertNull(MainlineProgression.syncNodePulledStage(state, key, 0))
        assertEquals(1, MainlineProgression.syncNodePulledStage(state, key, 1))
        assertEquals(1, state.workOrderStageIndex[key])

        // 幂等：同进度重复同步不写入；进度回落不倒退
        assertNull(MainlineProgression.syncNodePulledStage(state, key, 1))
        assertEquals(2, MainlineProgression.syncNodePulledStage(state, key, 2))
        assertNull(MainlineProgression.syncNodePulledStage(state, key, 1))
        assertEquals(2, state.workOrderStageIndex[key])

        // 跨级追平：工单挂出前已拔 3 座 → 直接同步到战斗阶段
        val caughtUp = BountyState()
        assertEquals(3, MainlineProgression.syncNodePulledStage(caughtUp, key, 3))
        assertEquals(3, caughtUp.workOrderStageIndex[key])
        assertNull(
            MainlineProgression.syncNodePulledStage(caughtUp, key, 3),
            "进入战斗阶段后节点拔除不再驱动",
        )
    }

    @Test
    fun `节点同步不触碰终态与非节点驱动工单且不推进清算进度`() {
        val state = BountyState()
        val key = MainBounties.KEY_ZW_0309

        // 终态（待核销/已核销）不再同步
        state.destroyedWorkOrders.add(key)
        assertNull(MainlineProgression.syncNodePulledStage(state, key, 3))
        state.destroyedWorkOrders.remove(key)
        state.settledWorkOrders.add(key)
        assertNull(MainlineProgression.syncNodePulledStage(state, key, 3))

        // 非节点驱动工单与未知 key：空操作
        assertNull(MainlineProgression.syncNodePulledStage(state, MainBounties.KEY_XC_0216, 3))
        assertNull(MainlineProgression.syncNodePulledStage(state, "astd_main_unknown", 3))
        assertTrue(state.workOrderStageIndex.isEmpty())

        // 节点驱动阶段不产生清算进度增量（ZW 各阶段 liquidationDelta=0）
        val progress = BountyState()
        MainlineProgression.syncNodePulledStage(progress, key, 3)
        assertEquals(MainlineProgression.LIQUIDATION_BASELINE, progress.liquidationProgress, 1e-6f)
    }
}
