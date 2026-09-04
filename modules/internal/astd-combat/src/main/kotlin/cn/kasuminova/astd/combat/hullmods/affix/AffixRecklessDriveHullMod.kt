package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixRecklessDriveHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val speed = 0.10f + 0.15f * k
        val accel = 0.20f + 0.30f * k
        val armorTaken = 0.05f + 0.10f * k

        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speed)
        ship.mutableStats.acceleration.modifyMult(id, 1f + accel)
        ship.mutableStats.deceleration.modifyMult(id, 1f + accel)
        ship.mutableStats.turnAcceleration.modifyMult(id, 1f + accel)
        ship.mutableStats.maxTurnRate.modifyMult(id, 1f + (0.12f + 0.18f * k))

        ship.mutableStats.armorDamageTakenMult.modifyMult(id, 1f + armorTaken)
    }
}
