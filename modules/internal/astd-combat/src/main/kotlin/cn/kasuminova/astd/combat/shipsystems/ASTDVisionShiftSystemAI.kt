package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils

/**
 * 视界变速系统 AI：对当前集火目标（或射程内最高威胁敌舰）施放时流压制。
 *
 * 口径：长窗口（10s）长冷却（15s）的决斗向系统，不在琐碎目标上浪费——
 * 仅当锁定目标为存活非战机敌舰且处于本舰武器射程 1.2 倍以内时激活；
 * 无锁定目标时选取射程内部署点最高的敌舰（压制高价值单位收益最大）。
 * 施放前 setShipTarget 定格目标，供 stats 脚本锁定读取。
 */
class ASTDVisionShiftSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.5f
        private const val ACTIVATE_RANGE_MULT = 1.2f
        private const val FALLBACK_SCAN_RANGE = 2000f
        private const val HIGH_FLUX = 0.85f
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

    override fun advance(amount: Float, missileDangerDir: org.lwjgl.util.vector.Vector2f?, collisionDangerDir: org.lwjgl.util.vector.Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val fluxTracker = ship.fluxTracker
        if (fluxTracker != null && (fluxTracker.isOverloadedOrVenting || fluxTracker.fluxLevel >= HIGH_FLUX)) return

        val range = longestWeaponRange(ship) * ACTIVATE_RANGE_MULT
        var chosen = validTarget(ship, engine, ship.shipTarget)
        if (chosen == null || MathUtils.getDistance(ship.location, chosen.location) > range) {
            chosen = engine.ships
                .asSequence()
                .filter { validTarget(ship, engine, it) != null }
                .filter { MathUtils.getDistance(ship.location, it.location) <= FALLBACK_SCAN_RANGE }
                .maxByOrNull { it.fleetMember?.deploymentPointsCost ?: 0f }
        }
        chosen ?: return

        ship.setShipTarget(chosen)
        ship.useSystem()
    }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || !target.isAlive) return null
        if (target.owner == ship.owner || target.isFighter || target.isDrone) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }

    private fun longestWeaponRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 600f
}
