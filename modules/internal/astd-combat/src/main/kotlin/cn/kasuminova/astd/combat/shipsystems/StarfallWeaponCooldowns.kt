package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI

internal class StarfallWeaponCooldowns(ship: ShipAPI) {
    private val projectiles = ArrayList<ProjectileCooldown>()
    private val beams = ArrayList<StarfallBeamCooldown>()

    init {
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.type == WeaponAPI.WeaponType.MISSILE) continue
            when (val spec = weapon.spec) {
                is ProjectileWeaponSpecAPI -> projectiles += ProjectileCooldown(weapon, spec)
                is BeamWeaponSpecAPI -> if (spec.isBurstBeam) beams += StarfallBeamCooldown(weapon)
            }
        }
    }

    fun apply(shipSeconds: Float) {
        for (projectile in projectiles) projectile.apply()
        for (beam in beams) beam.apply(shipSeconds)
    }

    fun restore() {
        for (projectile in projectiles) projectile.restore()
        for (beam in beams) beam.restore()
    }

    private class ProjectileCooldown(private val weapon: WeaponAPI, original: ProjectileWeaponSpecAPI) {
        private val originalSpecDelay = original.refireDelay
        private val target = projectileCooldown(weapon.usesAmmo(), original.burstSize, original.burstDelay, originalSpecDelay)
        private val spec: ProjectileWeaponSpecAPI
        private val window = StarfallRefireWindow(target, ::setTracker)

        init {
            weapon.ensureClonedSpec()
            spec = weapon.spec as ProjectileWeaponSpecAPI
            spec.refireDelay = target
        }

        fun apply() {
            window.apply(weapon.isInBurst, weapon.refireDelay, weapon.cooldownRemaining / target)
        }

        fun restore() {
            val remainingFraction = weapon.cooldownRemaining / target
            spec.refireDelay = originalSpecDelay
            window.restore(weapon.isInBurst, weapon.refireDelay, remainingFraction)
        }

        private fun setTracker(delay: Float, remaining: Float?) {
            weapon.refireDelay = delay
            if (remaining != null && spec.refireDelay > 0f) {
                // API 按规格时长换算已走过的秒数；武器实例上的计时器可有独立时长。
                weapon.setRemainingCooldownTo(spec.refireDelay - (delay - remaining))
            }
        }
    }

    companion object {
        const val COOLDOWN_MULT = 0.25f

        fun projectileCooldown(usesAmmo: Boolean, burstSize: Int, burstDelay: Float, originalCooldown: Float): Float {
            if (usesAmmo || burstSize > 1) return burstDelay.coerceAtLeast(0.05f)
            return (originalCooldown * COOLDOWN_MULT).coerceAtLeast(0.25f)
        }
    }
}

internal class StarfallRefireWindow(private val target: Float, private val setTracker: (Float, Float?) -> Unit) {
    private var previous: Float? = null

    fun apply(inBurst: Boolean, delay: Float, remainingFraction: Float) {
        // 连射间隔与齐射后冷却共用一个原版计时器，连射过程中必须保留其当前时长。
        if (inBurst || delay == target) return
        previous = delay
        // setRefireDelay 保留原生计时器的已耗秒数。超过新时长的部分须截掉，否则会被带入下一轮。
        val finishNow = remainingFraction > 0f && delay * (1f - remainingFraction) >= target
        setTracker(target, if (finishNow) 0f else null)
    }

    fun restore(inBurst: Boolean, delay: Float, remainingFraction: Float) {
        val original = previous ?: return
        if (inBurst || delay != target) return
        val remaining = if (remainingFraction > 0f) minOf(original, target) * remainingFraction else null
        setTracker(original, remaining)
    }
}
