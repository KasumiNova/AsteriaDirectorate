package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

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

class HeavyIonPulseEmpResistStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
) : StackableBuff {

    var perStack: Float = HeavyIonPulseTuning.EMP_RESIST_PER_STACK.v2

    var armamentUpgrade: Boolean = false

    var showOnPlayerHud: Boolean = false

    private var stacksFloat: Float = 0f

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = floor(stacksFloat).toInt()
    override val maxStacks: Int get() = HeavyIonPulseTuning.RESIST_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksFloat
        stacksFloat = (stacksFloat + n).coerceIn(0f, maxStacks.toFloat())
        return (stacksFloat - before).toInt()
    }

    override fun advance(amount: Float) {
        stacksFloat = (stacksFloat - HeavyIonPulseTuning.RESIST_DECAY_PER_SECOND * amount).coerceAtLeast(0f)
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        refreshEmpResist()
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.empDamageTakenMult.unmodifyMult(BUFF_ID)
        ship.mutableStats.energyDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    private fun refreshEmpResist() {
        val (empShift, energyVuln) = HeavyIonPulseTuning.splitEmpShift(stacks * perStack, armamentUpgrade)

        val stat = ship.mutableStats.empDamageTakenMult
        stat.unmodifyMult(BUFF_ID)
        val current = stat.modifiedValue
        if (current > 0f) {
            val shifted = current + empShift
            stat.modifyMult(BUFF_ID, shifted / current)
        }

        val energyStat = ship.mutableStats.energyDamageTakenMult
        energyStat.unmodifyMult(BUFF_ID)
        if (energyVuln > 0f) {
            energyStat.modifyMult(BUFF_ID, 1f + energyVuln)
        }
    }

    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacks * perStack * 100f)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.heavy_ion_pulse.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.heavy_ion_pulse.status.desc", "stacks" to stacks, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.heavy_ion_pulse.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.heavy_ion_pulse.status.victim_desc", "stacks" to stacks, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        const val BUFF_ID = "astd_heavy_ion_pulse_emp_resist"

        private const val HUD_KEY = "astd_heavy_ion_pulse_emp_resist_status"

        private const val HUD_VICTIM_KEY = "astd_heavy_ion_pulse_emp_resist_victim_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

fun ShipAPI.heavyIonPulseEmpResistStacks(): HeavyIonPulseEmpResistStacks? =
    getBuff(HeavyIonPulseEmpResistStacks.Companion.BUFF_ID) as? HeavyIonPulseEmpResistStacks
