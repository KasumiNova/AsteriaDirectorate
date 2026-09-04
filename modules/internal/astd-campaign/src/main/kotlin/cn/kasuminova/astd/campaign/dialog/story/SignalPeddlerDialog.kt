package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 序章事件：酒吧遇见「信号贩子」。
 *
 * 触发：完成序章赏金后，某处酒吧触发（使用 BarEvent 机制，此处仅为 Dialog 实现）。
 */
object SignalPeddlerDialog {

    private const val NODE_INTRO = "intro"
    private const val NODE_LEAVE = "leave"

    private const val OPT_TAKE = "peddler_take"
    private const val OPT_LEAVE = "peddler_leave"

    private val CAT = I18n.Categories.MOD

    fun graph(): DialogGraph = dialogGraph(start = NODE_INTRO) {

        // ═══════════════════════════════════════════════════════════
        // Node: Intro (Timed text)
        // ═══════════════════════════════════════════════════════════
        node(
            NODE_INTRO,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.text.clear()

                    // 第一段描述：外貌
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.0", delay = 0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.1", delay = 1.0f, fadeIn = 0.3f)

                    // 对话
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.2", delay = 1.2f, fadeIn = 0.2f)

                    // 动作
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.3", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.4", delay = 1.2f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.5", delay = 1.0f, fadeIn = 0.3f)
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.6", delay = 1.0f, fadeIn = 0.25f)

                    // 结尾（暗示）
                    ctx.enqueueI18nFading(CAT, "dialog.peddler.intro.7", delay = 1.5f, fadeIn = 0.3f)
                },
                options = {
                    listOf(
                        DialogDsl.option(
                            id = OPT_TAKE,
                            text = I18n[CAT, "dialog.peddler.option.take"],
                            action = DialogDsl.run(then = DialogDsl.close()) { ctx ->
                                // 实际逻辑：给予 Intel 条目或道具
                                // TODO: Add actual item/intel logic here
                                ctx.hudMessageI18n(CAT, "dialog.peddler.hud.item")
                            }
                        ),
                        DialogDsl.option(
                            id = OPT_LEAVE,
                            text = I18n[CAT, "dialog.peddler.option.leave"],
                            action = DialogDsl.close()
                        )
                    )
                }
            )
        )
    }

    fun open(target: SectorEntityToken) {
        Global.getSector()?.campaignUI?.showInteractionDialog(
            GraphDialogPlugin(
                graph(),
                closeOnEscapeOptionId = OPT_LEAVE,
                closeOnEscapeText = I18n[CAT, "dialog.peddler.option.leave"]
            ),
            target
        )
    }
}
