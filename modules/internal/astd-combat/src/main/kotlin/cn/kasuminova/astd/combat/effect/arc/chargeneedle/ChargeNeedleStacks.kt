package cn.kasuminova.astd.combat.effect.arc.chargeneedle

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleStacks.Companion.BUFF_ID
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import kotlin.math.floor
import kotlin.math.round

class ChargeNeedleStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
) : StackableBuff {

    var perStack: Float = ChargeNeedleTuning.PER_STACK.v2

    var flatPerStack: Float = ChargeNeedleTuning.FLAT_FLUX_FRIGATE.v2

    var fluxRegardlessOfShield: Boolean = false

    var showOnPlayerHud: Boolean = false

    private var stacksFloat: Float = 0f

    private var lastFactor: Float = 1f

    private var lastFlatPerSec: Float = 0f

    private var warnedZeroDissipation = false
    private var erroredZeroPerStack = false

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = floor(stacksFloat).toInt()

    override val maxStacks: Int get() = ChargeNeedleTuning.ABSOLUTE_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksFloat
        stacksFloat = (stacksFloat + n).coerceIn(0f, maxStacks.toFloat())
        return (stacksFloat - before).toInt()
    }

    override fun advance(amount: Float) {
        val shieldOn = ship.shield?.isOn == true

        stacksFloat = (stacksFloat - ChargeNeedleTuning.decayPerSecond(stacksFloat, shieldOn) * amount).coerceAtLeast(0f)
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        warnOnAbnormalBranches()

        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        val baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f
        val upkeepExtra = baseUpkeep * stacks * perStack
        val flatPerSec = ChargeNeedleTuning.flatFluxPerSec(stacks, flatPerStack, shieldOn, fluxRegardlessOfShield)
        val factor = ChargeNeedleTuning.dissipationCapFactor(dissipation, upkeepExtra, flatPerSec)
        lastFactor = factor
        lastFlatPerSec = flatPerSec

        ship.mutableStats.shieldUpkeepMult.modifyMult(BUFF_ID, 1f + stacks * perStack * factor)
        if (flatPerSec * factor > 0f) {
            ship.fluxTracker.increaseFlux(flatPerSec * factor * amount, false)
        }

        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.shieldUpkeepMult.unmodifyMult(BUFF_ID)
    }

    private fun warnOnAbnormalBranches() {
        if (perStack <= 0f && !erroredZeroPerStack) {
            erroredZeroPerStack = true
            log.error("电荷淤积 perStack ≤ 0（$perStack），难度配置错误: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        if (dissipation <= 0f && !warnedZeroDissipation) {
            warnedZeroDissipation = true
            log.warn("电荷淤积目标耗散 ≤ 0（$dissipation），异常态产出压没: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
    }

    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacks * perStack * lastFactor * 100f)
        val flatText = formatPercent(lastFlatPerSec * lastFactor)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.title"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.charge_needle.status.desc",
                    "stacks" to stacks, "percent" to pctText, "flat" to flatText,
                ),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.victim_title"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.charge_needle.status.victim_desc",
                    "stacks" to stacks, "percent" to pctText, "flat" to flatText,
                ),
                negative = true,
            )
        }
    }

    companion object {
        const val BUFF_ID = "astd_charge_needle_stacks"

        private const val HUD_KEY = "astd_charge_needle_stacks_status"

        private const val HUD_VICTIM_KEY = "astd_charge_needle_stacks_victim_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ChargeNeedleStacks::class.java)

        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

class ChargeNeedleShots(
    val seed: Long,
) : Buff {

    var callIndex: Int = 0

    constructor(source: ShipAPI, weapon: WeaponAPI) : this(CombatRandom.seedOf(source.id, weapon.slot.id))

    override val id: String get() = SHOTS_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    override fun isHostValid(): Boolean = true

    companion object {
        const val SHOTS_ID = "astd_charge_needle_shots"
    }
}

fun ShipAPI.chargeNeedleStacks(): ChargeNeedleStacks? = getBuff(BUFF_ID) as? ChargeNeedleStacks
