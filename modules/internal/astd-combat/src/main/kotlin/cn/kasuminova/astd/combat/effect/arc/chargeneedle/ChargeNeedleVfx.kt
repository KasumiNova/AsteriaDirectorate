package cn.kasuminova.astd.combat.effect.arc.chargeneedle

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object ChargeNeedleVfx {

    private val SHIELD_HIT_COLOR = Color(140, 200, 255, 195)

    private val ARC_FRINGE = Color(140, 200, 255)

    private val ARC_CORE = Color(225, 242, 255)

    // spawnEmpArc 的 soundId 为 null 时原版不播音效。
    private const val ARC_SOUND_ID = "shock_repeater_emp_impact"

    const val TELEMETRY_DISCHARGE_COUNT = "astd_charge_needle_discharge_count"

    fun shieldHitParticles(engine: CombatEngineAPI, point: Vector2f, ship: ShipAPI) {
        val vel = ship.velocity?.let { Vector2f(it) } ?: Vector2f()
        engine.addHitParticle(point, vel, 35f, 1f, 0.18f, SHIELD_HIT_COLOR)
        engine.addSmoothParticle(point, vel, 55f, 0.65f, 0.25f, SHIELD_HIT_COLOR)
    }

    // spawnEmpArc 不提供命中点参数，命中位置由原版在目标舰上自行选取。
    fun discharge(engine: CombatEngineAPI, source: ShipAPI?, from: Vector2f, target: ShipAPI, emp: Float) {
        engine.spawnEmpArc(
            source,
            from,
            target,
            target,
            DamageType.ENERGY,
            0f,
            emp,
            1000f,
            ARC_SOUND_ID,
            20f,
            ARC_FRINGE,
            ARC_CORE,
        )
        engine.customData[TELEMETRY_DISCHARGE_COUNT] = dischargeCount(engine) + 1
    }

    fun dischargeCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_DISCHARGE_COUNT] as? Int ?: 0
}
