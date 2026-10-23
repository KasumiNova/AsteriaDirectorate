package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
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
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.ceil

class AffixAggressiveSwarmNetworkHullMod : BaseHullMod() {

    companion object {
        val COMMAND_POINT_RATE = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

        const val COMMAND_POINT_RATE_MOD = "command_point_rate_flat"

        private const val PLUGIN_KEY_PREFIX = "astd_affix_asn_directive_"

        private const val MOD_ID = "astd_affix_aggressive_swarm_network"
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val isFlagship = ship.fleetMember?.isFlagship == true
        val mod = ship.mutableStats.dynamic.getMod(COMMAND_POINT_RATE_MOD)
        if (isFlagship) {
            mod.modifyFlat(MOD_ID, AffixShared.tuning.value(COMMAND_POINT_RATE))
        } else {
            mod.unmodifyFlat(MOD_ID)
        }

        if (!ship.isAlive || ship.isHulk) return

        val key = PLUGIN_KEY_PREFIX + ship.owner
        if (engine.customData[key] == null) {
            val plugin = AnnihilationDirectivePlugin(ship.owner, AffixShared.tuning)
            engine.customData[key] = plugin
            engine.addPlugin(plugin)
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_aggressive_swarm_network.desc")
        }
    }

    class AnnihilationDirectivePlugin(
        private val owner: Int,
        tuning: DifficultyTuning,
    ) : BaseEveryFrameCombatPlugin() {

        private val damageMod: Float = tuning.value(DAMAGE_MOD)

        private var engine: CombatEngineAPI? = null
        private var timer = FIRST_DIRECTIVE_DELAY
        private var directiveElapsed = 0f

        var target: ShipAPI? = null
            private set

        var responders: Set<ShipAPI> = emptySet()
            private set

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

            val responderCount = ceil(allies.size / 2f).toInt().coerceAtLeast(1)
            val chosen = allies.sortedBy { MathUtils.getDistance(it, newTarget) }.take(responderCount).toSet()

            target = newTarget
            responders = chosen
            directiveElapsed = 0f

            if (!newTarget.hasListenerOfClass(MarkedTargetListener::class.java)) {
                newTarget.addListener(MarkedTargetListener(newTarget, this))
            }
            for (responder in chosen) {
                // 原版 AI 旗标会在 0.5 秒后自然衰减，指令期间必须持续刷新。
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
                    responder.aiFlags?.let { flags ->
                        flags.setFlag(ShipwideAIFlags.AIFlags.PURSUING, FLAG_REFRESH_SEC, current)
                        flags.setFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET, FLAG_REFRESH_SEC, current)
                        flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, FLAG_REFRESH_SEC)
                    }
                }
            }
        }

        fun responderDamageMultAgainst(ship: ShipAPI): Float =
            if (directiveActive && ship in responders) 1f + damageMod else 1f

        fun targetDamageMultAgainst(ship: ShipAPI): Float =
            if (directiveActive && ship in responders) 1f - damageMod else 1f

        companion object {
            const val INTERVAL = 45f

            const val DURATION = 30f

            const val FIRST_DIRECTIVE_DELAY = INTERVAL

            const val FLAG_REFRESH_SEC = 1.0f

            val DAMAGE_MOD = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)

            const val STAT_ID_DEALT: String = "astd_affix_asn_marked"
            const val STAT_ID_TAKEN: String = "astd_affix_asn_guard"

            fun attackerShip(param: Any?): ShipAPI? = when (param) {
                is ShipAPI -> param
                is DamagingProjectileAPI -> param.source
                is BeamAPI -> param.source
                else -> null
            }
        }
    }

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
