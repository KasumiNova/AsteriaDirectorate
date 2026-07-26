package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.renderer.effect.system.ArcFlareEmissiveOverlayManager
import cn.kasuminova.astd.renderer.effect.system.ArcFlareEngineFlareManager
import cn.kasuminova.astd.renderer.effect.system.ArcFlareAfterimageManager
import cn.kasuminova.astd.renderer.effect.system.ASTDVectorThrustEngineManager
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * 战斗内 VFX 启动器：战斗开始后尽早把弹体 VFX 驱动插件与各舰体视觉插件装入引擎。
 */
internal object CombatVfxBootstrap {

    private val log = Global.getLogger(CombatVfxBootstrap::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        // 弹体 VFX 的每帧驱动插件（RenderEntity 管线，见 ProjectileVfxDriverPlugin / ProjectileVfxSpecs）。
        ProjectileVfxDriverPlugin.ensureInstalled(engine)

        // 顺手尝试让 BoxUtil 进入 ready。
        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] BoxUtilCombatVfx.ensureReady failed", ex)
        }

        // 舰体 emissive 覆盖层：目前用于 Arc Flare 整船发光贴图。
        try {
            ArcFlareEmissiveOverlayManager.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ArcFlareEmissiveOverlayManager.ensureInstalled failed", ex)
        }

        try {
            ArcFlareEngineFlareManager.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ArcFlareEngineFlareManager.ensureInstalled failed", ex)
        }

        try {
            ArcFlareAfterimageManager.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ArcFlareAfterimageManager.ensureInstalled failed", ex)
        }

        // ASTD 全系类矢量推进：按引擎贡献逐引擎调火焰强度。
        try {
            ASTDVectorThrustEngineManager.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ASTDVectorThrustEngineManager.ensureInstalled failed", ex)
        }
    }
}
