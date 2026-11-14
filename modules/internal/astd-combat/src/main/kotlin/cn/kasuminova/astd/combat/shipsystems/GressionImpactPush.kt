package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.impl.combat.ConeImpactHandler
import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatAsteroidAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.exp

internal object GressionImpactPush {
    var applyingDamage: Boolean = false
        private set

    fun spawn(engine: CombatEngineAPI, source: ShipAPI, spec: ConeImpactSpec) {
        engine.addPlugin(Wave(engine, source, spec))
    }

    private class Wave(
        private val engine: CombatEngineAPI,
        private val source: ShipAPI,
        private val spec: ConeImpactSpec,
    ) : BaseEveryFrameCombatPlugin() {
        private val origin = Vector2f(spec.origin)
        private val direction = Vector2f(spec.direction)
        private val range = spec.range
        private val halfAngle = spec.halfAngleDeg
        private var age = 0f
        private val damageQueue = ArrayList<DelayedDamage>()
        private val sourceMass = source.mass
        private val affected = Collections.newSetFromMap(IdentityHashMap<CombatEntityAPI, Boolean>())

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            age += amount
            val radius = range * (1f - exp(-6f * age.coerceAtMost(0.78f)))
            if (age - amount < 0.78f) {
                for (target in engine.ships) push(target, radius)
                for (target in engine.asteroids) push(target, radius)
                for (target in engine.projectiles) push(target, radius)
                for (target in engine.missiles) push(target, radius)
            }
            applyPendingDamage()
            if (age >= 0.78f && damageQueue.isEmpty()) engine.removePlugin(this)
        }

        private class DelayedDamage(val target: CombatEntityAPI, val pointOffset: Vector2f, val at: Float)

        private fun applyPendingDamage() {
            val iterator = damageQueue.iterator()
            val previousDamageScope = applyingDamage
            applyingDamage = true
            try {
                while (iterator.hasNext()) {
                    val pending = iterator.next()
                    if (age < pending.at) continue
                    iterator.remove()
                    val target = pending.target
                    if (!engine.isEntityInPlay(target)) continue
                    if (target is ShipAPI && (target.isHulk || target.isPhased)) continue
                    if (target is DamagingProjectileAPI && (target.isFading || target.didDamage())) continue
                    val point = Vector2f.add(target.location, pending.pointOffset, null)
                    engine.applyDamage(target, point, spec.damage, spec.damageType, spec.empDamage, false, false, source, true)
                    DcrDamageAttribution.clarify(source, target, spec.damageType, spec.damage, spec.empDamage, spec.weaponName)
                }
            } finally {
                applyingDamage = previousDamageScope
            }
        }

        private fun push(target: CombatEntityAPI, radius: Float) {
            if (target === source || target in affected || target.collisionClass == CollisionClass.NONE) return
            if (target is ShipAPI && target.isPhased) return
            if (target is DamagingProjectileAPI && (target.isFading || target.didDamage() || target.explosionSpecIfExplosion != null)) return
            if (target !is CombatAsteroidAPI && target.owner == source.owner) return
            if (!ConeImpactHandler.isInsideCone(origin, direction.x, direction.y, halfAngle, radius, target.location, target.collisionRadius)) return
            val anchored = target is ShipAPI && (target.isStation || target.isStationModule)
            val delta = if (anchored) 0f else GressionChargeMath.pushSpeed(sourceMass, target.mass)
            if (delta > 0f) {
                val projection = target.velocity.x * direction.x + target.velocity.y * direction.y
                val impulse = GressionChargeMath.pushImpulse(projection, delta) * GressionChargeTuning.IMPACT_PUSH_MULT
                target.velocity.translate(direction.x * impulse, direction.y * impulse)
                if (target is DamagingProjectileAPI && target !is MissileAPI) {
                    target.facing = Math.toDegrees(atan2(target.velocity.y.toDouble(), target.velocity.x.toDouble())).toFloat()
                }
            }
            affected += target
            val dealsDamage = when (target) {
                is MissileAPI -> spec.hitMissiles
                is ShipAPI -> if (target.isFighter) spec.hitFighters else spec.hitShips
                else -> false
            }
            if (dealsDamage && spec.filter.accept(target)) {
                val point = ConeImpactHandler.surfacePoint(origin, target.location, target.collisionRadius)
                damageQueue += DelayedDamage(target, Vector2f.sub(point, target.location, null), age + 0.12f)
            }
        }
    }
}
