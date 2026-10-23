package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object HeavyIonPulseVfx {

    private val ARC_FRINGE = Color(140, 200, 255)
    private val ARC_CORE = Color(225, 242, 255)

    // spawnEmpArc 的 soundId 为 null 时原版不播音效。
    private const val ARC_SOUND_ID = "shock_repeater_emp_impact"

    private val feedback: CombatFeedback = CombatFeedbackImpl

    const val TELEMETRY_HULL_HITS_PLAYER = "astd_hip_hull_hits_player"
    const val TELEMETRY_HULL_HITS_OTHER = "astd_hip_hull_hits_other"

    const val TELEMETRY_DISCHARGE_PLAYER = "astd_hip_discharge_count_player"
    const val TELEMETRY_DISCHARGE_OTHER = "astd_hip_discharge_count_other"

    const val TELEMETRY_PIERCE_PLAYER = "astd_hip_pierce_count_player"
    const val TELEMETRY_PIERCE_OTHER = "astd_hip_pierce_count_other"
    const val TELEMETRY_PIERCE_LAST_EXTRA = "astd_hip_pierce_last_extra"
    const val TELEMETRY_PIERCE_LAST_APPLIED = "astd_hip_pierce_last_applied"
    const val TELEMETRY_PIERCE_LAST_MULT = "astd_hip_pierce_last_mult"
    const val TELEMETRY_PIERCE_LAST_BASE_EMP = "astd_hip_pierce_last_base_emp"
    const val TELEMETRY_PIERCE_LAST_ARC_EMP = "astd_hip_pierce_last_arc_emp"
    const val TELEMETRY_PIERCE_SUM_EXTRA = "astd_hip_pierce_sum_extra"

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
            24f,
            ARC_FRINGE,
            ARC_CORE,
        )
        increment(engine, if (source?.owner == 0) TELEMETRY_DISCHARGE_PLAYER else TELEMETRY_DISCHARGE_OTHER)
    }

    fun pierce(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        point: Vector2f,
        extra: Float,
        applied: Float,
        source: ShipAPI?,
        mult: Float,
        baseEmp: Float,
        arcEmp: Float
    ) {
        engine.applyDamage(ship, point, 0f, DamageType.ENERGY, applied, false, false, source)
        feedback.floatingDamage(engine, point, extra, ARC_CORE, ship, source)
        engine.addHitParticle(point, Vector2f(), 30f, 1f, 0.2f, ARC_CORE)

        val playerCaused = source?.owner == 0
        increment(engine, if (playerCaused) TELEMETRY_PIERCE_PLAYER else TELEMETRY_PIERCE_OTHER)
        engine.customData[TELEMETRY_PIERCE_LAST_EXTRA] = extra
        engine.customData[TELEMETRY_PIERCE_LAST_APPLIED] = applied
        engine.customData[TELEMETRY_PIERCE_LAST_MULT] = mult
        engine.customData[TELEMETRY_PIERCE_LAST_BASE_EMP] = baseEmp
        engine.customData[TELEMETRY_PIERCE_LAST_ARC_EMP] = arcEmp
        engine.customData[TELEMETRY_PIERCE_SUM_EXTRA] = ((engine.customData[TELEMETRY_PIERCE_SUM_EXTRA] as? Float) ?: 0f) + extra
    }

    fun recordHullHit(engine: CombatEngineAPI, source: ShipAPI?) {
        increment(engine, if (source?.owner == 0) TELEMETRY_HULL_HITS_PLAYER else TELEMETRY_HULL_HITS_OTHER)
    }

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: 0f

    private fun increment(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
