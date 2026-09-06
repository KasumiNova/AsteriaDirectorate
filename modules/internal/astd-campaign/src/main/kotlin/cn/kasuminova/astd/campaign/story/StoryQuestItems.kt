package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SpecialItemData
import org.apache.log4j.Logger
import java.awt.Color

/**
 * 剧情任务物品（doc 03 节拍 2~3、doc 07「任务物品锁定」）。
 *
 * 物品定义走 SpecialItemData 路线（contents/data/campaign/special_items.csv）：
 * - 「任务物品锁定」为双层口径：
 *   1. 原版 UI 约定层——交割物统一带 `mission_item, no_drop, no_drop_salvage` 标签，
 *      原版货运 UI 对 `mission_item` 直接拒绝向子市场/仓储侧转移
 *      （CargoTransferHandler/CargoDisplayV2 口径），`no_drop` 族阻止打捞掉落与抛洒；
 *   2. 本模组硬校验层——交付核销前置 [playerHas] 校验、交付动作走 [consumeFromPlayer]
 *      （见 [cn.kasuminova.astd.campaign.story.BranchStationBackendImpl] 核销流程）；
 *      工单已击毁待核销而交割物未持有时核销台按底档自动补发一份（D9 自愈：打捞后
 *      丢弃/未捡/被原版意外移除的兜底），未击毁工单缺交割物仍拒绝受理；
 * - 交付入口：分局站工单终端核销与遗址站「回收托管资产」交互（[StorySites]）。
 *
 * 物品显示名/描述文案：special_items.csv 的 name/desc 列；对话内引用名取
 * strings.json `story.item.name.<itemId>`（与 CSV 保持一致）。
 */
object StoryQuestItems {

    private val log: Logger = Global.getLogger(StoryQuestItems::class.java)

    /** 委托文书「XW-c206-0447／核销-03」（序章签署时发放，可反复阅读，不随核销回收）。 */
    const val DOC_XW_C206_0447: String = "astd_doc_xw_c206_0447"

    /** 铅封数据柜（序章核销-03 交割物；击毁目标后打捞入舱，doc 03 节拍 3）。 */
    const val SEALED_DATA_VAULT: String = "astd_asset_sealed_data_vault"

    /** 未交付设计原型（星坠线 XC-c208-0221 交割物；试验场核心库回收，doc 07）。 */
    const val DESIGN_PROTOTYPE: String = "astd_asset_design_prototype"

    /** 科研数据核心（紫菀线 ZW-c208-0309 交割物；核心数据舱回收，doc 07）。 */
    const val DATA_CORE: String = "astd_asset_data_core"

    /** 击毁最终阶段后打捞即入玩家货舱的交割物（工单 key → 物品 id）。 */
    private val SALVAGE_ON_DESTROY: Map<String, String> = mapOf(
        MainBounties.KEY_PROLOGUE to SEALED_DATA_VAULT,
    )

    /** 核销时必须随单交付的交割物（工单 key → 物品 id）。 */
    val REQUIRED_DELIVERABLE: Map<String, String> = mapOf(
        MainBounties.KEY_PROLOGUE to SEALED_DATA_VAULT,
        MainBounties.KEY_XC_0221 to DESIGN_PROTOTYPE,
        MainBounties.KEY_ZW_0309 to DATA_CORE,
    )

    /** 该工单最终阶段击毁后需打捞入舱的交割物（无则 null）。 */
    fun salvageOnDestroy(orderKey: String): String? = SALVAGE_ON_DESTROY[orderKey]

    /**
     * 工单最终阶段击毁时的交割物打捞（[cn.kasuminova.astd.campaign.bounty.MainBountyBridge.onMainBountySucceeded] 调用点）。
     * 无打捞物的工单为空操作。
     */
    fun onFinalStageDestroyed(orderKey: String) {
        val itemId = SALVAGE_ON_DESTROY[orderKey] ?: return
        grantToPlayer(itemId)
        HudMessages.campaign(
            I18n.t(I18n.Categories.MOD, "story.item.grant.$itemId"),
            Color(200, 170, 120),
        )
    }

    /** 把物品放进玩家货舱（1 件）。货舱不可用记错误日志。 */
    fun grantToPlayer(itemId: String) {
        val cargo = Global.getSector()?.playerFleet?.cargo
        if (cargo == null) {
            log.error("[ASTD] 发放任务物品失败：playerFleet/cargo 不可用（$itemId）")
            return
        }
        cargo.addSpecial(SpecialItemData(itemId, null), 1f)
        log.info("[ASTD] 任务物品已入舱：$itemId")
    }

    /** 玩家货舱是否持有该物品（至少 1 件）。 */
    fun playerHas(itemId: String): Boolean {
        val cargo = Global.getSector()?.playerFleet?.cargo ?: return false
        return cargo.stacksCopy.any {
            it.isSpecialStack && it.specialItemSpecIfSpecial?.id == itemId && it.size >= 1f
        }
    }

    /**
     * 从玩家货舱消耗 1 件该物品（交付核销）。
     * @return 是否成功消耗（未持有时返回 false 并记告警日志——调用方应先经 [playerHas] 校验）
     */
    fun consumeFromPlayer(itemId: String): Boolean {
        val cargo = Global.getSector()?.playerFleet?.cargo
        val stack = cargo?.stacksCopy?.firstOrNull {
            it.isSpecialStack && it.specialItemSpecIfSpecial?.id == itemId && it.size >= 1f
        }
        if (stack == null) {
            log.warn("[ASTD] 消耗任务物品失败：玩家未持有（$itemId）")
            return false
        }
        stack.subtract(1f)
        log.info("[ASTD] 任务物品已交付：$itemId")
        return true
    }
}
