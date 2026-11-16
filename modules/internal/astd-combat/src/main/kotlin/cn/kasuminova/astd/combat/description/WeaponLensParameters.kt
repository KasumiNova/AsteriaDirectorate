package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.combat.description.DescriptionNumbers.number
import cn.kasuminova.astd.combat.description.DescriptionNumbers.percent
import cn.kasuminova.astd.combat.effect.generic.gravitycollapse.GravityCollapseWeaponSpecs
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexDifficulty
import cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardMirvDifficulty
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmDifficulty

internal object WeaponLensParameters {
    fun collapse(id: String, tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val spec = requireNotNull(GravityCollapseWeaponSpecs.forWeaponId(id)) { "Unregistered collapse weapon: $id" }
        return linkedMapOf(
            "damageRatio" to percent(tuning.valueFor(spec.aoeDamageRatio, isPlayer)),
            "hullRatio" to percent(tuning.valueFor(spec.hullDamageRatio, isPlayer)),
            "mobilityReduction" to percent(tuning.valueFor(spec.mobilityReduction, isPlayer)),
            "duration" to number(tuning.valueFor(spec.mobilityDuration, isPlayer)),
            "phasedDamage" to percent(tuning.valueFor(spec.phasedDamageRatio, isPlayer)),
        )
    }

    fun vortex(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "collapseMult" to percent(tuning.valueFor(AnnihilationVortexDifficulty.AOE_MULT, isPlayer)),
    )

    fun stellar(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "weaponEmp" to percent(tuning.valueFor(StellarMrmDifficulty.WEAPON_EMP, isPlayer)),
        "explosionDamage" to percent(tuning.valueFor(StellarMrmDifficulty.EXPLOSION_MULT, isPlayer)),
    )

    fun ice(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "amplification" to percent(tuning.valueFor(IceShardMirvDifficulty.AMP, isPlayer)),
    )
}
