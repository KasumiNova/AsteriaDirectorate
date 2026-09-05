package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.story.EndingSettlement
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import java.awt.Color
import java.util.Locale

/**
 * 战役侧管理脚本：监听 MagicBounty 的 ActiveBounty 状态，
 * 驱动主线运行时模型（[MainBounties] 定义 + [BountyState] 存档状态）。
 *
 * 职责：
 * - 启动时（或加载器定义缺失时）经 [MagicBountyBridge] 注册全部主线赏金；
 * - “接受”后按主线定义重建目标舰队（缩放 + 词缀 + 核心编成），并写入核销回执文案；
 * - 结清后推进：报酬结算、批次/章节解锁、清算序列进度、承包商等级、一次性回执；
 * - 主线单失败终态（非玩家击毁等）自动重置重新挂出，避免死档；
 * - 第五章 gating：第四章结清后置档案处置申请，归档三选见 [recordArchiveChoice]。
 */
class BountyCampaignManager : EveryFrameScript {

    companion object {
        private val log: Logger = Global.getLogger(BountyCampaignManager::class.java)

        /** 失败终态：主线单进入这些阶段时重置重新挂出（过期未接受不算失败，赏金板会自行刷新）。 */
        private val FAILURE_STAGES = setOf(
            ActiveBounty.Stage.FailedSalvagedFlagship,
            ActiveBounty.Stage.ExpiredAfterAccepting,
            ActiveBounty.Stage.EndedWithoutPlayerInvolvement,
        )

        private val MAIN_COLOR = Color(120, 200, 255)
        private val RECEIPT_COLOR = Color(200, 170, 120)

        /** 归档三选的合法取值（对应 [BountyState.archiveChoice]）。 */
        const val ARCHIVE_PUBLIC: String = "public"
        const val ARCHIVE_SEALED: String = "sealed"
        const val ARCHIVE_TRADED: String = "traded"

        /** 交易选候选势力（13 文档：星区主要势力，余晖除外——不向非在编自动智能出售，章程条款）。 */
        val TRADEABLE_FACTIONS: List<String> = listOf(
            Factions.HEGEMONY,
            Factions.DIKTAT,
            Factions.TRITACHYON,
            Factions.LUDDIC_CHURCH,
            Factions.PERSEAN,
            Factions.INDEPENDENT,
        )

        /** 交易选一次性报酬区间（13 文档：5,000,000 ~ 25,000,000，随难度系数缩放）。 */
        private const val TRADE_REWARD_MIN: Int = 5_000_000
        private const val TRADE_REWARD_MAX: Int = 25_000_000

        /** 交易选关系变动（13 文档：对象显著提升、其余微降；具体数值文档待定，此处为提案值）。 */
        private const val TRADE_REL_TARGET_DELTA: Float = 0.3f
        private const val TRADE_REL_OTHERS_DELTA: Float = -0.05f

        /** 第二章结清时首次显示的清算序列进度（07 文档章末钩子：97.3%）。 */
        private const val CH2_END_LIQUIDATION: Float = 97.3f

        /**
         * 记录归档三选结果（第五章结局 gating；由分局空间站终端签署流程调用）。
         *
         * - 仅当 [BountyState.archivalPending] 为 true 且尚未签署时受理；不可多选、不可反悔；
         * - 签署后写入 memKey gating（[BountyKeys.MEM_ARCHIVE_CHOICE] 等），
         *   发放《无限期承包合同》认证（无限赏金解锁标记）；
         * - 交易选额外结算一次性巨额报酬与关系变动，并记录对象势力。
         *
         * @return 是否受理成功
         */
        /** 战斗胜利后的分局交付：只有交付才推进章节、发放星币并满足后续工单门槛。 */
        @JvmStatic
        fun settleBounty(key: String): Boolean {
            if (InfiniteBounties.isInfiniteKey(key)) {
                if (key !in InfiniteBounties.pendingDeliveries()) {
                    log.info("[BountyCampaignManager] 无限工单 '$key' 当前没有待交付战果，拒绝核销")
                    return false
                }
                return InfiniteBounties.onSettled(key)
            }

            val state = BountyState.getOrCreate()
            if (key !in state.defeatedBountyKeys || key in state.succeededBountyKeys) {
                log.info("[BountyCampaignManager] 工单 '$key' 当前没有待交付战果，拒绝核销")
                return false
            }
            if (key !in MainBounties.defsByKey) return false
            state.settlementRequests.add(key)
            return true
        }

        @JvmStatic
        fun recordArchiveChoice(choice: String, tradeFactionId: String? = null): Boolean {
            val state = BountyState.getOrCreate()
            if (!state.archivalPending) {
                log.warn("[BountyCampaignManager] 档案处置申请未受理时调用 recordArchiveChoice（choice=$choice），已拒绝")
                return false
            }
            if (state.archiveChoice.isNotEmpty()) {
                log.warn("[BountyCampaignManager] 归档已签署（${state.archiveChoice}），重复签署被拒绝（choice=$choice）")
                return false
            }
            if (choice != ARCHIVE_PUBLIC && choice != ARCHIVE_SEALED && choice != ARCHIVE_TRADED) {
                log.warn("[BountyCampaignManager] 未知归档方式：$choice，已拒绝")
                return false
            }
            if (choice == ARCHIVE_TRADED) {
                if (tradeFactionId == null || tradeFactionId !in TRADEABLE_FACTIONS) {
                    log.warn("[BountyCampaignManager] 交易选缺少合法移交对象（faction=$tradeFactionId），已拒绝")
                    return false
                }
            }

            val sector = Global.getSector() ?: return false
            state.archiveChoice = choice
            sector.memoryWithoutUpdate.set(BountyKeys.MEM_ARCHIVE_CHOICE, choice)

            if (choice == ARCHIVE_TRADED && tradeFactionId != null) {
                state.archiveTradeFactionId = tradeFactionId
                sector.memoryWithoutUpdate.set(BountyKeys.MEM_ARCHIVE_TRADE_FACTION, tradeFactionId)
                val tradeAmount = settleArchiveTrade(sector, tradeFactionId)
                if (tradeAmount > 0) {
                    val date = sector.clock.getDateString()
                    state.ledgerEntries.add(
                        BountyLedgerEntry(
                            I18n[BountyKeys.I18N_CATEGORY, "ending.archive.traded.title"],
                            date,
                            tradeAmount.toLong(),
                            I18n[BountyKeys.I18N_CATEGORY, "terminal.ledger.note.trade"],
                        )
                    )
                }
            }

            // 《无限期承包合同》：归档流程完成后签发，随机无限赏金解锁。
            state.archivalPending = false
            state.infiniteContractor = true
            sector.memoryWithoutUpdate.set(BountyKeys.MEM_INFINITE_CONTRACTOR, true)

            HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "archive.signed.$choice"], RECEIPT_COLOR)
            HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "archive.contract"], RECEIPT_COLOR)

            // 终局收尾：「执行官」签发 + 最终回执（13 文档结算顺序）。
            EndingSettlement.onArchiveSigned(sector, state)
            return true
        }

        /**
         * 交易选结算：一次性报酬（随 k_s 缩放）+ 对象势力关系提升、其余微降。
         *
         * @return 实际发放星币数（无玩家舰队等异常场景返回 0）
         */
        private fun settleArchiveTrade(sector: com.fs.starfarer.api.campaign.SectorAPI, factionId: String): Int {
            val ks = cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl.fixedScale
            val amount = BountyRewards.computeTicketPayout(
                TRADE_REWARD_MIN, TRADE_REWARD_MAX, ks, java.util.Random(sector.clock.timestamp)
            )
            val credits = sector.playerFleet?.cargo?.credits
            if (credits == null) {
                log.warn("[BountyCampaignManager] 交易选报酬发放失败：无玩家舰队（amount=$amount）")
                return 0
            }
            credits.add(amount.toFloat())

            for (fid in TRADEABLE_FACTIONS) {
                val faction = sector.getFaction(fid) ?: continue
                val delta = if (fid == factionId) TRADE_REL_TARGET_DELTA else TRADE_REL_OTHERS_DELTA
                faction.adjustRelationship(Factions.PLAYER, delta)
            }
            HudMessages.campaign(
                I18n.t(BountyKeys.I18N_CATEGORY, "archive.trade_settled", "credits" to amount.toString()),
                RECEIPT_COLOR,
            )
            return amount
        }
    }

    private var timer = 0f
    private var registered = false
    private var loggedCoordinatorMissing = false

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    /** 记一行履约流水（终端账户页展示；日期取当前星区纪年）。 */
    private fun recordLedger(state: BountyState, code: String, amount: Long, note: String) {
        val date = Global.getSector()?.clock?.getDateString() ?: run {
            log.warn("[BountyCampaignManager] 记流水时星区不可用（code=$code, amount=$amount），日期栏留白。")
            ""
        }
        state.ledgerEntries.add(BountyLedgerEntry(code, date, amount, note))
    }

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 0.7f) return
        timer = 0f

        val sector = Global.getSector() ?: return

        try {
            InfiniteBounties.ensureAvailable()
        } catch (t: Throwable) {
            log.warn("[BountyCampaignManager] 无限赏金维护失败：${t.message}", t)
        }

        if (!registered || !MagicBountyBridge.mainBountiesRegistered()) {
            try {
                MagicBountyBridge.registerMainBounties(overwrite = true)
                registered = true
            } catch (t: Throwable) {
                // MagicLib 为硬依赖；失败时持续重试并输出日志，不静默放弃。
                log.warn("[BountyCampaignManager] Failed to register bounties: ${t.message}", t)
            }
        }

        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            if (!loggedCoordinatorMissing) {
                loggedCoordinatorMissing = true
                log.warn("[BountyCampaignManager] MagicBountyCoordinator 不可用：${t.message}")
            }
            return
        }

        val state = BountyState.getOrCreate()
        for (key in state.settlementRequests.toList()) {
            if (key in state.defeatedBountyKeys) {
                state.settlementRequests.remove(key)
                concludeBountySuccess(key, state)
            } else {
                state.settlementRequests.remove(key)
                log.warn("[BountyCampaignManager] 交付请求 '$key' 已失去待交付战果，已清除请求")
            }
        }

        val active = coord.activeBounties
        if (active.isEmpty()) return

        val pendingResets = ArrayList<String>()

        for ((key, bounty) in active) {
            if (!key.startsWith(BountyKeys.BOUNTY_KEY_PREFIX)) continue

            // 1) 接受后：按定义动态重建舰队
            if (bounty.stage == ActiveBounty.Stage.Accepted && key !in state.patchedBountyKeys) {
                patchAcceptedBounty(key, bounty, state)
            }

            // 2) 主线战斗胜利只登记待交付；无限续展由 InfiniteBounties 自己登记，不能污染主线状态。
            if (!InfiniteBounties.isInfiniteKey(key) &&
                bounty.stage == ActiveBounty.Stage.Succeeded && key !in state.succeededBountyKeys
            ) {
                state.defeatedBountyKeys.add(key)
                state.patchedBountyKeys.remove(key)
                log.info("[BountyCampaignManager] 工单战斗目标已击破，等待分局交付：$key")
            }

            // 3) 失败终态：仅主线单重置重新挂出；无限续展由 InfiniteBounties 换代。
            if (!InfiniteBounties.isInfiniteKey(key) &&
                bounty.stage in FAILURE_STAGES && key !in state.concludedBountyKeys
            ) {
                state.concludedBountyKeys.add(key)
                if (MainBounties.defsByKey.containsKey(key)) {
                    pendingResets.add(key)
                }
            }
        }

        for (key in pendingResets) {
            resetMainBounty(coord, key, state)
        }
    }

    private fun patchAcceptedBounty(key: String, bounty: ActiveBounty, state: BountyState) {
        val fleet = bounty.fleet ?: return
        val flagship = fleet.flagship ?: return
        val seed = (Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()) xor key.hashCode().toLong()

        val def = MainBounties.defsByKey[key] ?: InfiniteBounties.definition(key)
        if (def != null) {
            // 主线与无限续展都使用持久化定义（FP/词缀规则/回执文案），不从 spec 反推。
            val comp = FleetComposer.buildComposition(def, seed)
            val successText = if (InfiniteBounties.isInfiniteKey(key)) {
                InfiniteBounties.receiptText(key)
            } else {
                buildMainSuccessText(def)
            }
            patchFleetMembers(key, fleet, flagship, comp, successText, def.flagshipDMods, seed)
            state.patchedBountyKeys.add(key)
            HudMessages.campaign(
                I18n.t(BountyKeys.I18N_CATEGORY, "hud.fleet_rebuilt", "scale" to "${(comp.totalMult * 100).toInt()}%"),
                MAIN_COLOR,
            )
        } else {
            // 其它 astd_ 前缀赏金不属于本模组主线，保留通用补丁路径。
            patchAcceptedBountyGeneric(key, bounty, state, fleet, flagship, seed)
        }
    }

    private fun patchAcceptedBountyGeneric(
        key: String,
        bounty: ActiveBounty,
        state: BountyState,
        fleet: CampaignFleetAPI,
        flagship: FleetMemberAPI,
        seed: Long,
    ) {
        val def = BountyDef(
            key = key,
            chapter = -1,
            groupId = "",
            dangerLevel = parseThreatTierFromSpec(bounty.spec.job_difficultyDescription),
            baselineFP = bounty.spec.fleet_min_FP.coerceAtLeast(50),
            flagshipVariantId = bounty.spec.fleet_flagship_variant,
            fleetFactionId = bounty.spec.fleet_faction ?: "pirates",
            affixRule = AffixRule.STANDARD,
        )
        val comp = FleetComposer.buildComposition(def, seed)
        val successText = I18n[BountyKeys.I18N_CATEGORY, "generic.success_text"]
        patchFleetMembers(key, fleet, flagship, comp, successText, flagshipDMods = 0, seed)
        state.patchedBountyKeys.add(key)
    }

    /** 主线核销回执：批注正文 + （剧本化读数存在时）清算序列进度行。 */
    private fun buildMainSuccessText(def: BountyDef): String {
        val receipt = I18n[BountyKeys.I18N_CATEGORY, "main.${def.key}.receipt"]
        val progress = def.liquidationDisplay ?: return receipt
        val progressLine = I18n.t(
            BountyKeys.I18N_CATEGORY,
            "receipt.liquidation_progress",
            "progress" to String.format(Locale.ROOT, "%.1f", progress),
        )
        return receipt + "\n\n" + progressLine
    }

    private fun patchFleetMembers(
        key: String,
        fleet: CampaignFleetAPI,
        flagship: FleetMemberAPI,
        comp: FleetComposer.Composition,
        successText: String?,
        flagshipDMods: Int,
        seed: Long,
    ) {
        if (fleet.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_FLEET_PATCHED)) {
            return
        }
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_FLEET_PATCHED, true)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_BOUNTY_KEY, key)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_AFFIXES, comp.affixHullMods.joinToString(","))
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_K, comp.k)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_TOTAL_MULT, comp.totalMult)

        // 让该舰队在交互时使用自定义的 FleetInteractionDialog 配置。
        // 注意：key 名是原版内部约定（拼写如此）。
        fleet.memoryWithoutUpdate.set("\$fidConifgGen", BountyFidConfigGen(key))

        // 某些原版逻辑会读取该 flag 来倾向“死战到底”。
        fleet.memoryWithoutUpdate.set("\$core_fightToTheLast", true)

        if (!successText.isNullOrBlank()) {
            fleet.memoryWithoutUpdate.set(BountyKeys.MEM_SUCCESS_TEXT, successText)
        }

        val data = fleet.fleetData
        val existing = data.membersListCopy
        for (m in existing) {
            if (m !== flagship) {
                data.removeFleetMember(m)
            }
        }

        val created = FleetComposer.rebuildFleetMembers(
            bountyKey = key,
            fleetMembers = comp.pickedVariantIds,
            affixHullMods = comp.affixHullMods,
            phaseOnlyHullMods = comp.phaseOnlyHullMods,
            flagship = flagship,
            flagshipDMods = flagshipDMods,
            seed = seed,
        )

        // 把创建出来的成员添加到 fleet
        for (m in created) {
            if (m !== flagship) {
                data.addFleetMember(m)
            }
        }
        data.setFlagship(flagship)
    }

    /**
     * 成功结清：维护存档状态 + 推进主线（报酬/批次/章节/清算进度/终局 gating）。
     */
    private fun concludeBountySuccess(key: String, state: BountyState) {
        state.concludedBountyKeys.add(key)
        state.defeatedBountyKeys.remove(key)
        state.settlementRequests.remove(key)

        // 保险：显式写入 memKey，作为后续单 gating 条件。
        // MagicBounty 通常会处理这一点，但这里主动写入可避免版本/配置差异导致的门槛失效。
        Global.getSector()?.memoryWithoutUpdate?.set("\$astd_battle_$key", true)
        Global.getSector()?.memoryWithoutUpdate?.set("\$$key", true)

        val def = MainBounties.defsByKey[key] ?: return
        state.succeededBountyKeys.add(key)
        state.mainCompleted += 1

        // 单票报酬采用接取时锁定的报价，避免战斗胜利后重掷金额。
        val quoted = state.quotedRewards.remove(def.key)
            ?: BountyRewards.computeTicketPayout(
                def.rewardMin, def.rewardMax,
                cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl.fixedScale,
                java.util.Random(def.key.hashCode().toLong()),
            )
        val payout = BountyRewards.grantMainPayout(def, quoted)
        if (payout > 0) {
            recordLedger(
                state, def.code, payout.toLong(),
                I18n[BountyKeys.I18N_CATEGORY, "terminal.ledger.note.payout"],
            )
        }

        // 清算序列进度：剧本化读数推进（含第三章的反常回跳）。
        def.liquidationDisplay?.let { state.liquidationProgress = it }

        // 批次/线路结清：结清奖金 + 回执。
        val newGroups = MainlineProgression.newlyClearedGroups(state.succeededBountyKeys, state.clearedGroupIds)
        for (group in newGroups) {
            state.clearedGroupIds.add(group.id)
            val bonus = BountyRewards.grantGroupBonus(group)
            if (bonus > 0) {
                recordLedger(
                    state, I18n[BountyKeys.I18N_CATEGORY, "group.${group.id}.title"], bonus.toLong(),
                    I18n[BountyKeys.I18N_CATEGORY, "terminal.ledger.note.group_bonus"],
                )
            }
            if (group.receiptKey != null) {
                HudMessages.campaign(
                    I18n.t(BountyKeys.I18N_CATEGORY, group.receiptKey, "bonus" to bonus.toString()),
                    RECEIPT_COLOR,
                )
            }
        }

        // 章节结清：承包商等级递升 + 章末回执 + 终局 gating。
        val newChapters = MainlineProgression.newlyClearedChapters(state.succeededBountyKeys, state.completedChapters)
        for (chapter in newChapters) {
            state.completedChapters.add(chapter)
            onChapterCleared(chapter, state)
        }
    }

    private fun onChapterCleared(chapter: Int, state: BountyState) {
        val sector = Global.getSector() ?: return
        state.contractorLevel = MainlineProgression.contractorLevelAfterChapter(chapter)
            .coerceAtLeast(state.contractorLevel)

        when (chapter) {
            // 序章结清：承包商注册确认（档案室/工单终端开放由空间站交互侧承接）。
            0 -> HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "chapter.0.receipt"], RECEIPT_COLOR)
            // 第一章结清：两份封存工单类目解锁（第二章双线入口）+ 遗址双星系即时生成。
            1 -> {
                HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "chapter.1.receipt"], RECEIPT_COLOR)
                StoryWorldBootstrap.notifyChapterTwoUnlocked()
            }
            // 第二章结清：清算序列进度首次显示（97.3%）+ 重复目标提示（第三章开场钩子）。
            2 -> {
                state.liquidationProgress = CH2_END_LIQUIDATION
                HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "chapter.2.receipt"], RECEIPT_COLOR)
            }
            // 第三章结清：封存类目全部结清，无编号半行工单抬头（第四章入口）。
            3 -> HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "chapter.3.receipt"], RECEIPT_COLOR)
            // 第四章结清：进度 100.0%，终局事件——档案处置申请受理，等玩家到终端签署（第五章）。
            4 -> {
                state.liquidationProgress = 100.0f
                state.archivalPending = true
                sector.memoryWithoutUpdate.set(BountyKeys.MEM_ARCHIVE_PENDING, true)
                HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "chapter.4.receipt"], RECEIPT_COLOR)
            }
        }
    }

    /**
     * 主线单失败终态处理：重置赏金重新挂出，并清掉本侧的补丁/gating 痕迹。
     */
    private fun resetMainBounty(coord: MagicBountyCoordinator, key: String, state: BountyState) {
        log.info("[BountyCampaignManager] 主线赏金进入失败终态，重置重新挂出：$key")
        state.patchedBountyKeys.remove(key)
        try {
            coord.resetBounty(key)
        } catch (t: Throwable) {
            log.warn("[BountyCampaignManager] resetBounty 失败（$key）：${t.message}", t)
        }
        Global.getSector()?.memoryWithoutUpdate?.unset("\$$key")
        // 保险：加载器内部重载后定义可能丢失，补注册。
        MainBounties.defsByKey[key]?.let { MagicBountyBridge.registerMainBounty(it, overwrite = true) }
    }

    private fun parseThreatTierFromSpec(desc: String?): Int {
        if (desc == null) return 3
        val idx = desc.indexOf('T')
        if (idx >= 0 && idx + 1 < desc.length) {
            val c = desc[idx + 1]
            if (c in '1'..'6') return (c - '0')
        }
        return 3
    }
}
