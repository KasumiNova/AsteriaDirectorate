package cn.kasuminova.astd.combat.effect.arc.chargeneedle

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingTable
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_FLOOR_PER_SECOND
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_RATIO_PER_SECOND
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_SHIELD_OFF_MULT
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DISSIPATION_CAP_MULT
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.min

object ChargeNeedleTuning {

    @Volatile
    private var warnedNullHullSize = false

    private val log = Global.getLogger(ChargeNeedleTuning::class.java)

    val PER_STACK = ScalingEntry(0.01f, 0.02f, 0.05f)

    val DISCHARGE_CHANCE = ScalingTable(0.20f, 0.30f, 0.40f, 0.50f, 0.60f)

    val DISCHARGE_EMP_MULT = ScalingTable(1.00f, 1.50f, 2.00f, 2.50f, 3.00f)

    const val BASE_DISCHARGE_EMP = 100f

    val FLAT_FLUX_FRIGATE = ScalingEntry(0.5f, 1.5f, 2.5f)

    val FLAT_FLUX_DESTROYER = ScalingEntry(1f, 3f, 5f)

    val FLAT_FLUX_CRUISER = ScalingEntry(1.5f, 4.5f, 7.5f)

    val FLAT_FLUX_CAPITAL = ScalingEntry(2f, 6f, 10f)

    const val ABSOLUTE_MAX_STACKS = 200

    const val DECAY_RATIO_PER_SECOND = 0.04f

    const val DECAY_SHIELD_OFF_MULT = 2f

    const val DECAY_FLOOR_PER_SECOND = 2f

    const val DISSIPATION_CAP_MULT = 2f

    data class Values(
        val perStack: Float,
        val flatFluxPerStack: Float,
        val dischargeChance: Float,
        val dischargeEmpMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean, hullSize: ShipAPI.HullSize?): Values = Values(
        perStack = tuning.valueFor(PER_STACK, isPlayer),
        flatFluxPerStack = flatFluxForSize(tuning, isPlayer, hullSize),
        dischargeChance = tuning.valueFor(DISCHARGE_CHANCE, isPlayer),
        dischargeEmpMult = tuning.valueFor(DISCHARGE_EMP_MULT, isPlayer),
    )

    fun flatFluxForSize(tuning: DifficultyTuning, isPlayer: Boolean, hullSize: ShipAPI.HullSize?): Float {
        val entry = when (hullSize) {
            ShipAPI.HullSize.DESTROYER -> FLAT_FLUX_DESTROYER
            ShipAPI.HullSize.CRUISER -> FLAT_FLUX_CRUISER
            ShipAPI.HullSize.CAPITAL_SHIP -> FLAT_FLUX_CAPITAL
            null -> {
                if (!warnedNullHullSize) {
                    warnedNullHullSize = true
                    log.warn("电荷针刺固定软辐能查表收到 null 体型（配置异常的上游遗漏），按护卫舰档兜底（一次性告警）")
                }
                FLAT_FLUX_FRIGATE
            }

            else -> FLAT_FLUX_FRIGATE
        }
        return tuning.valueFor(entry, isPlayer)
    }

    fun flatFluxPerSec(stacks: Int, flatPerStack: Float, shieldOn: Boolean, fluxRegardlessOfShield: Boolean): Float =
        if (shieldOn || fluxRegardlessOfShield) stacks.coerceAtLeast(0) * flatPerStack else 0f

    fun decayPerSecond(stacks: Float, shieldOn: Boolean): Float =
        if (stacks <= 0f) {
            0f
        } else {
            val ratio = DECAY_RATIO_PER_SECOND * (if (shieldOn) 1f else DECAY_SHIELD_OFF_MULT)
            maxOf(stacks * ratio, DECAY_FLOOR_PER_SECOND)
        }

    fun dissipationCapFactor(dissipation: Float, upkeepExtra: Float, flatExtra: Float): Float {
        val total = upkeepExtra + flatExtra
        if (total <= 0f) return 1f
        if (dissipation <= 0f) return 0f
        return min(1f, DISSIPATION_CAP_MULT * dissipation / total)
    }

    fun shouldDischarge(roll: Float, chance: Float): Boolean = roll < chance
}
