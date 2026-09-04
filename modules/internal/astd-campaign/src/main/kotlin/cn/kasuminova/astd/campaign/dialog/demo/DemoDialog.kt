package cn.kasuminova.astd.campaign.dialog.demo

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 一个最小可复用示例：
 * - 普通节点（立即输出）
 * - Timed 节点（按延迟逐条输出，期间锁定选项，提供“跳过”）
 */
object DemoDialog {

    private const val NODE_INTRO = "intro"
    private const val NODE_TIMED = "timed"

    private const val OPT_PLAY_TIMED = "demo_play_timed"
    private const val OPT_BACK = "demo_back"
    private const val OPT_LEAVE = "demo_leave"

    fun graph(): DialogGraph = dialogGraph(start = NODE_INTRO) {
        node(
            NODE_INTRO,
            DialogDsl.node(
                onEnter = { ctx ->
                    val targetName = ctx.target?.name ?: "(unknown)"
                    ctx.sayI18n(I18n.Categories.MOD, "dialog.demo.intro.0", baseColor = null, "targetName" to targetName)
                    ctx.sayI18n(I18n.Categories.MOD, "dialog.demo.intro.1")
                },
                options = {
                    listOf(
                        DialogDsl.option(
                            id = OPT_PLAY_TIMED,
                            text = I18n[I18n.Categories.MOD, "dialog.demo.option.playTimed"],
                            action = DialogDsl.goto(NODE_TIMED)
                        ),
                        DialogDsl.option(
                            id = OPT_LEAVE,
                            text = I18n[I18n.Categories.MOD, "dialog.demo.option.leave"],
                            action = DialogDsl.close()
                        ),
                    )
                }
            )
        )

        node(
            NODE_TIMED,
            DialogDsl.timedNode(
                onEnter = { ctx ->
                    // 注意：enqueue 的 delay 是“相对上一条”的延迟。
                    ctx.enqueueI18nFading(I18n.Categories.MOD, "dialog.demo.timed.0", delay = 0f, fadeIn = 0.25f)
                    ctx.enqueueI18nFading(I18n.Categories.MOD, "dialog.demo.timed.1", delay = 0.6f, fadeIn = 0.25f)
                    // 示范淡出：适合做短提示/播报（注意：淡出后仍占据布局高度）
                    ctx.enqueueI18nFading(I18n.Categories.MOD, "dialog.demo.timed.2", delay = 0.8f, fadeIn = 0.25f, hold = 1.2f, fadeOut = 0.6f)

                    // 同时投递一条战役右侧 HUD 消息（原版自带淡入淡出）
                    ctx.hudMessageI18n(I18n.Categories.MOD, "dialog.demo.hud.toast")
                },
                options = {
                    listOf(
                        DialogDsl.option(
                            id = OPT_BACK,
                            text = I18n[I18n.Categories.MOD, "dialog.demo.option.back"],
                            action = DialogDsl.goto(NODE_INTRO)
                        ),
                        DialogDsl.option(
                            id = OPT_LEAVE,
                            text = I18n[I18n.Categories.MOD, "dialog.demo.option.leave"],
                            action = DialogDsl.close()
                        ),
                    )
                }
            )
        )
    }

    /**
     * 在任意位置调用即可弹出示例对话。
     *
     * 例如：在某个自定义交互、或 dev/test 注入脚本里调用。
     */
    fun open(target: SectorEntityToken) {
        Global.getSector()?.campaignUI?.showInteractionDialog(
            GraphDialogPlugin(graph(), closeOnEscapeOptionId = OPT_LEAVE, closeOnEscapeText = I18n[I18n.Categories.MOD, "dialog.demo.option.leave"]),
            target
        )
    }
}
