package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 引力磁暴发生器系统 AI（密蒙级 ZW-002；原版「量子干扰」增强基线）。
 *
 * 决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（未激活、冷却完毕、canBeActivated）；
 * 2. 本舰未相位（相位中不可释放，与 [GravStormSystemStats.isUsable] 同口径）、未过载/排气；
 * 3. 辐能余量：当前辐能水平 + 激活代价（[GravStormTuning.ACTIVATION_FLUX_FRACTION]）不超过
 *    [MAX_FLUX_LEVEL_AFTER_USE]（对齐原版量子干扰 AI 的 0.85 辐能闸）；
 * 4. 交战分：前方 60° 锥、有效射程 × [ENGAGE_RANGE_FRAC] 内的敌对舰船按舰级计分
 *    （护卫 1 / 驱逐 2 / 巡洋 3 / 主力 4，[threatScore]），总分 ≥ [ENGAGE_SCORE_THRESHOLD] 才施放
 *    （单艘护卫舰不值得一发 24s 冷却的磁暴）。
 *
 * AI 不主动提前结束充能：施放后充满 4s 自然释放（stats 侧 ACTIVE 首帧接管），
 * 相位打断/取消由 stats 侧统一处理。
 */
class GravStormSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.5f

        /** 交战距离占有效射程的比例（留出余量覆盖 stats 侧的目标碰撞半径口径）。 */
        private const val ENGAGE_RANGE_FRAC = 0.9f

        /** 施放后的辐能水平上限（对齐原版量子干扰 AI 的 0.85 口径）。 */
        private const val MAX_FLUX_LEVEL_AFTER_USE = 0.85f

        /** 交战分阈值：锥内敌舰舰级分总和达到该值才施放。 */
        private const val ENGAGE_SCORE_THRESHOLD = 3f

        /** 舰级交战分（纯函数，单测可驱动）。 */
        internal fun threatScore(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
            ShipAPI.HullSize.FRIGATE -> 1f
            ShipAPI.HullSize.DESTROYER -> 2f
            ShipAPI.HullSize.CRUISER -> 3f
            ShipAPI.HullSize.CAPITAL_SHIP -> 4f
            else -> 0f
        }
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

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return
        if (ship.isPhased) return
        if (ship.fluxTracker.isOverloadedOrVenting) return
        if (ship.fluxTracker.fluxLevel + GravStormTuning.ACTIVATION_FLUX_FRACTION > MAX_FLUX_LEVEL_AFTER_USE) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravStormTuning.BASE_RANGE) * ENGAGE_RANGE_FRAC
        var score = 0f
        for (candidate in engine.ships) {
            if (candidate == null || candidate === ship) continue
            if (candidate.owner == ship.owner || candidate.isFighter || candidate.isHulk || !candidate.isAlive) continue
            val candidateScore = threatScore(candidate.hullSize)
            if (candidateScore <= 0f) continue
            val dist = Misc.getDistance(ship.location, candidate.location) - candidate.collisionRadius
            if (dist > range) continue
            val angleDiff = Misc.getAngleDiff(ship.facing, Misc.getAngleInDegrees(ship.location, candidate.location))
            if (!GravStormTuning.isInCone(angleDiff)) continue
            score += candidateScore
            if (score >= ENGAGE_SCORE_THRESHOLD) {
                ship.useSystem()
                return
            }
        }
    }
}
