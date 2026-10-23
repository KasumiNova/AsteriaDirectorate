package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxKeys

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class CombatVfxBootstrapEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private val log = Global.getLogger(CombatVfxBootstrapEveryFrameEffect::class.java)
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        if (engine.customData[ProjectileVfxKeys.ENGINE_LOG_BOOTSTRAP_ONCE] != true) {
            engine.customData[ProjectileVfxKeys.ENGINE_LOG_BOOTSTRAP_ONCE] = true
            val weaponId = try {
                weapon.spec?.weaponId
            } catch (_: Throwable) {
                null
            }
            val src = try {
                CombatVfxBootstrapEveryFrameEffect::class.java.protectionDomain?.codeSource?.location?.toString()
            } catch (_: Throwable) {
                null
            }
            log.info("[ASTD] CombatVfxBootstrapEveryFrameEffect running (weaponId=$weaponId, src=$src)")
        }

        CombatVfxBootstrap.ensureInstalled(engine)
    }

}
