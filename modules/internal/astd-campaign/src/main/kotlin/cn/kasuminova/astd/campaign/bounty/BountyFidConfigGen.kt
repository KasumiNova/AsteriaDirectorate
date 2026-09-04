package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.combat.BattleCreationContext
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl
import com.fs.starfarer.api.util.Misc

/**
 * 赏金目标舰队交互配置：
 * - 允许定制 FleetInteractionDialogPlugin 的行为
 * - 在玩家战斗结束后（生成打捞前后）向对话框输出“任务完成文案”（而不是在赏金板里显示）
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
        private val bountyKey: String,
    ) : FleetInteractionDialogPluginImpl.BaseFIDDelegate() {

        override fun battleContextCreated(dialog: InteractionDialogAPI, bcc: BattleCreationContext) {
            // 这里先不做强行“禁止撤退”的硬规则；具体案子需要时再按 key 细化。
            // bcc.aiRetreatAllowed = false
            // bcc.fightToTheLast = true
        }

        override fun postPlayerSalvageGeneration(dialog: InteractionDialogAPI, context: FleetEncounterContext, salvage: CargoAPI) {
            // 仅在玩家明确赢下遭遇战时输出。
            if (!context.didPlayerWinEncounterOutright()) return

            val other = dialog.interactionTarget ?: return
            val mem = other.memoryWithoutUpdate
            if (mem.getBoolean(BountyKeys.MEM_SUCCESS_SHOWN)) return
            mem.set(BountyKeys.MEM_SUCCESS_SHOWN, true)

            // 读取完成文案：
            // - 主线：从 bounty_strings.json 表拿
            // - 支线/动态：优先读 fleet memory 写入的文本
            val successText = (mem.getString(BountyKeys.MEM_SUCCESS_TEXT) ?: "").ifBlank {
                if (bountyKey in StoryBounties.mainsByKey) {
                    I18n.t("asteria_directorate_bounty", "bounty.${bountyKey}.success")
                } else {
                    ""
                }
            }

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
