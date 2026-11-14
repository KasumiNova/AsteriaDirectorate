package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemAI.Companion.SCAN_INTERVAL_SEC
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

class GravityRiftSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.1f

        private const val TARGET_FLAG_DURATION = 1.5f
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

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (system.state != ShipSystemAPI.SystemState.IDLE) return
        if (ship.isPhased) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val victim = pickTarget(engine, ship, range) ?: return

        // 引擎处理 useSystem 指令时，isUsable 通过此旗标读取施放目标。
        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, TARGET_FLAG_DURATION, victim)
        if (system.canBeActivated()) ship.useSystem()
    }

    private fun pickTarget(engine: CombatEngineAPI, ship: ShipAPI, engageRange: Float): ShipAPI? {
        val current = ship.shipTarget
        if (current != null && isValidVictim(ship, current) &&
            Misc.getDistance(ship.location, current.location) <= engageRange + ship.collisionRadius + current.collisionRadius
        ) {
            return current
        }

        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in engine.ships) {
            if (!isValidVictim(ship, candidate)) continue
            val dist = Misc.getDistance(ship.location, candidate.location)
            if (dist <= engageRange + ship.collisionRadius + candidate.collisionRadius && dist < bestDist) {
                best = candidate
                bestDist = dist
            }
        }
        return best
    }

    private fun isValidVictim(ship: ShipAPI, candidate: ShipAPI): Boolean =
        candidate !== ship && candidate.owner != ship.owner && !candidate.isFighter &&
                !candidate.isHulk && !candidate.isPhased && candidate.isAlive
}
