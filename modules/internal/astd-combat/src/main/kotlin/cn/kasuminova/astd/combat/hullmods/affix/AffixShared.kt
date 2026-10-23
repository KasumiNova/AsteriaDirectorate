package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

internal object AffixShared {

    val tuning: DifficultyTuning get() = DifficultyTuningImpl

    fun bySize(
        hullSize: ShipAPI.HullSize?,
        frigate: ScalingEntry,
        destroyer: ScalingEntry,
        cruiser: ScalingEntry,
        capital: ScalingEntry,
    ): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> capital
        ShipAPI.HullSize.CRUISER -> cruiser
        ShipAPI.HullSize.DESTROYER -> destroyer
        else -> frigate
    }

    fun aliveAllies(ship: ShipAPI): List<ShipAPI> {
        val engine = Global.getCombatEngine() ?: return emptyList()
        return engine.ships.filter {
            it !== ship && it.owner == ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
        }
    }
}
