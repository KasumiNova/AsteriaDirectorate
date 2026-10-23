package cn.kasuminova.astd.combat.effect.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

object SicWeaponUpgradeFlags {

    const val ARMAMENT_UPGRADE_KEY = "astd_sic_armament_upgrade"

    fun isArmamentUpgradeActive(ship: ShipAPI?): Boolean =
        ship?.customData?.containsKey(ARMAMENT_UPGRADE_KEY) == true

    fun isArmamentUpgradeActiveForOwner(engine: CombatEngineAPI, owner: Int): Boolean =
        engine.ships.any { it.owner == owner && isArmamentUpgradeActive(it) }
}
