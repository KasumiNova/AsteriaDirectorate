package cn.kasuminova.astd.combat.effect.aster

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 第二章「两份章程」紫菀引力节点战斗效果（docs/story/07-第二章-两份章程.md:53-64）。
 *
 * 设计口径：
 * - 引力节点作为**可摧毁的战场单位**存在（默认使用原版遗迹空间站 `remnant_station2_Standard`，
 *   紫菀遗址即遗迹 AI 阵地，资产语义一致；可由 [NodeSpec.variantId] 覆盖）；
 * - 守方增益（按与各存活节点的最近距离生效，多节点取最强一档）：
 *   按舰船大小与难度系数提升时间流速 30~60% / 25~50% / 20~40% / 15~30%（护卫/驱逐/巡洋/主力），
 *   降低 20~40% 受到的伤害；满效半径 800~1600su，1600~3200su 外完全失去加成，其间线性衰减；
 * - 攻方减成（全场常驻，不随距离衰减）：仅削减"额外"部分（技能/船插/词缀等附加来源，不动基础值）——
 *   额外时间流速 -25~75%、额外舰船武器射程 -15~30%、额外护盾伤害减免 -25~75%；
 * - 全部节点摧毁后所有修正撤销；暂停时不推进状态；玩家船的全局时间补偿按原版时流之壳口径反比校正。
 *
 * 入口（campaign 的 FID delegate / BattleCreationPlugin 拿到 [CombatEngineAPI] 后调用）：
 * [AsterGravityNodeBattle.install]；本模块不依赖、也不修改 campaign 代码。
 */
object AsterGravityNodeBattle {

    /** engine.customData 键：已安装的节点战插件实例（[AsterGravityNodeBattlePlugin]）。 */
    const val ENGINE_PLUGIN_KEY = "astd_aster_gravity_node_battle"

    /** 节点舰船 customData 键（值 = [NodeSpec.id]），供 campaign/规则侧识别节点单位。 */
    const val SHIP_NODE_ID_DATA_KEY = "astd_aster_gravity_node_id"

    /** 默认节点机体：原版遗迹空间站（可破坏结构资产，语义贴合紫菀遗址阵地）。 */
    const val DEFAULT_NODE_VARIANT_ID = "remnant_station2_Standard"

    /** 节点规格：编目 id、战场位置、机体 variant（默认原版遗迹空间站）、朝向。 */
    data class NodeSpec(
        val id: String,
        val location: Vector2f,
        val variantId: String = DEFAULT_NODE_VARIANT_ID,
        val facing: Float = 0f,
    )

    /**
     * 在战斗中安装引力节点阵地。幂等：已安装或节点列表为空时不重复安装。
     *
     * @param defenderOwner 守方（节点阵地所属方）的 owner 编号，赏金/剧情遭遇中通常为 1（敌方）
     * @return 是否实际安装
     */
    @JvmStatic
    fun install(engine: CombatEngineAPI, nodes: List<NodeSpec>, defenderOwner: Int = 1): Boolean {
        if (nodes.isEmpty()) return false
        if (engine.customData[ENGINE_PLUGIN_KEY] != null) return false
        val plugin = AsterGravityNodeBattlePlugin(nodes, defenderOwner)
        engine.addPlugin(plugin)
        engine.customData[ENGINE_PLUGIN_KEY] = plugin
        return true
    }

    private var pendingNodes: List<NodeSpec>? = null

    /** FID 创建战斗上下文时调用；实际 engine 仅在战斗插件 init 时可用。 */
    @JvmStatic
    fun requestInstall(nodes: List<NodeSpec>, defenderOwner: Int = 1) {
        require(nodes.isNotEmpty())
        pendingNodes = nodes.map { it.copy() }
        pendingOwner = defenderOwner
    }

    internal var pendingOwner: Int = 1

    /** 全局战斗插件在 init 时消费待安装的节点阵地。 */
    @JvmStatic
    fun installPending(engine: CombatEngineAPI): Boolean {
        val nodes = pendingNodes ?: return false
        val owner = pendingOwner
        pendingNodes = null
        pendingOwner = 1
        return install(engine, nodes, owner)
    }

    /** 当前战斗是否已安装节点阵地。 */
    @JvmStatic
    fun isActive(engine: CombatEngineAPI): Boolean = engine.customData[ENGINE_PLUGIN_KEY] != null

    /** 仍存活的节点编目 id 列表（未安装时为空）。 */
    @JvmStatic
    fun aliveNodeIds(engine: CombatEngineAPI): List<String> =
        (engine.customData[ENGINE_PLUGIN_KEY] as? AsterGravityNodeBattlePlugin)?.aliveNodeIds() ?: emptyList()
}

/** 节点战数值与纯逻辑（全部直调可测）。 */
object AsterGravityNodeMath {

    // ─── 守方增益 ───

    /** 时间流速提升（按舰船大小分档，护卫/驱逐/巡洋/主力）。 */
    val DEFENDER_TIME_FLOW_BONUS: Map<ShipAPI.HullSize, ScalingEntry> = mapOf(
        ShipAPI.HullSize.FRIGATE to ScalingEntry(v1 = 0.30f, v2 = 0.45f, v5 = 0.60f),
        ShipAPI.HullSize.DESTROYER to ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f),
        ShipAPI.HullSize.CRUISER to ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f),
        ShipAPI.HullSize.CAPITAL_SHIP to ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f),
    )

    /** 守方受到的伤害减免。 */
    val DEFENDER_DAMAGE_TAKEN_REDUCTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

    /** 满效半径（su）。 */
    val FULL_EFFECT_RADIUS = ScalingEntry(v1 = 800f, v2 = 1200f, v5 = 1600f)

    /** 完全失效外半径（su）。 */
    val OUTER_EFFECT_RADIUS = ScalingEntry(v1 = 1600f, v2 = 2400f, v5 = 3200f)

    // ─── 攻方减成（仅"额外"部分） ───

    /** 额外时间流速削减。 */
    val ATTACKER_EXTRA_TIME_FLOW_CUT = ScalingEntry(v1 = 0.25f, v2 = 0.50f, v5 = 0.75f)

    /** 额外舰船武器射程削减。 */
    val ATTACKER_EXTRA_RANGE_CUT = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)

    /** 额外护盾伤害减免削减。 */
    val ATTACKER_EXTRA_SHIELD_REDUCTION_CUT = ScalingEntry(v1 = 0.25f, v2 = 0.50f, v5 = 0.75f)

    /** 距离衰减系数：满效半径内 1，外半径外 0，其间线性。 */
    fun falloffFactor(distance: Float, fullRadius: Float, outerRadius: Float): Float = when {
        distance <= fullRadius -> 1f
        distance >= outerRadius -> 0f
        else -> (outerRadius - distance) / (outerRadius - fullRadius)
    }

    fun defenderTimeFlowBonus(hullSize: ShipAPI.HullSize, tuning: DifficultyTuning): Float =
        tuning.value(DEFENDER_TIME_FLOW_BONUS[hullSize] ?: DEFENDER_TIME_FLOW_BONUS.getValue(ShipAPI.HullSize.CAPITAL_SHIP))

    /**
     * "额外"时间流速削减的补偿乘区（基础值 1 不动）：
     * 当前有效值 eff > 1 时，超出部分按 cut 缩减 → 目标值 = 1 + (eff-1)(1-cut)，返回 target/eff。
     */
    fun extraTimeFlowCompMult(currentEffective: Float, cut: Float): Float {
        if (currentEffective <= 1f || cut <= 0f) return 1f
        return (1f + (currentEffective - 1f) * (1f - cut)) / currentEffective
    }

    /**
     * "额外"射程削减的补偿百分修饰（射程加成域基准为 0%）：
     * 当前额外加成 cur > 0 时返回 -cur*cut（直接抵消 cut 比例的附加加成）。
     */
    fun extraRangeCompPercent(currentPercent: Float, cut: Float): Float =
        if (currentPercent > 0f && cut > 0f) -currentPercent * cut else 0f

    /** 额外射程平值的补偿：保留基础射程，只削减正向 flat 来源。 */
    fun extraRangeCompFlat(currentFlat: Float, cut: Float): Float =
        if (currentFlat > 0f && cut > 0f) -currentFlat * cut else 0f

    /** 额外射程乘区的补偿：将 m 调整为 1+(m-1)(1-cut)。 */
    fun extraRangeCompMult(currentMult: Float, cut: Float): Float =
        if (currentMult > 1f && cut > 0f) {
            (1f + (currentMult - 1f) * (1f - cut)) / currentMult
        } else {
            1f
        }

    /**
     * "额外"护盾伤害减免削减的补偿乘区（基础值 1 不动）：
     * 当前有效值 eff < 1 时，减免部分 (1-eff) 按 cut 缩减 → 目标值 = 1 - (1-eff)(1-cut)，返回 target/eff。
     */
    fun extraShieldReductionCompMult(currentEffective: Float, cut: Float): Float {
        if (currentEffective >= 1f || cut <= 0f) return 1f
        return (1f - (1f - currentEffective) * (1f - cut)) / currentEffective
    }
}

/**
 * 引力节点阵地战斗插件：init 后首帧刷出节点单位，逐帧（暂停除外）按设计挂/摘修正。
 *
 * 修正 id 全部带 `astd_gravity_node_` 前缀；撤销路径（节点全毁 / 舰船离场）逐帧 unmodify，
 * 保证节点摧毁后所有修正移除、无残留叠乘。
 */
class AsterGravityNodeBattlePlugin internal constructor(
    private val specs: List<AsterGravityNodeBattle.NodeSpec>,
    private val defenderOwner: Int,
) : BaseEveryFrameCombatPlugin() {

    private lateinit var engine: CombatEngineAPI
    private val log = Global.getLogger(AsterGravityNodeBattlePlugin::class.java)
    private val nodes = mutableListOf<Pair<AsterGravityNodeBattle.NodeSpec, ShipAPI>>()
    private var spawned = false

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
    }

    fun aliveNodeIds(): List<String> =
        nodes.filter { it.second.isAlive && !it.second.isHulk }.map { it.first.id }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (!spawned) {
            spawned = true
            spawnNodes()
        }
        if (engine.isPaused) return

        val aliveNodes = nodes.filter { it.second.isAlive && !it.second.isHulk }
        if (aliveNodes.isEmpty()) {
            revertAll()
            engine.customData.remove(AsterGravityNodeBattle.ENGINE_PLUGIN_KEY)
            return
        }

        val tuning = DifficultyTuningImpl
        val fullRadius = tuning.value(AsterGravityNodeMath.FULL_EFFECT_RADIUS)
        val outerRadius = tuning.value(AsterGravityNodeMath.OUTER_EFFECT_RADIUS)
        val timeCut = tuning.value(AsterGravityNodeMath.ATTACKER_EXTRA_TIME_FLOW_CUT)
        val rangeCut = tuning.value(AsterGravityNodeMath.ATTACKER_EXTRA_RANGE_CUT)
        val shieldCut = tuning.value(AsterGravityNodeMath.ATTACKER_EXTRA_SHIELD_REDUCTION_CUT)

        val playerShip = engine.playerShip
        var playerTimeFactor = 1f

        for (ship in engine.ships) {
            if (!ship.isAlive || ship.isHulk) continue
            if (ship.isFighter || ship.isStationModule) continue
            if (nodes.any { it.second === ship }) continue

            if (ship.owner == defenderOwner) {
                // 守方增益：多节点取最近距离的最强衰减档。
                val nearest = aliveNodes.minOf { MathUtils.getDistance(ship, it.second.location) }
                val factor = AsterGravityNodeMath.falloffFactor(nearest, fullRadius, outerRadius)
                if (factor > 0f) {
                    val timeMult = 1f + AsterGravityNodeMath.defenderTimeFlowBonus(ship.hullSize, tuning) * factor
                    ship.mutableStats.timeMult.modifyMult(MOD_DEFENDER_TIME, timeMult)
                    val defenseMult = 1f - tuning.value(AsterGravityNodeMath.DEFENDER_DAMAGE_TAKEN_REDUCTION) * factor
                    applyDefenseMult(ship.mutableStats, defenseMult)
                    if (ship === playerShip) playerTimeFactor *= timeMult
                } else {
                    unapplyDefender(ship.mutableStats)
                }
            } else {
                // 攻方减成：全场常驻，仅削"额外"部分。
                val stats = ship.mutableStats

                stats.timeMult.unmodify(MOD_ATTACKER_TIME)
                val timeComp = AsterGravityNodeMath.extraTimeFlowCompMult(stats.timeMult.modifiedValue, timeCut)
                if (timeComp != 1f) stats.timeMult.modifyMult(MOD_ATTACKER_TIME, timeComp)

                applyRangeCut(stats, rangeCut)

                stats.shieldDamageTakenMult.unmodify(MOD_ATTACKER_SHIELD)
                val shieldComp = AsterGravityNodeMath.extraShieldReductionCompMult(
                    stats.shieldDamageTakenMult.modifiedValue, shieldCut,
                )
                if (shieldComp != 1f) stats.shieldDamageTakenMult.modifyMult(MOD_ATTACKER_SHIELD, shieldComp)

                if (ship === playerShip) playerTimeFactor *= timeComp
            }
        }

        // 玩家船全局时间补偿（原版时流之壳口径）：使玩家主观时间流速不被我们的乘区改变。
        if (playerTimeFactor != 1f && playerShip != null && playerShip.isAlive && !playerShip.isHulk) {
            engine.timeMult.modifyMult(MOD_ENGINE_TIME_COMP, 1f / playerTimeFactor)
        } else {
            engine.timeMult.unmodify(MOD_ENGINE_TIME_COMP)
        }
    }

    private fun spawnNodes() {
        val fleetManager = engine.getFleetManager(defenderOwner) ?: run {
            log.warn("[AsterGravityNodeBattle] 守方舰队管理器不存在，无法生成 ${specs.size} 个引力节点")
            return
        }
        for (spec in specs) {
            val ship = fleetManager.spawnShipOrWing(spec.variantId, spec.location, spec.facing)
            if (ship == null) {
                log.warn("[AsterGravityNodeBattle] 引力节点生成失败：${spec.id}，variant=${spec.variantId}")
                continue
            }
            ship.setCustomData(AsterGravityNodeBattle.SHIP_NODE_ID_DATA_KEY, spec.id)
            nodes += spec to ship
        }
    }

    private fun applyDefenseMult(stats: MutableShipStatsAPI, mult: Float) {
        stats.hullDamageTakenMult.modifyMult(MOD_DEFENDER_DEFENSE, mult)
        stats.armorDamageTakenMult.modifyMult(MOD_DEFENDER_DEFENSE, mult)
        stats.shieldDamageTakenMult.modifyMult(MOD_DEFENDER_DEFENSE, mult)
    }

    private fun unapplyDefender(stats: MutableShipStatsAPI) {
        stats.timeMult.unmodify(MOD_DEFENDER_TIME)
        stats.hullDamageTakenMult.unmodify(MOD_DEFENDER_DEFENSE)
        stats.armorDamageTakenMult.unmodify(MOD_DEFENDER_DEFENSE)
        stats.shieldDamageTakenMult.unmodify(MOD_DEFENDER_DEFENSE)
    }

    /**
     * 射程减成覆盖 StatBonus 的 percent、flat、mult 三个修正域。
     * 先摘除本插件的上一帧补偿，再读取其它来源，避免重复累计。
     */
    private fun applyRangeCut(stats: MutableShipStatsAPI, cut: Float) {
        val bonuses = listOf(
            stats.ballisticWeaponRangeBonus,
            stats.energyWeaponRangeBonus,
            stats.missileWeaponRangeBonus,
        )
        for (bonus in bonuses) {
            bonus.unmodify(MOD_ATTACKER_RANGE)
            val percent = AsterGravityNodeMath.extraRangeCompPercent(bonus.getPercentMod(), cut)
            val flat = AsterGravityNodeMath.extraRangeCompFlat(bonus.getFlatBonus(), cut)
            val mult = AsterGravityNodeMath.extraRangeCompMult(bonus.getMult(), cut)
            if (percent != 0f) bonus.modifyPercent(MOD_ATTACKER_RANGE, percent)
            if (flat != 0f) bonus.modifyFlat(MOD_ATTACKER_RANGE, flat)
            if (mult != 1f) bonus.modifyMult(MOD_ATTACKER_RANGE, mult)
        }
    }

    /** 全部节点摧毁后的全量撤销：对场内所有舰船摘除本插件全部修饰（unmodify 对不存在修饰为空操作）。 */
    private fun revertAll() {
        for (ship in engine.ships) {
            unapplyDefender(ship.mutableStats)
            ship.mutableStats.timeMult.unmodify(MOD_ATTACKER_TIME)
            ship.mutableStats.shieldDamageTakenMult.unmodify(MOD_ATTACKER_SHIELD)
            ship.mutableStats.ballisticWeaponRangeBonus.unmodify(MOD_ATTACKER_RANGE)
            ship.mutableStats.energyWeaponRangeBonus.unmodify(MOD_ATTACKER_RANGE)
            ship.mutableStats.missileWeaponRangeBonus.unmodify(MOD_ATTACKER_RANGE)
        }
        engine.timeMult.unmodify(MOD_ENGINE_TIME_COMP)
    }

    companion object {
        private const val MOD_DEFENDER_TIME = "astd_gravity_node_def_time"
        private const val MOD_DEFENDER_DEFENSE = "astd_gravity_node_def_defense"
        private const val MOD_ATTACKER_TIME = "astd_gravity_node_atk_time"
        private const val MOD_ATTACKER_RANGE = "astd_gravity_node_atk_range"
        private const val MOD_ATTACKER_SHIELD = "astd_gravity_node_atk_shield"
        private const val MOD_ENGINE_TIME_COMP = "astd_gravity_node_engine_time_comp"
    }
}
