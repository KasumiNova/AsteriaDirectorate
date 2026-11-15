package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.random.Random

internal class StarfallWingDamage(val sourceStats: MutableShipStatsAPI)

internal object StarfallWingEmp {
    private const val ARC_SOUND_ID = "tachyon_lance_emp_impact"

    fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        shot: StarfallWingShot,
        target: ShipAPI,
        from: Vector2f,
        shieldHit: Boolean,
    ) {
        if (shieldHit || !target.isAlive || target.isHulk || target.isPhased || target.owner == projectile.owner ||
            target.owner == 100
        ) return
        val source = projectile.source ?: return
        val emp = projectile.damage.computeFluxDealt(1f)
        if (emp <= 0f) return
        val isPlayer = source.owner == 0
        val minimum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MIN, isPlayer) * shot.power).roundToInt()
        val maximum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MAX, isPlayer) * shot.power).roundToInt()
        repeat(Random.nextInt(minimum, maximum + 1)) {
            val points = ArrayList<Vector2f>()
            for (weapon in target.allWeapons) {
                if (!weapon.isDecorative && !weapon.isDisabled && !weapon.isPermanentlyDisabled) {
                    points += Vector2f(weapon.location)
                }
            }
            for (shipEngine in target.engineController.shipEngines) {
                if (!shipEngine.isDisabled && !shipEngine.isPermanentlyDisabled) {
                    points += Vector2f(shipEngine.location)
                }
            }
            val point = if (points.isEmpty()) Vector2f(from) else points[Random.nextInt(points.size)]
            engine.applyDamage(
                StarfallWingDamage(source.mutableStats), target, point, 0f, projectile.damageType, emp,
                false, false, source, false,
            )
            DcrDamageAttribution.clarify(source, target, projectile.damageType, 0f, emp, projectile.weapon?.displayName)
            engine.spawnEmpArcVisual(
                Vector2f(from), target, point, target, 18f,
                StarfallWingVfx.color(StarfallWingTuning.ShuttleType.BLUE), Color(225, 242, 255),
            )
            Global.getSoundPlayer().playSound(ARC_SOUND_ID, 1f, 1f, point, target.velocity)
        }
    }
}
