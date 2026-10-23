package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class ShipSystemEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.SHIP_SYSTEMS

    abstract val id: String

    abstract val name: String

    open val fluxPerSecond: Double = 0.0

    open val fluxPerSecondBaseRate: Double = 0.0

    open val fluxPerSecondBaseCap: Double = 0.0

    open val fluxUse: Double = 0.0

    open val fluxUseBaseRate: Double = 0.0

    open val fluxUseBaseCap: Double = 0.0

    open val maxUses: Int? = null

    open val regen: Double? = null

    open val chargeUp: Double = 0.5

    open val active: Double? = 1.0

    open val down: Double = 0.5

    open val cooldown: Double = 10.0

    open val toggle: Boolean = false

    open val noDissipation: Boolean = false

    open val noHardDissipation: Boolean = false

    open val hardFlux: Boolean = false

    open val noFiring: Boolean = false

    open val noTurning: Boolean = false

    open val noStrafing: Boolean = false

    open val noAccel: Boolean = false

    open val noShield: Boolean = false

    open val noVent: Boolean = false

    open val isPhaseCloak: Boolean = false

    open val tags: String = ""

    open val icon: String = "graphics/icons/hullsys/ammo_feeder.png"

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "flux/second" to fluxPerSecond,
        "f/s (base rate)" to fluxPerSecondBaseRate,
        "f/s (base cap)" to fluxPerSecondBaseCap,
        "flux/use" to fluxUse,
        "f/u (base rate)" to fluxUseBaseRate,
        "f/u (base cap)" to fluxUseBaseCap,
        "cr/u" to 0,
        "max uses" to (maxUses ?: ""),
        "regen" to (regen ?: ""),
        "charge up" to chargeUp,
        "active" to (active ?: ""),
        "down" to down,
        "cooldown" to cooldown,
        "toggle" to if (toggle) "TRUE" else "FALSE",
        "noDissipation" to if (noDissipation) "TRUE" else "FALSE",
        "noHardDissipation" to if (noHardDissipation) "TRUE" else "FALSE",
        "hardFlux" to if (hardFlux) "TRUE" else "FALSE",
        "noFiring" to if (noFiring) "TRUE" else "FALSE",
        "noTurning" to if (noTurning) "TRUE" else "FALSE",
        "noStrafing" to if (noStrafing) "TRUE" else "FALSE",
        "noAccel" to if (noAccel) "TRUE" else "FALSE",
        "noShield" to if (noShield) "TRUE" else "FALSE",
        "noVent" to if (noVent) "TRUE" else "FALSE",
        "isPhaseCloak" to if (isPhaseCloak) "TRUE" else "FALSE",
        "tags" to tags,
        "icon" to icon,
    )
}
