package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.intel.bar.BarEventDialogPlugin
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventCreator
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventWithPerson

/**
 * 序章酒馆遭遇「代办」（docs/story/03 节拍 1~2、doc 04 对话定稿）。
 *
 * 触发链路：
 * - [PrologueAgentBarEventCreator] 注册进 [BarEventManager]（StoryWorldBootstrap.onGameLoad）；
 * - 等级门槛：玩家等级 ≥ 游戏设置最大玩家等级（settings.json `playerMaxLevel`）× 60%，
 *   每次判定动态读取设置值（doc 03：避免玩家改设置后剧情永不触发）；不达标直接不出事件，零提示；
 * - 随机可居住星球酒馆：原版 BarEventManager 按权重检定创建事件，
 *   事件只在带 `habitable` 状况的行星市场酒馆出现；触发后全局唯一（[StoryDialogBackend.isPrologueAccepted]）；
 * - 玩家点选后把对话切换为 [GraphDialogPlugin] 驱动的九节点图（[PrologueAgentDialog]），
 *   图播完收束到关闭时通过 [BarEventDialogPlugin.endEvent] 交还酒馆流程。
 *
 * 再现冷却：签完或中断后，同一酒馆不再立刻重播本事件——该冷却由原版机制负责
 * （BarCMD 把已展示事件 id 写入市场 memory `$BarCMD_shownEvents`，BarEventManager
 * 内部按 20~40 天 IntervalUtil 滚动刷新），非本模组控制，本模组不实现冷却逻辑。
 *
 * XStream 存档兼容：本类只持有可序列化字段（基类 shownAt / person / seed 等），
 * 图/插件对象均为 transient，在 [init] 时重建。
 *
 * 右侧视觉面板：沿用原版酒馆人物事件模式（[BaseBarEventWithPerson] + init 时
 * `showPersonInfo`）——点进事件后右侧切换为代办人物卡；头像暂用原版 corporate
 * 占位素材（用户裁定：素材缺口先用原版资源），TODO(素材) 后续接入实际素材。
 */
class PrologueAgentBarEvent : BaseBarEventWithPerson() {

    /** 对话期间由本事件安装的图驱动插件（transient：存档后不恢复，读档时事件已结束或重进）。 */
    @Transient
    private var graphPlugin: GraphDialogPlugin? = null

    override fun getBarEventId(): String = PrologueAgentBarEventCreator.EVENT_ID

    override fun isAlwaysShow(): Boolean = true

    override fun shouldShowAtMarket(market: MarketAPI): Boolean {
        if (!super.shouldShowAtMarket(market)) return false
        if (StoryDialogBackends.get().isPrologueAccepted()) return false
        // 随机可居住星球酒馆：仅带 habitable 状况的行星市场
        return market.planetEntity != null && market.hasCondition(Conditions.HABITABLE)
    }

    override fun addPromptAndOption(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        // 原版模式：在展示选项前（按市场缓存）重建人物，保证 init 时 person 可用
        regen(dialog.interactionTarget.market)
        dialog.textPanel.addPara(I18n[CAT, "story.prologue.agent.bar.prompt"])
        dialog.optionPanel.addOption(I18n[CAT, "story.prologue.agent.bar.option"], this)
    }

    override fun getPersonPortrait(): String = "graphics/portraits/portrait_corporate03.png"

    override fun init(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        super.init(dialog, memoryMap)

        // 右侧视觉面板切换为代办人物卡（原版 BaseBarEventWithPerson 模式；
        // 交还酒馆时 BarCMD.showOptions 会 restoreSavedVisual 复原酒吧场景）
        dialog.visualPanel.showPersonInfo(person, true)

        // 中断恢复标记：谈话一开始即写入；强退后下次进酒馆重新触发并走差异开场白（doc 04）
        StoryDialogBackends.get().markPrologueAgentMet()

        // 当前激活插件是 BarEventDialogPlugin（BarCMD 先 setPlugin 再调 event.init）。
        // 替换为图驱动插件；对话图收束关闭时经 endEvent 交还酒馆。
        val barPlugin = dialog.plugin as? BarEventDialogPlugin
        val plugin = GraphDialogPlugin(
            graph = PrologueAgentDialog.createGraph(),
            closeOnEscapeOptionId = null,
            onClose = {
                done = true
                if (barPlugin != null) {
                    barPlugin.endEvent()
                } else {
                    Global.getLogger(PrologueAgentBarEvent::class.java)
                        .warn("序章酒馆对话关闭时宿主不是 BarEventDialogPlugin，改为直接 dismiss（dialog.plugin=${dialog.plugin?.javaClass?.name}）")
                    dialog.dismiss()
                }
            },
        )
        graphPlugin = plugin
        dialog.plugin = plugin
        plugin.init(dialog)
    }

    override fun shouldRemoveEvent(): Boolean = done || StoryDialogBackends.get().isPrologueAccepted()

    companion object {
        private val CAT = I18n.Categories.MOD
    }
}

/**
 * 序章代办事件的 Creator：等级门槛与唯一性判定（不达标/已接取时 `createBarEvent` 返回 null——
 * 原版管理器对本 tick 不做任何展示，玩家无感知，doc 03「触发失败不惩罚」）。
 */
class PrologueAgentBarEventCreator : BaseBarEventCreator() {

    override fun getBarEventId(): String = EVENT_ID

    override fun createBarEvent(): PrologueAgentBarEvent? {
        if (StoryDialogBackends.get().isPrologueAccepted()) return null
        val sector = Global.getSector() ?: return null
        val playerLevel = sector.playerPerson?.stats?.level ?: return null
        // 动态读设置：游戏设置最大玩家等级 × 60%（doc 03 触发条件）
        val threshold = (Global.getSettings().getInt("playerMaxLevel") * LEVEL_THRESHOLD_RATIO).toInt()
        if (playerLevel < threshold) return null
        return PrologueAgentBarEvent()
    }

    /** 剧情事件不占用常规权重池：一旦达标，下一次进可居住星球酒馆即出现（doc 03 节奏设计 30 分钟）。 */
    override fun isPriority(): Boolean = true

    /** 事件激活后长期有效：玩家不点不消失（直到存档级唯一性条件满足）。 */
    override fun getBarEventActiveDuration(): Float = 1_000_000f

    companion object {
        const val EVENT_ID: String = "astd_prologue_agent"

        /** 触发等级比例：设置最大玩家等级 × 60%（doc 03）。 */
        const val LEVEL_THRESHOLD_RATIO: Float = 0.6f
    }
}
