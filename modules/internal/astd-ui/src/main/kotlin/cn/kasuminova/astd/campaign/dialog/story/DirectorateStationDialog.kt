package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.ui.DirectorateTerminal
import cn.kasuminova.astd.campaign.ui.TerminalTab
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 分局空间站服务对话：工单终端 / 档案室 / 归档核销 三选入口。
 *
 * 供 campaign 侧（空间站交互规则）调用：玩家与分局空间站交互后弹出本对话，
 * 三个功能选项分别打开模组总 UI 的对应页签；「离开终端」与 Escape 均可退出
 * （终端是玩家的地盘，与代办对话相反）。
 */
object DirectorateStationDialog {

    /** 命名回调：打开工单终端页签。 */
    const val CALLBACK_WORK_ORDERS: String = "station.ui.work_orders"

    /** 命名回调：打开档案室页签。 */
    const val CALLBACK_ARCHIVES: String = "station.ui.archives"

    /** 命名回调：打开归档/核销（账户）页签。 */
    const val CALLBACK_FILING: String = "station.ui.filing"

    const val OPT_LEAVE: String = "leave"

    private const val PREFIX: String = "story.station."

    /** 构建脚本（纯数据，可在单元测试中做结构校验）。 */
    fun script(): StoryScript = StoryScript(
        id = "astd_station_services",
        category = I18n.Categories.MOD.id,
        keyPrefix = PREFIX,
        startNodeId = "root",
        allowEscape = true,
        escapeOptionId = OPT_LEAVE,
        escapeTextKey = "option.leave",
        nodes = listOf(
            StoryNode(
                id = "root",
                lines = listOf(StoryLine("root.intro", style = StoryLineStyle.NARRATION)),
                options = listOf(
                    StoryOption("work_orders", "option.work_orders",
                        StoryOptionAction.Callback(CALLBACK_WORK_ORDERS)),
                    StoryOption("archives", "option.archives",
                        StoryOptionAction.Callback(CALLBACK_ARCHIVES)),
                    StoryOption("filing", "option.filing",
                        StoryOptionAction.Callback(CALLBACK_FILING)),
                    StoryOption(OPT_LEAVE, "option.leave", StoryOptionAction.Close),
                ),
            ),
        ),
    )

    /** 默认回调：三个功能选项打开模组总 UI 的对应页签。 */
    fun defaultCallbacks(): Map<String, (DialogContext) -> Unit> = mapOf(
        CALLBACK_WORK_ORDERS to { ctx ->
            DirectorateTerminal.showOn(ctx.dialog, TerminalTab.WORK_ORDERS)
        },
        CALLBACK_ARCHIVES to { ctx ->
            DirectorateTerminal.showOn(ctx.dialog, TerminalTab.ARCHIVES)
        },
        CALLBACK_FILING to { ctx ->
            DirectorateTerminal.showOn(ctx.dialog, TerminalTab.ACCOUNT)
        },
    )

    /**
     * 创建对话插件。
     *
     * @param extraCallbacks 额外/覆盖命名回调（campaign 侧可替换默认 UI 打开行为）
     */
    fun createPlugin(
        extraCallbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): GraphDialogPlugin =
        StoryDialogs.createPlugin(script(), callbacks = defaultCallbacks() + extraCallbacks)

    /** 对分局空间站实体弹出服务对话。 */
    fun open(
        target: SectorEntityToken,
        extraCallbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): Boolean = StoryDialogs.open(target, script(), callbacks = defaultCallbacks() + extraCallbacks)
}
