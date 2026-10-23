package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceDifficulty.CONE_DAMAGE
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI

object PiercingLanceDifficulty {

    const val PANEL_DAMAGE = 2500f

    val CONE_ARC = ScalingEntry(40f, 50f, 80f)

    val CONE_RANGE = ScalingEntry(300f, 375f, 600f)

    val CONE_DAMAGE = ScalingEntry(1.00f, 1.25f, 2.00f)

    fun valueFor(source: ShipAPI?, entry: ScalingEntry): Float =
        DifficultyTuningImpl.valueFor(entry, source?.owner == 0)
}
