package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残响的开火路由（挂普通弹 spec 的 `onFireEffect`）：连射序数计数 + 第 5 发弹体替换。
 *
 * - 前 4 发：登记普通弹 VFX 树（等价 ProjectileSpecOnFireDispatcher 的 track 行为）；
 * - 第 [StarfallEchoTuning.BURST_SIZE] 发：移除原弹体，原地替换为 `astd_starfall_echo_shot_final`
 *   （200% 尺寸 / 200% 伤害），追加 300% 辐能差额（[StarfallEchoTuning.FINAL_FLUX_EXTRA]），
 *   并显式 track final spec（脚本 spawn 不触发 onFireEffect，见 ProjectileVfxSpecs 双子星注记）。
 *
 * 连射序数按武器实例计数，状态存 `engine.customData`（OnFire 插件实例为 spec 级共享，
 * 不能持有单武器状态）；距上一发超过 [StarfallEchoTuning.BURST_RESET_SECONDS] 视为新一轮。
 */
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

        // 第 5 发：替换弹体。源舰/武器缺失属理论边界（弹体必有武器），缺源舰时不替换只告警。
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
            source, weapon, WEAPON_ID, SPEC_ID_FINAL, loc, facing, Vector2f(source.velocity),
        ) as? DamagingProjectileAPI
        if (spawned == null) {
            log.warn("坠星残响第 5 发替换失败：spawnProjectile 未产出 DamagingProjectileAPI: weapon=${weapon.id}")
            return
        }
        spawned.setDamageAmount(baseDamage * StarfallEchoTuning.FINAL_DAMAGE_MULT)
        spawned.collisionRadius = baseRadius * StarfallEchoTuning.FINAL_SIZE_MULT
        source.fluxTracker.increaseFlux(StarfallEchoTuning.FINAL_FLUX_EXTRA, false)
        ProjectileVfxDriverPlugin.track(engine, spawned, SPEC_ID_FINAL)
    }

    /** 武器级连射状态（序数 + 上一发时刻）。 */
    private class BurstState(var ordinal: Int = 0, var lastFireTime: Float = Float.NEGATIVE_INFINITY)

    companion object {
        const val WEAPON_ID = "astd_starfall_echo"
        const val SPEC_ID_NORMAL = "astd_starfall_echo_shot"
        const val SPEC_ID_FINAL = "astd_starfall_echo_shot_final"

        /** engine.customData 键：武器 identityHashCode → 连射状态。 */
        private const val KEY_BURST_STATES = "astd_starfall_echo_burst_states"

        private val log = Global.getLogger(StarfallEchoOnFireEffect::class.java)

        @Suppress("UNCHECKED_CAST")
        private fun burstState(engine: CombatEngineAPI, weapon: WeaponAPI): BurstState {
            val states = engine.customData.getOrPut(KEY_BURST_STATES) { HashMap<Int, BurstState>() }
                as HashMap<Int, BurstState>
            return states.getOrPut(System.identityHashCode(weapon)) { BurstState() }
        }
    }
}
