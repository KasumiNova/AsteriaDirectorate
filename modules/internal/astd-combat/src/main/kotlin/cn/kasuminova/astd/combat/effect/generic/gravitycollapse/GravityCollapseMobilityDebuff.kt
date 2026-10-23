package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.combat.buffs.ShipBuffApplier
import cn.kasuminova.astd.combat.buffs.StackingBuffSpec
import cn.kasuminova.astd.combat.buffs.StackingBuffState
import cn.kasuminova.astd.combat.buffs.StackingShipBuffs
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI

internal object GravityCollapseMobilityDebuff {

    private const val BUFF_ID = "astd_gcp_gravity_suppression"

    private val APPLIER = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            if (state.stacks <= 0) {
                unapplyFrom(ship, buffId)
                return
            }

            val mult = (1f - state.magnitudeMult).coerceAtLeast(0.05f)

            ship.mutableStats.maxSpeed.modifyMult(buffId, mult)

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
