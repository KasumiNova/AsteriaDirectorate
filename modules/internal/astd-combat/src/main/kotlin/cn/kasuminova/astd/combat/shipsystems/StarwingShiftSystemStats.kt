package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMoteAi
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMotes
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.LinearExplosionFalloffImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 星翼折跃（astd_rift_shift，星翼-级舰船系统）。
 * 切入延迟后沿运动方向做 easeInOutExpo 曲线位移（位移期间锁定转向，不改相位、不改时流），落点产生冲击；
 * 现身结算净空力场、星羽齐射、主炮瞬装、软幅排散与超翼过载。
 * 透支折跃的充能池逻辑由 [cn.kasuminova.astd.combat.hullmods.arc.ASTDImaginaryWingsHullMod] 管理。
 */
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
            // 系统被 EMP 等打断导致 OUT 结算未执行时，透支标记不能留到下一次折跃
            ImaginaryCharge.of(ship).overdraftActive = false
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
        // unapply 是残骸化、脚本移除等旁路的唯一回收点，透支标记必须在这里同步清空
        ImaginaryCharge.of(ship).overdraftActive = false
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

    // ---------- 位移 ----------

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
        // 冲击必须落在到达终点的同一帧：位移走完后立刻结算，不等 OUT 段那 0.1s 收束
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
        val eased = easeInOutExpo(progress)
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

        // 中途关停时只结算已经走完的那一段，不做满距离瞬移
        val end = travelPoint(shift, system, now)
        StarwingShiftVfx.trail(engine, ship, shift.lastPosition, end)
        ship.location.set(end)
        val exitSpeed = ship.mutableStats.maxSpeed.modifiedValue * EXIT_SPEED_MULT
        ship.velocity.set(shift.dir.x * exitSpeed, shift.dir.y * exitSpeed)

        clearPulse(engine, ship)
        StarwingShiftVfx.impact(engine, ship)
        impactStrike(engine, ship, end)
        resolveEmerge(engine, ship)
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

    private fun easeInOutExpo(t: Float): Float = when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        t < 0.5f -> 2f.pow(20f * t - 10f) / 2f
        else -> (2f - 2f.pow(-20f * t + 10f)) / 2f
    }

    // ---------- 落点冲击 ----------

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

    // ---------- 净空力场 ----------

    /** 抹除半径内单发伤害不超过阈值的敌方非导弹射弹，超过阈值的重型射弹永久削减伤害；并断开所有锁定本舰的敌方导弹诱导。 */
    private fun clearPulse(engine: CombatEngineAPI, ship: ShipAPI) {
        val reduction = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.CLEAR_HEAVY_REDUCTION, ship.owner == 0)
        val radius = ImaginaryWingsTuning.CLEAR_RADIUS
        for (proj in engine.projectiles.toList()) {
            if (proj is MissileAPI) continue
            if (proj.owner == ship.owner) continue
            if (MathUtils.getDistance(proj.location, ship.location) > radius + proj.collisionRadius) continue
            if (proj.damageAmount <= ImaginaryWingsTuning.CLEAR_DAMAGE_THRESHOLD) {
                engine.removeEntity(proj)
            } else {
                proj.setDamageAmount(proj.damageAmount * (1f - reduction))
            }
        }
        for (missile in engine.missiles) {
            if (missile.owner == ship.owner) continue
            val ai = missile.missileAI as? GuidedMissileAI ?: continue
            if (ai.target === ship) ai.setTarget(null)
        }
    }

    // ---------- 现身结算 ----------

    private fun resolveEmerge(engine: CombatEngineAPI, ship: ShipAPI) {
        val pool = ImaginaryCharge.of(ship)
        val isPlayer = ship.owner == 0

        if (pool.overdraftActive) {
            // 透支折跃跳过拉满与扣费，成本已在激活边沿结算
            pool.overdraftActive = false
        } else {
            pool.charge = ImaginaryWingsTuning.SALVO_REFILL
            pool.consume(ImaginaryWingsTuning.SALVO_COST)
        }

        val target = resolveSalvoTarget(engine, ship, pool)
        for ((left, right) in StarfallWingMotes.wingSlotPairs(ship)) {
            StarfallWingMotes.spawnFromSlot(engine, ship, left, target)
            StarfallWingMotes.spawnFromSlot(engine, ship, right, target)
        }

        val reload = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SHIFT_AMMO_RELOAD, isPlayer).toInt()
        for (weapon in ship.allWeapons) {
            if (weapon.id != StarfallWingTuning.WEAPON_ID) continue
            val tracker = weapon.ammoTracker ?: continue
            if (tracker.maxAmmo <= 0) continue
            tracker.setAmmo((tracker.ammo + reload).coerceAtMost(tracker.maxAmmo))
        }

        val softFlux = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SHIFT_SOFT_FLUX, isPlayer)
        ship.fluxTracker.decreaseFlux(softFlux)

        pool.overdriveRemaining = ImaginaryWingsTuning.OVERDRIVE_SECONDS
    }

    private fun resolveSalvoTarget(engine: CombatEngineAPI, ship: ShipAPI, pool: ImaginaryCharge): CombatEntityAPI? {
        fun valid(t: ShipAPI?): Boolean {
            if (t == null || t === ship) return false
            if (!engine.isEntityInPlay(t) || !t.isAlive || t.isHulk || t.isFighter || t.isDrone) return false
            return t.owner != ship.owner
        }
        if (valid(ship.shipTarget)) return ship.shipTarget
        if (valid(pool.lastMainGunHit)) return pool.lastMainGunHit
        // 光矢按点防定位运作，齐射同样沿用导弹 → 战机 → 舰船的接敌优先级
        return StarfallWingMoteAi.acquirePointDefenseTarget(engine, ship, ship.location)
    }

    companion object {
        private const val STATE_KEY_PREFIX = "astd_starwing_shift_state:"

        private const val EXIT_SPEED_MULT = 1.5f
    }
}
