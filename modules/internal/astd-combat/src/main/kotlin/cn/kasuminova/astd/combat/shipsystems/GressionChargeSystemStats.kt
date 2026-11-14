package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.combat.hullmods.arc.VectorBoostThrusterTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

class GressionChargeSystemStats : BaseShipSystemScript() {

    private val log = AstdLog.logger
    private val warnedSites = HashSet<String>()

    private class ChargeState(val collision: GressionCollisionTrigger) {
        var sprint = GressionChargeMath.Sprint()
        var lastLocation = Vector2f()
        var hasLastLocation = false
        var buffsApplied = false
        val trailSampler = GressionChargeVfx.TrailSampler()
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        if (ship == null) {
            logOnce("entity", "[ASTD] 贯虹突进 apply 收到非舰船实体，系统状态未推进（id=$id）")
            return
        }
        val engine = Global.getCombatEngine() ?: return
        if (ship.isHulk) {
            releaseAll(engine, ship, stats, id)
            return
        }
        if (engine.isPaused) return

        val key = stateKey(ship)
        if (state == ShipSystemStatsScript.State.IDLE || state == ShipSystemStatsScript.State.COOLDOWN) {
            releaseAll(engine, ship, stats, id)
            return
        }

        val system = ship.system
        if (system == null) {
            releaseAll(engine, ship, stats, id)
            return
        }
        val charge = engine.customData.getOrPut(key) {
            val listener = GressionCollisionTrigger(ship)
            ship.addListener(listener)
            ChargeState(listener)
        } as ChargeState
        val isPlayer = ship.owner == 0

        if (state == ShipSystemStatsScript.State.IN || state == ShipSystemStatsScript.State.ACTIVE) {
            GressionChargeVfx.updateWrap(engine, ship)
        }

        when (state) {
            ShipSystemStatsScript.State.IN -> {
                charge.sprint = GressionChargeMath.Sprint(phase = GressionChargeMath.Phase.CHARGING)
                rememberLocation(charge, ship)
            }
            ShipSystemStatsScript.State.ACTIVE -> sprintFrame(engine, ship, system, stats, id, charge, isPlayer)
            ShipSystemStatsScript.State.OUT -> finishIfPending(engine, ship, system, stats, id, charge, isPlayer)
            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        releaseAll(engine, ship, stats, id)
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val key = when (state) {
            ShipSystemStatsScript.State.IN -> "system.gression_charge.status.in"
            ShipSystemStatsScript.State.ACTIVE -> "system.gression_charge.status.active"
            ShipSystemStatsScript.State.OUT -> "system.gression_charge.status.out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, key], false)
    }

    private fun sprintFrame(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        stats: MutableShipStatsAPI,
        id: String,
        charge: ChargeState,
        isPlayer: Boolean,
    ) {
        if (charge.sprint.phase != GressionChargeMath.Phase.SPRINTING && !charge.sprint.settled) {
            charge.sprint = GressionChargeMath.beginSprint(charge.sprint)
            charge.collision.armed = true
        }
        if (!charge.buffsApplied && !charge.sprint.settled) {
            applyBuffs(engine, ship, stats, id, isPlayer)
            charge.buffsApplied = true
        }
        if (!charge.sprint.settled) {
            GressionChargeVfx.driveEngines(engine, ship)
            GressionChargeVfx.jitter(ship, id, 1f)
            if (!charge.collision.triggered) lockSprintSpeed(ship)
            if (charge.hasLastLocation) {
                GressionChargeVfx.trail(engine, ship, charge.lastLocation, ship.location, charge.trailSampler)
            }
        }

        val before = charge.sprint
        charge.sprint = GressionChargeMath.onCollision(before, charge.collision.triggered)
        if (!before.settled && charge.sprint.settled) {
            charge.collision.armed = false
            resolveImpact(engine, ship, isPlayer)
            releaseBuffs(engine, ship, stats, id)
            charge.buffsApplied = false
            system.deactivate()
        }
        rememberLocation(charge, ship)
    }

    private fun finishIfPending(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        stats: MutableShipStatsAPI,
        id: String,
        charge: ChargeState,
        isPlayer: Boolean,
    ) {
        if (charge.sprint.phase == GressionChargeMath.Phase.DONE && charge.sprint.settled) {
            releaseBuffs(engine, ship, stats, id)
            charge.buffsApplied = false
            return
        }
        val before = charge.sprint
        charge.sprint = GressionChargeMath.onSprintEnd(before)
        if (!before.settled && charge.sprint.settled) {
            charge.collision.armed = false
            resolveImpact(engine, ship, isPlayer)
        }
        releaseBuffs(engine, ship, stats, id)
        charge.buffsApplied = false
        if (system.state == ShipSystemAPI.SystemState.ACTIVE) {
            system.deactivate()
        }
    }

    private fun lockSprintSpeed(ship: ShipAPI) {
        if (ship.engineController?.isFlamedOut == true) return
        val cap = ship.maxSpeed
        if (cap.isNaN() || cap <= 0f) return
        val facing = Math.toRadians(ship.facing.toDouble())
        ship.velocity.set(cos(facing).toFloat() * cap, sin(facing).toFloat() * cap)
    }

    private fun applyBuffs(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        stats: MutableShipStatsAPI,
        id: String,
        isPlayer: Boolean,
    ) {
        val speed = GressionChargeTuning.speedPercent(DifficultyTuningImpl, isPlayer)
        stats.maxSpeed.modifyPercent(id, speed)
        stats.acceleration.modifyPercent(id, speed)
        stats.maxTurnRate.modifyMult(id, GressionChargeTuning.TURN_RETAIN)
        stats.turnAcceleration.modifyMult(id, GressionChargeTuning.TURN_RETAIN)

        val taken = GressionChargeTuning.damageTakenMult(DifficultyTuningImpl, isPlayer)
        stats.hullDamageTakenMult.modifyMult(id, taken)
        stats.armorDamageTakenMult.modifyMult(id, taken)
        stats.shieldDamageTakenMult.modifyMult(id, taken)
        stats.empDamageTakenMult.modifyMult(id, taken)

        val ownerKey = VectorBoostThrusterTuning.massOwnerKey(ship.id)
        engine.customData[ownerKey] = true
        val base = GressionChargeTuning.claimBaseMass(engine.customData, ship.id, ship.mass)
        if (base == null) {
            logOnce("mass-$ownerKey", "[ASTD] 贯虹突进无法缓存基准质量，本帧不写质量（ship=${ship.id}）")
            return
        }
        val multiplier = GressionChargeTuning.massMultiplier(
            DifficultyTuningImpl.valueFor(GressionChargeTuning.MASS_BONUS, isPlayer),
        )
        ship.mass = GressionChargeTuning.scaledMass(base, multiplier)
    }

    private fun releaseBuffs(engine: CombatEngineAPI, ship: ShipAPI, stats: MutableShipStatsAPI, id: String) {
        (engine.customData[stateKey(ship)] as? ChargeState)?.collision?.armed = false
        GressionChargeVfx.releaseEngines(engine, ship)
        stats.maxSpeed.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.maxTurnRate.unmodify(id)
        stats.turnAcceleration.unmodify(id)
        stats.hullDamageTakenMult.unmodify(id)
        stats.armorDamageTakenMult.unmodify(id)
        stats.shieldDamageTakenMult.unmodify(id)
        stats.empDamageTakenMult.unmodify(id)
        releaseMass(engine, ship)
    }

    private fun releaseMass(engine: CombatEngineAPI, ship: ShipAPI) {
        engine.customData.remove(VectorBoostThrusterTuning.massOwnerKey(ship.id))
        val base = engine.customData[VectorBoostThrusterTuning.massBaseKey(ship.id)] as? Float ?: return
        if (base > 0f) ship.mass = base
    }

    private fun releaseAll(engine: CombatEngineAPI, ship: ShipAPI, stats: MutableShipStatsAPI, id: String) {
        releaseBuffs(engine, ship, stats, id)
        val charge = engine.customData.remove(stateKey(ship)) as? ChargeState
        charge?.collision?.let(ship::removeListener)
    }

    private fun resolveImpact(engine: CombatEngineAPI, ship: ShipAPI, isPlayer: Boolean) {
        val origin = bowPoint(ship)
        val facing = Math.toRadians(ship.facing.toDouble())
        val direction = Vector2f(cos(facing).toFloat(), sin(facing).toFloat())
        val spec = ConeImpactSpec(
            origin = origin,
            direction = direction,
            halfAngleDeg = GressionChargeTuning.halfAngleDeg(
                DifficultyTuningImpl.valueFor(GressionChargeTuning.CONE_ARC_DEG, isPlayer),
            ),
            range = GressionChargeTuning.coneRange(DifficultyTuningImpl, isPlayer),
            damage = GressionChargeTuning.coneDamage(DifficultyTuningImpl, isPlayer),
            damageType = DamageType.ENERGY,
            source = ship,
            owner = ship.owner,
            filter = ConeTargetFilter { true },
            hitShips = true,
            hitFighters = true,
            hitMissiles = true,
        )
        GressionChargeVfx.spawnImpact(engine, origin, direction, spec.range, spec.halfAngleDeg)
        GressionImpactPush.spawn(engine, ship, spec)
    }

    private fun bowPoint(ship: ShipAPI): Vector2f {
        val facing = Math.toRadians(ship.facing.toDouble())
        val reach = ship.collisionRadius
        return Vector2f(
            ship.location.x + cos(facing).toFloat() * reach,
            ship.location.y + sin(facing).toFloat() * reach,
        )
    }

    private fun rememberLocation(charge: ChargeState, ship: ShipAPI) {
        charge.lastLocation.set(ship.location)
        charge.hasLastLocation = true
    }

    private fun stateKey(ship: ShipAPI): String = STATE_KEY_PREFIX + ship.id

    private fun logOnce(site: String, message: String) {
        if (warnedSites.add(site)) log.warn(message)
    }

    companion object {
        private const val STATE_KEY_PREFIX = "astd_gression_charge_state:"
    }
}
