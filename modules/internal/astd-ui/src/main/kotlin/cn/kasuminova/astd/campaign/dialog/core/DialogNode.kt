package cn.kasuminova.astd.campaign.dialog.core

/**
 * 对话节点（node）。
 *
 * - [onEnter]：切换到此 node 时调用（适合输出文案、初始化 state、排队延迟文本等）。
 * - [onAdvance]：每帧调用（用于计时、动画、自动跳转等）。
 * - [buildOptions]：需要刷新选项面板时调用。
 */
interface DialogNode {

    fun onEnter(ctx: DialogContext) {}

    fun onLeave(ctx: DialogContext) {}

    fun onAdvance(ctx: DialogContext, amount: Float) {}

    fun buildOptions(ctx: DialogContext): List<DialogOptionSpec> = emptyList()

    /**
     * 当 [DialogContext.textQueue] 仍在输出“延迟逐条文案”时，是否锁定此 node 的选项。
     *
     * - true：通常用于“自动播报/旁白”节点，让玩家先看完文本再选择。
     * - false：允许玩家边看边选（不推荐，容易造成文本与状态错位）。
     */
    fun lockOptionsWhileTextQueueActive(ctx: DialogContext): Boolean = false

    /**
     * 当选项被锁定时（[lockOptionsWhileTextQueueActive] = true 且队列非空），是否提供“跳过/快进”按钮。
     */
    fun showSkipWhileLocked(ctx: DialogContext): Boolean = true
}

abstract class BaseDialogNode : DialogNode

/**
 * 典型的“逐条延迟输出”节点基类：默认锁定选项，直到文本队列输出完。
 *
 * 子类只需要在 [onEnter] 里调用 [DialogContext.enqueue] / [DialogContext.enqueueI18n] 即可。
 */
abstract class TimedTextDialogNode : BaseDialogNode() {

    final override fun lockOptionsWhileTextQueueActive(ctx: DialogContext): Boolean = true
}
