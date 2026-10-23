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

    private var stacksInt: Int = 0

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = stacksInt
    override val maxStacks: Int get() = StarfallEchoTuning.RESONANCE_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksInt
        stacksInt = (stacksInt + n).coerceIn(0, maxStacks)
        if (stacksInt != before) refreshVulnerability()
        return stacksInt - before
    }

    fun consume(): Int {
        val consumed = stacksInt
        if (consumed <= 0) return 0
        stacksInt = 0
        host.remove(this)
        return consumed
    }

    override fun advance(amount: Float) {
        if (stacksInt <= 0) return
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.armorDamageTakenMult.unmodifyMult(BUFF_ID)
        ship.mutableStats.hullDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    private fun refreshVulnerability() {
        val mult = 1f + stacksInt * perStack
        ship.mutableStats.armorDamageTakenMult.modifyMult(BUFF_ID, mult)
        ship.mutableStats.hullDamageTakenMult.modifyMult(BUFF_ID, mult)
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
