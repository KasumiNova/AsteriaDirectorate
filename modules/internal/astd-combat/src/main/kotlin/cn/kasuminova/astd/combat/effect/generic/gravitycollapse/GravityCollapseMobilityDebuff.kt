package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.combat.buffs.ShipBuffApplier
import cn.kasuminova.astd.combat.buffs.StackingBuffSpec
import cn.kasuminova.astd.combat.buffs.StackingBuffState
import cn.kasuminova.astd.combat.buffs.StackingShipBuffs
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * GCP 系列：坍缩脉冲对范围内单位施加的“引力抑制” debuff。
 *
 * 需求：
 * - 机动性降低 10%/层（加速度/减速度/转向）；
 * - 最大航速降低 10%/层；
 * - 持续 3 秒；
 * - 最多 5 层。
 */
internal object GravityCollapseMobilityDebuff {

    private const val BUFF_ID = "astd_gcp_gravity_suppression"

    private const val BASE_DURATION = 3f
    private const val BASE_MAX_STACKS = 5

    private const val SPEED_REDUCTION_PER_STACK = 0.10f
    private const val MOBILITY_REDUCTION_PER_STACK = 0.10f

    private val SPEC = StackingBuffSpec(
        id = BUFF_ID,
        baseDuration = BASE_DURATION,
        baseMaxStacks = BASE_MAX_STACKS,
        // 本 debuff 暂不做难度缩放；但 StackingShipBuffs 已具备该能力。
    )

    private val APPLIER = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            val stacks = state.stacks.coerceIn(0, state.maxStacks.coerceAtLeast(1))
            if (stacks <= 0) {
                unapplyFrom(ship, buffId)
                return
            }

            val speedMult = (1f - SPEED_REDUCTION_PER_STACK * stacks.toFloat()).coerceAtLeast(0.05f)
            val mobilityMult = (1f - MOBILITY_REDUCTION_PER_STACK * stacks.toFloat()).coerceAtLeast(0.05f)

            // 速度
            ship.mutableStats.maxSpeed.modifyMult(buffId, speedMult)

            // 机动（这里按“综合机动性”处理：加/减速 + 转向）
            ship.mutableStats.acceleration.modifyMult(buffId, mobilityMult)
            ship.mutableStats.deceleration.modifyMult(buffId, mobilityMult)
            ship.mutableStats.maxTurnRate.modifyMult(buffId, mobilityMult)
            ship.mutableStats.turnAcceleration.modifyMult(buffId, mobilityMult)
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.maxSpeed.unmodify(buffId)
            ship.mutableStats.acceleration.unmodify(buffId)
            ship.mutableStats.deceleration.unmodify(buffId)
            ship.mutableStats.maxTurnRate.unmodify(buffId)
            ship.mutableStats.turnAcceleration.unmodify(buffId)
        }
    }

    fun apply(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, stacks: Int = 1) {
        if (target.isHulk) return
        StackingShipBuffs.applyOrRefresh(
            engine = engine,
            source = source,
            target = target,
            spec = SPEC,
            applier = APPLIER,
            addStacks = stacks,
        )
    }
}
