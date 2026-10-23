package cn.kasuminova.astd.combat.effect.arc.sevenstars

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsDifficulty.snapshot
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

object SevenStarsDifficulty {
    private val log = Global.getLogger(SevenStarsDifficulty::class.java)

    val FIRST_HIT_MULT = ScalingEntry(1.00f, 1.25f, 2.00f)

    val PER_JUMP_BONUS = ScalingEntry(0.50f, 0.625f, 1.00f)

    val BONUS_CAP = ScalingEntry(1.00f, 1.75f, 4.00f)

    const val MAX_JUMPS = 7

    const val CHAIN_COOLDOWN = 0.3f

    const val EXPLOSION_DELAY = 0.5f

    const val JUMP_RANGE_MULT = 0.5f

    const val AOE_RADIUS = 100f

    const val TERMINAL_BASE_FRACTION = 0.5f

    const val TERMINAL_STEP_FRACTION = 0.25f

    const val TERMINAL_MAX_FRACTION = 2.0f

    const val TERMINAL_SEGMENT_INTERVAL = 0.12f

    const val DAWN_SCALE_THRESHOLD = 5f

    @Volatile
    private var nullSourceWarned = false

    data class SevenStarsTuning(
        val firstHitMult: Float,
        val perJumpBonus: Float,
        val bonusCap: Float,
        val multiSegmentTerminal: Boolean,
    )

    fun snapshot(source: ShipAPI?): SevenStarsTuning {
        if (source == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("“七星”折跃发射器弹体无来源舰船，难度取值按敌方口径（k_s 映射）结算")
        }
        val playerOwned = source?.owner == 0
        return SevenStarsTuning(
            firstHitMult = DifficultyTuningImpl.valueFor(FIRST_HIT_MULT, playerOwned),
            perJumpBonus = DifficultyTuningImpl.valueFor(PER_JUMP_BONUS, playerOwned),
            bonusCap = DifficultyTuningImpl.valueFor(BONUS_CAP, playerOwned),
            multiSegmentTerminal = !playerOwned && DifficultyTuningImpl.fixedScale >= DAWN_SCALE_THRESHOLD,
        )
    }
}
