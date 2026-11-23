package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.floor
import kotlin.math.round

class StarfallEchoResonanceStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
) : StackableBuff {

    var perStack: Float = StarfallEchoTuning.RESONANCE_VULN_PER_STACK.v2

    var showOnPlayerHud: Boolean = false

    private var stacksInt = 0
    private var lastDecayOrHitAt = 0f

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.WINDOWED
    override val stacks: Int get() = activeStacks(engine.getTotalElapsedTime(false))
    override val maxStacks: Int get() = StarfallEchoTuning.RESONANCE_MAX_STACKS

    private fun activeStacks(now: Float): Int {
        val elapsedIntervals = ((now - lastDecayOrHitAt) / StarfallEchoTuning.RESONANCE_DECAY_INTERVAL).toInt()
        return (stacksInt - elapsedIntervals).coerceIn(0, stacksInt)
    }

    private fun settleDecay(now: Float): Boolean {
        val remaining = activeStacks(now)
        if (remaining == stacksInt) return false
        lastDecayOrHitAt += (stacksInt - remaining) * StarfallEchoTuning.RESONANCE_DECAY_INTERVAL
        stacksInt = remaining
        return true
    }

    override fun addStacks(n: Int): Int {
        val now = engine.getTotalElapsedTime(false)
        settleDecay(now)
        val before = stacksInt
        stacksInt = (before + n.coerceIn(-maxStacks, maxStacks)).coerceIn(0, maxStacks)
        if (n > 0) lastDecayOrHitAt = now
        if (stacksInt == 0) host.remove(this) else refreshVulnerability()
        return stacksInt - before
    }

    fun consume(): Int {
        if (stacksInt == 0) return 0
        val consumed = stacks
        host.remove(this)
        return consumed
    }

    override fun advance(amount: Float) {
        if (settleDecay(engine.getTotalElapsedTime(false))) {
            if (stacksInt == 0) {
                host.remove(this)
                return
            }
            refreshVulnerability()
        }
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        stacksInt = 0
        val stats = ship.mutableStats
        stats.shieldDamageTakenMult.unmodifyMult(BUFF_ID)
        stats.armorDamageTakenMult.unmodifyMult(BUFF_ID)
        stats.hullDamageTakenMult.unmodifyMult(BUFF_ID)
        stats.empDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    private fun refreshVulnerability() {
        val mult = 1f + stacksInt * perStack
        val stats = ship.mutableStats
        stats.shieldDamageTakenMult.modifyMult(BUFF_ID, mult)
        stats.armorDamageTakenMult.modifyMult(BUFF_ID, mult)
        stats.hullDamageTakenMult.modifyMult(BUFF_ID, mult)
        stats.empDamageTakenMult.modifyMult(BUFF_ID, mult)
    }

    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacksInt * perStack * 100f)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_echo.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_echo.status.desc", "stacks" to stacksInt, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_echo.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_echo.status.victim_desc", "stacks" to stacksInt, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        const val BUFF_ID = "astd_starfall_echo_resonance"

        private const val HUD_KEY = "astd_starfall_echo_resonance_status"

        private const val HUD_VICTIM_KEY = "astd_starfall_echo_resonance_victim_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

fun ShipAPI.starfallEchoResonanceStacks(): StarfallEchoResonanceStacks? =
    getBuff(StarfallEchoResonanceStacks.Companion.BUFF_ID) as? StarfallEchoResonanceStacks
