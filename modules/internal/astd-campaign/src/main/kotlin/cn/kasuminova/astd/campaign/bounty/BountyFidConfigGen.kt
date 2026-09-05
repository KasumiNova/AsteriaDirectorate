package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl
import com.fs.starfarer.api.util.Misc
import cn.kasuminova.astd.combat.effect.aster.AsterGravityNodeBattle
import org.lwjgl.util.vector.Vector2f

/**
 * 赏金目标舰队交互配置：
 * - 允许定制 FleetInteractionDialogPlugin 的行为
 * - 在玩家战斗结束后（生成打捞前后）向对话框输出“任务完成文案”（而不是在赏金板里显示）
 *
 * 文案来源为接受赏金时写入 fleet memory 的 [BountyKeys.MEM_SUCCESS_TEXT]；
 * 主线由 [BountyCampaignManager] 按 def 从 i18n 表读取核销回执批注（含清算序列进度行）写入。
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
        private val key: String,
    ) : FleetInteractionDialogPluginImpl.BaseFIDDelegate() {

        override fun battleContextCreated(
            dialog: InteractionDialogAPI,
            context: com.fs.starfarer.api.combat.BattleCreationContext,
        ) {
            if (!key.startsWith("astd_main_c2_zw_s")) return
            val stage = key.substringAfterLast("_s").toIntOrNull() ?: return
            if (stage !in 1..3) return
            val nodePositions = listOf(
                Vector2f(2800f, 0f),
                Vector2f(-1400f, 2424.87f),
                Vector2f(-1400f, -2424.87f),
            )
            AsterGravityNodeBattle.requestInstall(
                nodePositions.mapIndexed { index, position ->
                    AsterGravityNodeBattle.NodeSpec(
                        id = "astd_aster_gravity_node_${stage}_${index + 1}",
                        location = position,
                    )
                },
                defenderOwner = 1,
            )
        }

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
