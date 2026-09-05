package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

/**
 * R-16 激进式集群作战网络（affixes.md v3.0）：
 * - 按难度系数获得 100%~200% 指挥点恢复速率（最终乘区，逐帧写入本方 CPRateModifier）；
 * - 歼灭指令：每隔 45 秒（固定值）对敌方部署点最高的单位发起歼灭指令，
 *   至少一半左右舰船响应，持续 30 秒；
 *   响应舰船对目标造成伤害提升 15%~30%、目标受到响应舰船伤害降低 15%~30%（最终乘区）；
 * - AI 行为侧：指令期间响应舰船（AI 驾驶）强制锁定目标（shipTarget 断言）。
 *
 * 战斗侧事件由 [AffixExterminationDirectivePlugin] 承载：首个携带本改装的舰船入场时
 * 懒安装到 CombatEngine（engine.customData 去重）。
 */
class AffixAggressiveSwarmNetworkHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_aggressive_swarm_network"
        const val ENGINE_PLUGIN_KEY = "astd_affix_extermination_directive_plugin"

        /** 指挥点恢复速率提升（最终乘区增量）。 */
        val CP_RATE_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

        /** 歼灭指令触发间隔（固定，不随难度系数缩放）。 */
        const val DIRECTIVE_INTERVAL_SECONDS = 45f

        /** 歼灭指令持续时间。 */
        const val DIRECTIVE_DURATION_SECONDS = 30f

        /** 响应舰船对目标伤害提升（最终乘区）。 */
        val DIRECTIVE_DAMAGE_DEALT_BONUS = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)

        /** 目标受到响应舰船伤害减免（最终乘区）。 */
        val DIRECTIVE_DAMAGE_TAKEN_REDUCTION = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)

        /** 指令期间响应舰船的目标断言间隔。 */
        const val RETARGET_INTERVAL_SECONDS = 1f

        /**
         * 响应舰船选择（"至少一半左右"）：按 id 排序后取前半（向上取整），保证确定性。
         * 入参为可用舰船 id 列表（调用方已完成存活/舰载机过滤）。
         */
        fun selectResponderIds(shipIds: List<String>): Set<String> =
            shipIds.sorted().take((shipIds.size + 1) / 2).toSet()

        /**
         * 歼灭目标选择：部署点最高；并列时取 id 字典序最小者保证确定性。
         * 入参为 (舰船 id, 部署点) 列表（调用方已完成存活/舰载机过滤）。
         */
        fun selectTargetId(candidates: List<Pair<String, Float>>): String? =
            candidates.minWithOrNull(compareBy({ -it.second }, { it.first }))?.first

        internal fun directivePlugin(engine: CombatEngineAPI): AffixExterminationDirectivePlugin? =
            engine.customData[ENGINE_PLUGIN_KEY] as? AffixExterminationDirectivePlugin
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.customData[ENGINE_PLUGIN_KEY] == null) {
            val plugin = AffixExterminationDirectivePlugin()
            engine.addPlugin(plugin)
            engine.customData[ENGINE_PLUGIN_KEY] = plugin
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        val taskManager = engine.getFleetManager(ship.owner)?.getTaskManager(false) ?: return
        taskManager.cpRateModifier.unmodify(HULLMOD_ID)
        taskManager.cpRateModifier.modifyMult(HULLMOD_ID, 1f + DifficultyTuningImpl.value(CP_RATE_BONUS))
    }
}

/** 单方（owner）歼灭指令状态。 */
internal class DirectiveState {
    var nextTriggerAt: Float = AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_INTERVAL_SECONDS
    var activeUntil: Float = 0f
    var targetId: String? = null
    var responderIds: Set<String> = emptySet()
    var damageDealtBonus: Float = 0f
    var damageTakenReduction: Float = 0f
    var retargetTimer: Float = 0f
    val statId: String = "astd_affix_extermination"

    fun isActive(now: Float): Boolean = now < activeUntil && targetId != null

    fun reset() {
        activeUntil = 0f
        targetId = null
        responderIds = emptySet()
    }
}

/**
 * 歼灭指令事件插件：按 owner 维护状态机（45s 触发 / 30s 持续），
 * 并在指令期间为响应舰船断言 shipTarget（AI 响应口径）。
 */
internal class AffixExterminationDirectivePlugin : BaseEveryFrameCombatPlugin() {

    private lateinit var engine: CombatEngineAPI
    val states: MutableMap<Int, DirectiveState> = mutableMapOf()

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (engine.isPaused) return
        val now = engine.getTotalElapsedTime(false)
        for (owner in intArrayOf(0, 1)) {
            advanceForOwner(owner, now, amount)
        }
    }

    private fun advanceForOwner(owner: Int, now: Float, amount: Float) {
        val carriers = engine.ships.filter { carrier ->
            carrier.owner == owner && carrier.isAlive && !carrier.isHulk &&
                carrier.variant?.hasHullMod(AffixAggressiveSwarmNetworkHullMod.HULLMOD_ID) == true
        }
        if (carriers.isEmpty()) {
            states.remove(owner)
            return
        }
        val state = states.getOrPut(owner) { DirectiveState() }

        if (state.isActive(now)) {
            val target = state.targetId?.let(::findShipById)
            if (target == null || !target.isAlive || target.isHulk) {
                state.reset()
                return
            }
            // AI 响应：周期性把响应舰船的 shipTarget 断言到歼灭目标。
            state.retargetTimer -= amount
            if (state.retargetTimer <= 0f) {
                state.retargetTimer = AffixAggressiveSwarmNetworkHullMod.RETARGET_INTERVAL_SECONDS
                for (ship in engine.ships) {
                    if (ship.id in state.responderIds && ship.isAlive && !ship.isHulk && ship.shipAI != null) {
                        if (ship.shipTarget !== target) ship.shipTarget = target
                    }
                }
            }
            return
        }

        if (now < state.nextTriggerAt) return
        state.nextTriggerAt = now + AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_INTERVAL_SECONDS

        val enemies = engine.ships.filter { enemy ->
            enemy.owner != owner && enemy.isAlive && !enemy.isHulk && !enemy.isFighter && !enemy.isStationModule
        }
        val targetId = AffixAggressiveSwarmNetworkHullMod.selectTargetId(
            enemies.map { it.id to (it.fleetMember?.unmodifiedDeploymentPointsCost ?: 0f) },
        ) ?: return

        val responders = AffixAggressiveSwarmNetworkHullMod.selectResponderIds(
            engine.ships.filter { ally ->
                ally.owner == owner && ally.isAlive && !ally.isHulk && !ally.isFighter && !ally.isStationModule
            }.map { it.id },
        )
        if (responders.isEmpty()) return

        state.activeUntil = now + AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DURATION_SECONDS
        state.targetId = targetId
        state.responderIds = responders
        state.damageDealtBonus = DifficultyTuningImpl.value(
            AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DAMAGE_DEALT_BONUS,
        )
        state.damageTakenReduction = DifficultyTuningImpl.value(
            AffixAggressiveSwarmNetworkHullMod.DIRECTIVE_DAMAGE_TAKEN_REDUCTION,
        )
        state.retargetTimer = 0f

        // 挂伤害监听器（每船一次，跨指令复用；监听器按当前状态生效）。
        for (ship in engine.ships) {
            if (ship.id in responders && !ship.hasListenerOfClass(ExterminationDamageDealtListener::class.java)) {
                ship.addListener(ExterminationDamageDealtListener(ship))
            }
        }
        findShipById(targetId)?.let { target ->
            if (!target.hasListenerOfClass(ExterminationDamageTakenListener::class.java)) {
                target.addListener(ExterminationDamageTakenListener(target))
            }
        }
    }

    private fun findShipById(id: String): ShipAPI? = engine.ships.firstOrNull { it.id == id }

    /** 响应舰船侧：对歼灭目标的伤害提升。 */
    private class ExterminationDamageDealtListener(
        private val ship: ShipAPI,
    ) : DamageDealtModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) ship.removeListener(this)
        }

        override fun modifyDamageDealt(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val dmg = damage ?: return null
            val engine = Global.getCombatEngine() ?: return null
            val state = AffixAggressiveSwarmNetworkHullMod.directivePlugin(engine)
                ?.states?.get(ship.owner) ?: return null
            val now = engine.getTotalElapsedTime(false)
            if (!state.isActive(now) || ship.id !in state.responderIds) return null
            val targetShip = target as? ShipAPI ?: return null
            if (targetShip.id != state.targetId) return null
            dmg.modifier.modifyMult(state.statId, 1f + state.damageDealtBonus)
            return null
        }
    }

    /** 目标侧：受到响应舰船的伤害减免。 */
    private class ExterminationDamageTakenListener(
        private val ship: ShipAPI,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) ship.removeListener(this)
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val dmg = damage ?: return null
            if (target !== ship) return null
            val engine = Global.getCombatEngine() ?: return null
            val attacker = when (param) {
                is DamagingProjectileAPI -> param.source
                is BeamAPI -> param.source
                else -> null
            } ?: return null
            val state = AffixAggressiveSwarmNetworkHullMod.directivePlugin(engine)
                ?.states?.get(attacker.owner) ?: return null
            val now = engine.getTotalElapsedTime(false)
            if (!state.isActive(now) || state.targetId != ship.id) return null
            if (attacker.id !in state.responderIds) return null
            dmg.modifier.modifyMult(state.statId, 1f - state.damageTakenReduction)
            return null
        }
    }
}
