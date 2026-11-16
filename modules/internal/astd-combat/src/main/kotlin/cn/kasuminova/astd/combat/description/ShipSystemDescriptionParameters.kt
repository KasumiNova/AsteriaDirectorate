package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.combat.lens.system.FighterGravLinkTuning
import cn.kasuminova.astd.combat.lens.system.GravReplicatorTuning
import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.combat.shipsystems.FluxLinkNetworkTuning
import cn.kasuminova.astd.combat.shipsystems.GressionChargeTuning
import cn.kasuminova.astd.combat.shipsystems.SuppressionModeTuning

internal object ShipSystemDescriptionParameters {
    val ids: Set<String> = linkedSetOf(
        "astd_arc_shared_flux_network",
        "astd_suppression_mode",
        "astd_gression_charge",
        "astd_fighter_grav_link",
        "astd_grav_rift_generator",
        "astd_grav_storm",
        "astd_grav_timeflow_interference",
        "astd_grav_replicator",
    )

    fun resolve(id: String, tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = when (id) {
        "astd_arc_shared_flux_network" -> fluxNetwork(tuning, isPlayer)
        "astd_suppression_mode" -> suppression(tuning, isPlayer)
        "astd_gression_charge" -> gression(tuning, isPlayer)
        "astd_fighter_grav_link" -> fighterLink(tuning, isPlayer)
        "astd_grav_rift_generator" -> gravityRift(tuning, isPlayer)
        "astd_grav_storm" -> gravStorm(tuning, isPlayer)
        "astd_grav_timeflow_interference" -> timeflow(tuning, isPlayer)
        "astd_grav_replicator" -> replicator(tuning, isPlayer)
        else -> error("Unknown dynamic ship system: $id")
    }

    private fun fluxNetwork(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = FluxLinkNetworkTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "dissipationBonus" to DescriptionNumbers.percent(values.dissipationBonus),
            "hardDissipationPoints" to DescriptionNumbers.percent(values.hardDissipationBonus),
            "transferRate" to DescriptionNumbers.percent(values.transferRate),
            "hardTransferRate" to DescriptionNumbers.percent(values.hardTransferMult),
            "range" to DescriptionNumbers.distance(values.range),
            "minEfficiencyRange" to DescriptionNumbers.distance(values.range * FluxLinkNetworkTuning.MIN_EFFICIENCY_RANGE_FRACTION),
            "fullEfficiencyRange" to DescriptionNumbers.distance(values.range * FluxLinkNetworkTuning.FULL_EFFICIENCY_RANGE_FRACTION),
        )
    }

    private fun suppression(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = SuppressionModeTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "speedPenalty" to DescriptionNumbers.percent(1f - values.speedManeuverMult),
            "weaponFluxReduction" to DescriptionNumbers.percent(1f - values.weaponFluxMult),
            "shieldReduction" to DescriptionNumbers.percent(1f - values.shieldDamageTakenMult),
            "rangeBonus" to DescriptionNumbers.percent(values.rangeBonusPercent),
        )
    }

    private fun gression(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "speedBonus" to DescriptionNumbers.percentPoints(GressionChargeTuning.speedPercent(tuning, isPlayer)),
        "damageReduction" to DescriptionNumbers.percent(1f - GressionChargeTuning.damageTakenMult(tuning, isPlayer)),
        "massMult" to DescriptionNumbers.percent(tuning.valueFor(GressionChargeTuning.MASS_BONUS, isPlayer)),
        "coneArc" to DescriptionNumbers.number(tuning.valueFor(GressionChargeTuning.CONE_ARC_DEG, isPlayer)),
        "coneRange" to DescriptionNumbers.distance(GressionChargeTuning.coneRange(tuning, isPlayer)),
        "coneDamage" to DescriptionNumbers.number(GressionChargeTuning.coneDamage(tuning, isPlayer)),
    )

    private fun fighterLink(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = FighterGravLinkTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "timeMult" to DescriptionNumbers.percent(values.timeMult - 1f),
            "damageReduction" to DescriptionNumbers.percent(1f - values.damageTakenMult),
        )
    }

    private fun gravityRift(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = GravityRiftTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "damageReduction" to DescriptionNumbers.percent(values.damageTakenReduction),
            "riftDamageMin" to DescriptionNumbers.number(values.damageMin),
            "riftDamageMax" to DescriptionNumbers.number(values.damageMax),
        )
    }

    private fun gravStorm(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = GravStormTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "damageReduction" to DescriptionNumbers.percent(values.damageTakenReduction),
        )
    }

    private fun timeflow(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = GravTimeflowTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "timeMult" to DescriptionNumbers.percent(values.timeMult),
            "cooldownReduction" to DescriptionNumbers.percent(values.cooldownReduction),
        )
    }

    private fun replicator(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val values = GravReplicatorTuning.resolve(tuning, isPlayer)
        return linkedMapOf(
            "damageRatio" to DescriptionNumbers.percent(values.damageRatio),
            "fluxRatio" to DescriptionNumbers.percent(values.fluxRatio),
        )
    }

}
