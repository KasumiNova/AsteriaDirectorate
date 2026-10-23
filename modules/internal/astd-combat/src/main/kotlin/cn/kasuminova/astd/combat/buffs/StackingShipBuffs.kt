package cn.kasuminova.astd.combat.buffs

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import kotlin.math.pow
import kotlin.math.roundToInt

object StackingShipBuffs {
    private const val ENGINE_PLUGIN_KEY = "astd_stacking_ship_buffs_plugin"

    private fun stateKey(buffId: String): String = "astd_buff_state:$buffId"

    fun applyOrRefresh(
        engine: CombatEngineAPI,
        source: CombatEntityAPI?,
        target: ShipAPI,
        spec: StackingBuffSpec,
        applier: ShipBuffApplier,
        addStacks: Int = 1,
    ) {
        if (engine.isPaused) return
        if (target.isHulk) return

        val scaled = spec.scaled(engine, source, target)
        val now = engine.getTotalElapsedTime(false)

        val key = stateKey(spec.id)
        val state = (target.customData[key] as? StackingBuffState) ?: StackingBuffState()
        state.applyStacks(
            now = now,
            addStacks = addStacks,
            duration = scaled.duration,
            maxStacks = scaled.maxStacks,
            magnitudeMult = scaled.magnitudeMult,
        )
        target.setCustomData(key, state)

        ensurePlugin(engine).register(spec.id, applier)
        applier.applyTo(target, spec.id, state)
    }

    fun getState(ship: ShipAPI, buffId: String): StackingBuffState? =
        ship.customData[stateKey(buffId)] as? StackingBuffState

    private fun clearState(ship: ShipAPI, buffId: String) {
        ship.setCustomData(stateKey(buffId), null)
    }

    fun clear(engine: CombatEngineAPI, ship: ShipAPI, buffId: String) {
        if (getState(ship, buffId) == null) return
        val plugin = engine.customData[ENGINE_PLUGIN_KEY] as? Plugin
        plugin?.applierFor(buffId)?.unapplyFrom(ship, buffId)
        clearState(ship, buffId)
    }

    private fun ensurePlugin(engine: CombatEngineAPI): Plugin {
        val existing = engine.customData[ENGINE_PLUGIN_KEY] as? Plugin
        if (existing != null) return existing

        val plugin = Plugin()
        engine.customData[ENGINE_PLUGIN_KEY] = plugin
        engine.addPlugin(plugin)
        return plugin
    }

    private class Plugin : BaseEveryFrameCombatPlugin() {
        private val appliers: MutableMap<String, ShipBuffApplier> = LinkedHashMap()

        fun register(buffId: String, applier: ShipBuffApplier) {
            appliers[buffId] = applier
        }

        fun applierFor(buffId: String): ShipBuffApplier? = appliers[buffId]

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            if (appliers.isEmpty()) return

            val now = engine.getTotalElapsedTime(false)

            for (ship in engine.ships) {
                if (ship == null) continue

                if (ship.isHulk) {
                    for ((buffId, applier) in appliers) {
                        applier.unapplyFrom(ship, buffId)
                        clearState(ship, buffId)
                    }
                    continue
                }

                for ((buffId, applier) in appliers) {
                    val state = getState(ship, buffId) ?: continue

                    if (!state.isActive(now)) {
                        applier.unapplyFrom(ship, buffId)
                        clearState(ship, buffId)
                        continue
                    }

                    applier.applyTo(ship, buffId, state)
                }
            }
        }
    }
}

data class DifficultyScaling(
    val magnitudePower: Float = 0f,
    val durationPower: Float = 0f,
    val maxStacksPower: Float = 0f,
) {
    internal fun scaleMagnitude(base: Float, factor: Float): Float = base * factor.pow(magnitudePower)
    internal fun scaleDuration(base: Float, factor: Float): Float = base * factor.pow(durationPower)
    internal fun scaleMaxStacks(base: Int, factor: Float): Int =
        (base.toFloat() * factor.pow(maxStacksPower)).roundToInt().coerceAtLeast(1)
}

fun interface DifficultyFactorProvider {
    fun getFactor(engine: CombatEngineAPI, source: CombatEntityAPI?, target: CombatEntityAPI?): Float
}

data class StackingBuffSpec(
    val id: String,
    val baseDuration: Float,
    val baseMaxStacks: Int,
    val baseMagnitudeMult: Float = 1f,
    val scaling: DifficultyScaling = DifficultyScaling(),
    val difficultyFactorProvider: DifficultyFactorProvider = DifficultyFactorProvider { _, _, _ -> 1f },
) {
    fun scaled(engine: CombatEngineAPI, source: CombatEntityAPI?, target: CombatEntityAPI?): ScaledStackingBuffSpec {
        val raw = difficultyFactorProvider.getFactor(engine, source, target)
        val factor = if (raw.isFinite() && raw > 0f) raw else 1f

        val duration = scaling.scaleDuration(baseDuration, factor).coerceAtLeast(0.01f)
        val maxStacks = scaling.scaleMaxStacks(baseMaxStacks, factor)
        val magnitudeMult = scaling.scaleMagnitude(baseMagnitudeMult, factor).coerceAtLeast(0f)

        return ScaledStackingBuffSpec(
            id = id,
            duration = duration,
            maxStacks = maxStacks,
            magnitudeMult = magnitudeMult,
        )
    }
}

data class ScaledStackingBuffSpec(
    val id: String,
    val duration: Float,
    val maxStacks: Int,
    val magnitudeMult: Float,
)

data class StackingBuffState(
    var stacks: Int = 0,
    var expiresAt: Float = Float.NEGATIVE_INFINITY,
    var magnitudeMult: Float = 1f,
    var maxStacks: Int = 1,
) {
    fun isActive(now: Float): Boolean = stacks > 0 && now < expiresAt

    fun applyStacks(now: Float, addStacks: Int, duration: Float, maxStacks: Int, magnitudeMult: Float) {
        val add = addStacks.coerceAtLeast(0)

        if (!isActive(now)) {
            stacks = 0
            expiresAt = Float.NEGATIVE_INFINITY
        }

        this.maxStacks = maxStacks.coerceAtLeast(1)
        this.magnitudeMult = magnitudeMult
        stacks = (stacks + add).coerceIn(0, this.maxStacks)

        expiresAt = now + duration
    }
}

interface ShipBuffApplier {
    fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState)

    fun unapplyFrom(ship: ShipAPI, buffId: String)
}
