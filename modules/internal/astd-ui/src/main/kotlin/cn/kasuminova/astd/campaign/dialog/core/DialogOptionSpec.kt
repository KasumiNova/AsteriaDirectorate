package cn.kasuminova.astd.campaign.dialog.core

/**
 * 一个可显示在 [com.fs.starfarer.api.campaign.OptionPanelAPI] 上的选项。
 */
data class DialogOptionSpec(
    /**
     * 必须唯一；会作为 OptionPanel 的 optionData 传回 [com.fs.starfarer.api.campaign.InteractionDialogPlugin.optionSelected]。
     */
    val id: String,
    val text: String,
    val action: DialogAction,
    val enabled: Boolean = true,
    val tooltip: String? = null,
    val shortcut: Int? = null,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)
