package cn.kasuminova.astd.campaign.rules

import cn.kasuminova.astd.campaign.dialog.story.DirectorateStationDialog
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin
import com.fs.starfarer.api.util.Misc

/**
 * rules.csv 命令插件：与剧情主站（分局空间站）交互时，把对话插件整体替换为
 * [DirectorateStationDialog]（工单终端 / 档案室 / 归档核销三入口）。
 *
 * 挂接方式（正文不落 rules.csv）：
 * - rules.csv 仅一行 `OpenInteractionDialog` 规则（条件：实体 memory 带分局角色标记，
 *   高分抢占默认市场对话），script 列为本类；
 * - `dialog.setPlugin` 是原版事件流内部的既有换插件方式（BarCMD 同款）；
 *   默认市场问候规则因本规则返回 true 而不再触发。
 */
class AstdOpenBranchServices : BaseCommandPlugin() {

    override fun execute(
        ruleId: String,
        dialog: InteractionDialogAPI?,
        params: List<Misc.Token>,
        memoryMap: Map<String, MemoryAPI>,
    ): Boolean {
        if (dialog == null) return false
        val entity = dialog.interactionTarget ?: return false
        if (entity.memoryWithoutUpdate.getString(StoryWorldIds.MEM_STORY_ROLE) != StoryWorldIds.ROLE_BRANCH_OFFICE) {
            return false
        }

        val plugin = DirectorateStationDialog.createPlugin()
        dialog.setPlugin(plugin)
        plugin.init(dialog)
        return true
    }
}
