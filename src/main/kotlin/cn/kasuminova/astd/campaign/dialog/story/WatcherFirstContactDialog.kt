package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 「守望者」首次接触对话。
 *
 * 触发时机：完成任务 1-3「守望者的讯息」后首次通讯。
 * 对话结构：
 * - intro: 守望者自我介绍（timed 逐条输出）
 * - askWho / askInheritance / askReward / askMission: 四个分支回应
 * - farewell: 结束语
 */
object WatcherFirstContactDialog {

    // ─────────────────────────────────────────────────────────────
    // Node IDs
    // ─────────────────────────────────────────────────────────────
    private const val NODE_INTRO = "intro"
    private const val NODE_ASK_WHO = "ask_who"
    private const val NODE_ASK_INHERITANCE = "ask_inheritance"
    private const val NODE_ASK_REWARD = "ask_reward"
    private const val NODE_ASK_MISSION = "ask_mission"
    private const val NODE_FAREWELL = "farewell"

    // ─────────────────────────────────────────────────────────────
    // Option IDs
    // ─────────────────────────────────────────────────────────────
    private const val OPT_ASK_WHO = "watcher_ask_who"
    private const val OPT_ASK_INHERITANCE = "watcher_ask_inheritance"
    private const val OPT_ASK_REWARD = "watcher_ask_reward"
    private const val OPT_ASK_MISSION = "watcher_ask_mission"
    private const val OPT_CONTINUE = "watcher_continue"
    private const val OPT_UNDERSTOOD = "watcher_understood"
    private const val OPT_LEAVE = "watcher_leave"

    // ─────────────────────────────────────────────────────────────
    // Session state keys (用于追踪玩家已选过的选项)
    // ─────────────────────────────────────────────────────────────
    private const val STATE_ASKED_WHO = "asked_who"
    private const val STATE_ASKED_INHERITANCE = "asked_inheritance"
    private const val STATE_ASKED_REWARD = "asked_reward"
    private const val STATE_ASKED_MISSION = "asked_mission"

    private val CAT = I18n.Categories.MOD

    fun graph(): DialogGraph = dialogGraph(start = NODE_INTRO) {

        // ═══════════════════════════════════════════════════════════
        // 节点：intro - 守望者首次接触 (timed 逐条输出)
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_INTRO,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    // 清空面板，建立沉浸感
                    ctx.text.clear()

                    // 逐条输出：徽标旋转、声音描述、自我介绍
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.0", delay = 0f, fadeIn = 0.4f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.2", delay = 1.5f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.3", delay = 2.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.4", delay = 2.5f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.5", delay = 2.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.6", delay = 1.5f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.7", delay = 1.2f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.intro.8", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx ->
                    buildIntroOptions(ctx.sessionState)
                }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // 节点：ask_who - 询问守望者身份
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_ASK_WHO,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_WHO] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.watcher.option.askWho"])

                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.1", delay = 1.0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.2", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.3", delay = 1.2f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.4", delay = 1.0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.5", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.6", delay = 1.5f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askWho.7", delay = 1.2f, fadeIn = 0.3f)
                },
                options = { ctx ->
                    buildBranchReturnOptions(ctx.sessionState)
                }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // 节点：ask_inheritance - 询问继承者
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_ASK_INHERITANCE,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_INHERITANCE] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.watcher.option.askInheritance"])

                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.2", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.3", delay = 1.2f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.4", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.5", delay = 1.5f, fadeIn = 0.3f)
                    // 旁白抒情
                    ctx.enqueueI18nFading(
                        CAT, "dialog.watcher.askInheritance.6",
                        delay = 1.8f, fadeIn = 0.5f, hold = 2.0f, fadeOut = 1.0f
                    )
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.7", delay = 2.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askInheritance.8", delay = 1.0f, fadeIn = 0.25f)
                },
                options = { ctx ->
                    buildBranchReturnOptions(ctx.sessionState)
                }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // 节点：ask_reward - 询问报酬
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_ASK_REWARD,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_REWARD] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.watcher.option.askReward"])

                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.0", delay = 0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.1", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.2", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.3", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.4", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.5", delay = 1.0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askReward.6", delay = 1.2f, fadeIn = 0.3f)
                },
                options = { ctx ->
                    buildBranchReturnOptions(ctx.sessionState)
                }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // 节点：ask_mission - 询问任务
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_ASK_MISSION,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.sessionState[STATE_ASKED_MISSION] = true
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.watcher.option.askMission"])

                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.0", delay = 0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.1", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.2", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.3", delay = 1.5f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.4", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.5", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.askMission.6", delay = 1.5f, fadeIn = 0.3f)
                },
                options = { ctx ->
                    buildBranchReturnOptions(ctx.sessionState)
                }
            )
        )

        // ═══════════════════════════════════════════════════════════
        // 节点：farewell - 结束语 (timed + 抒情收尾)
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_FAREWELL,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.addOptionSelectedEcho(I18n[CAT, "dialog.watcher.option.leave"])

                    ctx.enqueueI18nFading(CAT, "dialog.watcher.farewell.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.farewell.1", delay = 1.2f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.farewell.2", delay = 1.5f, fadeIn = 0.4f)
                    ctx.enqueueI18nFading(CAT, "dialog.watcher.farewell.3", delay = 1.5f, fadeIn = 0.3f)
                    // 抒情结尾：带淡出
                    ctx.enqueueI18nFading(
                        CAT, "dialog.watcher.farewell.4",
                        delay = 1.0f, fadeIn = 0.6f, hold = 2.5f, fadeOut = 1.5f
                    )

                    // HUD 提示解锁
                    ctx.hudMessageI18n(CAT, "dialog.watcher.intro.2") // 「我观察你很久了，陌生人。」
                },
                options = {
                    listOf(
                        DialogDsl.option(
                            id = OPT_LEAVE,
                            text = I18n[CAT, "dialog.watcher.option.leave"],
                            action = DialogDsl.close()
                        )
                    )
                }
            )
        )
    }

    /**
     * 构建 intro 节点的选项列表。
     * 根据玩家已选过的选项，动态启用/禁用。
     */
    private fun buildIntroOptions(state: MutableMap<String, Any?>): List<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec> {
        val askedWho = state[STATE_ASKED_WHO] == true
        val askedInheritance = state[STATE_ASKED_INHERITANCE] == true
        val askedReward = state[STATE_ASKED_REWARD] == true
        val askedMission = state[STATE_ASKED_MISSION] == true

        return listOf(
            DialogDsl.option(
                id = OPT_ASK_WHO,
                text = I18n[CAT, "dialog.watcher.option.askWho"],
                action = DialogDsl.goto(NODE_ASK_WHO),
                enabled = !askedWho
            ),
            DialogDsl.option(
                id = OPT_ASK_INHERITANCE,
                text = I18n[CAT, "dialog.watcher.option.askInheritance"],
                action = DialogDsl.goto(NODE_ASK_INHERITANCE),
                enabled = !askedInheritance
            ),
            DialogDsl.option(
                id = OPT_ASK_REWARD,
                text = I18n[CAT, "dialog.watcher.option.askReward"],
                action = DialogDsl.goto(NODE_ASK_REWARD),
                enabled = !askedReward
            ),
            DialogDsl.option(
                id = OPT_ASK_MISSION,
                text = I18n[CAT, "dialog.watcher.option.askMission"],
                action = DialogDsl.goto(NODE_ASK_MISSION),
                enabled = !askedMission
            ),
            DialogDsl.option(
                id = OPT_LEAVE,
                text = I18n[CAT, "dialog.watcher.option.leave"],
                action = DialogDsl.goto(NODE_FAREWELL)
            )
        )
    }

    /**
     * 构建分支节点的返回选项。
     * 允许玩家继续询问其他问题或结束对话。
     */
    private fun buildBranchReturnOptions(state: MutableMap<String, Any?>): List<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec> {
        val askedWho = state[STATE_ASKED_WHO] == true
        val askedInheritance = state[STATE_ASKED_INHERITANCE] == true
        val askedReward = state[STATE_ASKED_REWARD] == true
        val askedMission = state[STATE_ASKED_MISSION] == true

        val options = mutableListOf<cn.kasuminova.astd.campaign.dialog.core.DialogOptionSpec>()

        // 如果还有未问过的问题，可以继续
        if (!askedWho) {
            options.add(
                DialogDsl.option(
                    id = OPT_ASK_WHO,
                    text = I18n[CAT, "dialog.watcher.option.askWho"],
                    action = DialogDsl.goto(NODE_ASK_WHO)
                )
            )
        }
        if (!askedInheritance) {
            options.add(
                DialogDsl.option(
                    id = OPT_ASK_INHERITANCE,
                    text = I18n[CAT, "dialog.watcher.option.askInheritance"],
                    action = DialogDsl.goto(NODE_ASK_INHERITANCE)
                )
            )
        }
        if (!askedReward) {
            options.add(
                DialogDsl.option(
                    id = OPT_ASK_REWARD,
                    text = I18n[CAT, "dialog.watcher.option.askReward"],
                    action = DialogDsl.goto(NODE_ASK_REWARD)
                )
            )
        }
        if (!askedMission) {
            options.add(
                DialogDsl.option(
                    id = OPT_ASK_MISSION,
                    text = I18n[CAT, "dialog.watcher.option.askMission"],
                    action = DialogDsl.goto(NODE_ASK_MISSION)
                )
            )
        }

        // 始终提供结束选项
        options.add(
            DialogDsl.option(
                id = OPT_LEAVE,
                text = I18n[CAT, "dialog.watcher.option.leave"],
                action = DialogDsl.goto(NODE_FAREWELL)
            )
        )

        return options
    }

    /**
     * 在任意位置调用即可弹出守望者首次接触对话。
     *
     * @param target 交互目标（通常是通讯中继或虚拟实体）
     */
    fun open(target: SectorEntityToken) {
        Global.getSector()?.campaignUI?.showInteractionDialog(
            GraphDialogPlugin(
                graph(),
                closeOnEscapeOptionId = OPT_LEAVE,
                closeOnEscapeText = I18n[CAT, "dialog.watcher.option.leave"]
            ),
            target
        )
    }
}
