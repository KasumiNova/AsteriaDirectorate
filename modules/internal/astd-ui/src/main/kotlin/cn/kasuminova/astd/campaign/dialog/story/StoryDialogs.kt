package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogAction
import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.DialogNode
import cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 故事对话工厂：把纯数据的 [StoryScript] 编译为可运行的 `DialogGraph` / `GraphDialogPlugin`。
 *
 * - 文本全部经 `I18n`（category + keyPrefix + 相对 key）解析，支持命名占位符变量；
 * - 叙事行（NARRATION）以灰色 + 淡入呈现，与角色台词区分观感；
 * - 可变状态只落在 `DialogContext.sessionState` 与 playerMemory，节点/脚本本身无状态；
 * - [StoryScript.allowEscape] = false 时不向 `GraphDialogPlugin` 注册 Escape 选项，对话不可退出。
 */
object StoryDialogs {

    private val log by lazy { Global.getLogger(StoryDialogs::class.java) }

    /** 叙事/旁白文本颜色（区别于角色台词的默认白）。 */
    private val narrationColor: Color
        get() = Misc.getGrayColor()

    /**
     * 编译为 `DialogGraph`；脚本非法时抛 [IllegalArgumentException]（附带全部问题清单）。
     *
     * @param vars 命名占位符变量（每次输出台词时取值，可携带玩家名等运行时数据）
     * @param callbacks 命名回调表（campaign 侧业务逻辑的挂载点）
     */
    fun compile(
        script: StoryScript,
        vars: () -> List<Pair<String, Any?>> = { emptyList() },
        callbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): DialogGraph {
        val problems = script.validate()
        require(problems.isEmpty()) {
            "StoryScript '${script.id}' 校验失败：\n" + problems.joinToString("\n")
        }

        return dialogGraph(start = script.startNodeId) {
            for (node in script.nodes) {
                node(node.id, compileNode(script, node, vars, callbacks))
            }
        }
    }

    /** 编译并创建 `GraphDialogPlugin`；Escape 行为完全由脚本声明决定。 */
    fun createPlugin(
        script: StoryScript,
        vars: () -> List<Pair<String, Any?>> = { emptyList() },
        callbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): GraphDialogPlugin {
        val graph = compile(script, vars, callbacks)
        return GraphDialogPlugin(
            graph = graph,
            closeOnEscapeOptionId = if (script.allowEscape) script.escapeOptionId else null,
            closeOnEscapeText = if (script.escapeTextKey != null) {
                I18n[script.category, script.keyPrefix + script.escapeTextKey]
            } else {
                I18n[I18n.Categories.MOD, "dialog.core.leave"]
            },
        )
    }

    /** 直接对目标实体弹出对话。 */
    fun open(
        target: SectorEntityToken,
        script: StoryScript,
        vars: () -> List<Pair<String, Any?>> = { emptyList() },
        callbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): Boolean {
        val ui = Global.getSector()?.campaignUI ?: return false
        return ui.showInteractionDialog(createPlugin(script, vars, callbacks), target)
    }

    private fun compileNode(
        script: StoryScript,
        node: StoryNode,
        vars: () -> List<Pair<String, Any?>>,
        callbacks: Map<String, (DialogContext) -> Unit>,
    ): DialogNode {
        val onEnter: (DialogContext) -> Unit = { ctx ->
            // 中断恢复判定必须先于标记写入，否则首次进入就会被当作恢复。
            val resume = node.resumeFlagKey != null && ctx.playerMemory?.getBoolean(node.resumeFlagKey) == true
            val lines = if (resume) node.resumeLines else node.lines
            for (flag in node.setMemoryFlags) {
                ctx.playerMemory?.set(flag, true, 0f)
            }
            node.enterCallback?.let { id ->
                val cb = callbacks[id]
                if (cb != null) {
                    cb(ctx)
                } else {
                    log.warn("StoryScript '${script.id}': 节点 '${node.id}' 缺少命名回调 '$id'")
                }
            }
            outputLines(ctx, script, lines, vars(), timed = node.timed)
        }

        val onAdvance: (DialogContext, Float) -> Unit = adv@{ ctx, _ ->
            val autoNext = node.autoNext
            if (!node.autoClose && autoNext == null) return@adv
            // timed 节点：台词队列播完后自动跳转/关闭；“跳过” flush 后同样在下一帧生效。
            if (ctx.textQueue.hasPending) return@adv
            if (node.autoClose) ctx.close() else ctx.goto(autoNext!!)
        }

        val options: (DialogContext) -> List<DialogOptionSpec> = { ctx ->
            node.options.map { compileOption(script, node, it, ctx, vars(), callbacks) }
        }

        return if (node.timed) {
            DialogDsl.timedNode(onEnter = onEnter, onAdvance = onAdvance, options = options)
        } else {
            DialogDsl.node(onEnter = onEnter, onAdvance = onAdvance, options = options)
        }
    }

    private fun compileOption(
        script: StoryScript,
        node: StoryNode,
        option: StoryOption,
        ctx: DialogContext,
        vars: List<Pair<String, Any?>>,
        callbacks: Map<String, (DialogContext) -> Unit>,
    ): DialogOptionSpec {
        var text = I18n.t(script.category, script.keyPrefix + option.textKey, *vars.toTypedArray())

        val readKey = "story.read.${script.id}.${option.id}"
        if (option.rereadable && ctx.sessionState[readKey] == true && node.rereadSuffixKey != null) {
            text += I18n.t(script.category, script.keyPrefix + node.rereadSuffixKey)
        }

        val action = mapAction(script, option.action, vars, callbacks)
        val finalAction = if (option.rereadable || option.sessionFlag != null) {
            DialogAction.Run(
                block = { c ->
                    if (option.rereadable) {
                        c.sessionState[readKey] = true
                        c.markOptionsDirty()
                    }
                    option.sessionFlag?.let { c.sessionState["story.flag.${script.id}.$it"] = true }
                },
                then = action,
            )
        } else {
            action
        }

        return DialogOptionSpec(id = option.id, text = text, action = finalAction)
    }

    private fun mapAction(
        script: StoryScript,
        action: StoryOptionAction,
        vars: List<Pair<String, Any?>>,
        callbacks: Map<String, (DialogContext) -> Unit>,
    ): DialogAction = when (action) {
        is StoryOptionAction.Goto -> DialogAction.Goto(action.nodeId)
        StoryOptionAction.Close -> DialogAction.Close()
        is StoryOptionAction.Reply -> DialogAction.Run(
            block = { ctx -> outputLines(ctx, script, action.lines, vars, timed = false) },
            then = action.then?.let { mapAction(script, it, vars, callbacks) },
        )
        is StoryOptionAction.Callback -> DialogAction.Run(
            block = { ctx ->
                val cb = callbacks[action.id]
                if (cb != null) {
                    cb(ctx)
                } else {
                    log.warn("StoryScript '${script.id}': 缺少命名回调 '${action.id}'")
                }
            },
            then = action.then?.let { mapAction(script, it, vars, callbacks) },
        )
    }

    private fun outputLines(
        ctx: DialogContext,
        script: StoryScript,
        lines: List<StoryLine>,
        vars: List<Pair<String, Any?>>,
        timed: Boolean,
    ) {
        for (line in lines) {
            val rendered = I18n.tr(script.category, script.keyPrefix + line.key, *vars.toTypedArray())
            if (timed) {
                when (line.style) {
                    StoryLineStyle.NARRATION -> ctx.enqueueI18nFading(
                        rendered, line.delay, fadeIn = 0.35f, baseColor = narrationColor,
                    )
                    StoryLineStyle.SPEECH -> ctx.enqueueI18n(rendered, line.delay)
                }
            } else {
                when (line.style) {
                    StoryLineStyle.NARRATION -> ctx.sayI18n(rendered, narrationColor)
                    StoryLineStyle.SPEECH -> ctx.sayI18n(rendered)
                }
            }
        }
    }
}
