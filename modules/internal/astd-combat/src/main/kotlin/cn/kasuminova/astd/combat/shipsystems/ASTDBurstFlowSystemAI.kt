package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemAI.Companion.AMMO_LOW_RATIO
import cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemAI.Companion.ENGAGE_RANGE_MULT
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils

class ASTDBurstFlowSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f

        private const val ENGAGE_RANGE_MULT = 1.2f

        private const val AMMO_LOW_RATIO = 0.5f

        private const val TRAVEL_SPEED_RATIO = 0.5f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(
        amount: Float,
        missileDangerDir: org.lwjgl.util.vector.Vector2f?,
        collisionDangerDir: org.lwjgl.util.vector.Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val engaged = isEngaged(ship, engine)

        if (engaged && system.ammo > 1 && hasLowAmmoWeapon(ship)) {
            ship.useSystem()
            return
        }

        if (system.ammo > system.maxAmmo - 1 && (engaged || isTraveling(ship))) {
            ship.useSystem()
        }
    }

    private fun hasLowAmmoWeapon(ship: ShipAPI): Boolean =
        ship.allWeapons.any { weapon ->
            !weapon.isDecorative &&
                    weapon.slot?.isSystemSlot == false &&
                    weapon.type != WeaponAPI.WeaponType.MISSILE &&
                    weapon.usesAmmo() &&
                    weapon.maxAmmo > 0 &&
                    weapon.ammo.toFloat() / weapon.maxAmmo < AMMO_LOW_RATIO
        }

    private fun isEngaged(ship: ShipAPI, engine: CombatEngineAPI): Boolean {
        val range = longestNonMissileRange(ship) * ENGAGE_RANGE_MULT
        return engine.ships.any { other ->
            other !== ship &&
                    !other.isHulk &&
                    other.owner != ship.owner &&
                    other.owner != 100 &&
                    MathUtils.getDistance(ship.location, other.location) <= range
        }
    }

    private fun isTraveling(ship: ShipAPI): Boolean =
        ship.velocity.length() > ship.mutableStats.maxSpeed.modifiedValue * TRAVEL_SPEED_RATIO

    private fun longestNonMissileRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative && it.type != WeaponAPI.WeaponType.MISSILE }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 600f
}
