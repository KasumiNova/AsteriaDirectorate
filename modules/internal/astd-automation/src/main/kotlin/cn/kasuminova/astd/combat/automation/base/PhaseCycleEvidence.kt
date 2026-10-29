package cn.kasuminova.astd.combat.automation.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import kotlin.math.abs

class PhaseCycleEvidence {
    enum class Resource { COOLDOWN, AMMO }

    data class Cooldown(
        val weapon: WeaponAPI,
        val remaining: Float,
        val refireDelay: Float,
        val cooldown: Float,
        val resource: Resource = Resource.COOLDOWN,
        val naturalRate: Float = 1f,
        val entryAmmo: Int = 0,
        val requiredAmmo: Int = 0,
        val maxAmmo: Int = 0,
        val reloadProgress: Float = 0f,
        val reloadSize: Float = 0f,
        val ammoPerSecond: Float = 0f,
        var remainingAtExit: Float = Float.NaN,
        var ammoAtExit: Int = -1,
        var reloadProgressAtExit: Float = Float.NaN,
        var stable: Boolean = true,
        var firstInstabilityTime: Float = -1f,
        var firstInstabilityReason: String = "",
        var instabilityDisabled: Boolean = false,
        var instabilityForceDisabled: Boolean = false,
        var instabilityInBurst: Boolean = false,
        var instabilityRefireDelay: Float = Float.NaN,
        var instabilityCooldown: Float = Float.NaN,
    )

    data class Cycle(
        val start: Float,
        val commandTime: Float,
        val action: String,
        val recoverMode: String,
        val reason: String,
        val entryHardFlux: Float,
        val maxFlux: Float,
        val baseTimeMult: Float,
        val targetId: String?,
        val cooldowns: List<Cooldown>,
        val attributed: Boolean,
        var surface: Float = -1f,
        var end: Float = -1f,
        var stableTimeMult: Boolean = true,
        var firstClockChangeTime: Float = Float.NaN,
        var firstChangedBaseTimeMult: Float = Float.NaN,
    )

    val cycles = ArrayList<Cycle>()
    private var previousState: SystemState? = null
    private var previousPhased = false
    private var consumedCommand = -1f
    private var active: Cycle? = null

    fun bind(ship: ShipAPI) {
        previousState = ship.phaseCloak?.state
        previousPhased = ship.isPhased
    }

    fun update(engine: CombatEngineAPI, ship: ShipAPI, now: Float) {
        val cloak = ship.phaseCloak ?: return
        val state = cloak.state
        if (state in setOf(SystemState.IN, SystemState.ACTIVE) &&
            previousState !in setOf(SystemState.IN, SystemState.ACTIVE, SystemState.OUT)) {
            val fact = engine.customData["astd_phase_activation_${ship.id}"] as? Map<*, *>
            val command = (fact?.get("time") as? Number)?.toFloat() ?: -1f
            val attributed = previousState == SystemState.IDLE && state == SystemState.IN &&
                command > consumedCommand && command in (now - 0.5f)..now
            val weapons = if (attributed) buildList {
                val entries = (fact?.get("weapons") as? List<*>).orEmpty()
                for (entry in entries) {
                    val item = entry as? Map<*, *> ?: continue
                    val weapon = item["weapon"] as? WeaponAPI ?: continue
                    val remaining = (item["remaining"] as? Number)?.toFloat() ?: continue
                    val refireDelay = (item["refireDelay"] as? Number)?.toFloat() ?: continue
                    val cooldown = (item["cooldown"] as? Number)?.toFloat() ?: continue
                    val naturalCooldownRate = (item["naturalCooldownRate"] as? Number)?.toFloat() ?: continue
                    if (item["inBurst"] != false) continue
                    if (remaining > 0f) {
                        add(Cooldown(weapon, remaining, refireDelay, cooldown, naturalRate = naturalCooldownRate))
                    }
                    if (item["usesAmmo"] != true) continue
                    val ammo = (item["ammo"] as? Number)?.toInt() ?: continue
                    val required = (item["requiredAmmo"] as? Number)?.toInt() ?: continue
                    val maxAmmo = (item["maxAmmo"] as? Number)?.toInt() ?: continue
                    val progress = (item["reloadProgress"] as? Number)?.toFloat() ?: continue
                    val reloadSize = (item["reloadSize"] as? Number)?.toFloat() ?: continue
                    val ammoPerSecond = (item["ammoPerSecond"] as? Number)?.toFloat() ?: continue
                    val naturalRate = (item["naturalAmmoRegenMult"] as? Number)?.toFloat() ?: continue
                    val reload = reloadRemaining(ammo, required, maxAmmo, progress, reloadSize, ammoPerSecond)
                    if (reload > 0f && reload.isFinite()) {
                        add(Cooldown(
                            weapon, reload, refireDelay, cooldown, Resource.AMMO, naturalRate,
                            ammo, required, maxAmmo, progress, reloadSize, ammoPerSecond,
                        ))
                    }
                }
            } else emptyList()
            fun number(key: String) = if (attributed) (fact?.get(key) as? Number)?.toFloat() ?: Float.NaN else Float.NaN
            active = Cycle(now, command,
                if (attributed) fact?.get("action") as? String ?: "UNKNOWN" else "UNKNOWN",
                if (attributed) fact?.get("recoverMode") as? String ?: "UNKNOWN" else "UNKNOWN",
                if (attributed) fact?.get("reason") as? String ?: "" else "",
                number("hardFlux"), number("maxFlux"), number("timeMult"),
                if (attributed) fact?.get("targetId") as? String else null, weapons, attributed)
            cycles += active!!
            if (attributed) consumedCommand = command
        }
        active?.let { cycle ->
            val phaseFactor = 1f + (PhaseCloakStats.getMaxTimeMult(ship.mutableStats) - 1f) * cloak.effectLevel
            val base = ship.mutableStats.timeMult.modifiedValue / phaseFactor
            if (cycle.stableTimeMult && (!base.isFinite() || abs(base - cycle.baseTimeMult) > 0.01f)) {
                cycle.stableTimeMult = false
                cycle.firstClockChangeTime = now
                cycle.firstChangedBaseTimeMult = base
            }
            for (sample in cycle.cooldowns) {
                if (!sample.stable) continue
                val weapon = sample.weapon
                val disabled = weapon.isDisabled
                val forceDisabled = weapon.isForceDisabled
                val inBurst = weapon.isInBurst
                val refireMismatch = abs(weapon.refireDelay - sample.refireDelay) > 0.001f
                val cooldownMismatch = abs(weapon.cooldown - sample.cooldown) > 0.001f
                val currentRate = when (sample.resource) {
                    Resource.COOLDOWN -> naturalCooldownRate(ship, weapon)
                    Resource.AMMO -> naturalAmmoRegenMult(ship, weapon)
                }
                val rateMismatch = !currentRate.isFinite() || abs(currentRate - sample.naturalRate) > 0.001f
                val ammoSpecMismatch = sample.resource == Resource.AMMO && (!weapon.usesAmmo() ||
                    weapon.ammoTracker.maxAmmo != sample.maxAmmo ||
                    abs(weapon.ammoTracker.reloadSize - sample.reloadSize) > 0.001f ||
                    abs(weapon.ammoTracker.ammoPerSecond - sample.ammoPerSecond) > 0.001f)
                if (disabled || forceDisabled || inBurst || refireMismatch || cooldownMismatch ||
                    rateMismatch || ammoSpecMismatch) {
                    sample.stable = false
                    sample.firstInstabilityTime = now
                    sample.instabilityDisabled = disabled
                    sample.instabilityForceDisabled = forceDisabled
                    sample.instabilityInBurst = inBurst
                    sample.instabilityRefireDelay = weapon.refireDelay
                    sample.instabilityCooldown = weapon.cooldown
                    val reasons = mutableListOf<String>()
                    if (disabled) reasons += "disabled"
                    if (forceDisabled) reasons += "forceDisabled"
                    if (inBurst) reasons += "inBurst"
                    if (refireMismatch) reasons += "refireDelay:${weapon.refireDelay}!=${sample.refireDelay}"
                    if (cooldownMismatch) reasons += "cooldown:${weapon.cooldown}!=${sample.cooldown}"
                    if (rateMismatch) reasons += "naturalRate:$currentRate!=${sample.naturalRate}"
                    if (ammoSpecMismatch) reasons += "ammoTrackerChanged"
                    sample.firstInstabilityReason = reasons.joinToString(",")
                }
            }
            if (previousPhased && !ship.isPhased && cycle.surface < 0f) cycle.surface = now
            if (state == SystemState.COOLDOWN || state == SystemState.IDLE) {
                cycle.end = now
                for (sample in cycle.cooldowns) {
                    sample.remainingAtExit = when (sample.resource) {
                        Resource.COOLDOWN -> sample.weapon.cooldownRemaining
                        Resource.AMMO -> reloadRemaining(
                            sample.weapon.ammo, sample.requiredAmmo, sample.maxAmmo,
                            sample.weapon.ammoTracker.reloadProgress, sample.reloadSize, sample.ammoPerSecond,
                        )
                    }
                    if (sample.resource == Resource.AMMO) {
                        sample.ammoAtExit = sample.weapon.ammo
                        sample.reloadProgressAtExit = sample.weapon.ammoTracker.reloadProgress
                    }
                }
                active = null
            }
        }
        previousState = state
        previousPhased = ship.isPhased
    }

    private fun naturalCooldownRate(ship: ShipAPI, weapon: WeaponAPI): Float =
        cooldownRate(weapon, naturalRoFMult(ship, weapon))

    private fun naturalRoFMult(ship: ShipAPI, weapon: WeaponAPI): Float {
        val stat = when (weapon.type) {
            WeaponAPI.WeaponType.BALLISTIC -> ship.mutableStats.ballisticRoFMult
            WeaponAPI.WeaponType.ENERGY -> ship.mutableStats.energyRoFMult
            WeaponAPI.WeaponType.MISSILE -> ship.mutableStats.missileRoFMult
            else -> return 1f
        }
        if (stat.getMultStatMod("phase_anchor_modifier") == null) return stat.modifiedValue
        return stat.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
    }

    private fun cooldownRate(weapon: WeaponAPI, rate: Float): Float {
        if (weapon.isBeam) return if (weapon.isBurstBeam) rate else 1f
        val spec = weapon.spec as? com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI ?:
            return kotlin.math.min(rate, 1f)
        val refireDelay = weapon.refireDelay
        if (refireDelay <= 0f) return 0f
        val effectiveRate = if (rate <= 1f) rate else {
            val interval = if (weapon.isInBurst) spec.burstDelay else spec.refireDelay + spec.chargeTime
            val minimum = com.fs.starfarer.api.Global.getSettings().getFloat("minRefireDelay")
            if (interval / rate < minimum) kotlin.math.max(1f, interval / minimum) else rate
        }
        return effectiveRate * spec.refireDelay / refireDelay
    }

    private fun naturalAmmoRegenMult(ship: ShipAPI, weapon: WeaponAPI): Float {
        val stat = when (weapon.type) {
            WeaponAPI.WeaponType.BALLISTIC -> ship.mutableStats.ballisticAmmoRegenMult
            WeaponAPI.WeaponType.ENERGY -> ship.mutableStats.energyAmmoRegenMult
            WeaponAPI.WeaponType.MISSILE -> ship.mutableStats.missileAmmoRegenMult
            else -> return 1f
        }
        if (stat.getMultStatMod("phase_anchor_modifier") == null) return stat.modifiedValue
        return stat.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
    }

    private fun reloadRemaining(
        ammo: Int,
        requiredAmmo: Int,
        maxAmmo: Int,
        reloadProgress: Float,
        reloadSize: Float,
        ammoPerSecond: Float,
    ): Float {
        val missing = requiredAmmo - ammo
        if (missing <= 0) return 0f
        val batchAmmo = reloadSize.toInt()
        if (requiredAmmo > maxAmmo || ammoPerSecond <= 0f || reloadSize <= 0f || batchAmmo <= 0 ||
            !ammoPerSecond.isFinite() || !reloadSize.isFinite() || !reloadProgress.isFinite()) {
            return Float.POSITIVE_INFINITY
        }
        val batches = kotlin.math.ceil(missing.toDouble() / batchAmmo).toFloat()
        val committedProgress = if (reloadProgress >= 1f) 0f else reloadProgress.coerceIn(0f, 1f)
        return ((batches - committedProgress) * reloadSize / ammoPerSecond).coerceAtLeast(0f)
    }
}
