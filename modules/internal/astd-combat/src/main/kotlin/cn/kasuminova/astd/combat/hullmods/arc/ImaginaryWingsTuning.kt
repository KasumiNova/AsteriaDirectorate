package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry

object ImaginaryWingsTuning {

    const val CHARGE_MAX = 100f

    val CHARGE_REGEN = ScalingEntry(2f, 4f, 10f)

    const val EXCHANGE_THRESHOLD = 60f

    const val EXCHANGE_COST = 60f

    const val ZERO_FLUX_THRESHOLD = 0.2f

    val GLIDE_ROF_PERCENT = ScalingEntry(20f, 40f, 100f)

    const val SHIFT_DISTANCE = 400f

    const val SHIFT_IMPACT_RADIUS = 350f

    const val SHIFT_IMPACT_DAMAGE = 400f

    const val SHIFT_IMPACT_FORCE = 1500f

    const val CLEAR_RADIUS = 250f
}
