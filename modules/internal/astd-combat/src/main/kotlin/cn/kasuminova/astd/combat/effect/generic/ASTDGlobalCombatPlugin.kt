package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.hullmods.lens.purgeEcmCommanderBoosts
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI

class ASTDGlobalCombatPlugin : BaseEveryFrameCombatPlugin() {

    override fun init(engine: CombatEngineAPI) {
        try {
            CombatVfxBootstrap.ensureInstalled(engine)
            // 指挥官 PersonAPI stat 跨战斗/跨存档存活，每场战斗 init 时清理上一场残留的增幅修饰。
            purgeEcmCommanderBoosts(engine)
        } catch (t: Throwable) {
            Global.getLogger(ASTDGlobalCombatPlugin::class.java)
                .warn("[ASTD] ASTDGlobalCombatPlugin init failed", t)
        }
    }
}
