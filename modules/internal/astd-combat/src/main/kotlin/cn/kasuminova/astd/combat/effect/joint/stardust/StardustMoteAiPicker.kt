package cn.kasuminova.astd.combat.effect.joint.stardust

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI

// 原版引擎不会为 MOTE 弹体自动指派 AI，必须在 pickMissileAI 中安装。
object StardustMoteAiPicker {

    fun pick(missile: MissileAPI): PluginPick<MissileAIPlugin>? {
        val projId = missile.projectileSpecId ?: return null
        if (!StardustMoteIds.isStardustProj(projId)) return null
        missile.empResistance = 10000
        return PluginPick(StardustMoteAI(missile), PickPriority.MOD_SPECIFIC)
    }
}
