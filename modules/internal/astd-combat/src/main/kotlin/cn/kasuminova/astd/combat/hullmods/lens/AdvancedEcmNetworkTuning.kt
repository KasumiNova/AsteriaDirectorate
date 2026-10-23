package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI

object AdvancedEcmNetworkTuning {

    const val HULLMOD_ID = "astd_advanced_ecm_network"

    const val ECM_FLAT_STAT = "electronic_warfare_flat"

    const val EW_MAX_STAT = "electronic_warfare_max"

    const val CAP_RATE_STAT = "ship_objective_cap_rate_mult"

    const val VANILLA_MAX_EFFECT = 10f

    const val SENSOR_ARRAY_ECM = 5f

    const val SENSOR_ARRAY_TYPE = "sensor_array"

    const val MOD_ID_PREFIX = "astd_advanced_ecm_network:"

    const val LEGACY_MOD_ID_PREFIX = "astd_grav_ew_field:"

    val SELF_ECM = ScalingEntry(15f, 20f, 35f)

    val ALLY_ECM_FRIGATE = ScalingEntry(2f, 4f, 8f)

    val ALLY_ECM_DESTROYER = ScalingEntry(4f, 8f, 16f)

    val ALLY_ECM_CRUISER = ScalingEntry(6f, 12f, 24f)

    val ALLY_ECM_CAPITAL = ScalingEntry(8f, 16f, 32f)

    const val ENEMY_CAP_FRIGATE = 4f
    const val ENEMY_CAP_DESTROYER = 8f
    const val ENEMY_CAP_CRUISER = 12f
    const val ENEMY_CAP_CAPITAL = 16f

    val MAX_EFFECT_BOOST = ScalingEntry(0.5f, 1.0f, 2.5f)

    val POINT_MAX_BOOST = ScalingEntry(0.2f, 0.3f, 0.6f)

    val CAPTURE_RATE_BOOST = ScalingEntry(1.0f, 2.0f, 5.0f)

    data class Values(
        val selfEcm: Float,
        val allyEcmFrigate: Float,
        val allyEcmDestroyer: Float,
        val allyEcmCruiser: Float,
        val allyEcmCapital: Float,
        val maxEffectBoost: Float,
        val pointMaxBoost: Float,
        val captureRateMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        selfEcm = tuning.valueFor(SELF_ECM, isPlayer),
        allyEcmFrigate = tuning.valueFor(ALLY_ECM_FRIGATE, isPlayer),
        allyEcmDestroyer = tuning.valueFor(ALLY_ECM_DESTROYER, isPlayer),
        allyEcmCruiser = tuning.valueFor(ALLY_ECM_CRUISER, isPlayer),
        allyEcmCapital = tuning.valueFor(ALLY_ECM_CAPITAL, isPlayer),
        maxEffectBoost = tuning.valueFor(MAX_EFFECT_BOOST, isPlayer),
        pointMaxBoost = tuning.valueFor(POINT_MAX_BOOST, isPlayer),
        captureRateMult = 1f + tuning.valueFor(CAPTURE_RATE_BOOST, isPlayer),
    )

    fun totalOwnEcm(values: Values, frigates: Int, destroyers: Int, cruisers: Int, capitals: Int): Float =
        values.selfEcm +
            frigates.coerceAtLeast(0) * values.allyEcmFrigate +
            destroyers.coerceAtLeast(0) * values.allyEcmDestroyer +
            cruisers.coerceAtLeast(0) * values.allyEcmCruiser +
            capitals.coerceAtLeast(0) * values.allyEcmCapital

    fun enemyCapFor(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> ENEMY_CAP_CAPITAL
        ShipAPI.HullSize.CRUISER -> ENEMY_CAP_CRUISER
        ShipAPI.HullSize.DESTROYER -> ENEMY_CAP_DESTROYER
        else -> ENEMY_CAP_FRIGATE
    }

    fun clampDelta(current: Float, cap: Float): Float = if (current > cap) cap - current else 0f

    fun commanderBoost(values: Values, capturedPoints: Int): Float =
        VANILLA_MAX_EFFECT * (values.maxEffectBoost + capturedPoints.coerceAtLeast(0) * values.pointMaxBoost)

    fun penaltyDealt(ownTotal: Float, ownMax: Float, enemyTotal: Float): Int {
        val total = ownTotal.toInt()
        val max = ownMax.toInt()
        val enemy = enemyTotal.toInt()
        var penalty = minOf(total, max).toFloat()
        if (enemy > 0 && penalty > 0f) penalty *= total.toFloat() / (enemy + total)
        return Math.round(penalty)
    }

    fun debuffMagnitude(penalty: Int): Float = penalty / 100f

    fun fieldModSources(sources: Collection<String>): List<String> =
        sources.filter { it.startsWith(MOD_ID_PREFIX) || it.startsWith(LEGACY_MOD_ID_PREFIX) }
}
