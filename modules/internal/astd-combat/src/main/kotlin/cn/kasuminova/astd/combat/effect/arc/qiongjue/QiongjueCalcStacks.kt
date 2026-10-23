package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

class QiongjueCalcStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
    private val perStackEntry: ScalingEntry = QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS,
    private val decayRateEntry: ScalingEntry = QiongjuePhaseRailgunDifficulty.DECAY_RATE,
) : StackableBuff {

    var target: ShipAPI? = null

    var lastHitTime: Float = 0f

    private var stacksField: Int = 0

    private var pendingDecay: Float = 0f

    private var currentPerStack: Float = QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS.v2

    private val lastCooldownBySlot = mutableMapOf<String, Float>()

    private var warnedZeroDecayRate: Boolean = false

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.WINDOWED
    override val stacks: Int get() = stacksField
    override val maxStacks: Int get() =
        QiongjueStackMath.maxStacks(SicWeaponUpgradeFlags.isArmamentUpgradeActive(ship))

    override fun addStacks(n: Int): Int {
        val before = stacksField
        stacksField = (stacksField + n).coerceIn(0, maxStacks)
        return stacksField - before
    }

    fun isTargetAlive(): Boolean {
        val t = target ?: return false
        return !t.isHulk && engine.isEntityInPlay(t)
    }

    override fun advance(amount: Float) {
        val now = engine.getTotalElapsedTime(false)
        val perStack = QiongjueStackMath.resolve(DifficultyTuningImpl, perStackEntry, ship.owner)
        val decayRate = QiongjueStackMath.resolve(DifficultyTuningImpl, decayRateEntry, ship.owner)
        currentPerStack = perStack

        if (!QiongjueStackMath.isDecayRateValid(decayRate) && !warnedZeroDecayRate) {
            warnedZeroDecayRate = true
            log.warn("穷距演算衰减速率非法（$decayRate），难度配置异常，层数不衰减继续运行: ship=${ship.id}")
        }
        val step = QiongjueStackMath.decayAdvance(stacks, pendingDecay, now - lastHitTime, amount, decayRate)
        if (step.stacks != stacks) addStacks(step.stacks - stacks)
        pendingDecay = step.pendingDecay

        if (stacks <= 0) {
            host.remove(this)
            return
        }

        val mult = QiongjueStackMath.mult(stacks, perStack)

        for (w in ship.allWeapons) {
            if (w?.spec?.weaponId != QiongjuePhaseRailgunDifficulty.WEAPON_ID) continue
            val slotId = w.slot?.id ?: continue
            val cooldown = w.cooldownRemaining
            val last = lastCooldownBySlot[slotId] ?: 0f
            if (cooldown - last > REFIRE_EDGE_EPS) {
                w.setRemainingCooldownTo(cooldown / mult)
                engine.customData[TELEMETRY_SPIKE_APPLIED] = (engine.customData[TELEMETRY_SPIKE_APPLIED] as? Int ?: 0) + 1
            }
            lastCooldownBySlot[slotId] = cooldown
        }

        if (stacks > 0 && ship === engine.playerShip) {
            val pct = QiongjueStackMath.formatPercent((mult - 1f) * 100f)
            feedback.maintainPlayerStatus(
                engine,
                HUD_KEY,
                HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.qiongjue.status.calc"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.qiongjue.status.stacks",
                    "stacks" to stacks,
                    "max" to maxStacks,
                    "dmg" to pct,
                    "rof" to pct,
                ),
                negative = false,
            )
            engine.customData[TELEMETRY_HUD_FRAMES] = (engine.customData[TELEMETRY_HUD_FRAMES] as? Int ?: 0) + 1
        }
    }

    override fun isHostValid(): Boolean =
        ship.isAlive && !ship.isHulk &&
                ship.allWeapons.any { it?.spec?.weaponId == QiongjuePhaseRailgunDifficulty.WEAPON_ID }

    companion object {
        const val BUFF_ID = "astd_qiongjue_stacks"

        private const val HUD_KEY = "astd_qiongjue_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private const val REFIRE_EDGE_EPS = 0.5f

        const val TELEMETRY_SPIKE_APPLIED = "astd_qiongjue_spike_applied"

        const val TELEMETRY_HUD_FRAMES = "astd_qiongjue_hud_frames"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(QiongjueCalcStacks::class.java)
    }
}

fun ShipAPI.qiongjueCalcStacks(): QiongjueCalcStacks? = getBuff(QiongjueCalcStacks.BUFF_ID) as? QiongjueCalcStacks
