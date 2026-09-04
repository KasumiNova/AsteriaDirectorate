package cn.kasuminova.astd.combat.buffs

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 通用“叠加 + 计时”的舰船 Buff/Debuff 框架：
 * - Buff 状态保存在 ship.customData 中；
 * - 统一由战斗插件每帧刷新/到期清理；
 * - 支持“难度系数”（factor）对数值/时长/层数做缩放。
 */

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
        // 立刻应用一次，保证“命中 tick”瞬间读数就能变化。
        applier.applyTo(target, spec.id, state)
    }

    fun getState(ship: ShipAPI, buffId: String): StackingBuffState? =
        ship.customData[stateKey(buffId)] as? StackingBuffState

    private fun clearState(ship: ShipAPI, buffId: String) {
        ship.setCustomData(stateKey(buffId), null)
    }

    /**
     * 立即清除某船的指定 buff：先 unapply 其 stat 修改（若该 buff 已注册 applier），再清 customData 状态。
     *
     * 动机：渗透潮汐「过载退潮」需即时移除标记效果，不能等下一帧插件自然到期清理，
     * 否则 stat modifier 会残留至少一帧（甚至更久），与「退潮瞬间归零」的语义不符。
     *
     * 若该 buffId 尚未注册过 applier（从未施加过），则不存在可 unapply 的 stat 修改，
     * 此时仅清 customData 即可——属正常情况，无需日志。
     */
    fun clear(engine: CombatEngineAPI, ship: ShipAPI, buffId: String) {
        // 无状态则无需清理：避免对从未被标记的船写入 null entry 污染 customData，
        // 与 advance 里 getState(...) ?: continue 的惰性跳过风格一致。
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

        /** 供 [StackingShipBuffs.clear] 取出已注册的 applier 以执行即时 unapply，保持 appliers 封装。 */
        fun applierFor(buffId: String): ShipBuffApplier? = appliers[buffId]

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            if (appliers.isEmpty()) return

            val now = engine.getTotalElapsedTime(false)

            for (ship in engine.ships) {
                if (ship == null) continue

                if (ship.isHulk) {
                    // 保险：避免残骸/报废实体长期挂着 modifier。
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
    /** 数值缩放：$base * factor^{magnitudePower}$。 */
    val magnitudePower: Float = 0f,
    /** 时长缩放：$base * factor^{durationPower}$。 */
    val durationPower: Float = 0f,
    /** 层数上限缩放：$base * factor^{maxStacksPower}$（四舍五入到 Int）。 */
    val maxStacksPower: Float = 0f,
) {
    internal fun scaleMagnitude(base: Float, factor: Float): Float = base * factor.pow(magnitudePower)
    internal fun scaleDuration(base: Float, factor: Float): Float = base * factor.pow(durationPower)
    internal fun scaleMaxStacks(base: Int, factor: Float): Int =
        (base.toFloat() * factor.pow(maxStacksPower)).roundToInt().coerceAtLeast(1)
}

fun interface DifficultyFactorProvider {
    /** 返回难度系数（>0）。你可以按“是否玩家/是否 AI/战役难度”等自定义规则实现。 */
    fun getFactor(engine: CombatEngineAPI, source: CombatEntityAPI?, target: CombatEntityAPI?): Float
}

data class StackingBuffSpec(
    /** Buff 唯一 ID：必须稳定、全局唯一（用于 modifierId 与 customData key）。 */
    val id: String,
    /** 基础持续时间（秒）。 */
    val baseDuration: Float,
    /** 基础最大层数。 */
    val baseMaxStacks: Int,
    /** 数值倍率（供具体 applier 解释）；默认 1。 */
    val baseMagnitudeMult: Float = 1f,
    /** 难度缩放配置（默认不缩放）。 */
    val scaling: DifficultyScaling = DifficultyScaling(),
    /** 难度系数提供器（默认恒为 1）。 */
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
    /** 记录本次缩放后的“数值倍率”，便于 applier 做一致的属性更新。 */
    var magnitudeMult: Float = 1f,
    /** 记录本次缩放后的层数上限（避免难度变化后出现越界）。 */
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

        // 约定：每次叠加都会“刷新”持续时间（不做逐层独立计时）。
        expiresAt = now + duration
    }
}

interface ShipBuffApplier {
    /** 把 [state] 的效果写入 ship.stats（应当幂等：重复调用结果一致）。 */
    fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState)

    /** 清理该 buffId 对 ship.stats 的所有修改。 */
    fun unapplyFrom(ship: ShipAPI, buffId: String)
}
