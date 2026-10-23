package cn.kasuminova.astd.combat.effect.arc.sevenstars

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

class SevenStarsOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val source = weapon.ship
        val owner = source?.owner ?: projectile.owner

        val panelDamage = projectile.damageAmount
        if (panelDamage.isNaN() || panelDamage <= 0f) {
            log.warn("“七星”弹体面板伤害非法（$panelDamage），跳过全部结算直接消散（不产生任何 applyDamage 调用）")
            SevenStarsVfx.dissipate(engine, projectile.location)
            engine.removeEntity(projectile)
            return
        }

        val tuning = SevenStarsDifficulty.snapshot(source)
        val jumpRange = SevenStarsChainMath.jumpRange(weapon.range)
        projectile.velocity.set(0f, 0f)

        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_ONFIRE)

        val firstMult = SevenStarsChainMath.flashMult(tuning, 1)
        val target = SevenStarsTargetSelector.select(
            engine = engine,
            from = projectile.location,
            jumpRange = jumpRange,
            owner = owner,
            aoeDamage = panelDamage * firstMult,
        )

        if (target == null) {
            engine.addPlugin(
                SevenStarsChainScript(
                    anchor = Vector2f(projectile.location),
                    source = source,
                    tuning = tuning,
                    panelDamage = panelDamage,
                    jumpRange = jumpRange,
                    owner = owner,
                    initialJumps = 0,
                    initialState = SevenStarsChainScript.State.TERMINAL_PENDING,
                ),
            )
            engine.removeEntity(projectile)
            return
        }

        SevenStarsVfx.teleport(engine, projectile.location, target.location, source, target as? ShipAPI)
        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)
        projectile.location.set(target.location)
        projectile.velocity.set(0f, 0f)
        SevenStarsVfx.riftWindup(engine, projectile.location, scale = 1f)

        val script = SevenStarsChainScript(
            anchor = Vector2f(projectile.location),
            source = source,
            tuning = tuning,
            panelDamage = panelDamage,
            jumpRange = jumpRange,
            owner = owner,
            initialJumps = 1,
            initialState = SevenStarsChainScript.State.CHAIN_COOLDOWN,
        )
        script.queueFlash(projectile.location, target, firstMult, jumpIndex = 1)
        engine.addPlugin(script)
        SevenStarsChainScript.trackChainJumps(engine, 1)
        engine.removeEntity(projectile)
    }

    private companion object {
        private val log = Global.getLogger(SevenStarsOnFireEffect::class.java)
    }
}
