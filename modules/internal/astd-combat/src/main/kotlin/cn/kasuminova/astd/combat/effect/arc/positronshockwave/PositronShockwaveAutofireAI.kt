package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil

class PositronShockwaveAutofireAI(
    private val weapon: WeaponAPI,
) : AutofireAIPlugin {

    private var shouldFire = false
    private var target: CombatEntityAPI? = null
    private val aimPoint = Vector2f()
    private var zeroSpeedWarned = false

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val ship = weapon.ship
        if (ship == null || !ship.isAlive || weapon.isDisabled) {
            disengage(engine)
            return
        }

        val weaponLoc = weapon.location ?: run {
            disengage(engine)
            return
        }
        val owner = ship.owner

        val inRange = CombatUtils.getEntitiesWithinRange(weaponLoc, weapon.range)
        val missiles = ArrayList<MissileAPI>()
        val fighters = ArrayList<ShipAPI>()
        for (e in inRange) {
            if (e == null || e.owner == owner) continue
            if (weapon.distanceFromArc(e.location) > ARC_EPS) continue
            when {
                e is MissileAPI -> if (!e.isFading && e.hitpoints > 0f) missiles += e
                e is ShipAPI && (e.isFighter || e.isDrone) && !e.isHulk -> fighters += e
            }
        }
        val pool: List<CombatEntityAPI> = if (missiles.isNotEmpty()) missiles else fighters
        if (pool.isEmpty()) {
            disengage(engine)
            return
        }

        val claims = PositronShockwaveFireControl.claimsOf(engine)
        val now = engine.getTotalElapsedTime(false)
        PositronShockwaveFireControl.purgeStale(claims, now)

        var best: CombatEntityAPI? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestFresh: CombatEntityAPI? = null
        var bestFreshScore = Float.NEGATIVE_INFINITY
        var prevScore = Float.NEGATIVE_INFINITY
        var prevFresh = false
        val prev = target
        for (candidate in pool) {
            val score = score(candidate, weaponLoc, owner, inRange)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            val needed = PositronShockwaveFireControl.neededWeapons(
                isMissile = candidate is MissileAPI,
                hitpoints = candidate.hitpoints,
            )
            val claimed = PositronShockwaveFireControl.freshClaimCount(claims, candidate, ship, weapon, now)
            val fresh = claimed < needed
            if (candidate === prev) {
                prevScore = score
                prevFresh = fresh
            }
            if (fresh && score > bestFreshScore) {
                bestFreshScore = score
                bestFresh = candidate
            }
        }

        val chosen = if (prev != null && prevScore > Float.NEGATIVE_INFINITY) {
            if (!prevFresh && bestFresh != null) {
                bestFresh
            } else {
                val challengerScore = if (prevFresh) bestFreshScore else bestScore
                if (challengerScore <= prevScore + TARGET_SWITCH_HYSTERESIS) prev else bestFresh ?: best
            }
        } else {
            bestFresh ?: best
        } ?: run {
            disengage(engine)
            return
        }

        claims[weapon] = PositronShockwaveFireControl.Claim(chosen, now)
        target = chosen
        updateAimPoint(chosen, ship, weaponLoc)
        shouldFire = isAligned(chosen, weaponLoc)
    }

    // 原版语义下 shouldFire()=true 即立刻击发，开火前必须自行判定炮口对齐。
    private fun isAligned(targetEntity: CombatEntityAPI, weaponLoc: Vector2f): Boolean {
        if (weapon.distanceFromArc(aimPoint) > ARC_EPS) return false
        val dist = MathUtils.getDistance(weaponLoc, aimPoint)
        if (dist <= 0f) return true
        val aimAngle = Misc.getAngleInDegrees(weaponLoc, aimPoint)
        val diff = Misc.getAngleDiff(weapon.currAngle, aimAngle)
        return diff <= hitToleranceDeg(targetEntity.collisionRadius, dist)
    }

    private fun score(
        candidate: CombatEntityAPI,
        weaponLoc: Vector2f,
        owner: Int,
        inRange: List<CombatEntityAPI>,
    ): Float {
        val loc = candidate.location
        val dist = MathUtils.getDistance(weaponLoc, loc)
        val arcDev = abs(Misc.getAngleDiff(Misc.getAngleInDegrees(weaponLoc, loc), weapon.arcFacing))
        val density = swarmDensity(loc, owner, inRange)
        val hpThreat = if (candidate is MissileAPI) {
            candidate.hitpoints / PositronShockwaveDifficulty.PANEL_DAMAGE
        } else {
            0f
        }
        return density * DENSITY_WEIGHT + hpThreat * HP_WEIGHT - dist * DIST_WEIGHT - arcDev * ARC_WEIGHT
    }

    private fun swarmDensity(loc: Vector2f, owner: Int, inRange: List<CombatEntityAPI>): Int {
        var count = 0
        for (e in inRange) {
            if (e == null || e.owner == owner) continue
            val isSwarm = e is MissileAPI || (e as? ShipAPI)?.let { it.isFighter || it.isDrone } == true
            if (!isSwarm) continue
            if (MathUtils.getDistance(loc, e.location) <= DENSITY_RADIUS) count++
        }
        return count
    }

    private fun updateAimPoint(targetEntity: CombatEntityAPI, ship: ShipAPI, weaponLoc: Vector2f) {
        val speed = weapon.projectileSpeed
        if (speed.isNaN() || speed <= 0f) {
            if (!zeroSpeedWarned) {
                zeroSpeedWarned = true
                log.warn("正电子冲击波自动开火：weapon.projectileSpeed 非法（$speed），本武器退化为直瞄（一次性告警）")
            }
            aimPoint.set(targetEntity.location)
            return
        }
        val dist = MathUtils.getDistance(weaponLoc, targetEntity.location)
        val tof = dist / speed
        aimPoint.set(
            targetEntity.location.x + (targetEntity.velocity.x - ship.velocity.x) * tof,
            targetEntity.location.y + (targetEntity.velocity.y - ship.velocity.y) * tof,
        )
    }

    private fun disengage(engine: CombatEngineAPI) {
        PositronShockwaveFireControl.claimsOf(engine).remove(weapon)
        target = null
        shouldFire = false
    }

    override fun shouldFire(): Boolean = shouldFire

    override fun forceOff() {
        shouldFire = false
    }

    override fun getTarget(): Vector2f {
        val t = target ?: return MathUtils.getPointOnCircumference(weapon.location, TARGET_POINT_DISTANCE, weapon.currAngle)
        return Vector2f(aimPoint)
    }

    override fun getTargetShip(): ShipAPI? = target as? ShipAPI

    override fun getWeapon(): WeaponAPI = weapon

    override fun getTargetMissile(): MissileAPI? = target as? MissileAPI

    companion object {
        private val log = Global.getLogger(PositronShockwaveAutofireAI::class.java)

        private const val ARC_EPS = 0.5f

        private val DENSITY_RADIUS =
            PositronShockwaveDifficulty.CONE_RANGE.v2 * PositronShockwaveDifficulty.FUSE_RANGE_RATIO

        private const val DENSITY_WEIGHT = 100f
        private const val HP_WEIGHT = 50f
        private const val DIST_WEIGHT = 1f
        private const val ARC_WEIGHT = 2f

        private const val TARGET_SWITCH_HYSTERESIS = 75f

        internal const val MIN_HIT_TOLERANCE_DEG = 1.5f

        internal const val MAX_HIT_TOLERANCE_DEG = 6f

        internal const val HIT_MARGIN_SU = 10f

        private const val TARGET_POINT_DISTANCE = 100f

        internal fun hitToleranceDeg(collisionRadius: Float, dist: Float): Float {
            if (dist <= 0f) return MAX_HIT_TOLERANCE_DEG
            val deg = Math.toDegrees(atan((collisionRadius + HIT_MARGIN_SU) / dist).toDouble()).toFloat()
            return deg.coerceIn(MIN_HIT_TOLERANCE_DEG, MAX_HIT_TOLERANCE_DEG)
        }
    }
}

internal object PositronShockwaveFireControl {

    private const val CLAIMS_KEY = "astd_positron_af_claims"

    const val CLAIM_STALE_AFTER = 0.5f

    class Claim(val target: CombatEntityAPI, val time: Float)

    fun neededWeapons(isMissile: Boolean, hitpoints: Float): Int {
        if (!isMissile) return 1
        return ceil(hitpoints.coerceAtLeast(0f) / PositronShockwaveDifficulty.PANEL_DAMAGE)
            .toInt()
            .coerceAtLeast(1)
    }

    fun freshClaimCount(
        claims: Map<WeaponAPI, Claim>,
        target: CombatEntityAPI,
        ship: ShipAPI,
        self: WeaponAPI,
        now: Float,
    ): Int {
        var count = 0
        for ((w, claim) in claims) {
            if (w === self) continue
            if (claim.target !== target) continue
            if (now - claim.time > CLAIM_STALE_AFTER) continue
            if (w.ship !== ship) continue
            count++
        }
        return count
    }

    fun purgeStale(claims: MutableMap<WeaponAPI, Claim>, now: Float) {
        claims.values.removeIf { now - it.time > CLAIM_STALE_AFTER }
    }

    @Suppress("UNCHECKED_CAST")
    fun claimsOf(engine: CombatEngineAPI): MutableMap<WeaponAPI, Claim> =
        engine.customData.getOrPut(CLAIMS_KEY) { HashMap<WeaponAPI, Claim>() } as MutableMap<WeaponAPI, Claim>
}

object PositronShockwaveAutofireAiPicker {

    private const val WEAPON_ID = "astd_positron_shockwave"

    fun pick(weapon: WeaponAPI): PluginPick<AutofireAIPlugin>? {
        if (weapon.spec?.weaponId != WEAPON_ID) return null
        return PluginPick(PositronShockwaveAutofireAI(weapon), PickPriority.MOD_SPECIFIC)
    }
}
