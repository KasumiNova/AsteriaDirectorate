package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap

// OnFireEffectPlugin 实例按武器 spec 共享，单武器状态必须存于 engine.customData。
class StarfallEchoOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return

        val now = engine.getTotalElapsedTime(false)
        val state = burstState(engine, weapon)
        val ordinal = StarfallEchoTuning.nextBurstOrdinal(state.ordinal, state.lastFireTime, now)
        state.ordinal = ordinal
        state.lastFireTime = now

        if (ordinal < StarfallEchoTuning.BURST_SIZE) {
            ProjectileVfxDriverPlugin.track(engine, projectile, SPEC_ID_NORMAL)
            return
        }

        // spawnProjectile 指定 specId 的 7 参重载恒 NPE（原版加载器不给 projectile 类 spec 填 damage），
        // 重生成走武器自身 spec 的 6 参重载，第 5 发身份由 customData 标记承载。
        val source = weapon.ship ?: projectile.source
        if (source == null) {
            log.warn("坠星残响第 5 发替换跳过：源舰缺失: weapon=${weapon.id}")
            ProjectileVfxDriverPlugin.track(engine, projectile, SPEC_ID_NORMAL)
            return
        }

        val loc = Vector2f(projectile.location)
        val facing = projectile.facing
        val baseDamage = projectile.damageAmount
        val baseRadius = projectile.collisionRadius
        engine.removeEntity(projectile)

        val spawned = engine.spawnProjectile(
            source, weapon, WEAPON_ID, loc, facing, Vector2f(source.velocity),
        ) as? DamagingProjectileAPI
        if (spawned == null) {
            log.warn("坠星残响第 5 发替换失败：spawnProjectile 未产出 DamagingProjectileAPI: weapon=${weapon.id}")
            return
        }
        spawned.setDamageAmount(baseDamage * StarfallEchoTuning.FINAL_DAMAGE_MULT)
        spawned.collisionRadius = baseRadius * StarfallEchoTuning.FINAL_SIZE_MULT
        spawned.setCustomData(FINAL_SHOT_MARK_KEY, true)
        source.fluxTracker.increaseFlux(StarfallEchoTuning.FINAL_FLUX_EXTRA, false)
        // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
        ProjectileVfxDriverPlugin.track(engine, spawned, SPEC_ID_FINAL)
    }

    private class BurstState(var ordinal: Int = 0, var lastFireTime: Float = Float.NEGATIVE_INFINITY)

    companion object {
        const val WEAPON_ID = "astd_starfall_echo"
        const val SPEC_ID_NORMAL = "astd_starfall_echo_shot"
        const val SPEC_ID_FINAL = "astd_starfall_echo_shot_final"
        const val FINAL_SHOT_MARK_KEY = "astd_starfall_echo_final_shot"
        private const val KEY_BURST_STATES = "astd_starfall_echo_burst_states"

        private val log = Global.getLogger(StarfallEchoOnFireEffect::class.java)

        @Suppress("UNCHECKED_CAST")
        private fun burstState(engine: CombatEngineAPI, weapon: WeaponAPI): BurstState {
            val states = engine.customData.getOrPut(KEY_BURST_STATES) { IdentityHashMap<WeaponAPI, BurstState>() }
                as IdentityHashMap<WeaponAPI, BurstState>
            return states.getOrPut(weapon) { BurstState() }
        }
    }
}
