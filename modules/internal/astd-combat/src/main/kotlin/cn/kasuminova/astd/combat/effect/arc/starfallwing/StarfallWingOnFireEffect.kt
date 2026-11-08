package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap
import kotlin.math.roundToInt

// OnFireEffectPlugin 实例按武器 spec 共享，单武器状态必须存于 engine.customData。
class StarfallWingOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val ship = weapon.ship ?: projectile.source
        val counter = fireCounter(engine, weapon)
        counter.shots += 1

        val isPlayer = ship?.owner == 0
        val overdrive = ship != null && (ImaginaryCharge.peek(ship)?.inOverdrive == true)
        val period = (
            if (overdrive) DifficultyTuningImpl.valueFor(StarfallWingTuning.EMPOWERED_PERIOD_OVERDRIVE, isPlayer)
            else DifficultyTuningImpl.valueFor(StarfallWingTuning.EMPOWERED_PERIOD, isPlayer)
            ).roundToInt().coerceAtLeast(1)

        if (counter.shots % period != 0) {
            ProjectileVfxDriverPlugin.track(engine, projectile, StarfallWingTuning.SHOT_SPEC_ID)
            StarfallWingWeaponEffect.projectileStates(engine)[projectile] =
                StarfallWingWeaponEffect.ProjectileState(
                    empowered = false, ownerWeapon = weapon, lastPierceLocation = Vector2f(projectile.location),
                )
            return
        }

        // 断熵升格：spawnProjectile 指定 specId 的 7 参重载恒 NPE，重生成走武器自身 spec 的 6 参重载。
        val source = ship
        if (source == null) {
            log.warn("坠星残翼断熵升格跳过：源舰缺失: weapon=${weapon.id}")
            ProjectileVfxDriverPlugin.track(engine, projectile, StarfallWingTuning.SHOT_SPEC_ID)
            return
        }

        val loc = Vector2f(projectile.location)
        val facing = projectile.facing
        val baseDamage = projectile.damageAmount
        val baseRadius = projectile.collisionRadius
        engine.removeEntity(projectile)

        val spawned = engine.spawnProjectile(
            source, weapon, StarfallWingTuning.WEAPON_ID, loc, facing, Vector2f(source.velocity),
        ) as? DamagingProjectileAPI
        if (spawned == null) {
            log.warn("坠星残翼断熵升格失败：spawnProjectile 未产出 DamagingProjectileAPI: weapon=${weapon.id}")
            return
        }
        spawned.setDamageAmount(baseDamage * StarfallWingTuning.EMPOWERED_DAMAGE_MULT)
        spawned.collisionRadius = baseRadius * StarfallWingTuning.EMPOWERED_SIZE_MULT
        spawned.setCustomData(StarfallWingTuning.EMPOWERED_MARK_KEY, true)
        // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
        ProjectileVfxDriverPlugin.track(engine, spawned, StarfallWingTuning.EMPOWERED_SHOT_SPEC_ID)
        StarfallWingWeaponEffect.projectileStates(engine)[spawned] =
            StarfallWingWeaponEffect.ProjectileState(
                empowered = true, ownerWeapon = weapon, lastPierceLocation = Vector2f(spawned.location),
            )
    }

    private class FireCounter(var shots: Int = 0)

    companion object {
        private const val KEY_FIRE_COUNTERS = "astd_starfall_wing_fire_counters"

        private val log = Global.getLogger(StarfallWingOnFireEffect::class.java)

        @Suppress("UNCHECKED_CAST")
        private fun fireCounter(engine: CombatEngineAPI, weapon: WeaponAPI): FireCounter {
            val counters = engine.customData.getOrPut(KEY_FIRE_COUNTERS) { IdentityHashMap<WeaponAPI, FireCounter>() }
                as IdentityHashMap<WeaponAPI, FireCounter>
            return counters.getOrPut(weapon) { FireCounter() }
        }
    }
}
