package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.hullmods.lens.purgeGravEwFieldCommanderBoosts
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * ASTD 全局战斗入口插件。
 *
 * 背景：原先 CombatVfxBootstrap 只能由特定 ASTD 武器的 everyFrameEffect 触发安装，
 * 导致没装这些武器的 ASTD 船在战斗中拿不到全套 VFX（含类矢量推进引擎表现）。
 *
 * 本插件经 data/config/settings.json 的 "plugins" 段注册，游戏会在**每一场战斗**
 * 初始化时自动创建并调用 init(engine)，与是否装备某武器无关。我们在 init 里
 * 统一安装全套战斗 VFX 管理器。
 */
class ASTDGlobalCombatPlugin : BaseEveryFrameCombatPlugin() {

    override fun init(engine: CombatEngineAPI) {
        try {
            CombatVfxBootstrap.ensureInstalled(engine)
            // 引力电子干扰力场跨战斗残留安全网：指挥官 PersonAPI stat 跨战斗/跨存档存活，
            // 每场战斗 init 时清理上一场可能漏收口的增幅修饰（详见该函数 KDoc）
            purgeGravEwFieldCommanderBoosts(engine)
        } catch (t: Throwable) {
            Global.getLogger(ASTDGlobalCombatPlugin::class.java)
                .warn("[ASTD] ASTDGlobalCombatPlugin init failed", t)
        }
    }
}
