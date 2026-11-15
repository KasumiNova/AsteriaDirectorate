package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.LinearExplosionFalloffImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StarwingShiftSystemStats : BaseShipSystemScript() {

    private val log = AstdLog.logger

    private val warnedSites = HashSet<String>()

    private fun logOnce(site: String, message: String) {
        if (warnedSites.add(site)) log.warn(message)
    }

    private class ShiftState {
        var started = false
        var arrived = false
        var travelStartTime = -1f
        var lockedFacing = 0f
        var dist = 0f
        val origin = Vector2f()
        val dir = Vector2f(1f, 0f)
        val lastPosition = Vector2f()
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        if (ship == null) {
            logOnce("entity", "[ASTD] 星翼折跃 apply 收到非舰船实体，系统状态未推进（id=$id）")
            return
        }
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return

        val key = STATE_KEY_PREFIX + ship.id
        if (state == ShipSystemStatsScript.State.IDLE || state == ShipSystemStatsScript.State.COOLDOWN) {
            engine.customData.remove(key)
            return
        }

        val system = ship.system ?: return
        val shift = engine.customData.getOrPut(key) { ShiftState() } as ShiftState
        val now = engine.getTotalElapsedTime(false)

        if (!shift.started) beginShift(engine, ship, system, shift)
        when (state) {
            ShipSystemStatsScript.State.ACTIVE -> glide(engine, ship, system, shift, now)
            ShipSystemStatsScript.State.OUT -> arrive(engine, ship, system, shift, now)
            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        engine.customData.remove(STATE_KEY_PREFIX + ship.id)
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val key = when (state) {
            ShipSystemStatsScript.State.IN -> "system.rift_shift.status.in"
            ShipSystemStatsScript.State.ACTIVE -> "system.rift_shift.status.active"
            ShipSystemStatsScript.State.OUT -> "system.rift_shift.status.out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, key], false)
    }

    private fun beginShift(engine: CombatEngineAPI, ship: ShipAPI, system: ShipSystemAPI, shift: ShiftState) {
        shift.started = true
        shift.dist = ship.mutableStats.systemRangeBonus.computeEffective(ImaginaryWingsTuning.SHIFT_DISTANCE)
        clearPulse(engine, ship)
        StarwingShiftVfx.activate(engine, ship.location, ship.facing, system.chargeUpDur + system.chargeActiveDur)
    }

    private fun beginTravel(ship: ShipAPI, shift: ShiftState, now: Float) {
        shift.origin.set(ship.location)
        shift.dir.set(shiftDirection(ship))
        shift.lastPosition.set(shift.origin)
        shift.lockedFacing = ship.facing
        shift.travelStartTime = now
    }

    private fun glide(engine: CombatEngineAPI, ship: ShipAPI, system: ShipSystemAPI, shift: ShiftState, now: Float) {
        if (shift.arrived) return
        if (shift.travelStartTime < 0f) beginTravel(ship, shift, now)
        lockTurn(ship, shift)

        if (now - shift.travelStartTime >= system.chargeActiveDur) {
            arrive(engine, ship, system, shift, now)
            return
        }
        val next = travelPoint(shift, system, now)
        StarwingShiftVfx.trail(engine, ship, shift.lastPosition, next)
        shift.lastPosition.set(next)
        ship.location.set(next)
    }

    private fun travelPoint(shift: ShiftState, system: ShipSystemAPI, now: Float): Vector2f {
        val progress = ((now - shift.travelStartTime) / system.chargeActiveDur).coerceIn(0f, 1f)
        val eased = StarwingShiftMath.easeInOutExpo(progress)
        return Vector2f(
            shift.origin.x + shift.dir.x * shift.dist * eased,
            shift.origin.y + shift.dir.y * shift.dist * eased,
        )
    }

    private fun arrive(engine: CombatEngineAPI, ship: ShipAPI, system: ShipSystemAPI, shift: ShiftState, now: Float) {
        if (shift.arrived) return
        shift.arrived = true
        if (shift.travelStartTime < 0f) beginTravel(ship, shift, now)
        lockTurn(ship, shift)

        val end = travelPoint(shift, system, now)
        StarwingShiftVfx.trail(engine, ship, shift.lastPosition, end)
        ship.location.set(end)
        val exitSpeed = ship.mutableStats.maxSpeed.modifiedValue * EXIT_SPEED_MULT
        ship.velocity.set(shift.dir.x * exitSpeed, shift.dir.y * exitSpeed)

        clearPulse(engine, ship)
        StarwingShiftVfx.impact(engine, ship)
        impactStrike(engine, ship, end)
    }

    private fun lockTurn(ship: ShipAPI, shift: ShiftState) {
        ship.facing = shift.lockedFacing
        ship.angularVelocity = 0f
        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
    }

    private fun shiftDirection(ship: ShipAPI): Vector2f {
        if (ship.velocity.lengthSquared() > 1f) {
            val v = Vector2f(ship.velocity)
            v.normalise()
            return v
        }
        val rad = Math.toRadians(ship.facing.toDouble())
        return Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    }

    private fun impactStrike(engine: CombatEngineAPI, ship: ShipAPI, center: Vector2f) {
        val radius = ImaginaryWingsTuning.SHIFT_IMPACT_RADIUS
        val victims = ExplosionStrikeImpl.strike(
            engine = engine,
            center = center,
            radius = radius,
            damage = ImaginaryWingsTuning.SHIFT_IMPACT_DAMAGE,
            damageType = DamageType.ENERGY,
            emp = 0f,
            source = ship,
            owner = ship.owner,
            falloff = LinearExplosionFalloffImpl(coreRadius = 0f, minDamage = 0f),
        )
        for (victim in victims) {
            if (victim is ShipAPI && (victim.isStation || victim.isStationModule)) continue
            val dx = victim.location.x - center.x
            val dy = victim.location.y - center.y
            val dist = sqrt(dx * dx + dy * dy)
            val surfaceDist = (dist - victim.collisionRadius).coerceAtLeast(0f)
            val falloff = (1f - surfaceDist / radius).coerceIn(0f, 1f)
            if (falloff <= 0f) continue
            val direction = if (dist > 1f) Vector2f(dx, dy) else Vector2f(shiftOutward(ship))
            CombatUtils.applyForce(victim, direction, ImaginaryWingsTuning.SHIFT_IMPACT_FORCE * falloff)
        }
    }

    private fun shiftOutward(ship: ShipAPI): Vector2f {
        val rad = Math.toRadians(ship.facing.toDouble())
        return Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    }

    private fun clearPulse(engine: CombatEngineAPI, ship: ShipAPI) {
        val radius = ImaginaryWingsTuning.CLEAR_RADIUS
        val affected = Collections.newSetFromMap(IdentityHashMap<CombatEntityAPI, Boolean>())
        fun push(target: CombatEntityAPI) {
            if (target === ship || !affected.add(target) || target.collisionClass == CollisionClass.NONE) return
            if (target is ShipAPI && (target.isPhased || target.isStation || target.isStationModule)) return
            if (target is DamagingProjectileAPI && (target.didDamage() || target.isFading || target.explosionSpecIfExplosion != null)) return
            val dx = target.location.x - ship.location.x
            val dy = target.location.y - ship.location.y
            val distance = sqrt(dx * dx + dy * dy)
            val surfaceDistance = (distance - target.collisionRadius).coerceAtLeast(0f)
            if (surfaceDistance >= radius) return
            val direction = if (distance > 0.001f) Vector2f(dx / distance, dy / distance) else shiftOutward(ship)
            val falloff = 1f - surfaceDistance / radius
            val projection = Vector2f.dot(target.velocity, direction)
            val impulse = StarwingShiftMath.radialImpulse(ship.mass, target.mass, projection, falloff)
            target.velocity.translate(direction.x * impulse, direction.y * impulse)
            if (target is DamagingProjectileAPI && target !is MissileAPI) {
                target.facing = Math.toDegrees(atan2(target.velocity.y.toDouble(), target.velocity.x.toDouble())).toFloat()
            }
        }
        for (target in engine.ships) push(target)
        for (target in engine.projectiles) push(target)
        for (target in engine.missiles) push(target)
    }

    companion object {
        private const val STATE_KEY_PREFIX = "astd_starwing_shift_state:"

        private const val EXIT_SPEED_MULT = 1.5f
    }
}
