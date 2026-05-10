package cn.kasuminova.astd.campaign.dialog.core

/**
 * 对话选项被点击后要执行的动作。
 *
 * 说明：这里允许携带 lambda（[Run]），用于“模板/DSL”场景快速拼装逻辑。
 * 如果你计划把对话定义做成纯数据（JSON/YAML/ss-csv），建议在生成阶段把逻辑映射到固定的 action id，
 * 而不是序列化 lambda。
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
