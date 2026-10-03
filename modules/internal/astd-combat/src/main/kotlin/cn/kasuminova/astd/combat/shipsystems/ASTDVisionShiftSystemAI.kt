package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.VisionShiftTuning
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

/**
 * 视界变速系统 AI：对当前集火目标（或射程内最高威胁敌舰）施放时流压制。
 *
 * 口径：长窗口（14s）长冷却（15s）的决斗向系统，不在琐碎目标上浪费——
 * 仅当锁定目标为存活非战机敌舰且处于系统射程（[VisionShiftTuning.SYSTEM_RANGE]，
 * 受 systemRangeBonus 加成，与 stats 脚本激活门禁同口径）内时激活；
 * 无锁定目标时选取射程内部署点最高的敌舰（压制高价值单位收益最大）。
 * 施放前 setShipTarget 定格目标，供 stats 脚本锁定读取。
 */
class ASTDVisionShiftSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.5f
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

    override fun advance(
        amount: Float,
        missileDangerDir: org.lwjgl.util.vector.Vector2f?,
        collisionDangerDir: org.lwjgl.util.vector.Vector2f?,
        target: ShipAPI?
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val fluxTracker = ship.fluxTracker
        if (fluxTracker != null && (fluxTracker.isOverloadedOrVenting || fluxTracker.fluxLevel >= HIGH_FLUX)) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, VisionShiftTuning.SYSTEM_RANGE)
        var chosen = validTarget(ship, engine, ship.shipTarget)
        if (chosen == null || !withinRange(ship, chosen, range)) {
            chosen = engine.ships
                .asSequence()
                .mapNotNull { validTarget(ship, engine, it) }
                .filter { withinRange(ship, it, range) }
                .maxByOrNull { it.fleetMember?.deploymentPointsCost ?: 0f }
        }
        chosen ?: return

        ship.shipTarget = chosen
        ship.useSystem()
    }

    /** 射程判定：与 stats 脚本 [ASTDVisionShiftSystemStats] 的激活门禁同口径（碰撞半径和外推）。 */
    private fun withinRange(ship: ShipAPI, target: ShipAPI, range: Float): Boolean {
        val dist = Misc.getDistance(ship.location, target.location)
        return dist <= range + ship.collisionRadius + target.collisionRadius
    }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || !target.isAlive) return null
        if (target.owner == ship.owner || target.isFighter || target.isDrone) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }
}
