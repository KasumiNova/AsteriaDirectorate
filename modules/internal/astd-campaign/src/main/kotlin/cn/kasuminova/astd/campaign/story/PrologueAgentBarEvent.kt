package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.dialog.story.PrologueAgentDialog
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEvent
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventCreator

/**
 * 序章「代办」酒馆遭遇（docs/story/04 落地接线）。
 *
 * 原版酒馆事件流（BarCMD → BarEventDialogPlugin）只提供选项挂点：
 * [addPromptAndOption] 把本事件挂进酒吧选项；玩家选中后 [init] 被回调，
 * 此时将对话插件整体替换为 [PrologueAgentDialog] 的图对话插件——
 * 序章对话设计为不可退出、走完 end 节点自动关闭（关闭即退出整段交互）。
 *
 * 不使用 `PrologueAgentDialog.open`：酒吧事件选中时已有活动交互对话，
 * 再开一层 `showInteractionDialog` 会让 BarCMD 状态悬挂；`dialog.setPlugin`
 * 是原版事件流内部的既有换插件方式（BarCMD 同款）。
 */
class PrologueAgentBarEvent : BaseBarEvent() {

    /** 玩家已选中本事件（对话插件已接管）；供 PortsideBarData 清扫。 */
    private var consumed: Boolean = false

    override fun getBarEventId(): String = BAR_EVENT_ID

    /** 代办“久候”：事件存在期间始终出现在酒吧列表首位。 */
    override fun isAlwaysShow(): Boolean = true

    override fun addPromptAndOption(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        dialog.textPanel.addPara(I18n[I18n.Categories.MOD, "story.prologue.agent.bar.blurb"])
        dialog.optionPanel.addOption(I18n[I18n.Categories.MOD, "story.prologue.agent.bar.option"], this)
    }

    override fun init(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        super.init(dialog, memoryMap)
        consumed = true
        // 告知管理器本事件已被交互：从活动列表移除并让 creator 进入冷却（条件本身也会拦）。
        BarEventManager.getInstance()?.notifyWasInteractedWith(this)
        // 注意：isDialogFinished 必须保持 false——BarEventDialogPlugin.init 会在
        // 事件“已完成”时把插件换回 BarCMD，顶替掉我们的故事对话。
        val plugin = PrologueAgentDialog.createPlugin(PrologueContract.callbacks())
        dialog.setPlugin(plugin)
        plugin.init(dialog)
    }

    override fun shouldRemoveEvent(): Boolean = consumed

    companion object {
        const val BAR_EVENT_ID: String = "astd_prologue_agent"
    }
}

/**
 * 序章酒馆事件的 creator（[BarEventManager.addEventCreator] 注册）。
 *
 * 出现条件（docs/story/03：代办在玩家“资质核验通过”后找上门）：
 * - 玩家等级达到版本上限的 [LEVEL_FRACTION]（0.98a 上限 40 ⇒ 24 级）；
 * - 尚未签收序章文书（[BountyKeys.MEM_PROLOGUE_DOC_RECEIVED]）；
 * - 序章工单未结清。
 */
class PrologueAgentBarEventCreator : BaseBarEventCreator() {

    override fun getBarEventId(): String = PrologueAgentBarEvent.BAR_EVENT_ID

    override fun isPriority(): Boolean = true

    /** 代办长期等客：事件不过期（过期重roll反而会让酒馆遭遇闪烁）。 */
    override fun getBarEventActiveDuration(): Float = 1_000_000f

    override fun getBarEventAcceptedTimeoutDuration(): Float = 1_000_000f

    override fun createBarEvent(): PortsideBarEvent? {
        val sector = Global.getSector() ?: return null
        if (sector.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_PROLOGUE_DOC_RECEIVED)) return null
        if (MainBounties.KEY_PROLOGUE in BountyState.getOrCreate().succeededBountyKeys) return null

        val stats = sector.playerPerson?.stats ?: return null
        val maxLevel = Global.getSettings().getFloat("playerMaxLevel")
        if (stats.level < maxLevel * LEVEL_FRACTION) return null

        return PrologueAgentBarEvent()
    }

    companion object {
        /** 出现门槛：玩家等级 ≥ 最大玩家等级 × 60%。 */
        const val LEVEL_FRACTION: Float = 0.6f
    }
}
