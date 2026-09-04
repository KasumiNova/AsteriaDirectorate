package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl
import com.fs.starfarer.api.util.Misc

/**
 * 赏金目标舰队交互配置：
 * - 允许定制 FleetInteractionDialogPlugin 的行为
 * - 在玩家战斗结束后（生成打捞前后）向对话框输出“任务完成文案”（而不是在赏金板里显示）
 *
 * 文案来源为接受赏金时写入 fleet memory 的 [BountyKeys.MEM_SUCCESS_TEXT]；
 * 主线剧情长文案随旧内容链移除，新设定接入时按需恢复按 key 读表的分支。
 */
class BountyFidConfigGen(
    private val bountyKey: String,
) : FleetInteractionDialogPluginImpl.FIDConfigGen {

    override fun createConfig(): FleetInteractionDialogPluginImpl.FIDConfig {
        val cfg = FleetInteractionDialogPluginImpl.FIDConfig()
        cfg.showCommLinkOption = false
        cfg.showTransponderStatus = false
        cfg.showFleetAttitude = false
        cfg.showEngageText = true
        cfg.showWarningDialogWhenNotHostile = false

        // 赏金通常不希望把周边阵营也拉进来（减少不可控性）
        cfg.pullInAllies = false
        cfg.pullInEnemies = false
        cfg.pullInStations = false

        // 让“攻击 vs 攻击”的交互更直接
        cfg.alwaysAttackVsAttack = true

        // 打捞与经验仍保留
        cfg.withSalvage = true
        cfg.lootCredits = true
        cfg.printXPToDialog = true

        // 自定义 delegate：战斗结束后输出完成文案
        cfg.delegate = BountyFidDelegate(bountyKey)
        return cfg
    }

    private class BountyFidDelegate(
        @Suppress("unused")
        private val bountyKey: String,
    ) : FleetInteractionDialogPluginImpl.BaseFIDDelegate() {

        override fun postPlayerSalvageGeneration(dialog: InteractionDialogAPI, context: FleetEncounterContext, salvage: CargoAPI) {
            // 仅在玩家明确赢下遭遇战时输出。
            if (!context.didPlayerWinEncounterOutright()) return

            val other = dialog.interactionTarget ?: return
            val mem = other.memoryWithoutUpdate
            if (mem.getBoolean(BountyKeys.MEM_SUCCESS_SHOWN)) return
            mem.set(BountyKeys.MEM_SUCCESS_SHOWN, true)

            // 读取完成文案：接受赏金时写入 fleet memory 的文本。
            val successText = mem.getString(BountyKeys.MEM_SUCCESS_TEXT) ?: return
            if (successText.isBlank()) return

            dialog.textPanel.addPara(" ")
            dialog.textPanel.setFontInsignia()

            // 按空行切段，避免一大坨挤成一段
            val paras = successText
                .replace("\r\n", "\n")
                .split("\n\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val base = Misc.getTextColor()
            for (p in paras) {
                dialog.textPanel.addPara(p, base)
            }
        }
    }
}
