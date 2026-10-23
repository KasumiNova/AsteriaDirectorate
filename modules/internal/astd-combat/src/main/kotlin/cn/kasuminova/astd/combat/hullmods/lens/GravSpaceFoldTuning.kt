package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import org.lwjgl.util.vector.Vector2f

object GravSpaceFoldTuning {

    const val FOLD_RANGE_BONUS = 200f

    const val DAMAGE_NO_BONUS_BELOW = 50f

    const val DAMAGE_FULL_BONUS_AT = 500f

    val FOLD_CHANCE_BASE = ScalingEntry(0.40f, 0.50f, 0.80f)

    val FOLD_CHANCE_CAP = ScalingEntry(0.65f, 0.75f, 1.00f)

    val BEAM_DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    data class Values(
        val foldChanceBase: Float,
        val foldChanceCap: Float,
        val beamDamageTakenMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        foldChanceBase = tuning.valueFor(FOLD_CHANCE_BASE, isPlayer),
        foldChanceCap = tuning.valueFor(FOLD_CHANCE_CAP, isPlayer),
        beamDamageTakenMult = 1f - tuning.valueFor(BEAM_DAMAGE_TAKEN_REDUCTION, isPlayer),
    )

    fun foldChance(base: Float, cap: Float, damage: Float): Float {
        val t = ((damage - DAMAGE_NO_BONUS_BELOW) / (DAMAGE_FULL_BONUS_AT - DAMAGE_NO_BONUS_BELOW))
            .coerceIn(0f, 1f)
        return base + (cap - base) * t
    }

    fun mirroredPosition(center: Vector2f, pos: Vector2f): Vector2f =
        Vector2f(2f * center.x - pos.x, 2f * center.y - pos.y)

    const val MARK_FOLDED = "folded"

    const val MARK_NO_FOLD = "no_fold"

    fun resolveFold(existingMark: String?, roll: Float, chance: Float): Pair<String, Boolean> {
        if (existingMark != null) return existingMark to false
        return if (roll < chance) MARK_FOLDED to true else MARK_NO_FOLD to false
    }
}
