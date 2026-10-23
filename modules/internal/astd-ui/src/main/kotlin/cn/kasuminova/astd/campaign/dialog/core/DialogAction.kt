package cn.kasuminova.astd.campaign.dialog.core

/**
 * 对话选项点击后执行的动作。
 * [Run] 携带 lambda，仅用于代码内 DSL 拼装；数据驱动的对话定义必须映射到固定 action id，禁止序列化 lambda。
 */
sealed interface DialogAction {

    data class Goto(val nodeId: String) : DialogAction

    data class Close(
        /**
         * true: 调用 dialog.dismissAsCancel()
         * false: 调用 dialog.dismiss()
         */
        val asCancel: Boolean = false
    ) : DialogAction

    data class Run(
        val block: (DialogContext) -> Unit,
        val then: DialogAction? = null
    ) : DialogAction
}
