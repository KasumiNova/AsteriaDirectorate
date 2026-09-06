package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.ending.InfiniteBountyBridge
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 战役侧管理脚本：监听 MagicBounty 的 ActiveBounty 状态，
 * 在“接受”后对目标 fleet 应用难度缩放与词缀重建，并在结算后维护 [BountyState]。
 *
 * 主线工单（[MainBounties]）由本脚本驱动：
 * - 接受后按注册表定义（当前阶段）重建舰队；
 * - 击毁后推进多阶段/登记待核销（[MainBountyBridge.onMainBountySucceeded]）；
 * - 失败终态自动重挂（[MainBountyBridge.onMainBountyFailed] + [MainBountyBridge.tickPosted]）；
 * - 结算发放留给「分局终端交付」（[MainBountyBridge.settleWorkOrder]）。
 */
class BountyCampaignManager : EveryFrameScript {

    private companion object {
        private val log: Logger = Global.getLogger(BountyCampaignManager::class.java)
    }
    private var timer = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 0.7f) return
        timer = 0f

        if (Global.getSector() == null) return

        val state = BountyState.getOrCreate()

        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            return
        }

        val active = coord.activeBounties

        // 会修改 MagicBounty 侧集合的动作（重置/重挂）延迟到遍历结束后执行，避免并发修改
        val deferred = ArrayList<() -> Unit>()

        for ((key, bounty) in active) {
            if (!key.startsWith(BountyKeys.BOUNTY_KEY_PREFIX)) continue

            val main = MainBounties.byKey(key)
            // 无限赏金（《无限期承包合同》常驻工单）：key 含换代序号，按当前代反查槽位
            val infiniteSlot = if (main == null && key.startsWith(InfiniteBountyGenerator.KEY_PREFIX)) {
                InfiniteBountyBridge.slotOf(state, key)
            } else {
                null
            }

            // 1) 接受后：动态重建舰队（以 fleet 级 memory 为准，主线阶段重挂后可再次重建）
            if (bounty.stage == ActiveBounty.Stage.Accepted &&
                !bounty.fleet.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_FLEET_PATCHED)
            ) {
                patchAcceptedBounty(key, bounty, state, main, infiniteSlot)
            }

            if (main != null) {
                collectMainlineActions(key, bounty, main, state, coord, deferred)
            } else if (infiniteSlot != null) {
                collectInfiniteActions(key, bounty, state, coord, deferred)
            } else if (bounty.stage.ordinal >= ActiveBounty.Stage.Succeeded.ordinal && key !in state.concludedBountyKeys) {
                concludeBounty(key, state)
            }
        }

        deferred.forEach { it() }

        // 2) 主线：失败/消失重挂 + 挂出 gating 已满足的工单
        MainBountyBridge.tickPosted(state, coord)

        // 3) 无限赏金：补齐槽位 + 挂出待接取 + 失败/消失重挂
        InfiniteBountyBridge.tick(state, coord)
    }

    /**
     * 无限赏金的终态处理登记：成功（Succeeded）→ 已击毁待核销；失败终态 → 重置归待接取。
     * 以 key（含换代序号）标记防重，重挂时由 [InfiniteBountyBridge.postSlot] 清理。
     */
    private fun collectInfiniteActions(
        key: String,
        bounty: ActiveBounty,
        state: BountyState,
        coord: MagicBountyCoordinator,
        deferred: MutableList<() -> Unit>,
    ) {
        when {
            bounty.stage == ActiveBounty.Stage.Succeeded && key !in state.concludedBountyKeys -> {
                state.concludedBountyKeys.add(key)
                deferred += { InfiniteBountyBridge.onSucceeded(key, state) }
            }
            bounty.stage.ordinal in ActiveBounty.Stage.FailedSalvagedFlagship.ordinal..ActiveBounty.Stage.EndedWithoutPlayerInvolvement.ordinal -> {
                // 失败终态不加标记：resetBounty 后条目即移除；消失兜底走 InfiniteBountyBridge.tick
                deferred += { InfiniteBountyBridge.onFailed(key, state, coord) }
            }
        }
    }

    /**
     * 主线工单的终态处理登记：成功（Succeeded）推进阶段/待核销；失败终态重置重挂。
     * 以 `key#stageIndex` 标记防重，重挂时由 [MainBountyBridge.postWorkOrder] 清理。
     */
    private fun collectMainlineActions(
        key: String,
        bounty: ActiveBounty,
        def: MainBounties.WorkOrder,
        state: BountyState,
        coord: MagicBountyCoordinator,
        deferred: MutableList<() -> Unit>,
    ) {
        val stageIndex = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        val marker = "$key#$stageIndex"
        when {
            bounty.stage == ActiveBounty.Stage.Succeeded && marker !in state.concludedBountyKeys -> {
                state.concludedBountyKeys.add(marker)
                deferred += { MainBountyBridge.onMainBountySucceeded(key, def, state, coord) }
            }
            bounty.stage.ordinal in ActiveBounty.Stage.FailedSalvagedFlagship.ordinal..ActiveBounty.Stage.EndedWithoutPlayerInvolvement.ordinal -> {
                // 失败终态不加标记：resetBounty 后条目即移除；重挂走 tickPosted 的消失探测兜底
                deferred += { MainBountyBridge.onMainBountyFailed(key, state, coord) }
            }
        }
    }

    private fun patchAcceptedBounty(
        key: String,
        bounty: ActiveBounty,
        state: BountyState,
        main: MainBounties.WorkOrder?,
        infiniteSlot: cn.kasuminova.astd.campaign.bounty.InfiniteSlotState?,
    ) {
        val fleet = bounty.fleet
        val flagship = fleet.flagship ?: return

        val def: BountyDef
        val successText: String?
        if (main != null) {
            val stageIndex = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, main.stages.lastIndex)
            def = main.toBountyDef(stageIndex)
            successText = I18n["asteria_directorate_bounty", "main.${main.i18nId}.receipt"]
        } else if (infiniteSlot != null) {
            // 无限赏金：参数取槽位锁定值；词缀走固定表（生成时抽取锁定，文书「追加条款」栏具名）
            def = BountyDef(
                key = key,
                title = bounty.spec.job_name ?: key,
                shortDesc = bounty.spec.job_description ?: "",
                threatTier = infiniteSlot.danger,
                baselineFP = infiniteSlot.fp,
                flagshipVariantId = infiniteSlot.flagshipVariantId,
                requiredPreviousMainKey = null,
                isMain = false,
                allowRAffixes = true,
                allowAffixes = true,
                fixedAffixIds = infiniteSlot.affixIds,
            )
            successText = I18n.t(
                "asteria_directorate_bounty", "main.infinite.receipt",
                "serial" to InfiniteBountyGenerator.serialOf(infiniteSlot.index, infiniteSlot.generation),
            )
        } else {
            val tier = parseThreatTierFromSpec(bounty.spec.job_difficultyDescription)
            val baselineFP = bounty.spec.fleet_min_FP.coerceAtLeast(50)
            def = BountyDef(
                key = key,
                title = bounty.spec.job_name ?: key,
                shortDesc = bounty.spec.job_description ?: "",
                threatTier = tier,
                baselineFP = baselineFP,
                flagshipVariantId = bounty.spec.fleet_flagship_variant,
                requiredPreviousMainKey = null,
                isMain = false,
                // R 型词缀仅第三章赏金与结局后无限赏金开放（affixes.md v3）；
                // 动态赏金不开放 R 型。
                allowRAffixes = false,
            )
            // 动态 bounty：没有对应 i18n 表时，提供一个简短的结算提示。
            successText = I18n["asteria_directorate_bounty", "generic.success_text"]
        }

        val seed = (Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()) xor key.hashCode().toLong()
        val stageSeed = seed xor ((state.workOrderStageIndex[key] ?: 0) * 0x2545F4914F6CDD1DL)
        val comp = FleetComposer.buildComposition(def, stageSeed)
        patchFleetMembers(key, fleet, flagship, comp, successText = successText)
        state.patchedBountyKeys.add(key)
    }

    private fun patchFleetMembers(
        key: String,
        fleet: CampaignFleetAPI,
        flagship: FleetMemberAPI,
        comp: FleetComposer.Composition,
        successText: String?,
    ) {
        if (fleet.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_FLEET_PATCHED)) {
            return
        }
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_FLEET_PATCHED, true)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_BOUNTY_KEY, key)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_AFFIXES, comp.affixHullMods.joinToString(","))
        if (comp.flagshipAffixHullMods.isNotEmpty()) {
            fleet.memoryWithoutUpdate.set(BountyKeys.MEM_FLAGSHIP_AFFIXES, comp.flagshipAffixHullMods.joinToString(","))
        }
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_K, comp.k)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_TOTAL_MULT, comp.totalMult)

        // 让该舰队在交互时使用自定义的 FleetInteractionDialog 配置。
        // 注意：key 名是原版内部约定（拼写如此）。
        fleet.memoryWithoutUpdate.set("\$fidConifgGen", BountyFidConfigGen(key))

        // 某些原版逻辑会读取该 flag 来倾向“死战到底”。这里先开着，后续可按案子细化。
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
            k = comp.k,
            totalMult = comp.totalMult,
            affixHullMods = comp.affixHullMods,
            flagship = flagship,
            flagshipAffixHullMods = comp.flagshipAffixHullMods,
        )

        // 把创建出来的成员添加到 fleet
        for (m in created) {
            if (m !== flagship) {
                data.addFleetMember(m)
            }
        }
        data.setFlagship(flagship)
    }

    private fun concludeBounty(key: String, state: BountyState) {
        state.concludedBountyKeys.add(key)

        // `$<key>`（job_memKey）由 MagicLib 独占读写：接受时置 false、任意终态（含失败）置 true。
        // 本模组不重复写入，避免与 MagicLib 的值域打架；主线内容 gating 走自有键
        // （BountyKeys.MEM_DESTROYED_PREFIX / MEM_SETTLED_PREFIX，见 MainBountyBridge）。
    }

    private fun parseThreatTierFromSpec(desc: String?): Int {
        if (desc == null) return 3
        val idx = desc.indexOf('T')
        if (idx >= 0 && idx + 1 < desc.length) {
            val c = desc[idx + 1]
            if (c in '1'..'5') return (c - '0')
        }
        return 3
    }
}
