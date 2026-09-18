package cn.kasuminova.astd.combat.effect.joint.stardust

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI

/**
 * 星尘光尘的导弹 AI 指派入口（`ModPlugin.pickMissileAI` 覆盖钩子）。
 *
 * 动机：原版引擎不为 MOTE 型弹体自动指派 AI（原版光尘由 MoteControlScript 手动 setMissileAI），
 * 武器化发射必须经本钩子接管；同帧一并设置 EMP 抗性（原版 spawn 路径的等价物，
 * 防止光尘被 EMP 武器秒杀）。
 */
object StardustMoteAiPicker {

    /** 弹体 spec 命中星尘光尘时返回专属 AI；否则返回 null 交回原版/其他模组。 */
    fun pick(missile: MissileAPI): PluginPick<MissileAIPlugin>? {
        val projId = missile.projectileSpecId ?: return null
        if (!StardustMoteIds.isStardustProj(projId)) return null
        missile.empResistance = 10000
        return PluginPick(StardustMoteAI(missile), PickPriority.MOD_SPECIFIC)
    }
}
