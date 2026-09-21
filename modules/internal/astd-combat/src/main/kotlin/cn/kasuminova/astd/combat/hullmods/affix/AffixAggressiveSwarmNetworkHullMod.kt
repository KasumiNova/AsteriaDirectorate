package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.ceil

/**
 * 词缀 R-16：激进式集群作战网络（[AffixRegistry.ID_AGGRESSIVE_SWARM_NETWORK]）。
 * - 按难度系数：获得 100%~200% 指挥点恢复速率（最终乘区；仅旗舰承担全队增幅，避免逐舰叠加）；
 * - 歼灭指令：每 45 秒（固定值）对敌方部署点最高的单位发起指令，约一半舰船响应，持续 30 秒。
 *   响应舰船对目标造成的伤害提升 15%~30%，响应舰船受到目标造成的伤害降低 15%~30%（最终乘区）。
 *
 * 歼灭指令由 [AnnihilationDirectivePlugin] 承载（HullMod + CombatPlugin + AI 响应）。
 */
class AffixAggressiveSwarmNetworkHullMod : BaseHullMod() {

    companion object {
        /** 指挥点恢复速率增量（command_point_rate_flat 为百分数 flat，参考原版 OperationsCenter +2.5）。 */
        val COMMAND_POINT_RATE = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

        const val COMMAND_POINT_RATE_MOD = "command_point_rate_flat"

        private const val PLUGIN_KEY_PREFIX = "astd_affix_asn_directive_"

        /** 指挥点增幅的 stat 源 id（advanceInCombat 无 id 形参）。 */
        private const val MOD_ID = "astd_affix_aggressive_swarm_network"
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        // 指挥点恢复：仅旗舰承担全队增幅（同一舰队多舰携带本词缀时不叠加）。
        val isFlagship = ship.fleetMember?.isFlagship == true
        val mod = ship.mutableStats.dynamic.getMod(COMMAND_POINT_RATE_MOD)
        if (isFlagship) {
            mod.modifyFlat(MOD_ID, AffixShared.tuning.value(COMMAND_POINT_RATE))
        } else {
            mod.unmodifyFlat(MOD_ID)
        }

        if (!ship.isAlive || ship.isHulk) return

        // 每场战斗每方注册一个歼灭指令插件。
        val key = PLUGIN_KEY_PREFIX + ship.owner
        if (engine.customData[key] == null) {
            val plugin = AnnihilationDirectivePlugin(ship.owner, AffixShared.tuning)
            engine.customData[key] = plugin
            engine.addPlugin(plugin)
        }
    }

    /**
     * 歼灭指令战斗插件：定时标记敌方最高部署点单位，组织半数友舰集火，
     * 并结算指令期间的双向伤害修正。
     */
    class AnnihilationDirectivePlugin(
        private val owner: Int,
        tuning: DifficultyTuning,
    ) : BaseEveryFrameCombatPlugin() {

        /** 指令伤害修正系数（响应方增伤/减伤同值）。 */
        private val damageMod: Float = tuning.value(DAMAGE_MOD)

        private var engine: CombatEngineAPI? = null
        private var timer = FIRST_DIRECTIVE_DELAY
        private var directiveElapsed = 0f

        /** 当前指令目标（无指令时为 null）。 */
        var target: ShipAPI? = null
            private set

        /** 当前响应舰船集合（无指令时为空）。 */
        var responders: Set<ShipAPI> = emptySet()
            private set

        /** 指令是否进行中（供伤害监听器判定）。 */
        val directiveActive: Boolean get() = target != null

        override fun init(engine: CombatEngineAPI) {
            this.engine = engine
        }

        override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
            val engine = this.engine ?: return
            if (engine.isPaused) return

            if (directiveActive) {
                directiveElapsed += amount
                val current = target
                if (directiveElapsed >= DURATION || current == null || !current.isAlive || current.isHulk) {
                    endDirective()
                } else {
                    refreshResponderOrders(current)
                }
                return
            }

            timer -= amount
            if (timer <= 0f) {
                startDirective(engine)
                timer = INTERVAL
            }
        }

        private fun startDirective(engine: CombatEngineAPI) {
            val enemies = engine.ships.filter {
                it.owner != owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
            }
            val newTarget = enemies.maxByOrNull { it.fleetMember?.deploymentPointsCost ?: 0f } ?: return

            val allies = engine.ships.filter {
                it.owner == owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
            }
            if (allies.isEmpty()) return

            // 约一半舰船响应（向上取整），优先距目标最近者。
            val responderCount = ceil(allies.size / 2f).toInt().coerceAtLeast(1)
            val chosen = allies.sortedBy { MathUtils.getDistance(it, newTarget) }.take(responderCount).toSet()

            target = newTarget
            responders = chosen
            directiveElapsed = 0f

            if (!newTarget.hasListenerOfClass(MarkedTargetListener::class.java)) {
                newTarget.addListener(MarkedTargetListener(newTarget, this))
            }
            for (responder in chosen) {
                // AI 行为侧（指令响应集火）：setShipTarget 锁定攻击目标，同时按帧刷写
                // 原版舰 AI 每帧读取的旗标——向指令目标压进（PURSUING/MANEUVER_TARGET 携带目标）、
                // 指令期间不后撤（DO_NOT_BACK_OFF）。旗标 0.5s 自然衰减，指令结束后无需手动清除。
                responder.shipTarget = newTarget
                responder.aiFlags?.let { flags ->
                    flags.setFlag(ShipwideAIFlags.AIFlags.PURSUING, FLAG_REFRESH_SEC, newTarget)
                    flags.setFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET, FLAG_REFRESH_SEC, newTarget)
                    flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, FLAG_REFRESH_SEC)
                }
                if (!responder.hasListenerOfClass(ResponderGuardListener::class.java)) {
                    responder.addListener(ResponderGuardListener(responder, this))
                }
            }
        }

        private fun endDirective() {
            target = null
            responders = emptySet()
            directiveElapsed = 0f
        }

        private fun refreshResponderOrders(current: ShipAPI) {
            for (responder in responders) {
                if (responder.isAlive && !responder.isHulk) {
                    if (responder.shipTarget != current) {
                        responder.shipTarget = current
                    }
                    // 旗标自然衰减期为 0.5s，指令期间按帧续期（见 startDirective 注释）
                    responder.aiFlags?.let { flags ->
                        flags.setFlag(ShipwideAIFlags.AIFlags.PURSUING, FLAG_REFRESH_SEC, current)
                        flags.setFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET, FLAG_REFRESH_SEC, current)
                        flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, FLAG_REFRESH_SEC)
                    }
                }
            }
        }

        /** 响应方对指令目标的增伤倍率。 */
        fun responderDamageMultAgainst(ship: ShipAPI): Float =
            if (directiveActive && ship in responders) 1f + damageMod else 1f

        /** 指令目标对响应方的伤害减免倍率。 */
        fun targetDamageMultAgainst(ship: ShipAPI): Float =
            if (directiveActive && ship in responders) 1f - damageMod else 1f

        companion object {
            /** 指令间隔（秒，固定值，不随难度系数缩放）。 */
            const val INTERVAL = 45f

            /** 指令持续（秒）。 */
            const val DURATION = 30f

            /** 首场指令延迟（与间隔同值：开局 45 秒后首次发起）。 */
            const val FIRST_DIRECTIVE_DELAY = INTERVAL

            /** 响应舰 AI 旗标的单次刷写时长（秒）：略长于原版旗标自然衰减期 0.5s，指令期间按帧续期。 */
            const val FLAG_REFRESH_SEC = 1.0f

            /** 伤害修正系数：15%~30%。 */
            val DAMAGE_MOD = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)

            /** 响应方增伤 / 目标侧减伤的 stat 源 id。 */
            const val STAT_ID_DEALT: String = "astd_affix_asn_marked"
            const val STAT_ID_TAKEN: String = "astd_affix_asn_guard"

            /** 从伤害事件中解析攻击方舰船（弹体/光束取 source）。 */
            fun attackerShip(param: Any?): ShipAPI? = when (param) {
                is ShipAPI -> param
                is DamagingProjectileAPI -> param.source
                is BeamAPI -> param.source
                else -> null
            }
        }
    }

    /**
     * 指令目标侧监听器：响应舰船对目标造成的伤害提升（最终乘区）。
     * 指令结束后不再生效；目标死亡时自移除。
     */
    class MarkedTargetListener(
        private val ship: ShipAPI,
        private val plugin: AnnihilationDirectivePlugin,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null || plugin.target !== ship) return null
            val attacker = AnnihilationDirectivePlugin.attackerShip(param) ?: return null
            val mult = plugin.responderDamageMultAgainst(attacker)
            if (mult != 1f) {
                damage.modifier.modifyMult(AnnihilationDirectivePlugin.STAT_ID_DEALT, mult)
            }
            return null
        }
    }

    /**
     * 响应方侧监听器：指令持续期间，响应舰船受到目标造成的伤害降低（最终乘区）。
     * 舰船死亡时自移除。
     */
    class ResponderGuardListener(
        private val ship: ShipAPI,
        private val plugin: AnnihilationDirectivePlugin,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null) return null
            val attacker = AnnihilationDirectivePlugin.attackerShip(param) ?: return null
            if (attacker !== plugin.target) return null
            val mult = plugin.targetDamageMultAgainst(ship)
            if (mult != 1f) {
                damage.modifier.modifyMult(AnnihilationDirectivePlugin.STAT_ID_TAKEN, mult)
            }
            return null
        }
    }
}
