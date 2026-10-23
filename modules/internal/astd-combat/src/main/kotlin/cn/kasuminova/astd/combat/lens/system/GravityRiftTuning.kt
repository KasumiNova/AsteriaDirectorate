package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.MINE_BASE_DAMAGE
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.PHASE_SUPPRESS_COOLDOWN_REMAINING
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_DURATION
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_FADE_IN
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_FADE_OUT
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import kotlin.math.sqrt

object GravityRiftTuning {

    const val SYSTEM_RANGE = 1000f

    const val BEAM_RANGE = 1000f

    const val MAX_RIFTS = 5

    const val RANGE_PER_EXTRA_RIFT = 200f

    const val SPAWN_INTERVAL = 0.1f

    const val SPAWN_SPACING = 175f

    const val VORTEX_SPIN_DEG_PER_SEC = 60f

    const val VORTEX_DURATION = 3f
    const val VORTEX_FADE_IN = 1f
    const val VORTEX_FADE_OUT = 1f

    const val BEAM_DELAY = 1f

    const val BEAM_DURATION = 2f

    const val VORTEX_RADIUS_FRAC = 0.75f
    const val VORTEX_RADIUS_MIN = 90f
    const val VORTEX_RADIUS_MAX = 260f

    const val MINE_BASE_DAMAGE = 1000f

    val RIFT_DAMAGE_MIN = ScalingEntry(750f, 1000f, 1750f)

    val RIFT_DAMAGE_MAX = ScalingEntry(1250f, 1500f, 2250f)

    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.50f, 0.60f, 0.90f)

    data class Values(
        val damageMin: Float,
        val damageMax: Float,
        val damageTakenReduction: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageMin = tuning.valueFor(RIFT_DAMAGE_MIN, isPlayer),
        damageMax = tuning.valueFor(RIFT_DAMAGE_MAX, isPlayer),
        damageTakenReduction = tuning.valueFor(DAMAGE_TAKEN_REDUCTION, isPlayer),
    )

    fun damageTakenMult(reduction: Float): Float = (1f - reduction).coerceIn(0f, 1f)

    fun phaseSuppressCooldown(cooldownRemaining: Float, inCooldown: Boolean): Float? =
        if (inCooldown && cooldownRemaining >= PHASE_SUPPRESS_COOLDOWN_REMAINING) null
        else PHASE_SUPPRESS_COOLDOWN_REMAINING

    const val PHASE_SUPPRESS_COOLDOWN_REMAINING = 0.1f

    fun jitterLevelAndRange(
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
        chargeUpDur: Float,
    ): Pair<Float, Float> {
        var jitterLevel = effectLevel
        val rangeBonus: Float
        when (state) {
            ShipSystemStatsScript.State.IN -> {
                jitterLevel = (effectLevel / (1f / chargeUpDur)).coerceAtMost(1f)
                rangeBonus = jitterLevel * 10f
            }
            ShipSystemStatsScript.State.ACTIVE -> {
                jitterLevel = 1f
                rangeBonus = 10f
            }
            else -> {
                rangeBonus = effectLevel * 10f
            }
        }
        return sqrt(jitterLevel) to rangeBonus
    }

    fun riftCount(unusedRange: Float): Int =
        ((unusedRange / RANGE_PER_EXTRA_RIFT).toInt() + 1).coerceIn(1, MAX_RIFTS)

    fun riftDamage(damageMin: Float, damageMax: Float, index: Int, total: Int): Float =
        if (total <= 1) damageMax else damageMin + (damageMax - damageMin) * index / (total - 1)

    fun riftDamageMult(damage: Float): Float = damage / MINE_BASE_DAMAGE

    fun vortexAlpha(elapsed: Float, forceFadeOutAt: Float = -1f): Float {
        if (elapsed <= 0f) return 0f
        val envelope = (elapsed / VORTEX_FADE_IN).coerceIn(0f, 1f) *
                ((VORTEX_DURATION - elapsed) / VORTEX_FADE_OUT).coerceIn(0f, 1f)
        if (forceFadeOutAt < 0f || elapsed < forceFadeOutAt) return envelope
        val fade = (1f - (elapsed - forceFadeOutAt) / VORTEX_FADE_OUT).coerceIn(0f, 1f)
        return minOf(envelope, fade)
    }
}
