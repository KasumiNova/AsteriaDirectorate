package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.floor
import kotlin.math.round

class StarfallWingAdaptationStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
) : Buff {

    var showOnPlayerHud: Boolean = false

    var stacks: Float = 0f
        private set

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    fun addStacks(n: Float) {
        if (n <= 0f) return
        stacks += n
        refreshShieldMult()
    }

    override fun advance(amount: Float) {
        if (stacks <= 0f) return
        stacks -= StarfallWingTuning.ADAPTATION_DECAY_PER_SECOND * amount
        if (stacks <= 0f) {
            stacks = 0f
            host.remove(this)
            return
        }
        refreshShieldMult()
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.shieldDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    private fun refreshShieldMult() {
        val base = ship.mutableStats.shieldDamageTakenMult.baseValue
        ship.mutableStats.shieldDamageTakenMult.modifyMult(
            BUFF_ID, StarfallWingTuning.adaptationShieldMult(base, stacks),
        )
    }

    private fun maintainHud() {
        val player = engine.playerShip
        val weakened = stacks * StarfallWingTuning.ADAPTATION_TAKEN_PER_STACK
        val pctText = formatPercent(weakened * 100f)
        val stacksText = formatPercent(stacks)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.desc", "stacks" to stacksText, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.victim_desc", "stacks" to stacksText, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        const val BUFF_ID = "astd_starfall_wing_adaptation"

        fun attachStacks(ship: ShipAPI, engine: CombatEngineAPI, stacks: Float, attacker: ShipAPI?) {
            val host = ship.buffHost()
            val buff = host.find(BUFF_ID) as? StarfallWingAdaptationStacks
                ?: StarfallWingAdaptationStacks(ship, engine, host).also { host.register(it) }
            buff.addStacks(stacks)
            if (attacker != null && attacker == engine.playerShip) buff.showOnPlayerHud = true
        }

        private const val HUD_KEY = "astd_starfall_wing_adaptation_status"

        private const val HUD_VICTIM_KEY = "astd_starfall_wing_adaptation_victim_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

fun ShipAPI.starfallWingAdaptationStacks(): StarfallWingAdaptationStacks? =
    getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
