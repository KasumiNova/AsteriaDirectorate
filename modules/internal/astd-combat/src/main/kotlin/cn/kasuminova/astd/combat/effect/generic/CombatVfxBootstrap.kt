package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import cn.kasuminova.astd.renderer.effect.system.ASTDEngineShardSprayEffect
import cn.kasuminova.astd.renderer.effect.system.ASTDVectorThrustEngineManager
import cn.kasuminova.astd.renderer.effect.system.LoadedMissileGlowLayer
import cn.kasuminova.astd.renderer.effect.system.ShipGlowRenderer
import cn.kasuminova.astd.renderer.effect.system.WeaponGlowLayer
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI

internal object CombatVfxBootstrap {

    private val log = Global.getLogger(CombatVfxBootstrap::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        ProjectileVfxSpecs.install()

        ProjectileVfxDriverPlugin.ensureInstalled(engine)

        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] BoxUtilCombatVfx.ensureReady failed", ex)
        }

        try {
            ASTDAfterimageEffect.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ASTDAfterimageEffect.ensureInstalled failed", ex)
        }

        try {
            ASTDVectorThrustEngineManager.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ASTDVectorThrustEngineManager.ensureInstalled failed", ex)
        }

        try {
            ASTDEngineShardSprayEffect.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ASTDEngineShardSprayEffect.ensureInstalled failed", ex)
        }

        try {
            WeaponGlowLayer.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] WeaponGlowLayer.ensureInstalled failed", ex)
        }

        try {
            LoadedMissileGlowLayer.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] LoadedMissileGlowLayer.ensureInstalled failed", ex)
        }

        try {
            ShipGlowRenderer.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ShipGlowRenderer.ensureInstalled failed", ex)
        }

        try {
            Xc002GhostWingsEffect.ensureInstalled(engine)
        } catch (ex: Throwable) {
            log.warn("[ASTD] Xc002GhostWingsEffect.ensureInstalled failed", ex)
        }
    }
}
