package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.combat.description.DescriptionNumbers.number
import cn.kasuminova.astd.combat.description.DescriptionNumbers.percent
import cn.kasuminova.astd.combat.description.DescriptionNumbers.percentPoints
import cn.kasuminova.astd.combat.description.DescriptionNumbers.signedPercent
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning
import cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoDifficulty
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseTuning
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceDifficulty
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsDifficulty
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning

internal object WeaponArcParameters {
    fun echo(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "vulnerability" to percent(tuning.valueFor(StarfallEchoTuning.RESONANCE_VULN_PER_STACK, isPlayer)),
    )

    fun wing(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> {
        val damage = tuning.valueFor(StarfallWingTuning.DAMAGE_FACTOR, isPlayer)
        return linkedMapOf(
            "blueDamage" to percent(StarfallWingTuning.ShuttleType.BLUE.damageFactor * damage),
            "blueEmp" to percent(2f * damage),
            "redDamage" to percent(StarfallWingTuning.ShuttleType.RED.damageFactor * damage),
            "redHullDamage" to percent(tuning.valueFor(StarfallWingTuning.RED_HULL_DAMAGE, isPlayer)),
            "goldDamage" to percent(StarfallWingTuning.ShuttleType.GOLD.damageFactor * damage),
            "goldHardFlux" to percent(tuning.valueFor(StarfallWingTuning.GOLD_HARD_FLUX, isPlayer)),
            "purpleDamage" to percent(StarfallWingTuning.ShuttleType.PURPLE.damageFactor * damage),
            "powerMin" to signedPercent(tuning.valueFor(StarfallWingTuning.POWER_MIN, isPlayer) - 1f),
            "powerMax" to signedPercent(tuning.valueFor(StarfallWingTuning.POWER_MAX, isPlayer) - 1f),
            "chargeConversion" to percent(tuning.valueFor(StarfallWingTuning.CHARGE_CONVERSION, isPlayer)),
            "hitGain" to percent(tuning.valueFor(StarfallWingTuning.HIT_GAIN, isPlayer) / ImaginaryWingsTuning.CHARGE_MAX),
        )
    }

    fun needle(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "upkeepPerStack" to percent(tuning.valueFor(ChargeNeedleTuning.PER_STACK, isPlayer)),
        "dischargeChance" to percent(tuning.valueFor(ChargeNeedleTuning.DISCHARGE_CHANCE, isPlayer)),
        "dischargeEmp" to percent(tuning.valueFor(ChargeNeedleTuning.DISCHARGE_EMP_MULT, isPlayer)),
    )

    fun accelerator(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "extraDamageMax" to percentPoints(tuning.valueFor(ElectricDriveAcceleratorDifficulty.CHARGE_MAX_PCT, isPlayer)),
        "rangeBonus" to number(tuning.valueFor(ElectricDriveAcceleratorDifficulty.RANGE_BONUS_SU, isPlayer)),
    )

    fun qiongjue(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "perStack" to percent(tuning.valueFor(QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS, isPlayer)),
    )

    fun positron(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "damageMult" to percent(tuning.valueFor(PositronShockwaveDifficulty.DAMAGE_MULT, isPlayer)),
        "densityBonus" to percent(tuning.valueFor(PositronShockwaveDifficulty.DENSITY_BONUS, isPlayer)),
    )

    fun sevenStars(
        @Suppress("UNUSED_PARAMETER") tuning: DifficultyTuning,
        @Suppress("UNUSED_PARAMETER") isPlayer: Boolean,
    ): Map<String, String> = linkedMapOf(
        "maxJumps" to number(SevenStarsDifficulty.MAX_JUMPS.toFloat()),
    )

    fun gemini(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "empTotal" to percent(tuning.valueFor(GeminiDemDifficulty.EMP_TOTAL_FRACTION, isPlayer)),
        "syncBonus" to percent(tuning.valueFor(GeminiDemDifficulty.SYNC_DAMAGE_BONUS, isPlayer)),
    )

    fun ion(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "empShift" to percent(tuning.valueFor(HeavyIonPulseTuning.EMP_RESIST_PER_STACK, isPlayer)),
        "dischargeChance" to percent(tuning.valueFor(HeavyIonPulseTuning.DISCHARGE_CHANCE, isPlayer)),
        "dischargeEmp" to percent(tuning.valueFor(HeavyIonPulseTuning.DISCHARGE_EMP_MULT, isPlayer)),
    )

    fun lance(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "damageMult" to percent(tuning.valueFor(PiercingLanceDifficulty.CONE_DAMAGE, isPlayer)),
    )

    fun cuifeng(tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> = linkedMapOf(
        "hardFlux" to percent(tuning.valueFor(CuifengTorpedoDifficulty.HARD_FLUX_Y, isPlayer)),
    )
}
