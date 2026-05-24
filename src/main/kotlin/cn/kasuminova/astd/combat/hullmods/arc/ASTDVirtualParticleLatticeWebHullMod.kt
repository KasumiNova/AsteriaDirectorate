package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.renderer.effect.hullmods.ASTDNegentropyEdgeVfx
import cn.kasuminova.astd.combat.shipsystems.ASTDNegentropyEdgeState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ASTDVirtualParticleLatticeWebHullMod : BaseHullMod() {

    companion object {
        private const val BASE_THRESHOLD = 800f
        private const val CHARGE_PER_GROUP = 0.085f
        private const val DEFENSIVE_GROUP_SIZE = 1
        private const val FREE_GROUP_SIZE = 2
        private const val DEFENSIVE_MIN = 6
        private const val DEFENSIVE_MAX = 18
        private const val FREE_MAX = 24
        private const val MAX_PENDING_GROUPS = 18
        private const val MAX_GROUPS_PER_FRAME = 3
        private const val DEFENSIVE_STRIKE_RANGE = 540f
        private const val DEFENSIVE_ORBIT_BASE_OFFSET = 72f
        private const val DEFENSIVE_ORBIT_RING_STEP = 24f
        private const val PURSUIT_LAUNCH_SPEED = 440f
        private const val PURSUIT_SPEED = 1800f
        private const val PURSUIT_TARGET_REFRESH_RANGE = 2200f
        private const val MOD_ID = "astd_virtual_particle_lattice_web"
        const val PURSUIT_MISSILE_WEAPON_ID = "astd_virtual_particle_mote_launcher"
        const val PURSUIT_MISSILE_PROJECTILE_ID = "astd_virtual_particle_mote"
        const val PURSUIT_MISSILE_DAMAGE = 150f

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(184, 236, 255),
            borderColor = Color(96, 194, 255),
            headerBackground = Color(18, 54, 86, 190),
            sectionBackground = Color(10, 34, 58, 130),
            accentColor = Color(96, 178, 240),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (variant.hullSpec?.hullId != ASTDNegentropyEdgeState.HULL_ID) return
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ASTDNegentropyEdgeState.isNegentropyEdge(ship) || ship.isHulk) return
        if (!ship.hasListenerOfClass(ASTDVirtualParticleListener::class.java)) {
            ship.addListener(ASTDVirtualParticleListener(ship))
        }
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.lattice.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.lattice.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.lattice.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.lattice.line.3"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.lattice.line.4"),
            ),
            starTrails = false,
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ASTDNegentropyEdgeState.isNegentropyEdge(ship)

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private class ASTDVirtualParticleListener(private val ship: ShipAPI) : DamageDealtModifier, AdvanceableListener {
        private var accumulatedDamage = 0f
        private var pendingGroups = 0
        private var pendingPoint: Vector2f? = null
        private var suppressCounter = false
        private val defensive = ArrayList<DefensiveParticle>()
        private val free = ArrayList<FreeParticle>()

        override fun modifyDamageDealt(param: Any?, target: CombatEntityAPI?, damage: DamageAPI, point: Vector2f?, shieldHit: Boolean): String? {
            if (target == null || point == null || target.owner == ship.owner) return null
            if (!isShipWeaponDamage(param)) return null
            if (suppressCounter || isLatticeGeneratedDamage(param)) return null
            val appliedDamage = appliedDamageForCounter(param, damage)
            if (appliedDamage <= 0f) return null
            accumulatedDamage += appliedDamage * damageCounterMult(param)
            val threshold = if (ASTDNegentropyEdgeState.isThresholdHalved(ship)) BASE_THRESHOLD * 0.5f else BASE_THRESHOLD
            var groups = 0
            while (accumulatedDamage >= threshold && groups < 12) {
                accumulatedDamage -= threshold
                groups++
            }
            if (groups > 0) queueGroups(groups, point)
            return null
        }

        private fun isShipWeaponDamage(param: Any?): Boolean = when (param) {
            is DamagingProjectileAPI -> param.source == ship && param.weapon?.ship == ship
            is BeamAPI -> param.source == ship && param.weapon?.ship == ship
            else -> false
        }

        private fun queueGroups(groups: Int, point: Vector2f) {
            pendingGroups = (pendingGroups + groups).coerceAtMost(MAX_PENDING_GROUPS)
            pendingPoint = Vector2f(point)
        }

        private fun isLatticeGeneratedDamage(param: Any?): Boolean {
            val projectile = param as? DamagingProjectileAPI ?: return false
            if (projectile.projectileSpecId == PURSUIT_MISSILE_PROJECTILE_ID) return true
            return projectile.weapon?.spec?.weaponId == PURSUIT_MISSILE_WEAPON_ID
        }

        private fun appliedDamageForCounter(param: Any?, damage: DamageAPI): Float {
            val amount = damage.damage.coerceAtLeast(0f)
            if (!damage.isDps) return amount
            val duration = damage.dpsDuration.coerceAtLeast(0f)
            if (duration <= 0f) return 0f
            val beam = param as? BeamAPI
            if (beam != null && !beam.didDamageThisFrame()) return 0f
            return amount * duration
        }

        private fun damageCounterMult(param: Any?): Float {
            val weaponId = when (param) {
                is DamagingProjectileAPI -> param.weapon?.spec?.weaponId
                is BeamAPI -> param.weapon?.spec?.weaponId
                else -> null
            }
            return if (weaponId == ASTDNegentropyEdgeState.SPC3_WEAPON_ID) 2f else 1f
        }

        override fun advance(amount: Float) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused || amount <= 0f || ship.isHulk || !ship.isAlive) return
            defensive.removeAll { !it.alive }
            free.removeAll { !it.alive }
            flushPendingGroups(engine)
            val defensiveSnapshot = defensive.toList()
            val freeSnapshot = free.toList()
            val previousSuppression = suppressCounter
            suppressCounter = true
            try {
                for (p in defensiveSnapshot) p.advance(engine, ship, amount)
            } finally {
                suppressCounter = previousSuppression
            }
            for (p in freeSnapshot) p.advance(engine, ship, amount)
            defensive.removeAll { !it.alive }
            free.removeAll { !it.alive }
        }

        private fun flushPendingGroups(engine: CombatEngineAPI) {
            if (pendingGroups <= 0) return
            val groups = min(pendingGroups, MAX_GROUPS_PER_FRAME)
            pendingGroups -= groups
            val point = pendingPoint ?: ship.location
            createGroups(engine, groups, Vector2f(point))
            if (pendingGroups <= 0) pendingPoint = null
        }

        private fun createGroups(engine: CombatEngineAPI, groups: Int, point: Vector2f) {
            val defensiveCap = defensiveCap()
            repeat(groups) {
                ASTDNegentropyEdgeState.addCharge(ship, CHARGE_PER_GROUP)
                for (i in 0 until DEFENSIVE_GROUP_SIZE) {
                    if (defensive.size < defensiveCap) defensive.add(DefensiveParticle())
                }
                for (i in 0 until FREE_GROUP_SIZE) {
                    if (free.size < FREE_MAX) spawnPursuitMote(engine, point)
                }
                ASTDNegentropyEdgeVfx.spawnParticleBirth(engine, launchLocation(ship), ASTDNegentropyEdgeState.getCharge(ship))
            }
        }

        private fun spawnPursuitMote(engine: CombatEngineAPI, targetHint: Vector2f) {
            val target = FreeParticle.nearestEnemyShip(engine, ship, targetHint, 1400f)
                ?: FreeParticle.nearestEnemyShip(engine, ship, ship.location, 1400f)
            val launchLoc = launchLocation(ship)
            val facing = target?.let { VectorUtils.getAngle(launchLoc, FreeParticle.impactPoint(it, launchLoc)) }
                ?: VectorUtils.getAngle(launchLoc, targetHint)
            val launchVelocity = MathUtils.getPointOnCircumference(Vector2f(ship.velocity), PURSUIT_LAUNCH_SPEED, facing)
            val spawned = try {
                engine.spawnProjectile(ship, null, PURSUIT_MISSILE_WEAPON_ID, launchLoc, facing, launchVelocity)
            } catch (_: Throwable) {
                null
            } as? MissileAPI ?: return
            spawned.source = ship
            spawned.missileAI = ASTDPursuitVirtualParticleAI(spawned, target)
            spawned.setEmpResistance(10000)
            spawned.setArmingTime(0f)
            spawned.setDamageAmount(PURSUIT_MISSILE_DAMAGE)
            spawned.maxFlightTime = 2.0f
            spawned.setNoGlowTime(999f)
            spawned.setNoFlameoutOnFizzling(true)
            spawned.interruptContrail()
            spawned.spriteAlphaOverride = 0f
            spawned.glowRadius = 0f
            ASTDNegentropyEdgeVfx.spawnPursuitDustMote(engine, spawned.location, 1f)
            ASTDNegentropyEdgeVfx.spawnPursuitDustTrail(engine, ship.location, spawned.location, 0.85f)
            free.add(FreeParticle(spawned))
        }

        private fun launchLocation(ship: ShipAPI): Vector2f {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val radius = ship.collisionRadius + MathUtils.getRandomNumberInRange(56f, 160f)
            return MathUtils.getPointOnCircumference(Vector2f(ship.location), radius, angle)
        }

        private fun defensiveCap(): Int {
            val k = 0.6f
            return (DEFENSIVE_MIN + ((DEFENSIVE_MAX - DEFENSIVE_MIN) * k.coerceIn(0f, 1f))).toInt().coerceIn(DEFENSIVE_MIN, DEFENSIVE_MAX)
        }

        private data class DefensiveParticle(
            val id: String = UUID.randomUUID().toString(),
            var energy: Float = 600f,
            var phase: Float = MathUtils.getRandomNumberInRange(0f, 360f),
            var alive: Boolean = true,
        ) {
            private val orbitRadiusOffset = MathUtils.getRandomNumberInRange(0f, DEFENSIVE_ORBIT_RING_STEP * 2f)
            private val orbitSpeed = MathUtils.getRandomNumberInRange(34f, 54f)
            private var age = 0f
            private var dying = false
            private var deathAge = 0f
            private var strikeCooldown = MathUtils.getRandomNumberInRange(0.02f, 0.18f)
            private var moteCooldown = MathUtils.getRandomNumberInRange(0f, 0.08f)
            private var lastLoc: Vector2f? = null

            fun advance(engine: CombatEngineAPI, ship: ShipAPI, amount: Float) {
                age += amount
                if (dying) {
                    deathAge += amount
                    if (deathAge >= 0.22f) alive = false
                } else if (energy <= 40f) {
                    dying = true
                    deathAge = 0f
                }
                phase += amount * orbitSpeed
                val loc = orbitLocation(ship)
                renderDust(engine, loc, amount)
                if (dying) return
                strikeCooldown -= amount
                if (strikeCooldown > 0f) return
                val target = nearestThreat(engine, ship, loc, DEFENSIVE_STRIKE_RANGE) ?: return
                strikeCooldown = 0.18f
                val damage = min(energy, 600f)
                engine.applyDamage(target, target.location, damage, DamageType.FRAGMENTATION, 0f, false, true, ship)
                spawnDefensiveArc(engine, loc, target, energy / 600f)
                energy -= if (target is MissileAPI) 180f else 600f
                if (energy <= 40f) {
                    dying = true
                    deathAge = 0f
                }
            }

            private fun visualLevel(): Float {
                val birth = (age / 0.24f).coerceIn(0f, 1f)
                val death = if (dying) (1f - deathAge / 0.22f).coerceIn(0f, 1f) else 1f
                return birth * death * (energy / 600f).coerceIn(0.25f, 1f)
            }

            private fun spawnDefensiveArc(engine: CombatEngineAPI, from: Vector2f, target: CombatEntityAPI, level: Float) {
                val params = EmpArcEntityAPI.EmpArcParams().apply {
                    segmentLengthMult = 5f
                    zigZagReductionFactor = 0.12f
                    fadeOutDist = 36f
                    minFadeOutMult = 7f
                    flickerRateMult = 0.42f
                }
                try {
                    val arc = engine.spawnEmpArcVisual(
                        from,
                        null,
                        Vector2f(target.location),
                        target,
                        6.5f + 5f * level.coerceIn(0f, 1f),
                        Color(92, 178, 255, 210),
                        Color(235, 252, 255, 235),
                        params,
                    )
                    arc.setCoreWidthOverride(2.2f + 2.4f * level.coerceIn(0f, 1f))
                    arc.setSingleFlickerMode(true)
                    arc.setRenderGlowAtStart(false)
                    arc.setFadedOutAtStart(true)
                } catch (_: Throwable) {
                }
                ASTDNegentropyEdgeVfx.spawnDefensiveArcImpact(engine, target.location, level)
            }

            private fun renderDust(engine: CombatEngineAPI, loc: Vector2f, amount: Float) {
                val prev = lastLoc
                val level = visualLevel()
                if (prev != null) {
                    ASTDNegentropyEdgeVfx.spawnDustTrail(engine, prev, loc, level)
                    ASTDNegentropyEdgeVfx.spawnTrackingDustMote(engine, loc, VectorUtils.getAngle(prev, loc), level)
                }
                lastLoc = Vector2f(loc)
                moteCooldown -= amount
                if (moteCooldown <= 0f) {
                    moteCooldown = MathUtils.getRandomNumberInRange(0.11f, 0.17f)
                    ASTDNegentropyEdgeVfx.spawnDustMote(engine, loc, level)
                }
            }

            private fun orbitLocation(ship: ShipAPI): Vector2f {
                val angle = Math.toRadians(phase.toDouble())
                val radius = ship.collisionRadius + DEFENSIVE_ORBIT_BASE_OFFSET + orbitRadiusOffset
                return Vector2f(
                    ship.location.x + cos(angle).toFloat() * radius,
                    ship.location.y + sin(angle).toFloat() * radius,
                )
            }

            private fun nearestThreat(engine: CombatEngineAPI, ship: ShipAPI, loc: Vector2f, range: Float): CombatEntityAPI? {
                var best: CombatEntityAPI? = null
                var bestDist = range
                for (m in engine.missiles) {
                    if (m.owner == ship.owner || m.isFading || m.isFizzling) continue
                    val d = MathUtils.getDistance(loc, m.location)
                    if (d < bestDist) { bestDist = d; best = m }
                }
                for (s in engine.ships) {
                    if (s.owner == ship.owner || !s.isAlive || !s.isFighter) continue
                    val d = MathUtils.getDistance(loc, s.location)
                    if (d < bestDist) { bestDist = d; best = s }
                }
                return best
            }
        }

        private data class FreeParticle(
            val missile: MissileAPI,
            var alive: Boolean = true,
        ) {
            private var fadeAge = 0f
            private var moteCooldown = MathUtils.getRandomNumberInRange(0f, 0.04f)
            fun advance(engine: CombatEngineAPI, ship: ShipAPI, amount: Float) {
                if (!engine.isMissileAlive(missile) || missile.isExpired) { alive = false; return }
                if (missile.isFading || missile.isFizzling) {
                    fadeAge += amount
                    if (fadeAge >= 0.18f) { alive = false; return }
                } else {
                    fadeAge = 0f
                }
                missile.interruptContrail()
                missile.spriteAlphaOverride = 0f
                missile.glowRadius = 0f
                val loc = missile.location
                val level = (1f - fadeAge / 0.18f).coerceIn(0.1f, 1f)
                val trailFrom = Vector2f.sub(loc, missile.velocity, null)
                if (missile.velocity.lengthSquared() > 1f) {
                    val scale = (amount * 1.2f).coerceIn(0.01f, 0.045f)
                    trailFrom.x = loc.x - missile.velocity.x * scale
                    trailFrom.y = loc.y - missile.velocity.y * scale
                    ASTDNegentropyEdgeVfx.spawnPursuitDustTrail(engine, trailFrom, loc, level)
                    ASTDNegentropyEdgeVfx.spawnPursuitTrackingDustMote(engine, loc, missile.facing, level)
                }
                moteCooldown -= amount
                if (moteCooldown <= 0f) {
                    moteCooldown = MathUtils.getRandomNumberInRange(0.11f, 0.17f)
                    ASTDNegentropyEdgeVfx.spawnPursuitDustMote(engine, loc, level)
                }
            }

            companion object {
                fun impactPoint(target: ShipAPI, from: Vector2f): Vector2f {
                    val radius = if (target.shield != null && target.shield.isOn) {
                        max(target.shield.radius, target.collisionRadius)
                    } else {
                        target.collisionRadius
                    }
                    val dir = Vector2f.sub(from, target.location, null)
                    if (dir.lengthSquared() <= 1f) return Vector2f(target.location)
                    dir.normalise()
                    dir.scale(radius + 8f)
                    return Vector2f.add(target.location, dir, null)
                }

                fun nearestEnemyShip(engine: CombatEngineAPI, ship: ShipAPI, loc: Vector2f, range: Float): ShipAPI? {
                    var best: ShipAPI? = null
                    var bestDist = range
                    for (s in engine.ships) {
                        if (s.owner == ship.owner || !s.isAlive || s.isFighter || s.isDrone) continue
                        val d = MathUtils.getDistance(loc, s.location)
                        if (d < bestDist) { bestDist = d; best = s }
                    }
                    return best
                }
            }
        }

        private class ASTDPursuitVirtualParticleAI(
            private val missile: MissileAPI,
            initialTarget: ShipAPI?,
        ) : MissileAIPlugin {
            private var target: ShipAPI? = initialTarget

            override fun advance(amount: Float) {
                val engine = Global.getCombatEngine() ?: return
                if (engine.isPaused || missile.isFading || missile.isExpired) return
                missile.interruptContrail()
                missile.spriteAlphaOverride = 0f
                missile.glowRadius = 0f
                val source = missile.source ?: return
                if (!isValidTarget(engine, target, source)) {
                    target = FreeParticle.nearestEnemyShip(engine, source, missile.location, PURSUIT_TARGET_REFRESH_RANGE)
                }
                val t = target
                if (t != null && isValidTarget(engine, t, source)) {
                    val aim = Vector2f(t.location)
                    val angleTo = VectorUtils.getAngle(missile.location, aim)
                    val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
                    missile.facing = angleTo
                    missile.angularVelocity = 0f
                    if (abs(diff) > 1.0f) missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
                    applyVelocityToward(amount, angleTo)
                }
                missile.giveCommand(ShipCommand.ACCELERATE)
            }

            private fun applyVelocityToward(amount: Float, facing: Float) {
                val desired = MathUtils.getPointOnCircumference(null, PURSUIT_SPEED, facing)
                val lerp = (amount * 14f).coerceIn(0f, 1f)
                missile.velocity.x += (desired.x - missile.velocity.x) * lerp
                missile.velocity.y += (desired.y - missile.velocity.y) * lerp
            }

            private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI?, source: ShipAPI): Boolean {
                if (t == null) return false
                if (!engine.isEntityInPlay(t)) return false
                if (!t.isAlive || t.isHulk || t.isFighter || t.isDrone) return false
                if (t.owner == source.owner) return false
                return true
            }
        }
    }
}
