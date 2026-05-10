package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 第二幕事件：「回声」首次接触。
 *
 * 触发：完成 2-1 星云之心后，玩家前往坐标点与神秘护卫舰接触。
 */
object EchoFirstContactDialog {

    private const val NODE_INTRO = "intro"
    private const val NODE_ASK_GRUDGE = "ask_grudge"
    private const val NODE_ASK_COLLAPSE = "ask_collapse"
    private const val NODE_ASK_INTENT = "ask_intent"
    private const val NODE_DISTRUST = "distrust"
    private const val NODE_FINISH = "finish"

    private const val OPT_ASK_GRUDGE = "echo_ask_grudge"
    private const val OPT_ASK_COLLAPSE = "echo_ask_collapse"
    private const val OPT_ASK_INTENT = "echo_ask_intent"
    private const val OPT_DISTRUST = "echo_distrust"
    private const val OPT_FINISH = "echo_finish"
    private const val OPT_LEAVE = "echo_leave"

    private const val STATE_ASKED_GRUDGE = "echo_asked_grudge"
    private const val STATE_ASKED_COLLAPSE = "echo_asked_collapse"
    private const val STATE_ASKED_INTENT = "echo_asked_intent"
    private const val STATE_DISTRUSTED = "echo_distrusted"

    private val CAT = I18n.Categories.MOD

    fun graph(): DialogGraph = dialogGraph(start = NODE_INTRO) {

        // ═══════════════════════════════════════════════════════════
        // Node: Intro
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_INTRO,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.text.clear()
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.0", delay = 0f, fadeIn = 0.4f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.2", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.3", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.4", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.5", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.6", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.7", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.8", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.9", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.10", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.intro.11", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx -> buildIntroOptions(ctx.sessionState) }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // Branch Nodes
        // ═══════════════════════════════════════════════════════════

        // A. Ask Grudge
        node(
            NODE_ASK_GRUDGE,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_GRUDGE] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.echo.option.askGrudge"])
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.1", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.2", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.3", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.4", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.5", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.6", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.7", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askGrudge.8", delay = 1.2f, fadeIn = 0.3f)
                },
                options = { ctx -> buildBranchReturnOptions(ctx.sessionState) }
            )
        )

        // B. Ask Collapse
        node(
            NODE_ASK_COLLAPSE,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_COLLAPSE] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.echo.option.askCollapse"])
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.2", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.3", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.4", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.5", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.6", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.7", delay = 1.8f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askCollapse.8", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx -> buildBranchReturnOptions(ctx.sessionState) }
            )
        )

        // C. Ask Intent
        node(
            NODE_ASK_INTENT,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_INTENT] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.echo.option.askIntent"])
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.1", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.2", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.3", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.4", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.5", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.6", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.7", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.askIntent.8", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx -> buildBranchReturnOptions(ctx.sessionState) }
            )
        )

        // D. Distrust
        node(
            NODE_DISTRUST,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_DISTRUSTED] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.echo.option.distrust"])
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.2", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.3", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.4", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.5", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.6", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.7", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.8", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.echo.distrust.9", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx -> buildBranchReturnOptions(ctx.sessionState) }
            )
        )
    }

    private fun buildIntroOptions(state: MutableMap<String, Any?>): List<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec> {
        val askedGrudge = state[STATE_ASKED_GRUDGE] == true
        val askedCollapse = state[STATE_ASKED_COLLAPSE] == true
        val askedIntent = state[STATE_ASKED_INTENT] == true
        val distrusted = state[STATE_DISTRUSTED] == true

        return listOf(
            DialogDsl.option(
                id = OPT_ASK_GRUDGE,
                text = I18n[CAT, "dialog.echo.option.askGrudge"],
                action = DialogDsl.goto(NODE_ASK_GRUDGE),
                enabled = !askedGrudge
            ),
            DialogDsl.option(
                id = OPT_ASK_COLLAPSE,
                text = I18n[CAT, "dialog.echo.option.askCollapse"],
                action = DialogDsl.goto(NODE_ASK_COLLAPSE),
                enabled = !askedCollapse
            ),
            DialogDsl.option(
                id = OPT_ASK_INTENT,
                text = I18n[CAT, "dialog.echo.option.askIntent"],
                action = DialogDsl.goto(NODE_ASK_INTENT),
                enabled = !askedIntent
            ),
            DialogDsl.option(
                id = OPT_DISTRUST,
                text = I18n[CAT, "dialog.echo.option.distrust"],
                action = DialogDsl.goto(NODE_DISTRUST),
                enabled = !distrusted
            ),
            DialogDsl.option(
                id = OPT_FINISH,
                text = I18n[CAT, "dialog.echo.option.finish"],
                action = DialogDsl.close()
            )
        )
    }

    private fun buildBranchReturnOptions(state: MutableMap<String, Any?>): List<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec> {
        val askedGrudge = state[STATE_ASKED_GRUDGE] == true
        val askedCollapse = state[STATE_ASKED_COLLAPSE] == true
        val askedIntent = state[STATE_ASKED_INTENT] == true
        val distrusted = state[STATE_DISTRUSTED] == true

        val options = mutableListOf<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec>()

        if (!askedGrudge) {
            options.add(DialogDsl.option(OPT_ASK_GRUDGE, I18n[CAT, "dialog.echo.option.askGrudge"], DialogDsl.goto(NODE_ASK_GRUDGE)))
        }
        if (!askedCollapse) {
            options.add(DialogDsl.option(OPT_ASK_COLLAPSE, I18n[CAT, "dialog.echo.option.askCollapse"], DialogDsl.goto(NODE_ASK_COLLAPSE)))
        }
        if (!askedIntent) {
            options.add(DialogDsl.option(OPT_ASK_INTENT, I18n[CAT, "dialog.echo.option.askIntent"], DialogDsl.goto(NODE_ASK_INTENT)))
        }
        if (!distrusted) {
            options.add(DialogDsl.option(OPT_DISTRUST, I18n[CAT, "dialog.echo.option.distrust"], DialogDsl.goto(NODE_DISTRUST)))
        }

        options.add(
            DialogDsl.option(
                id = OPT_FINISH,
                text = I18n[CAT, "dialog.echo.option.finish"],
                action = DialogDsl.close()
            )
        )
        return options
    }

    fun open(target: SectorEntityToken) {
        Global.getSector()?.campaignUI?.showInteractionDialog(
            GraphDialogPlugin(
                graph(),
                closeOnEscapeOptionId = OPT_FINISH,
                closeOnEscapeText = I18n[CAT, "dialog.echo.option.finish"]
            ),
            target
        )
    }
}
