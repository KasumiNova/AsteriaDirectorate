package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.combat.buffs.ShipBuffApplier
import cn.kasuminova.astd.combat.buffs.StackingBuffSpec
import cn.kasuminova.astd.combat.buffs.StackingBuffState
import cn.kasuminova.astd.combat.buffs.StackingShipBuffs
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * GCP 系列：坍缩脉冲命中装甲/船体时施加的“引力抑制” debuff。
 *
 * 需求（weapon_data tooltip 文案双向绑定）：
 * - 最大航速与机动性（加/减速、转向）降低 [reduction] 比例（难度缩放，单次足额而非叠层）；
 * - 持续 [duration] 秒（难度缩放），重复命中刷新持续时间与数值。
 */
internal object GravityCollapseMobilityDebuff {

    private const val BUFF_ID = "astd_gcp_gravity_suppression"

    private val APPLIER = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            if (state.stacks <= 0) {
                unapplyFrom(ship, buffId)
                return
            }

            val mult = (1f - state.magnitudeMult).coerceAtLeast(0.05f)

            // 速度
            ship.mutableStats.maxSpeed.modifyMult(buffId, mult)

            // 机动（这里按“综合机动性”处理：加/减速 + 转向）
            ship.mutableStats.acceleration.modifyMult(buffId, mult)
            ship.mutableStats.deceleration.modifyMult(buffId, mult)
            ship.mutableStats.maxTurnRate.modifyMult(buffId, mult)
            ship.mutableStats.turnAcceleration.modifyMult(buffId, mult)
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.maxSpeed.unmodify(buffId)
            ship.mutableStats.acceleration.unmodify(buffId)
            ship.mutableStats.deceleration.unmodify(buffId)
            ship.mutableStats.maxTurnRate.unmodify(buffId)
            ship.mutableStats.turnAcceleration.unmodify(buffId)
        }
    }

    /**
     * @param reduction 最大航速/机动性降低比例（0~1，调用侧已按难度系数解析）
     * @param duration 持续时间（秒，调用侧已按难度系数解析）
     */
    fun apply(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, reduction: Float, duration: Float) {
        if (target.isHulk) return
        StackingShipBuffs.applyOrRefresh(
            engine = engine,
            source = source,
            target = target,
            spec = StackingBuffSpec(
                id = BUFF_ID,
                baseDuration = duration.coerceAtLeast(0.01f),
                baseMaxStacks = 1,
                baseMagnitudeMult = reduction.coerceIn(0f, 0.95f),
            ),
            applier = APPLIER,
            addStacks = 1,
        )
    }
}
