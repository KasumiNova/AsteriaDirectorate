package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemAI.Companion.ENGAGE_SCAN_RANGE
import cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemAI.Companion.MIN_DEPLOYED_FIGHTERS
import cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemAI.Companion.SCAN_INTERVAL_SEC
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

class FighterGravLinkSystemAI : ShipSystemAIScript {

    companion object {
        private const val MIN_DEPLOYED_FIGHTERS = 4

        private const val ENGAGE_SCAN_RANGE = 3000f

        private const val SCAN_INTERVAL_SEC = 0.5f
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
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        // canBeActivated() 不判激活状态与冷却，需脚本显式守卫
        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return

        if (countDeployedFighters(ship) < MIN_DEPLOYED_FIGHTERS) return
        if (!hasEnemyInRange(engine, ship)) return

        ship.useSystem()
    }

    private fun countDeployedFighters(ship: ShipAPI): Int =
        ship.allWings.sumOf { wing -> wing.wingMembers.count { !it.isHulk } }

    private fun hasEnemyInRange(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (candidate in engine.ships) {
            if (candidate.owner == ship.owner) continue
            if (candidate.isHulk) continue
            if (!engine.isEntityInPlay(candidate)) continue
            if (MathUtils.getDistance(ship.location, candidate.location) <= ENGAGE_SCAN_RANGE) return true
        }
        return false
    }
}
