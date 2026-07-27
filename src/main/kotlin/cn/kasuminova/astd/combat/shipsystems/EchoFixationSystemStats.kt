package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.EchoFixationField
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 回声定影（Echo Fixation）舰船系统 —— stats 脚本基类（spec §2 / purple/10-unique.md §1）。
 *
 * 动机：引力透镜级双模式（载人 / 无人）共用同一套「定影 → 回放」机制（spec §2），机制本身
 * 在两模式间**无功能差异**——模式差异（情报中枢 / 蜂群链路）属于核心插件（spec §3.1），不属于
 * 本系统。本基类承载完整施放逻辑；[isAutomatedSystem] 仅用于状态栏台词分版（crewed / automated），
 * 与 ArcFlare 范式一致。
 *
 * 职责：系统进入激活瞬间（IN 首帧），在落点建一个定影场（[EchoFixationField.spawn]）。
 * 定影 → 回放 → 认知撕裂的状态机、难度系数 m、承伤 debuff 计时全部由 [EchoFixationField] 内部
 * 推进插件负责；本脚本只负责「在哪建场」与「以多大射程倍率建场」。
 *
 * 落点选择（spec §2.1：鼠标选择范围 ~2000su）：
 * - 玩家本舰：取 `ship.mouseTarget`，限制最大距离 [MAX_PICK_RANGE]。
 * - AI：优先读 AI 在 [ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS] 写入的落点（由
 *   [EchoFixationSystemAI] 算出的敌群密集中心），限制到 [MAX_PICK_RANGE]；若 AI 未给坐标，
 *   退化为「前方敌群中心 / 正前方」。
 *
 * 系统射程倍率（spec §2.1：场半径 / 站桩范围受系统射程属性影响）：
 * 从 `ship.mutableStats.systemRangeBonus` 经 [ASTDArcCombatUtil.effectiveSystemRange] 求 base=1f 的
 * 有效值，即得倍率 systemRangeMult，传入 [EchoFixationField.spawn]。无任何系统射程加成时该值为 1f。
 *
 * Fail Fast：战斗内 `engine.playerShip` / `ship.mouseTarget` / `ship.aiFlags` 均为安全访问，不抛异常，
 * 故全程不包 try。建场仅在 IN 首帧触发一次（[BURST_DONE_KEY] customData 守卫），unapply 时清守卫。
 */
open class EchoFixationSystemStats : BaseShipSystemScript() {

    /** 子类覆盖以指定系统所属模式（编译期固定，仅影响状态栏台词分版）。 */
    protected open val isAutomatedSystem: Boolean = false

    companion object {
        /** 落点最大选择距离（su，spec §2.1：鼠标选择范围 ~2000su）。 */
        private const val MAX_PICK_RANGE = 2000f

        /** AI 落点退化方案：在本舰正前方此距离处取参考点，再向该点附近的敌群中心吸附。 */
        private const val FORWARD_PROBE_DIST = MAX_PICK_RANGE * 0.72f

        /** 退化方案敌群吸附扫描半径（su）：在前方探针点周围此半径内找敌群中心。 */
        private const val CLUSTER_SCAN_RADIUS = 900f

        /** IN 首帧建场守卫 customData key 前缀（每船一条，避免 IN 多帧重复建场）。 */
        private const val BURST_DONE_KEY = "astd_echo_fixation_burst_done:"
    }

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val shipKey = System.identityHashCode(ship).toString()
        val burstKey = "$BURST_DONE_KEY$shipKey"

        // 仅在系统进入激活瞬间（IN 首帧）建场一次；ACTIVE/OUT 期不重复建场。
        if (state != ShipSystemStatsScript.State.IN) return
        if (engine.customData[burstKey] == true) return
        engine.customData[burstKey] = true

        val center = pickFieldCenter(engine, ship)
        // systemRangeBonus.computeEffective(1f) 即系统射程倍率（无加成时为 1f）。
        val systemRangeMult = ASTDArcCombatUtil.effectiveSystemRange(ship, 1f).coerceAtLeast(0.01f)

        EchoFixationField.spawn(engine, ship, center.x, center.y, systemRangeMult)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        // 本系统不写任何 ship stat（建场是一次性副作用，由 EchoFixationField 自管生命周期），
        // 故无 stat modifier 需要 unmodify。此处仅清 IN 首帧建场守卫，保证下次激活能重新建场。
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        val shipKey = System.identityHashCode(ship).toString()
        engine.customData.remove("$BURST_DONE_KEY$shipKey")
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val prefix = if (isAutomatedSystem) "automated" else "crewed"
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN     -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT    -> "out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.echo_fixation.status.$prefix.$suffix"],
            false,
        )
    }

    /**
     * 选落点（世界坐标）：玩家用鼠标，AI 用 AI 提供坐标或前方敌群中心。
     *
     * @param engine 战斗引擎。
     * @param ship 施放舰。
     * @return 落点坐标，已限制到 [MAX_PICK_RANGE] 内。
     */
    private fun pickFieldCenter(engine: CombatEngineAPI, ship: ShipAPI): Vector2f {
        // 玩家本舰：取鼠标落点并限制最大距离。
        if (engine.playerShip === ship) {
            val mouse = ship.mouseTarget
            if (mouse != null) return clampToRange(ship, Vector2f(mouse))
        }

        // AI：优先采用 AI 在 SYSTEM_TARGET_COORDS 写入的落点（EchoFixationSystemAI 算出的敌群中心）。
        val aiCoords = ship.aiFlags?.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS) as? Vector2f
        if (aiCoords != null) return clampToRange(ship, Vector2f(aiCoords))

        // 退化：AI 未给坐标时，在前方探针点附近找敌群中心；无敌则放正前方。
        return forwardClusterCenter(engine, ship)
    }

    /** 把目标点限制到本舰 [MAX_PICK_RANGE] 半径内（超出则沿连线取边界点）。 */
    private fun clampToRange(ship: ShipAPI, target: Vector2f): Vector2f {
        val dist = MathUtils.getDistance(ship.location, target)
        if (dist <= MAX_PICK_RANGE) return target
        val ang = VectorUtils.getAngle(ship.location, target)
        return MathUtils.getPointOnCircumference(ship.location, MAX_PICK_RANGE, ang)
    }

    /**
     * 退化落点：在本舰正前方 [FORWARD_PROBE_DIST] 处取探针点，向该点 [CLUSTER_SCAN_RADIUS] 内
     * 的敌群质心吸附；无敌舰则直接用探针点（正前方）。
     */
    private fun forwardClusterCenter(engine: CombatEngineAPI, ship: ShipAPI): Vector2f {
        val probe = MathUtils.getPointOnCircumference(ship.location, FORWARD_PROBE_DIST, ship.facing)

        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (s in CombatUtils.getShipsWithinRange(probe, CLUSTER_SCAN_RADIUS)) {
            if (s.owner == ship.owner) continue
            if (s.isHulk || s.isFighter) continue
            sumX += s.location.x
            sumY += s.location.y
            count++
        }
        if (count == 0) return probe
        return Vector2f(sumX / count, sumY / count)
    }
}
