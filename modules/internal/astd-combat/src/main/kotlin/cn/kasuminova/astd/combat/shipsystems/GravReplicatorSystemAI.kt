package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil

class GravReplicatorSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f

        private const val FIRE_MEMORY_SEC = 0.5f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    private var fireMemoryRemaining = 0f

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
        fireMemoryRemaining = (fireMemoryRemaining - amount).coerceAtLeast(0f)
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (hasFiringEnergyWeapon(ship)) {
            fireMemoryRemaining = FIRE_MEMORY_SEC
        }
        if (fireMemoryRemaining <= 0f) return
        ship.useSystem()
    }

    // 充能判定取 chargeLevel > 0：原版 ChargeFireTracker 在 IDLE 恒为 0，充能/激活/降充阶段 > 0
    private fun hasFiringEnergyWeapon(ship: ShipAPI): Boolean =
        ship.allWeapons.any { weapon ->
            !weapon.isDecorative &&
                    weapon.slot?.isSystemSlot == false &&
                    weapon.type == WeaponAPI.WeaponType.ENERGY &&
                    !weapon.isBeam &&
                    !weapon.isDisabled &&
                    (!weapon.usesAmmo() || weapon.ammo > 0) &&
                    (weapon.isFiring || weapon.chargeLevel > 0f)
        }
}
