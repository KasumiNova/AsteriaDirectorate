package cn.kasuminova.astd.campaign.dialog.core

/** 对话节点。 */
interface DialogNode {

    fun onEnter(ctx: DialogContext) {}

    fun onLeave(ctx: DialogContext) {}

    fun onAdvance(ctx: DialogContext, amount: Float) {}

    fun buildOptions(ctx: DialogContext): List<DialogOptionSpec> = emptyList()

    /** [DialogContext.textQueue] 输出期间是否锁定本节点选项。 */
    fun lockOptionsWhileTextQueueActive(ctx: DialogContext): Boolean = false

    /** 选项锁定且队列非空时，是否提供跳过按钮。 */
    fun showSkipWhileLocked(ctx: DialogContext): Boolean = true
}

abstract class BaseDialogNode : DialogNode

/** 延迟逐条输出节点基类：锁定选项直到文本队列输出完，在 [onEnter] 中 enqueue 文本。 */
abstract class TimedTextDialogNode : BaseDialogNode() {

    final override fun lockOptionsWhileTextQueueActive(ctx: DialogContext): Boolean = true
}
