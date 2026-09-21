package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 「执行官」核心连线对话（第五章结局内容；AI 核心的「声音」，docs/story/13）。
 *
 * 结构：`entry`（timed：核心上线播报，按特化分战斗/行政两套声线）→
 * `menu`（签发记录 / 来历 / 断开连接）。从分局站主菜单「与『执行官』连线」进入
 * （[BranchStationDialog] 切换插件），断开即关闭整个交互对话。
 *
 * 文案键族（category=asteria_directorate，ending_strings.json）：`dialog.executor.*`。
 */
object ExecutorCoreDialog {

    const val NODE_ENTRY: String = "entry"
    const val NODE_MENU: String = "menu"

    private const val OPT_RECORD = "record"
    private const val OPT_ORIGIN = "origin"
    private const val OPT_DISCONNECT = "disconnect"

    /** sessionState：entry 自动跳转标记。 */
    private const val STATE_ENTRY_FIRED = "executor.entry.fired"

    private val CAT = I18n.Categories.MOD

    /** 构建核心连线插件。[spec] 为签发特化（null 时按战斗声线处理——签发物品存在即必已签发）。 */
    fun createPlugin(spec: ExecutorSpec?): GraphDialogPlugin =
        GraphDialogPlugin(
            graph = createGraph(spec),
            closeOnEscapeOptionId = OPT_DISCONNECT,
            closeOnEscapeText = I18n[CAT, "dialog.executor.option.disconnect"],
        )

    /** 构建对话图（测试可直接消费）。 */
    fun createGraph(spec: ExecutorSpec?): DialogGraph {
        val specId = spec?.name?.lowercase() ?: ExecutorSpec.COMBAT.name.lowercase()
        return dialogGraph(start = NODE_ENTRY) {
            node(
                NODE_ENTRY, DialogDsl.timedNode(
                    onEnter = { ctx ->
                        ctx.sessionState[STATE_ENTRY_FIRED] = false
                        ctx.enqueueI18n(CAT, "dialog.executor.voice.$specId.1", 0.4f)
                        ctx.enqueueI18n(CAT, "dialog.executor.voice.$specId.2", 0.9f)
                        ctx.enqueueI18n(CAT, "dialog.executor.voice.common.online", 0.9f)
                    },
                    onAdvance = { ctx, _ ->
                        if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_ENTRY_FIRED] != true) {
                            ctx.sessionState[STATE_ENTRY_FIRED] = true
                            ctx.goto(NODE_MENU)
                        }
                    },
                )
            )
            node(NODE_MENU, DialogDsl.node { _ ->
                listOf(
                    DialogDsl.option(
                        id = OPT_RECORD,
                        text = I18n[CAT, "dialog.executor.option.record"],
                        action = DialogDsl.run { c -> c.sayI18n(CAT, "dialog.executor.answer.record") },
                    ),
                    DialogDsl.option(
                        id = OPT_ORIGIN,
                        text = I18n[CAT, "dialog.executor.option.origin"],
                        action = DialogDsl.run { c -> c.sayI18n(CAT, "dialog.executor.answer.origin") },
                    ),
                    DialogDsl.option(
                        id = OPT_DISCONNECT,
                        text = I18n[CAT, "dialog.executor.option.disconnect"],
                        action = DialogDsl.run(then = DialogDsl.close()) { c ->
                            c.sayI18n(CAT, "dialog.executor.answer.disconnect")
                        },
                    ),
                )
            })
        }
    }
}
