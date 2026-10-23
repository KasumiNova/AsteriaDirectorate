package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class ShipDataEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.SHIP_DATA

    abstract val name: String

    abstract val id: String

    open val designation: String = ""

    open val tech: String = ""

    open val systemId: String = ""

    open val fleetPts: Int = 0

    open val hitpoints: Int = 0

    open val armorRating: Int = 0

    open val maxFlux: Int = 0

    open val fluxCapacityPercent_8654: String = ""

    open val fluxDissipation: Int = 0

    open val ordnancePoints: Int = 0

    open val fighterBays: Int = 0

    open val maxSpeed: Int = 0

    open val acceleration: Int = 0

    open val deceleration: Int = 0

    open val maxTurnRate: Int = 0

    open val turnAcceleration: Int = 0

    open val mass: Int = 0

    open val shieldType: String = ""

    open val defenseId: String = ""

    open val shieldArc: Int = 0

    open val shieldUpkeep: Double = 0.0

    open val shieldEfficiency: Double = 0.0

    open val phaseCost: Double? = null

    open val phaseUpkeep: Double? = null

    open val minCrew: Int = 0

    open val maxCrew: Int = 0

    open val cargo: Int = 0

    open val fuel: Int = 0

    open val fuelPerLy: Int = 0

    open val range: Int = 0

    open val maxBurn: Int = 0

    open val baseValue: Int = 0

    open val crPercentPerDay: Double = 0.0

    open val crToDeploy: Double = 0.0

    open val peakCrSec: Int = 0

    open val crLossPerSec: Double = 0.0

    open val suppliesRec: Int = 0

    open val suppliesPerMonth: Int = 0

    open val cPerS: Int = 0

    open val cPerF: Int = 0

    open val fPerS: Int = 0

    open val fPerF: Int = 0

    open val crewPerS: Int = 0

    open val crewPerF: Int = 0

    open val hints: String = ""

    open val tags: String = ""

    open val logisticsNaReason: String = ""

    open val codexVariantId: String = ""

    open val rarity: Int = 1

    open val breakProb: Double = 0.0

    open val minPieces: Int = 1

    open val maxPieces: Int = 1

    open val travelDrive: String = ""

    open val number: Int = 0

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "designation" to designation,
        "tech/manufacturer" to tech,
        "system id" to systemId,
        "fleet pts" to fleetPts,
        "hitpoints" to hitpoints,
        "armor rating" to armorRating,
        "max flux" to maxFlux,
        "8/6/5/4%" to fluxCapacityPercent_8654,
        "flux dissipation" to fluxDissipation,
        "ordnance points" to ordnancePoints,
        "fighter bays" to fighterBays,
        "max speed" to maxSpeed,
        "acceleration" to acceleration,
        "deceleration" to deceleration,
        "max turn rate" to maxTurnRate,
        "turn acceleration" to turnAcceleration,
        "mass" to mass,
        "shield type" to shieldType,
        "defense id" to defenseId,
        "shield arc" to shieldArc,
        "shield upkeep" to shieldUpkeep,
        "shield efficiency" to shieldEfficiency,
        "phase cost" to phaseCost,
        "phase upkeep" to phaseUpkeep,
        "min crew" to minCrew,
        "max crew" to maxCrew,
        "cargo" to cargo,
        "fuel" to fuel,
        "fuel/ly" to fuelPerLy,
        "range" to range,
        "max burn" to maxBurn,
        "base value" to baseValue,
        "cr %/day" to crPercentPerDay,
        "CR to deploy" to crToDeploy,
        "peak CR sec" to peakCrSec,
        "CR loss/sec" to crLossPerSec,
        "supplies/rec" to suppliesRec,
        "supplies/mo" to suppliesPerMonth,
        "c/s" to cPerS,
        "c/f" to cPerF,
        "f/s" to fPerS,
        "f/f" to fPerF,
        "crew/s" to crewPerS,
        "crew/f" to crewPerF,
        "hints" to hints,
        "tags" to tags,
        "logistics n/a reason" to logisticsNaReason,
        "codex variant id" to codexVariantId,
        "rarity" to rarity,
        "breakProb" to breakProb,
        "minPieces" to minPieces,
        "maxPieces" to maxPieces,
        "travel drive" to travelDrive,
        "number" to number,
    )
}
