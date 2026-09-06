package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.story.StoryQuestItems
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.campaign.world.GravityNodes
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.campaign.SectorAPI
import java.awt.Color
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountySpec

/**
 * 主线工单 ↔ MagicBounty 桥接层（游戏侧副作用集中在这里）。
 *
 * 走代码注册路径，不依赖 magicBounty_data.json：
 * - [postWorkOrder] 直接构造 [MagicBountySpec] 并调用
 *   `MagicBountyCoordinator.createActiveBounty` + `ActiveBounty.acceptBounty` 激活工单，
 *   舰队重建仍走既有 patchAcceptedBounty 管线（[BountyCampaignManager]）；
 * - 多阶段工单（ZW/ZQ）：阶段击毁即推进内部阶段并重挂下一阶段舰队，
 *   对外保持同一工单 key 演进；ZW 阶段 0~2 例外——由引力节点拔除驱动
 *   （[syncNodeDrivenStages]，不生成 MagicBounty 击毁工单），阶段 3 核心守备战仍走击毁路径；
 * - 失败终态自动重挂（[tickPosted] 探测 + [onMainBountyFailed]），防死档；
 * - 结算不在击毁时发放：[settleWorkOrder] 是「分局终端交付核销」入口，
 *   由阶段 4 终端 UI 调用。
 *
 * MagicBounty 生命周期口径（源码依据 dev-resources/sources/org/magiclib/bounty/）：
 * - 终态条目在 intel 消失后被 `cleanUpBounties` 从 activeBounties 移入
 *   `completedBounties`（`$MagicBounties_completed_keys`）；主线 key 为代码注册、
 *   不在 `MagicBountyLoader.BOUNTIES` 中，此时调 `resetBounty` 会直接抛异常——
 *   因此重挂/清理统一走 [resetMainlineBounty]：条目仍在 active 才 reset，
 *   否则显式从 completed 列表移除，保证失败重挂不与 completed 冲突、不死档；
 * - `$<key>`（job_memKey）由 MagicLib 独占读写（接受时置 false、任意终态——含
 *   失败——置 true）；本模组的内容 gating 使用自有键 [BountyKeys.MEM_DESTROYED_PREFIX] /
 *   [BountyKeys.MEM_SETTLED_PREFIX]（分工见 BountyKeys 注释），不读写 `$<key>`。
 *
 * 接取方式说明：本阶段工单挂出后即自动接取（终端 UI 尚未实装）；
 * 阶段 4 终端 UI 落地后改为玩家手动接取，复用同一 [postWorkOrder] / [settleWorkOrder] API。
 */
object MainBountyBridge {

    /** i18n category（与 AffixRegistry.CATEGORY 一致）。 */
    private const val CAT = "asteria_directorate_bounty"

    private val log: Logger = Global.getLogger(MainBountyBridge::class.java)

    private val RECEIPT_COLOR = Color(200, 170, 120)

    /** 结算防重入守卫（运行期标记，不持久化；同一 key 的结算不嵌套）。 */
    private val settlingKeys = HashSet<String>()

    /**
     * 周期性维护：探测已挂出工单的失败/消失终态并重挂；随后挂出 gating 已满足的新工单。
     * 由 [BountyCampaignManager.advance] 调用。
     */
    fun tickPosted(state: BountyState, coord: MagicBountyCoordinator) {
        // 0) 引力节点拔除 → 节点驱动工单阶段同步（ZW 阶段 0~2 不走 MagicBounty 击毁路径）
        syncNodeDrivenStages(state, coord)

        // 1) 已挂出工单从 activeBounties 消失且未击毁 = 失败（含被第三方消灭
        //    EndedWithoutPlayerInvolvement/尸体消散 despawn 等终态）→ 清理 MagicLib 侧残留并重挂
        val active = coord.activeBounties
        for (key in state.postedWorkOrders.toList()) {
            if (key in state.destroyedWorkOrders || key in state.settledWorkOrders) {
                state.postedWorkOrders.remove(key)
                continue
            }
            // 节点驱动阶段不创建 MagicBounty 条目，不属于「消失」失败
            val def = MainBounties.byKey(key)
            if (def != null && def.isNodeDrivenStage(currentStageIndex(state, def))) continue
            if (!active.containsKey(key)) {
                onMainBountyFailed(key, state, coord)
            }
        }

        // 2) 挂出 gating 已满足的工单（批次制：同批同时挂出；线性组按前置核销递进）
        for (def in MainlineProgression.postableOrders(state)) {
            postWorkOrder(def, state, coord)
        }
    }

    private fun currentStageIndex(state: BountyState, def: MainBounties.WorkOrder): Int =
        (state.workOrderStageIndex[def.key] ?: 0).coerceIn(0, def.stages.lastIndex)

    /**
     * 引力节点拔除 → 节点驱动工单（ZW）阶段同步。
     *
     * 拔除判定来自 GravityNodeWatchScript（节点实体被打捞/摧毁消失后记入
     * StoryWorldState.gravityNodesPulled），本函数周期性把拔除进度同步到工单阶段
     * （max 口径，见 [MainlineProgression.syncNodePulledStage]）；同步出节点驱动阶段
     * （三座节点拔全）时立即挂出核心数据舱守备战斗工单（800 FP，MagicBounty 击毁路径）。
     */
    private fun syncNodeDrivenStages(state: BountyState, coord: MagicBountyCoordinator) {
        for (def in MainBounties.all) {
            if (def.nodeDrivenStages <= 0 || def.key !in state.postedWorkOrders) continue
            val pulled = GravityNodes.pulledCount()
            val target = MainlineProgression.syncNodePulledStage(state, def.key, pulled) ?: continue
            HudMessages.campaign(
                I18n.t(CAT, "hud.main.zw_node_pulled", "serial" to def.serial, "count" to pulled),
                RECEIPT_COLOR,
            )
            log.info("[ASTD] 引力节点拔除推进工单：${def.serial}（${def.key}）进入阶段 ${target + 1}/${def.stages.size}")
            if (!def.isNodeDrivenStage(target)) {
                // 三节点拔全 → 阶段四「核心回收」：核心数据舱守备战（800 FP）
                postWorkOrder(def, state, coord)
            }
        }
    }

    /**
     * 挂出并激活一张主线工单（当前阶段）。
     *
     * 节点拔除驱动工单（ZW 阶段 0~2）：先按已拔除节点数同步阶段（max 口径——挂出前
     * 拔除的节点同样计入）；同步后仍落在节点驱动阶段时不创建 MagicBounty 击毁工单，
     * 仅登记挂出与锁定报价（节点护卫舰队由 GravityNodeScripts 接触触发），阶段推进
     * 由 [tickPosted] 消费拔除进度。同步直接推进到战斗阶段（三节点已拔全）时走
     * 正常 MagicBounty 路径挂出核心守备舰队。
     *
     * @return 是否成功（找不到落点/舰队创建失败时记错误日志并返回 false，下 tick 重试）
     */
    fun postWorkOrder(def: MainBounties.WorkOrder, state: BountyState, coord: MagicBountyCoordinator): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 挂出主线工单失败：sector 不可用（${def.key}）")
            return false
        }

        if (def.nodeDrivenStages > 0) {
            MainlineProgression.syncNodePulledStage(state, def.key, GravityNodes.pulledCount())
        }
        val stageIndex = currentStageIndex(state, def)

        // 节点驱动阶段：登记挂出 + 锁定整单报价，不创建 MagicBounty 条目
        if (def.isNodeDrivenStage(stageIndex)) {
            val seed = (sector.clock?.timestamp ?: 0L) xor def.key.hashCode().toLong()
            val quote = MainlineProgression.quoteOrderReward(def, DifficultyTuningImpl.fixedScale, seed)
            state.quotedRewards.putIfAbsent(MainlineProgression.quoteKey(def.key), quote)
            state.postedWorkOrders.add(def.key)
            log.info(
                "[ASTD] 主线工单已挂出（引力节点拔除驱动阶段）：${def.serial}（${def.key}，" +
                    "阶段 ${stageIndex + 1}/${def.stages.size}，整单报价 $quote）",
            )
            return true
        }

        val source = sector.playerFleet
        if (source == null) {
            log.error("[ASTD] 挂出主线工单失败：playerFleet 不可用（${def.key}）")
            return false
        }

        // 重挂前清理 MagicLib 侧 completed 残留：终态条目会被 cleanUpBounties 移入
        // completedBounties（$MagicBounties_completed_keys，同一 List 实例，remove 即写回 memory），
        // 滞留会与重挂后的同名 key 冲突
        if (coord.completedBounties.remove(def.key)) {
            log.info("[ASTD] 清理主线工单 completed 残留标记：${def.serial}（${def.key}）")
        }

        // 挂出（接取）时锁定舰队组建：舰载核心配置与核心打捞表由同一份组建结果滚动定型，
        // 失败重挂/重复构建沿用首次锁定（与 quotedRewards 同模式），保证「掉落的正是舰队里装的」
        val seed = (sector.clock?.timestamp ?: 0L) xor def.key.hashCode().toLong()
        val stageSeed = seed xor (stageIndex.toLong() * 0x2545F4914F6CDD1DL)
        val plan = StandardCores.lockFleetPlan(state.lockedFleetPlans, "${def.key}#$stageIndex") {
            val comp = FleetComposer.buildComposition(def.toBountyDef(stageIndex), stageSeed)
            LockedFleetPlan(comp, StandardCores.rollCoreLoot(comp.officerCoreIds, stageSeed xor 0x1007L))
        }

        val active = try {
            coord.createActiveBounty(def.key, buildSpec(def, stageIndex, plan.coreLoot))
        } catch (t: Throwable) {
            log.error("[ASTD] 创建主线工单异常：${def.serial}（${def.key}）", t)
            return false
        }
        if (active == null) {
            log.error("[ASTD] 创建主线工单失败（无合适落点或舰队生成失败）：${def.serial}（${def.key}）")
            return false
        }

        // TODO：阶段 4 终端 UI 落地后，接取来源改为分局空间站实体；当前以玩家舰队为落点占位
        active.acceptBounty(source, null, null, null)

        // 接取时锁定整单报价（失败重挂沿用首次报价；多阶段工单不按阶段数放大）
        val quote = MainlineProgression.quoteOrderReward(def, DifficultyTuningImpl.fixedScale, seed)
        state.quotedRewards.putIfAbsent(MainlineProgression.quoteKey(def.key), quote)

        state.postedWorkOrders.add(def.key)
        // 挂出即目标在世：清除本工单可能残留的已击毁标记（阶段推进/失败重挂路径）
        sector.memoryWithoutUpdate?.unset(BountyKeys.MEM_DESTROYED_PREFIX + def.key)
        // 重挂/阶段推进后清理本工单的旧处理标记，保证当前阶段可被再次处理
        state.concludedBountyKeys.removeIf { it == def.key || it.startsWith(def.key + "#") }
        state.patchedBountyKeys.remove(def.key)

        log.info(
            "[ASTD] 主线工单已挂出：${def.serial}（${def.key}，阶段 ${stageIndex + 1}/${def.stages.size}，" +
                "整单报价 $quote）",
        )
        return true
    }

    /**
     * 序章赏金的注册与接受入口。
     *
     * 触发点：序章节拍 2「首份赏金文书」——酒馆代办对话收束（所有分支收束到接下文书，
     * 见 docs/story/03、04；对话在后续阶段实装）时由对话动作调用本函数挂上核销单。
     * 序章条目不挂牌（boardPosted=false），只经此 API 激活。
     */
    fun acceptPrologueWorkOrder(state: BountyState, coord: MagicBountyCoordinator): Boolean {
        val def = MainBounties.byKey(MainBounties.KEY_PROLOGUE)
            ?: error("序章工单未注册")
        if (state.currentChapter > 0 ||
            def.key in state.postedWorkOrders ||
            def.key in state.destroyedWorkOrders ||
            def.key in state.settledWorkOrders
        ) {
            return false
        }
        return postWorkOrder(def, state, coord)
    }

    /**
     * 主线工单击毁（MagicBounty Succeeded）处理：登记阶段推进/待核销，多阶段工单重挂下一阶段。
     * 幂等性由 [BountyCampaignManager] 的处理标记保证。
     */
    fun onMainBountySucceeded(
        key: String,
        def: MainBounties.WorkOrder,
        state: BountyState,
        coord: MagicBountyCoordinator,
    ) {
        val justClearedStage = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        when (val result = MainlineProgression.onStageDestroyed(state, key)) {
            is MainlineProgression.StageDestroy.Advance -> {
                printStageReceipt(def, justClearedStage, state)
                // 拆除旧阶段（fleet/ intel）并重挂下一阶段：对外仍是同一工单 key
                resetMainlineBounty(key, def, coord)
                postWorkOrder(def, state, coord)
                log.info("[ASTD] 主线工单 ${def.serial} 进入阶段 ${result.nextStageIndex + 1}/${def.stages.size}")
            }
            MainlineProgression.StageDestroy.FinalStage -> {
                printStageReceipt(def, justClearedStage, state)
                // 已击毁待核销标记（本模组自有键；`$<key>` 归 MagicLib 写，见类注释）
                Global.getSector()?.memoryWithoutUpdate?.set(BountyKeys.MEM_DESTROYED_PREFIX + key, true)
                // 交割物打捞入舱（序章铅封数据柜等，doc 03 节拍 3；无打捞物的工单为空操作）
                StoryQuestItems.onFinalStageDestroyed(key)
                HudMessages.campaign(
                    I18n.t(CAT, "hud.main.pending_settle", "serial" to def.serial),
                    RECEIPT_COLOR,
                )
                log.info("[ASTD] 主线工单 ${def.serial}（$key）目标已击毁，待分局终端交付核销")
            }
        }
    }

    /**
     * 主线工单失败终态处理：重置 MagicBounty 侧 bounty 并回到未挂出状态（下一 tick 自动重挂）。
     *
     * 覆盖的失败终态：FailedSalvagedFlagship / ExpiredAfterAccepting / Dismissed /
     * ExpiredWithoutAccepting / EndedWithoutPlayerInvolvement（玩家未参战被第三方消灭、
     * 尸体消散 despawn 触发 reportFleetDespawnedToListener 亦归此终态）。
     * 幂等：同一终态在 intel 收尾前的多个 tick 只会生效一次（[MainlineProgression.markFailed] 守卫）。
     */
    fun onMainBountyFailed(key: String, state: BountyState, coord: MagicBountyCoordinator) {
        val def = MainlineProgression.markFailed(state, key) ?: return
        log.warn("[ASTD] 主线工单 ${def.serial}（$key）失败终态，重置并重新挂出")
        resetMainlineBounty(key, def, coord)
    }

    /**
     * 重置/清理主线工单的 MagicBounty 侧状态。
     *
     * `resetBounty` 仅当条目仍在 activeBounties 时安全：条目已被 cleanUpBounties
     * 移入 completedBounties 后，resetBounty 会落到 `MagicBountyLoader.BOUNTIES` 查询分支，
     * 而主线 key 为代码注册、不在其中，直接抛 RuntimeException。因此：
     * - 条目仍在 active → 走 resetBounty（其内部会清 memKey/intel/旧 fleet 并从 completed 移除）；
     * - 已不在 active → 只从 completedBounties 显式移除主线 key（同一 List 实例，写回 memory），
     *   避免 completed 残留与后续重挂冲突。
     */
    private fun resetMainlineBounty(key: String, def: MainBounties.WorkOrder, coord: MagicBountyCoordinator) {
        try {
            if (coord.getActiveBounty(key) != null) {
                coord.resetBounty(key)
            } else if (coord.completedBounties.remove(key)) {
                log.info("[ASTD] 主线工单 ${def.serial}（$key）已入 completed 列表，显式移除以便重挂")
            }
        } catch (t: Throwable) {
            log.error("[ASTD] 主线工单 MagicBounty 状态重置异常：${def.serial}（$key）", t)
        }
    }

    /**
     * 「分局终端交付核销」结算入口（阶段 4 终端 UI 调用）。
     *
     * 校验已击毁 → 按接取时锁定的整单报价发放报酬（单票 + 结清组奖金）→
     * 推进清算序列进度 → 本次产生的回执/钩子/进度**立即**写入 sector memory →
     * 战役层回执输出（HUD 消息；阶段 4 终端 UI 之后接管更完整的呈现，本路径保证
     * 「不打终端也有回执」）。
     *
     * 幂等：已核销工单被 [MainlineProgression.settle] 拒绝（不重复发款）；
     * [settlingKeys] 防同一调用栈内重入（如章节结清触发的世界生成回调）。
     */
    fun settleWorkOrder(key: String): MainlineProgression.SettleResult {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 结算主线工单失败：sector 不可用（$key）")
            return MainlineProgression.SettleResult(false, "no_sector")
        }
        if (!settlingKeys.add(key)) {
            log.warn("[ASTD] 主线工单结算重入被拒绝：$key")
            return MainlineProgression.SettleResult(false, "settle_in_progress:$key")
        }
        try {
            return settleWorkOrderInternal(sector, key)
        } finally {
            settlingKeys.remove(key)
        }
    }

    private fun settleWorkOrderInternal(sector: SectorAPI, key: String): MainlineProgression.SettleResult {
        val state = BountyState.getOrCreate()
        val def = MainBounties.byKey(key)
        val result = MainlineProgression.settle(state, key, DifficultyTuningImpl.fixedScale)
        if (!result.success) {
            log.warn("[ASTD] 主线工单结算被拒绝：${result.rejectReason}")
            return result
        }

        val cargo = sector.playerFleet?.cargo
        if (cargo == null) {
            log.error("[ASTD] 主线工单结算发款失败：playerFleet 不可用（$key，金额 ${result.totalPayout}）")
        } else {
            cargo.credits.add(result.totalPayout.toFloat())
        }

        // 本次结算产生的回执/钩子/清算进度立即写入 sector memory（供后续内容 gating 与 UI 消费）；
        // 不再等下一次结算做全量重写——章末钩子与本单同步生效，四章末终局钩子可正常落档
        val mem = sector.memoryWithoutUpdate
        mem.set("${BountyKeys.MEM_SETTLED_PREFIX}$key", true)
        mem.set(BountyKeys.MEM_LIQUIDATION_PROGRESS, result.liquidationProgress)
        result.newHook?.let { mem.set("${BountyKeys.MEM_HOOK_PREFIX}$it", true) }
        if (result.archivalPending) {
            mem.set(BountyKeys.MEM_ARCHIVAL_PENDING, true)
        }

        // 章末钩子消费：第一章结清 → 即时生成第二章双遗址星系（读档补齐路径在 StoryWorldBootstrap）
        result.chapterCleared?.let { chapter ->
            StoryWorldGenerator.onChapterCleared(sector, chapter)
        }

        printSettleReceipts(def, key, result)
        log.info(
            "[ASTD] 主线工单已核销：${def?.serial ?: key}，发放 ${result.totalPayout} " +
                "（单票 ${result.orderPayout} + 结清奖 ${result.groupBonus?.second ?: 0}），" +
                "清算序列进度 ${formatProgress(result.liquidationProgress)}",
        )
        return result
    }

    /**
     * 结算回执输出链：核销回执（报酬 + 清算进度）→ 结清组奖金回执 → 章节晋升/章末回执。
     * 全部走 HUD 战役消息，i18n 键 group.* / chapter.* / main.*.receipt 已定稿（bounty_strings.json）。
     */
    private fun printSettleReceipts(
        def: MainBounties.WorkOrder?,
        key: String,
        result: MainlineProgression.SettleResult,
    ) {
        HudMessages.campaign(
            I18n.t(
                CAT, "hud.main.settled",
                "serial" to (def?.serial ?: key),
                "amount" to result.totalPayout,
                "progress" to formatProgress(result.liquidationProgress),
            ),
            RECEIPT_COLOR,
        )
        result.groupBonus?.let { (groupId, bonus) ->
            HudMessages.campaign(
                I18n.t(CAT, "hud.main.group_bonus", "amount" to bonus),
                RECEIPT_COLOR,
            )
            HudMessages.campaign(I18n[CAT, "group.$groupId.receipt"], RECEIPT_COLOR)
        }
        result.chapterCleared?.let { chapter ->
            HudMessages.campaign(
                I18n.t(CAT, "chapter.$chapter.receipt", "progress" to formatProgress(result.liquidationProgress)),
                RECEIPT_COLOR,
            )
        }
    }

    /** 清算序列进度显示格式（一位小数百分比，如 `97.9%`）。 */
    fun formatProgress(progress: Float): String = "%.1f%%".format(progress)

    private fun printStageReceipt(def: MainBounties.WorkOrder, stageIndex: Int, state: BountyState) {
        val stage = def.stages.getOrNull(stageIndex) ?: return
        if (!stage.stageReceipt) return
        HudMessages.campaign(
            I18n.t(
                CAT, "main.${def.i18nId}.stage.${stageIndex + 1}.receipt",
                "progress" to formatProgress(state.liquidationProgress),
            ),
            RECEIPT_COLOR,
        )
    }

    /**
     * 构造主线工单当前阶段的 MagicBounty 规格（代码注册，不经过 magicBounty_data.json）。
     *
     * 口径：赏金自身信用点/声望奖励置 0（报酬由 [settleWorkOrder] 在交付核销时发放）；
     * 无时限（job_deadline=0，05 文档「批次之间无时间限制」）；舰队缩放由本模组管线负责
     * （fleet_scaling_multiplier=0）；[itemReward] 为挂出时锁定的核心打捞表
     * （lockKey = `key#stageIndex`，见 [postWorkOrder]）。
     */
    private fun buildSpec(def: MainBounties.WorkOrder, stageIndex: Int, itemReward: Map<String, Int>): MagicBountySpec {
        val stage = def.stages[stageIndex]
        val iid = def.i18nId

        val description = buildString {
            append(I18n[CAT, "main.$iid.desc"])
            for (i in 1..def.clauseCount) {
                append('\n')
                append(I18n[CAT, "main.$iid.clause.$i"])
            }
        }
        val fleetName = if (def.multiStage) {
            I18n[CAT, "main.$iid.stage.${stageIndex + 1}.fleet_name"]
        } else {
            I18n[CAT, "main.$iid.fleet_name"]
        }
        val dangerText = if (def.dangerOmitted) {
            I18n[CAT, "danger.omitted"]
        } else {
            I18n[CAT, "danger.${def.threatTier}"]
        }

        return MagicBountySpec(
            // ── trigger_*：不走赏金板刷新，全部置空/置零 ──
            emptyList(), // trigger_market_id
            emptyList(), // trigger_marketFaction_any
            false, // trigger_marketFaction_alliedWith
            emptyList(), // trigger_marketFaction_none
            false, // trigger_marketFaction_enemyWith
            0, // trigger_market_minSize
            0, // trigger_player_minLevel
            0, // trigger_min_days_elapsed
            0, // trigger_min_fleet_size
            0f, // trigger_weight_mult
            emptyMap(), // trigger_memKeys_all
            emptyMap(), // trigger_memKeys_any
            emptyMap(), // trigger_memKeys_none
            emptyMap(), // trigger_playerRelationship_atLeast
            emptyMap(), // trigger_playerRelationship_atMost
            null, // trigger_giverTargetRelationship_atLeast
            null, // trigger_giverTargetRelationship_atMost
            // ── job_* ──
            I18n[CAT, "main.$iid.name"], // job_name
            description, // job_description
            null, // job_comm_reply
            I18n.t(CAT, "main.$iid.receipt"), // job_intel_success
            null, // job_intel_failure
            null, // job_intel_expired
            null, // job_forFaction
            dangerText, // job_difficultyDescription
            0, // job_deadline：无时限
            0, // job_credit_reward：报酬由 settleWorkOrder 发放
            0f, // job_credit_scaling
            0f, // job_reputation_reward
            // 核心打捞表（挂出时锁定，掉落的正是舰队实际装舰核心；O 档不可获取不参与打捞，
            // 口径见 StandardCores.rollCoreLoot）。不可为 null：generatePlayerLoot 不判空直接迭代 entrySet
            itemReward, // job_item_reward
            "destruction", // job_type
            false, // job_show_type
            false, // job_show_captain
            "vanilla", // job_show_fleet
            "exact", // job_show_distance
            true, // job_show_arrow
            null, // job_pick_option
            null, // job_pick_script
            "\$${def.key}", // job_memKey
            null, // job_conclusion_script
            null, // existing_target_memkey
            // ── target_* ──
            null, // target_importantPersonId
            null, // target_first_name
            null, // target_last_name
            null, // target_portrait
            null, // target_gender
            null, // target_rank
            null, // target_post
            null, // target_personality
            null, // target_aiCoreId
            0, // target_level
            0, // target_elite_skills
            null, // target_skill_preference
            null, // target_skills
            // ── fleet_* ──
            fleetName, // fleet_name
            def.fleetFaction, // fleet_faction
            stage.flagshipVariantId, // fleet_flagship_variant
            null, // fleet_flagship_name
            false, // fleet_flagship_alwaysRecoverable
            true, // fleet_flagship_autofit
            null, // fleet_preset_ships
            true, // fleet_preset_autofit
            0f, // fleet_scaling_multiplier：缩放由本模组管线负责
            stage.baselineFP, // fleet_min_FP
            def.fleetFaction, // fleet_composition_faction
            1f, // fleet_composition_quality
            true, // fleet_transponder
            true, // fleet_no_retreat
            FleetAssignment.ORBIT_AGGRESSIVE, // fleet_behavior
            "hostile", // fleet_attitude
            null, // fleet_musicSetId
            // ── location_*：07 文档——二章工单坐标锚定遗址星系恒星（location_marketIDs 实参语义为
            //    实体 id 列表，锚点存在即直接落位）；无锚点工单走跳点偏好任意落位。
            //    注意 MagicCampaign.findSuitableTarget：location 偏好全空直接返回 null
            //    （defaultToAnyEntity 只在偏好检索路径内兜底）；进入偏好检索后
            //    `avoid_themes.removeAll(seek_themes)` 要求 location_themes 非 null。 ──
            def.spawnAnchorEntityId?.let { listOf(it) }, // location_marketIDs（实为实体 id 锚点）
            null, // location_marketFactions
            null, // location_distance
            emptyList(), // location_themes
            emptyList(), // location_themes_blacklist
            listOf("jump_point"), // location_entities：锚点缺失时的任意跳点落位偏好
            false, // location_prioritizeUnexplored
            true, // location_defaultToAnyEntity
        )
    }
}
