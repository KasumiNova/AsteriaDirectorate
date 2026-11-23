package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI

internal class StarfallBeamCooldown(private val weapon: WeaponAPI) {
    private val originalCooldown = weapon.cooldown
    private val rateStat = with(weapon.ship.mutableStats) {
        when (weapon.type) {
            WeaponAPI.WeaponType.ENERGY -> energyRoFMult
            WeaponAPI.WeaponType.BALLISTIC -> ballisticRoFMult
            else -> null
        }
    }
    private val spec: BeamWeaponSpecAPI
    private val burstCooldown: Float
    private val chargedown: Float
    private val burstLength: Float
    private val skipIdle: Boolean
    private val window: StarfallBeamWindow
    private var scheduledRate = Float.NaN
    private var previousAmmo = weapon.ammo

    init {
        weapon.ensureClonedSpec()
        spec = weapon.spec as BeamWeaponSpecAPI
        burstCooldown = spec.burstCooldown
        chargedown = spec.chargedownTime
        burstLength = spec.chargeupTime + spec.burstDuration
        skipIdle = spec.isSkipIdleFrameIfZeroBurstDelay
        window = StarfallBeamWindow(originalCooldown, burstLength, skipIdle)
        schedule(rateStat?.modifiedValue ?: 1f)
    }

    fun apply(shipSeconds: Float) {
        val rate = rateStat?.modifiedValue ?: 1f
        schedule(rate)
        val remaining = weapon.cooldownRemaining
        val ammo = weapon.ammo
        val block = window.advance(
            shipSeconds, weapon.burstFireTimeRemaining, remaining, weapon.isFiring && weapon.chargeLevel > 0f,
            ammoSpent = weapon.usesAmmo() && ammo < previousAmmo, rate = rate,
        )
        previousAmmo = ammo
        if (rate > 0f && remaining > window.remaining * rate) weapon.setRemainingCooldownTo(window.remaining * rate)
        if (block) weapon.setForceNoFireOneFrame(true)
    }

    private fun schedule(rate: Float) {
        if (rate == scheduledRate) return
        scheduledRate = rate
        if (rate <= 0f) {
            restore()
            return
        }
        // 交替组把蓄力、束段和等待的总时长除以射速；单门光束只有冷却阶段受射速影响。
        val wait = if (skipIdle) window.cooldownAt(rate) else maxOf(window.cooldownAt(rate), chargedown / rate)
        val scheduledWait = ((burstLength + wait) * rate - burstLength).coerceAtLeast(0f)
        spec.chargedownTime = minOf(chargedown, scheduledWait)
        spec.burstCooldown = scheduledWait - spec.chargedownTime
    }

    fun restore() {
        spec.burstCooldown = burstCooldown
        spec.chargedownTime = chargedown
    }
}

internal class StarfallBeamWindow(private val original: Float, private val burstLength: Float, private val skipIdle: Boolean) {
    var remaining = 0f
        private set
    private var previousBurst: Float? = null
    private var waited: Float? = null

    fun cooldownAt(rate: Float): Float = (original * StarfallWeaponCooldowns.COOLDOWN_MULT / rate).coerceAtLeast(0.5f)

    fun advance(
        seconds: Float, burstLeft: Float, nativeLeft: Float, emitting: Boolean,
        ammoSpent: Boolean = false, rate: Float = 1f,
    ): Boolean {
        // 零间隔点射可能在同一帧重启束段，此时剩余束长重新满值，充能亮度仍为正。
        if (burstLeft < burstLength || (skipIdle && nativeLeft == 0f && emitting)) {
            previousBurst = burstLeft
            waited = null
            remaining = 0f
            return skipIdle
        }
        val finished = previousBurst
        previousBurst = null
        val previousWait = waited
        val elapsed = when {
            finished != null -> (seconds - finished).coerceAtLeast(0f)
            ammoSpent -> (seconds - burstLength).coerceAtLeast(0f)
            previousWait != null -> previousWait + if (rate > 0f) seconds else 0f
            nativeLeft > 0f -> if (rate > 0f) ((original - nativeLeft) / rate).coerceAtLeast(0f) else 0f
            else -> return false
        }
        remaining = if (rate > 0f) (cooldownAt(rate) - elapsed).coerceAtLeast(0f) else Float.POSITIVE_INFINITY
        waited = if (remaining > 0f || nativeLeft > 0f) elapsed else null
        return remaining > 0f
    }
}
