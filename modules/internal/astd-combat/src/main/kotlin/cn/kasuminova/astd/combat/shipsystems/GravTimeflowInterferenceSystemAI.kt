package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.ENGAGE_RANGE_FRAC
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.SCAN_INTERVAL_SEC
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

/**
 * 引力时流干涉器系统 AI（purple/10-unique.md §1；决明级 ZW-001）。
 *
 * 决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（未激活、冷却完毕、canBeActivated）；
 * 2. 有效射程（[GravTimeflowTuning.BASE_RANGE] 经 systemRangeBonus 折算）× [ENGAGE_RANGE_FRAC]
 *    内存在可干涉的友军舰船（非自身、非战机/无人机、存活非残骸）；
 * 3. 按设计案优先级对候选评分（纯函数 [scoreCandidate]，输入快照 [CandidateSnapshot]），
 *    取最高分者施放：
 *    - 规格较大或部署点较高优先（[tierBaseDp] 与部署点 1:1 相加，基准 5/10/20/40 对应
 *      护卫/驱逐/巡洋/主力）；
 *    - 辐能水平较高（≥ [DANGER_FLUX_LEVEL]）且处于危险境地（NEEDS_HELP 旗标）加权；
 *    - 玩家旗舰、战斗类、自动分（fleetPoints）较高加权；
 *    - 处于进攻意愿（持有敌对 shipTarget）加权；
 *    - 纯航母/后勤类舰船乘以惩罚系数降权。
 *
 * 施放时把目标写入 [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM]
 * （原版熵放大器等目标锁定系统的同款约定，时长覆盖 chargeUp 0.5s 窗口），
 * 随后 useSystem()；stats 的 findTarget 读取该旗标完成锁定。
 */
class GravTimeflowInterferenceSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.4f

        /** 交战距离占有效射程的比例：留出余量覆盖 stats 侧的双舰碰撞半径和口径。 */
        private const val ENGAGE_RANGE_FRAC = 0.95f

        /** TARGET_FOR_SHIP_SYSTEM 旗标时长（s）：须覆盖 chargeUp 0.5s 窗口。 */
        private const val TARGET_FLAG_DURATION = 1.5f

        /** 危险境地加权的辐能水平门槛（≥ 85%）。 */
        const val DANGER_FLUX_LEVEL = 0.85f

        /** 各项评分权重。 */
        const val DANGER_BONUS = 30f
        const val FLAGSHIP_BONUS = 20f
        const val COMBAT_BONUS = 10f
        const val AUTO_SCORE_WEIGHT = 0.5f
        const val OFFENSE_BONUS = 15f

        /** 纯航母/后勤类舰船的评分惩罚乘区。 */
        const val CARRIER_LOGISTICS_PENALTY = 0.3f

        /** 评分输入快照（纯数据，AI 从 ShipAPI 提取；评分纯函数只读快照，可单测）。 */
        data class CandidateSnapshot(
            /** 舰级（护卫舰/驱逐舰/巡洋舰/主力舰；其余按护卫舰档）。 */
            val hullSize: ShipAPI.HullSize?,
            /** 部署点（suppliesToRecover）。 */
            val deployPoints: Float,
            /** 自动战斗分（fleetPoints）。 */
            val autoScore: Float,
            /** 当前辐能水平（0-1）。 */
            val fluxLevel: Float,
            /** 处于危险境地（NEEDS_HELP 旗标）。 */
            val inDanger: Boolean,
            /** 玩家旗舰。 */
            val isPlayerFlagship: Boolean,
            /** 战斗类舰船（hints COMBAT）。 */
            val isCombat: Boolean,
            /** 处于进攻意愿（持有敌对 shipTarget）。 */
            val offensiveIntent: Boolean,
            /** 纯航母（CARRIER 且无 COMBAT）/后勤类舰船。 */
            val carrierOrLogistics: Boolean,
        )

        /** 舰级部署点基准（纯函数）：5/10/20/40 对应 护卫/驱逐/巡洋/主力；其余非舰船级按护卫舰档。 */
        fun tierBaseDp(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
            ShipAPI.HullSize.DESTROYER -> 10f
            ShipAPI.HullSize.CRUISER -> 20f
            ShipAPI.HullSize.CAPITAL_SHIP -> 40f
            else -> 5f
        }

        /** 规格分（纯函数）：舰级基准与部署点 1:1 相加（规格较大或部署点较高优先）。 */
        fun sizeScore(hullSize: ShipAPI.HullSize?, deployPoints: Float): Float =
            tierBaseDp(hullSize) + deployPoints

        /** 危险境地加权（纯函数）：辐能 ≥ [DANGER_FLUX_LEVEL] 且处于危险境地。 */
        fun dangerBonus(fluxLevel: Float, inDanger: Boolean): Float =
            if (inDanger && fluxLevel >= DANGER_FLUX_LEVEL) DANGER_BONUS else 0f

        /** 角色加权（纯函数）：玩家旗舰 + 战斗类 + 自动分折算。 */
        fun roleBonus(isPlayerFlagship: Boolean, isCombat: Boolean, autoScore: Float): Float =
            (if (isPlayerFlagship) FLAGSHIP_BONUS else 0f) +
                    (if (isCombat) COMBAT_BONUS else 0f) +
                    autoScore * AUTO_SCORE_WEIGHT

        /** 进攻意愿加权（纯函数）。 */
        fun offenseBonus(offensiveIntent: Boolean): Float =
            if (offensiveIntent) OFFENSE_BONUS else 0f

        /** 降权乘区（纯函数）：纯航母/后勤类舰船降低优先级。 */
        fun rolePenaltyMult(carrierOrLogistics: Boolean): Float =
            if (carrierOrLogistics) CARRIER_LOGISTICS_PENALTY else 1f

        /** 候选总评分（纯函数）：各项加权之和乘以角色惩罚乘区。 */
        fun scoreCandidate(c: CandidateSnapshot): Float =
            (sizeScore(c.hullSize, c.deployPoints) +
                    dangerBonus(c.fluxLevel, c.inDanger) +
                    roleBonus(c.isPlayerFlagship, c.isCombat, c.autoScore) +
                    offenseBonus(c.offensiveIntent)) * rolePenaltyMult(c.carrierOrLogistics)

        /** 选靶（纯函数）：返回最高评分候选的下标；空列表返回 null。 */
        fun selectBest(candidates: List<CandidateSnapshot>): Int? {
            if (candidates.isEmpty()) return null
            var bestIdx = 0
            var bestScore = scoreCandidate(candidates[0])
            for (i in 1 until candidates.size) {
                val score = scoreCandidate(candidates[i])
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            return bestIdx
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

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravTimeflowTuning.BASE_RANGE)
        val engageRange = range * ENGAGE_RANGE_FRAC

        val candidates = ArrayList<ShipAPI>()
        val snapshots = ArrayList<CandidateSnapshot>()
        for (candidate in engine.ships) {
            if (!isValidAlly(ship, candidate)) continue
            val dist = Misc.getDistance(ship.location, candidate.location)
            if (dist > engageRange) continue
            candidates += candidate
            snapshots += snapshotOf(engine, candidate)
        }

        val bestIdx = selectBest(snapshots) ?: return
        val victim = candidates[bestIdx]

        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, TARGET_FLAG_DURATION, victim)
        ship.useSystem()
    }

    /** 可干涉的友军舰船：owner 相同、非自身、非战机/无人机、存活非残骸。 */
    private fun isValidAlly(ship: ShipAPI, candidate: ShipAPI): Boolean =
        candidate !== ship && candidate.owner == ship.owner &&
                !candidate.isFighter && !candidate.isDrone && !candidate.isHulk && candidate.isAlive

    /** 从 ShipAPI 提取评分快照（评分逻辑见 [scoreCandidate] 纯函数）。 */
    private fun snapshotOf(engine: CombatEngineAPI, candidate: ShipAPI): CandidateSnapshot {
        val hullSpec = candidate.hullSpec
        val hints = hullSpec.hints
        val isCombat = hints.contains(ShipHullSpecAPI.ShipTypeHints.COMBAT)
        val isCarrier = hullSpec.isCarrier
        val isLogistics = hints.contains(ShipHullSpecAPI.ShipTypeHints.CIVILIAN) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.FREIGHTER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.TANKER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.LINER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.TRANSPORT)
        val hostileTarget = candidate.shipTarget
        return CandidateSnapshot(
            hullSize = candidate.hullSize,
            deployPoints = hullSpec.suppliesToRecover,
            autoScore = hullSpec.fleetPoints.toFloat(),
            fluxLevel = candidate.fluxTracker?.fluxLevel ?: 0f,
            inDanger = candidate.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.NEEDS_HELP),
            isPlayerFlagship = candidate === engine.playerShip,
            isCombat = isCombat,
            offensiveIntent = hostileTarget != null && hostileTarget.owner != candidate.owner && hostileTarget.isAlive,
            carrierOrLogistics = (isCarrier && !isCombat) || isLogistics,
        )
    }
}
