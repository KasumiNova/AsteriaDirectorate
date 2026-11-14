package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatAsteroidAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

internal class GressionCollisionTrigger(private val ship: ShipAPI) : DamageTakenModifier, DamageDealtModifier, DamageListener {
    var armed = false
    var triggered = false
        private set
    private var pendingPoint: Vector2f? = null

    override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI, damage: DamageAPI, point: Vector2f?, shieldHit: Boolean): String? {
        pendingPoint = if (armed && target === ship && point != null && isCollisionDamage(param, damage)) Vector2f(point) else null
        return null
    }

    override fun reportDamageApplied(source: Any?, target: CombatEntityAPI, result: ApplyDamageResultAPI) {
        val point = pendingPoint
        pendingPoint = null
        if (point != null && target === ship && isSolidTarget(source) && touchingAt(source as CombatEntityAPI, point)) triggered = true
    }

    override fun modifyDamageDealt(param: Any?, target: CombatEntityAPI, damage: DamageAPI, point: Vector2f?, shieldHit: Boolean): String? {
        if (armed && point != null && isSolidTarget(target) && isCollisionDamage(param, damage) && touchingAt(target, point)) triggered = true
        return null
    }

    private fun isSolidTarget(target: Any?): Boolean = when (target) {
        is ShipAPI -> target !== ship && target.owner != ship.owner && !target.isPhased
        is CombatAsteroidAPI -> true
        else -> false
    }

    private fun touchingAt(other: CombatEntityAPI, point: Vector2f): Boolean {
        fun reaches(entity: CombatEntityAPI): Boolean {
            val shield = (entity as? ShipAPI)?.shield
            val shieldRadius = if (shield?.isOn == true) shield.radius else 0f
            val radius = max(entity.collisionRadius, shieldRadius) + 3f
            val dx = point.x - entity.location.x
            val dy = point.y - entity.location.y
            return dx * dx + dy * dy <= radius * radius
        }
        return reaches(ship) && reaches(other)
    }

    private fun isCollisionDamage(param: Any?, damage: DamageAPI): Boolean =
        GressionChargeMath.isHeavyCollision(
            param == null && !GressionImpactPush.applyingDamage,
            damage.type == DamageType.KINETIC || damage.type == DamageType.ENERGY,
            damage.isDps || damage.isMissile,
            damage.baseDamage,
            GressionChargeTuning.IMPACT_COLLISION_DAMAGE,
        )
}
