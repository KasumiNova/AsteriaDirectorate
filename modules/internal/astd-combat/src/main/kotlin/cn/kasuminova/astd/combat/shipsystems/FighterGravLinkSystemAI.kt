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

/**
 * 战机引力联结器系统 AI（purple/20-production.md §1；2026-09 二轮：RECALL_DEVICE → CUSTOM）。
 *
 * 原 aiType=RECALL_DEVICE 的决策口径是「需整备战机占比」，与召回语义匹配，但本系统的
 * 主要价值是**激活期机群强化**，召回只是收尾动作——按整备占比决策会导致 AI 几乎不主动
 * 激活。故改 CUSTOM 脚本，按强化收益决策：
 *
 * 施放时机（全部满足，每 [SCAN_INTERVAL_SEC] 评估一次）：
 * 1. 系统空闲（未激活含 IN/ACTIVE/OUT、冷却完毕）；[ShipSystemAPI.canBeActivated] 仅判
 *    原版 requiresZeroFluxBoost 约束（本系统未启用，恒 true），状态/冷却由脚本显式守卫；
 * 2. 在外存活战机 ≥ [MIN_DEPLOYED_FIGHTERS]（强化对象足够多才值得开）；
 * 3. [ENGAGE_SCAN_RANGE] 内存在敌对舰船（机群有交战对象，避免和平巡航期空开）。
 *
 * 不主动取消：15s 上限由 CSV active=15s 引擎自动收口（系统条同步显示剩余时间），
 * 机群全灭提前结束由 [FighterGravLinkSystemStats] 收口，AI 无需重复决策。
 */
class FighterGravLinkSystemAI : ShipSystemAIScript {

    companion object {
        /** 值得激活的最小在外存活战机数（约一个满编基础联队的规模）。 */
        private const val MIN_DEPLOYED_FIGHTERS = 4

        /** 交战威胁扫描半径（su）：与舰载机典型交战范围同量级。 */
        private const val ENGAGE_SCAN_RANGE = 3000f

        /** 评估间隔（s）。 */
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

        // 系统激活中（含 IN/ACTIVE/OUT）或冷却未完毕：不重复触发；取消由 stats 脚本收口。
        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return

        if (countDeployedFighters(ship) < MIN_DEPLOYED_FIGHTERS) return
        if (!hasEnemyInRange(engine, ship)) return

        ship.useSystem()
    }

    /** 在外存活战机总数（全部联队合计）。 */
    private fun countDeployedFighters(ship: ShipAPI): Int =
        ship.allWings.sumOf { wing -> wing.wingMembers.count { !it.isHulk } }

    /** [ENGAGE_SCAN_RANGE] 内是否存在敌对舰船（含战机：机群交战对象常以战机群形式出现）。 */
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
