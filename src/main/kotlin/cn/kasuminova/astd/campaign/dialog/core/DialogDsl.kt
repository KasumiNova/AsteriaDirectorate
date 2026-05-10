package cn.kasuminova.astd.campaign.dialog.core

/**
 * 轻量 DSL：让模板里写 node/option 更顺手。
 */
object DialogDsl {

    fun goto(nodeId: String): DialogAction = DialogAction.Goto(nodeId)

    fun close(asCancel: Boolean = false): DialogAction = DialogAction.Close(asCancel)

    fun run(then: DialogAction? = null, block: (DialogContext) -> Unit): DialogAction =
        DialogAction.Run(block = block, then = then)

    fun option(
        id: String,
        text: String,
        action: DialogAction,
        enabled: Boolean = true,
        tooltip: String? = null,
        shortcut: Int? = null,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): DialogOptionSpec = DialogOptionSpec(
        id = id,
        text = text,
        action = action,
        enabled = enabled,
        tooltip = tooltip,
        shortcut = shortcut,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
    )

    /**
     * 使用 lambda 定义一个 node（默认不锁定选项）。
     */
    fun node(
        onEnter: (DialogContext) -> Unit = {},
        onLeave: (DialogContext) -> Unit = {},
        onAdvance: (DialogContext, Float) -> Unit = { _, _ -> },
        options: (DialogContext) -> List<DialogOptionSpec> = { emptyList() },
    ): DialogNode = LambdaDialogNode(
        lockWhileQueueActive = false,
        showSkipWhileLocked = true,
        onEnter = onEnter,
        onLeave = onLeave,
        onAdvance = onAdvance,
        options = options,
    )

    /**
     * “逐条延迟输出”node：当文本队列未输出完时，锁定选项并提供“跳过”。
     */
    fun timedNode(
        onEnter: (DialogContext) -> Unit = {},
        onLeave: (DialogContext) -> Unit = {},
        onAdvance: (DialogContext, Float) -> Unit = { _, _ -> },
        options: (DialogContext) -> List<DialogOptionSpec> = { emptyList() },
    ): DialogNode = LambdaDialogNode(
        lockWhileQueueActive = true,
        showSkipWhileLocked = true,
        onEnter = onEnter,
        onLeave = onLeave,
        onAdvance = onAdvance,
        options = options,
    )

    private class LambdaDialogNode(
        private val lockWhileQueueActive: Boolean,
        private val showSkipWhileLocked: Boolean,
        private val onEnter: (DialogContext) -> Unit,
        private val onLeave: (DialogContext) -> Unit,
        private val onAdvance: (DialogContext, Float) -> Unit,
        private val options: (DialogContext) -> List<DialogOptionSpec>,
    ) : DialogNode {

        override fun onEnter(ctx: DialogContext) = onEnter.invoke(ctx)

        override fun onLeave(ctx: DialogContext) = onLeave.invoke(ctx)

        override fun onAdvance(ctx: DialogContext, amount: Float) = onAdvance.invoke(ctx, amount)

        override fun buildOptions(ctx: DialogContext): List<DialogOptionSpec> = options.invoke(ctx)

        override fun lockOptionsWhileTextQueueActive(ctx: DialogContext): Boolean = lockWhileQueueActive

        override fun showSkipWhileLocked(ctx: DialogContext): Boolean = showSkipWhileLocked
    }
}
