package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec
import cn.kasuminova.astd.campaign.ui.terminal.TerminalTab
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CoreUITabId
import com.fs.starfarer.api.campaign.InteractionDialogAPI

/**
 * 分局空间站入口对话（doc 03 节拍 4~5、doc 05「模组总 UI 入口」）。
 *
 * 本对话只保留入口职责：入场播报 + 主菜单。「工单终端 / 档案室 / 承包商账户」
 * 三入口统一打开全屏终端 UI（[cn.kasuminova.astd.campaign.ui.terminal.BranchTerminalUi]），
 * 由注入的 [TerminalOpener] 完成；另有停靠整备（关闭对话并打开原版核心 UI）与离开。
 *
 * 序章进度分相（[BranchStationPhase]）：
 * - LOCKED（未接序章）：占位说明 + 离开（doc 03：未到时候就是没到时候）；
 * - PENDING（已签未核销）：引导文本 + 终端入口 + 离开；
 * - OPEN（已注册）：三入口全开 + 停靠整备；
 *   持有「执行官」签发物品时追加「与『执行官』连线」入口（第五章结局内容，
 *   切换为 [ExecutorCoreDialog] 插件）。
 *
 * 文案键族（strings.json）：`story.branch.*`（终端 UI 文本另见 `ui.terminal.*`）。
 */
object BranchStationDialog {

    const val NODE_ENTRY: String = "entry"
    const val NODE_MENU: String = "menu"

    private const val OPT_LEAVE = "leave"
    private const val OPT_TERMINAL = "terminal"
    private const val OPT_ARCHIVES = "archives"
    private const val OPT_ACCOUNT = "account"
    private const val OPT_DOCK = "dock"
    private const val OPT_EXECUTOR = "executor"

    private const val STATE_ENTRY_FIRED = "branch.entry.fired"

    private val CAT = I18n.Categories.MOD

    /** 分局站功能分相。 */
    enum class BranchStationPhase { LOCKED, PENDING, OPEN }

    /** 分局站入口对话的数据后端（campaign 侧实现；入口分相由序章进度驱动）。 */
    interface BranchStationBackend {
        /** 当前功能分相。 */
        fun phase(): BranchStationPhase

        /** 玩家是否持有「执行官」签发物品（第五章结局内容；OPEN 相下显示连线入口）。 */
        fun hasExecutorCore(): Boolean

        /** 「执行官」签发特化（连线对话声线分派；未签发为 null）。 */
        fun executorSpec(): ExecutorSpec?
    }

    /** 全屏终端打开器（campaign/装配侧注入：一般为 `BranchTerminalUi.open` 的绑定）。 */
    fun interface TerminalOpener {
        /** 在 [dialog] 上打开全屏终端 UI 并定位到 [tab]。 */
        fun open(dialog: InteractionDialogAPI, tab: TerminalTab)
    }

    /** 构建分局站交互插件。 */
    fun createPlugin(backend: BranchStationBackend, terminalOpener: TerminalOpener): GraphDialogPlugin =
        GraphDialogPlugin(
            graph = createGraph(backend, terminalOpener),
            closeOnEscapeOptionId = OPT_LEAVE,
            closeOnEscapeText = I18n[CAT, "dialog.core.leave"],
        )

    /** 构建分局站对话图（测试可直接消费）。 */
    fun createGraph(backend: BranchStationBackend, terminalOpener: TerminalOpener): DialogGraph =
        dialogGraph(start = NODE_ENTRY) {
            node(NODE_ENTRY, DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ENTRY_FIRED] = false
                    val key = when (backend.phase()) {
                        BranchStationPhase.LOCKED -> "locked"
                        BranchStationPhase.PENDING -> "pending"
                        BranchStationPhase.OPEN -> "open"
                    }
                    ctx.enqueueI18nFading(CAT, "story.branch.intro.$key", 0.3f, fadeIn = 0.3f)
                },
                onAdvance = { ctx, _ ->
                    if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_ENTRY_FIRED] != true) {
                        ctx.sessionState[STATE_ENTRY_FIRED] = true
                        ctx.goto(NODE_MENU)
                    }
                },
            ))

            node(NODE_MENU, DialogDsl.node { ctx ->
                when (backend.phase()) {
                    BranchStationPhase.LOCKED -> listOf(leaveOption())
                    BranchStationPhase.PENDING -> listOf(
                        terminalOption(OPT_TERMINAL, TerminalTab.ORDERS, terminalOpener),
                        leaveOption(),
                    )
                    BranchStationPhase.OPEN -> buildList {
                        add(terminalOption(OPT_TERMINAL, TerminalTab.ORDERS, terminalOpener))
                        add(terminalOption(OPT_ARCHIVES, TerminalTab.ARCHIVES, terminalOpener))
                        add(terminalOption(OPT_ACCOUNT, TerminalTab.ACCOUNT, terminalOpener))
                        // 「与『执行官』连线」（第五章：签发物品在舱即出现；切换为核心对话插件）
                        if (backend.hasExecutorCore()) {
                            add(
                                DialogDsl.option(
                                    OPT_EXECUTOR,
                                    I18n[CAT, "story.branch.option.executor"],
                                    DialogDsl.run { c ->
                                        c.dialog.setPlugin(ExecutorCoreDialog.createPlugin(backend.executorSpec()))
                                    },
                                ),
                            )
                        }
                        add(
                            DialogDsl.option(
                                OPT_DOCK,
                                I18n[CAT, "story.branch.option.dock"],
                                DialogDsl.run(then = DialogDsl.close()) { c ->
                                    c.target?.let { Global.getSector()?.campaignUI?.showCoreUITab(CoreUITabId.CARGO, it) }
                                },
                            ),
                        )
                        add(leaveOption())
                    }
                }
            })
        }

    /** 「接入分局终端」系选项：打开全屏 UI（对话保持挂起，终端 Esc/关闭后回到本菜单）。 */
    private fun terminalOption(optionId: String, tab: TerminalTab, opener: TerminalOpener) =
        DialogDsl.option(
            id = optionId,
            text = I18n[CAT, "story.branch.option.$optionId"],
            action = DialogDsl.run { c -> opener.open(c.dialog, tab) },
        )

    private fun leaveOption() = DialogDsl.option(OPT_LEAVE, I18n[CAT, "dialog.core.leave"], DialogDsl.close())
}
