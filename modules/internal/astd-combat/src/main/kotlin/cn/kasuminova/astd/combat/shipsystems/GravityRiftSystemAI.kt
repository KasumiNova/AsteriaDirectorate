package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

/**
 * 引力裂隙发生器系统 AI（purple/20-production.md §2；茑萝级 ZW-103；
 * 2026-09-20 二轮重做：目标锁定制）。
 *
 * 目标打击系统的决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（未激活、冷却完毕、canBeActivated）；
 * 2. 本舰未处于相位态（相位中无法瞄准打击，且裂隙对相位目标无效）；
 * 3. 存在有效目标：优先当前 shipTarget（存活非残骸非相位），否则在有效射程
 *    （[GravityRiftTuning.SYSTEM_RANGE] 经 systemRangeBonus 折算）× [ENGAGE_RANGE_FRAC]
 *    内扫描最近敌舰（不含战机——对战机群裂隙打击得不偿失）；
 * 4. 目标距离 ≤ 有效射程 × [ENGAGE_RANGE_FRAC]（与 stats 的可用性口径一致：
 *    stats 侧另计双舰碰撞半径和，AI 侧留 [ENGAGE_RANGE_FRAC] 余量覆盖）。
 *
 * 施放时把目标写入 [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM]
 * （原版熵放大器等目标锁定系统的同款约定，时长覆盖 chargeUp 1s 蓄能窗口），
 * 随后 useSystem()；stats 的 findTarget 读取该旗标完成锁定。
 */
class GravityRiftSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.4f

        /** 交战距离占有效射程的比例：留出余量覆盖 stats 侧的双舰碰撞半径和口径。 */
        private const val ENGAGE_RANGE_FRAC = 0.95f

        /** TARGET_FOR_SHIP_SYSTEM 旗标时长（s）：须覆盖 chargeUp 1s 蓄能窗口。 */
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

        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return
        if (ship.isPhased) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val engageRange = range * ENGAGE_RANGE_FRAC
        val victim = pickTarget(engine, ship, engageRange) ?: return

        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, TARGET_FLAG_DURATION, victim)
        ship.useSystem()
    }

    /** 目标选取：当前 shipTarget 有效则用之，否则扫描交战距离内最近非战机敌舰。 */
    private fun pickTarget(engine: CombatEngineAPI, ship: ShipAPI, engageRange: Float): ShipAPI? {
        val current = ship.shipTarget
        if (current != null && isValidVictim(ship, current) &&
            Misc.getDistance(ship.location, current.location) <= engageRange
        ) {
            return current
        }

        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in engine.ships) {
            if (candidate.isFighter) continue
            if (!isValidVictim(ship, candidate)) continue
            val dist = Misc.getDistance(ship.location, candidate.location)
            if (dist <= engageRange && dist < bestDist) {
                best = candidate
                bestDist = dist
            }
        }
        return best
    }

    private fun isValidVictim(ship: ShipAPI, candidate: ShipAPI): Boolean =
        candidate.owner != ship.owner && !candidate.isHulk && !candidate.isPhased && candidate.isAlive
}
