package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.ScalingEntry

object QiongjuePhaseRailgunDifficulty {

    val PER_STACK_BONUS = ScalingEntry(0.025f, 0.05f, 0.125f)

    val SWITCH_RETAIN = ScalingEntry(0.25f, 0.5f, 1f)

    val DECAY_RATE = ScalingEntry(2f, 1.75f, 1f)

    const val MAX_STACKS = 10

    const val SIC_UPGRADE_MAX_STACKS = 15

    const val DECAY_WINDOW_SECONDS = 3f

    const val WEAPON_ID = "astd_qiongjue_phase_railgun"
}
