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
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 引力裂隙发生器系统 AI（purple/20-production.md §2；茑萝级 ZW-103）。
 *
 * 定点打击系统的决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（未激活、冷却完毕、canBeActivated）；
 * 2. 本舰未处于相位态（相位中无法瞄准打击，且裂隙对相位目标无效）；
 * 3. 存在有效目标：优先当前 shipTarget（存活非残骸非相位），否则在有效射程
 *    （[GravityRiftTuning.SYSTEM_RANGE] 经 systemRangeBonus 折算）× [ENGAGE_RANGE_FRAC]
 *    内扫描最近敌舰（不含战机——对战机群定点布雷得不偿失）；
 * 4. 目标距离 ≤ 有效射程 × [ENGAGE_RANGE_FRAC]（近距施放裂隙数量才够多，
 *    远距单裂隙性价比过低）。
 *
 * 施放时按目标速度做 [LEAD_TIME_SEC] 预判，把预判点写入
 * [ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS]（原版定点系统同款约定，时长覆盖
 * chargeUp 1s 蓄能窗口），随后 useSystem()。
 */
class GravityRiftSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.4f

        /** 交战距离占有效射程的比例：越近裂隙越多，远于该比例不放。 */
        private const val ENGAGE_RANGE_FRAC = 0.95f

        /** 落点预判时长（s）：按目标当前速度外推。 */
        private const val LEAD_TIME_SEC = 0.5f

        /** SYSTEM_TARGET_COORDS 旗标时长（s）：须覆盖 chargeUp 1s 蓄能窗口。 */
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

        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return
        if (ship.isPhased) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val engageRange = range * ENGAGE_RANGE_FRAC
        val victim = pickTarget(engine, ship, engageRange) ?: return

        // 预判点 = 目标当前位置 + 速度 × 提前量，钳回有效射程内（与 stats 的限幅口径一致）。
        val aim = Vector2f.add(
            victim.location,
            Vector2f(victim.velocity).also { it.scale(LEAD_TIME_SEC) },
            null,
        )
        if (MathUtils.getDistance(ship.location, aim) > range) {
            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(ship.location, aim))
            dir.scale(range)
            Vector2f.add(ship.location, dir, aim)
        }

        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, TARGET_FLAG_DURATION, aim)
        ship.useSystem()
    }

    /** 目标选取：当前 shipTarget 有效则用之，否则扫描交战距离内最近非战机敌舰。 */
    private fun pickTarget(engine: CombatEngineAPI, ship: ShipAPI, engageRange: Float): ShipAPI? {
        val current = ship.shipTarget
        if (current != null && isValidVictim(ship, current) &&
            MathUtils.getDistance(ship.location, current.location) <= engageRange
        ) {
            return current
        }

        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in engine.ships) {
            if (candidate.isFighter) continue
            if (!isValidVictim(ship, candidate)) continue
            val dist = MathUtils.getDistance(ship.location, candidate.location)
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
