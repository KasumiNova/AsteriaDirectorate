package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.renderer.effect.hullmods.ASTDNegentropyEdgeVfx
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.magiclib.subsystems.MagicSubsystemsManager
import org.magiclib.util.MagicLensFlare
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * astd_collapse_shift（坍缩折跃）
 *
 * 读取断熵弧刃共享充能池，激活时完成一次短程折跃，并提供 4s 激变窗口。
 */
open class CollapseShiftSystemStats : BaseShipSystemScript() {

    companion object {
        private const val BASE_SHIFT_DISTANCE = 800f
        private const val BONUS_SHIFT_DISTANCE = 1600f
        private const val SHIFT_DELAY_SEC = 0.25f
        private const val SAFE_EXTRA_RANGE_MULT = 0.3f
        private const val INVULNERABLE_SEC = 0.3f
        private const val WINDOW_SEC = 4f

        private const val MAX_ROF_BONUS = 0.30f
        private const val MAX_DAMAGE_BONUS = 0.20f
        private const val RECOIL_MULT = 0.8f
        private const val PENDING_KEY = "astd_collapse_shift_pending"
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val engine = Global.getCombatEngine() ?: return
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return

        val shipKey = System.identityHashCode(ship)
        val triggerKey = "astd_collapse_shift_trigger:$shipKey"
        if (state == ShipSystemStatsScript.State.IN && engine.customData[triggerKey] != true) {
            engine.customData[triggerKey] = true
            onActivate(ship, engine)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val engine = Global.getCombatEngine() ?: return
        val ship = stats.entity as? ShipAPI ?: return
        val shipKey = System.identityHashCode(ship)
        engine.customData["astd_collapse_shift_trigger:$shipKey"] = null
    }

    private fun onActivate(ship: ShipAPI, engine: CombatEngineAPI) {
        val shipKey = System.identityHashCode(ship)
        val chargeRatio = ASTDNegentropyEdgeState.consumeCharge(ship)
        val shiftDistance = BASE_SHIFT_DISTANCE + BONUS_SHIFT_DISTANCE * chargeRatio
        val from = Vector2f(ship.location)
        val desired = desiredDestination(ship, shiftDistance)
        val safe = findSafeDestination(engine, ship, from, desired, shiftDistance) ?: run {
            ship.system?.deactivate()
            return
        }

        spawnPreShiftVfx(engine, ship, from, chargeRatio)
        engine.customData[PENDING_KEY + ":$shipKey"] = PendingShift(ship, from, safe, chargeRatio, SHIFT_DELAY_SEC)
        ensurePendingPlugin(engine)

        ASTDNegentropyEdgeState.setCollapseWindow(ship, WINDOW_SEC)
        ASTDNegentropyEdgeState.markShiftOrigin(ship)

        val invulnEndKey = "astd_collapse_shift_invuln_end:$shipKey"
        val windowEndKey = "astd_collapse_shift_window_end:$shipKey"
        val cleanupKey = "astd_collapse_shift_window_cleanup:$shipKey"
        val modId = "astd_collapse_shift_window_$shipKey"

        val now = engine.getTotalElapsedTime(false)
        engine.customData[invulnEndKey] = now + SHIFT_DELAY_SEC + INVULNERABLE_SEC
        engine.customData[windowEndKey] = now + WINDOW_SEC

        val rofMult = 1f + MAX_ROF_BONUS * chargeRatio
        val damageMult = 1f + MAX_DAMAGE_BONUS * chargeRatio

        ship.mutableStats.ballisticRoFMult.modifyMult(modId, rofMult)
        ship.mutableStats.energyRoFMult.modifyMult(modId, rofMult)
        ship.mutableStats.missileRoFMult.modifyMult(modId, rofMult)
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(modId, damageMult)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(modId, damageMult)
        ship.mutableStats.missileWeaponDamageMult.modifyMult(modId, damageMult)
        ship.mutableStats.maxRecoilMult.modifyMult(modId, RECOIL_MULT)
        ship.mutableStats.recoilPerShotMult.modifyMult(modId, RECOIL_MULT)

        if (engine.customData[cleanupKey] == true) return
        engine.customData[cleanupKey] = true

        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return

                val t = engine.getTotalElapsedTime(false)
                val invulnEnd = engine.customData[invulnEndKey] as? Float ?: 0f
                val windowEnd = engine.customData[windowEndKey] as? Float ?: 0f

                if (t < invulnEnd && !ship.isHulk) {
                    ship.mutableStats.hullDamageTakenMult.modifyMult(modId, 0f)
                    ship.mutableStats.armorDamageTakenMult.modifyMult(modId, 0f)
                    ship.mutableStats.shieldDamageTakenMult.modifyMult(modId, 0f)
                    ship.mutableStats.empDamageTakenMult.modifyMult(modId, 0f)
                } else {
                    ship.mutableStats.hullDamageTakenMult.unmodify(modId)
                    ship.mutableStats.armorDamageTakenMult.unmodify(modId)
                    ship.mutableStats.shieldDamageTakenMult.unmodify(modId)
                    ship.mutableStats.empDamageTakenMult.unmodify(modId)
                }

                if (t >= windowEnd || ship.isHulk) {
                    ship.mutableStats.hullDamageTakenMult.unmodify(modId)
                    ship.mutableStats.armorDamageTakenMult.unmodify(modId)
                    ship.mutableStats.shieldDamageTakenMult.unmodify(modId)
                    ship.mutableStats.empDamageTakenMult.unmodify(modId)
                    ship.mutableStats.ballisticRoFMult.unmodify(modId)
                    ship.mutableStats.energyRoFMult.unmodify(modId)
                    ship.mutableStats.missileRoFMult.unmodify(modId)
                    ship.mutableStats.ballisticWeaponDamageMult.unmodify(modId)
                    ship.mutableStats.energyWeaponDamageMult.unmodify(modId)
                    ship.mutableStats.missileWeaponDamageMult.unmodify(modId)
                    ship.mutableStats.maxRecoilMult.unmodify(modId)
                    ship.mutableStats.recoilPerShotMult.unmodify(modId)

                    engine.customData[invulnEndKey] = null
                    engine.customData[windowEndKey] = null
                    engine.customData[cleanupKey] = null
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun performShift(engine: CombatEngineAPI, ship: ShipAPI, from: Vector2f, to: Vector2f, chargeRatio: Float) {
        val oldFacing = ship.facing
        val oldVelocity = Vector2f(ship.velocity)
        val oldAngularVelocity = ship.angularVelocity
        val escorts = collectEscortCraft(engine, ship)
        prepareDroneSubsystems(ship)
        ship.location.set(to)
        turnToAimingTarget(engine, ship)
        alignMomentumToFacing(ship, oldFacing, oldVelocity, oldAngularVelocity)
        ship.shield?.let { shield -> if (shield.isOn) shield.toggleOn() }
        spawnPostShiftVfx(engine, ship, from, to, chargeRatio)
        scheduleEscortShift(engine, ship, escorts, to, oldFacing)
        scheduleDroneSubsystemSync(engine, ship)
    }

    private fun desiredDestination(ship: ShipAPI, distance: Float): Vector2f {
        val target = aiTargetCoords(ship)
            ?: try { ship.mouseTarget } catch (_: Throwable) { null }
            ?: try { ship.shipTarget?.location } catch (_: Throwable) { null }
        if (target != null) {
            val delta = Vector2f.sub(target, ship.location, null)
            val targetDistance = delta.length()
            if (targetDistance > 32f) {
                delta.normalise()
                val actualDistance = targetDistance.coerceAtMost(distance)
                return Vector2f(ship.location.x + delta.x * actualDistance, ship.location.y + delta.y * actualDistance)
            }
        }
        val dir = facingDirection(ship)
        return Vector2f(ship.location.x + dir.x * distance, ship.location.y + dir.y * distance)
    }

    private fun aiTargetCoords(ship: ShipAPI): Vector2f? {
        if (ship.shipAI == null) return null
        val flags = ship.aiFlags ?: return null
        if (!flags.hasFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS)) return null
        return try { flags.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS) as? Vector2f } catch (_: Throwable) { null }
    }

    private fun facingDirection(ship: ShipAPI): Vector2f {
        val angleRad = Math.toRadians(ship.facing.toDouble())
        return Vector2f(kotlin.math.cos(angleRad).toFloat(), kotlin.math.sin(angleRad).toFloat())
    }

    private fun turnToAimingTarget(engine: CombatEngineAPI, ship: ShipAPI) {
        val target = nearestEnemyInWeaponArc(engine, ship)?.location
            ?: aiFacingTarget(ship)?.location
            ?: try { ship.mouseTarget } catch (_: Throwable) { null }
            ?: try { ship.shipTarget?.location } catch (_: Throwable) { null }
            ?: nearestEnemy(engine, ship)?.location
            ?: return
        if (MathUtils.getDistance(ship.location, target) <= 16f) return
        ship.facing = VectorUtils.getAngle(ship.location, target)
    }

    private fun nearestEnemyInWeaponArc(engine: CombatEngineAPI, ship: ShipAPI): ShipAPI? {
        var best: ShipAPI? = null
        var bestDistance = Float.MAX_VALUE
        for (other in engine.ships) {
            if (other === ship || other.owner == ship.owner || !other.isAlive || other.isHulk || other.isFighter) continue
            if (!isInAnyWeaponArc(ship, other)) continue
            val distance = MathUtils.getDistance(ship.location, other.location)
            if (distance < bestDistance) {
                best = other
                bestDistance = distance
            }
        }
        return best
    }

    private fun isInAnyWeaponArc(ship: ShipAPI, target: ShipAPI): Boolean {
        for (weapon in ship.allWeapons) {
            try {
                if (weapon.isDecorative || weapon.spec?.type?.name == "SYSTEM") continue
                if (weapon.range <= 0f || MathUtils.getDistance(weapon.location, target.location) > weapon.range + target.collisionRadius) continue
                if (weapon.distanceFromArc(target.location) <= target.collisionRadius + 24f) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun aiFacingTarget(ship: ShipAPI): ShipAPI? {
        if (ship.shipAI == null) return null
        val flags = ship.aiFlags ?: return null
        if (!flags.hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) return null
        return try { flags.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM) as? ShipAPI } catch (_: Throwable) { null }
    }

    private fun nearestEnemy(engine: CombatEngineAPI, ship: ShipAPI): ShipAPI? {
        var best: ShipAPI? = null
        var bestDistance = Float.MAX_VALUE
        for (other in engine.ships) {
            if (other === ship || other.owner == ship.owner || !other.isAlive || other.isHulk) continue
            val distance = MathUtils.getDistance(ship.location, other.location)
            if (distance < bestDistance) {
                best = other
                bestDistance = distance
            }
        }
        return best
    }

    private fun alignMomentumToFacing(ship: ShipAPI, oldFacing: Float, oldVelocity: Vector2f, oldAngularVelocity: Float) {
        val rotation = Misc.getAngleDiff(oldFacing, ship.facing)
        val rotatedVelocity = VectorUtils.rotate(oldVelocity, rotation, Vector2f())
        ship.velocity.set(rotatedVelocity)
        if (abs(oldAngularVelocity) > 0.01f) {
            ship.angularVelocity = abs(oldAngularVelocity) * turnSign(oldFacing, ship.facing)
        }
    }

    private fun turnSign(from: Float, to: Float): Float {
        val delta = ((to - from + 540f) % 360f) - 180f
        return if (delta >= 0f) 1f else -1f
    }

    private fun findSafeDestination(engine: CombatEngineAPI, ship: ShipAPI, from: Vector2f, desired: Vector2f, distance: Float): Vector2f? {
        if (isSafe(engine, ship, desired)) return desired
        val dir = Vector2f.sub(desired, from, null)
        if (dir.lengthSquared() <= 1f) return null
        dir.normalise()
        val perp = Vector2f(-dir.y, dir.x)
        val extra = distance * SAFE_EXTRA_RANGE_MULT
        val candidates = ArrayList<Vector2f>(48)
        for (step in 1..5) {
            val lateral = extra * step / 5f
            candidates += Vector2f(desired.x + perp.x * lateral, desired.y + perp.y * lateral)
            candidates += Vector2f(desired.x - perp.x * lateral, desired.y - perp.y * lateral)
        }
        for (step in 1..4) {
            val back = extra * step / 4f
            candidates += Vector2f(desired.x - dir.x * back, desired.y - dir.y * back)
        }
        for (ring in 1..3) {
            val radius = extra * ring / 3f
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30f).toDouble())
                candidates += Vector2f(desired.x + cos(a).toFloat() * radius, desired.y + sin(a).toFloat() * radius)
            }
        }
        return candidates.firstOrNull { isSafe(engine, ship, it) }
    }

    private fun isSafe(engine: CombatEngineAPI, ship: ShipAPI, loc: Vector2f): Boolean {
        val radius = ship.collisionRadius + 24f
        for (other in engine.ships) {
            if (other === ship || !other.isAlive || other.isHulk) continue
            val minDist = radius + other.collisionRadius
            if (MathUtils.getDistance(loc, other.location) < minDist) return false
        }
        return true
    }

    private fun ensurePendingPlugin(engine: CombatEngineAPI) {
        val key = "astd_collapse_shift_pending_plugin"
        if (engine.customData[key] == true) return
        engine.customData[key] = true
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused || amount <= 0f) return
                val entries = engine.customData.entries.filter { it.key.startsWith(PENDING_KEY) }.toList()
                for ((key, value) in entries) {
                    val pending = value as? PendingShift ?: continue
                    pending.delay -= amount
                    if (pending.delay > 0f && pending.ship.isAlive && !pending.ship.isHulk) continue
                    engine.customData[key] = null
                    if (pending.ship.isAlive && !pending.ship.isHulk) {
                        performShift(engine, pending.ship, pending.from, pending.to, pending.chargeRatio)
                    }
                }
                if (engine.customData.keys.none { it.startsWith(PENDING_KEY) }) {
                    engine.customData[key] = null
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun spawnPreShiftVfx(engine: CombatEngineAPI, ship: ShipAPI, loc: Vector2f, level: Float) {
        ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(engine, loc, 0.75f + 0.25f * level)
        spawnOutwardArcBurst(engine, ship, 0.75f + 0.25f * level)
    }

    private fun spawnPostShiftVfx(engine: CombatEngineAPI, ship: ShipAPI, from: Vector2f, to: Vector2f, level: Float) {
        spawnArc(engine, from, null, to, null, 24f, level)
        val delta = Vector2f.sub(to, from, null)
        val len = delta.length()
        if (len > 1f) {
            val facing = VectorUtils.getFacing(delta)
            val steps = (len / 70f).toInt().coerceIn(6, 18)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val loc = Vector2f(from.x + delta.x * t, from.y + delta.y * t)
                val side = if (i % 2 == 0) 1f else -1f
                val off = MathUtils.getPointOnCircumference(loc, MathUtils.getRandomNumberInRange(10f, 48f) * side, facing + 90f)
                spawnLensFlare(engine, ship, off, 0.65f + 0.35f * level)
            }
        }
        ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(engine, to, 1f)
        spawnLensFlare(engine, ship, to, 1f)
        ship.setJitter("astd_collapse_shift", Color(120, 220, 255, 120), 0.35f, 8, 2f, 12f)
    }

    private fun spawnArc(engine: CombatEngineAPI, from: Vector2f, fromEntity: CombatEntityAPI?, to: Vector2f, toEntity: CombatEntityAPI?, width: Float, level: Float) {
        val params = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 7f
            zigZagReductionFactor = 0.10f
            fadeOutDist = 72f
            minFadeOutMult = 8f
            flickerRateMult = 0.24f
            movementDurOverride = 0.2f
            movementDurMin = 0.18f
            movementDurMax = 0.28f
            brightSpotFullFraction = 0.55f
            brightSpotFadeFraction = 0.55f
        }
        try {
            val arc = engine.spawnEmpArcVisual(from, fromEntity, to, toEntity, width, Color(100, 205, 255, 210), Color(245, 252, 255, 240), params)
            arc.setFadedOutAtStart(true)
            arc.setSingleFlickerMode(true)
            arc.setCoreWidthOverride(width * (0.36f + 0.18f * level))
        } catch (_: Throwable) {
        }
    }

    private fun spawnOutwardArcBurst(engine: CombatEngineAPI, ship: ShipAPI, level: Float) {
        val s = level.coerceIn(0f, 1f)
        val arcFringe = Color(100, 205, 255, (170f + 35f * s).toInt().coerceIn(0, 255))
        val arcCore = Color(245, 252, 255, (175f + 45f * s).toInt().coerceIn(0, 255))
        val count = (7f + 3f * s).toInt()
        repeat(count) {
            val from = hullBoundaryPoint(ship)
            val outAngle = Misc.getAngleInDegrees(ship.location, from) + MathUtils.getRandomNumberInRange(-18f, 18f)
            val outDist = ship.collisionRadius * MathUtils.getRandomNumberInRange(0.22f, 0.46f)
            val rad = Math.toRadians(outAngle.toDouble())
            val to = Vector2f(
                from.x + (cos(rad) * outDist).toFloat(),
                from.y + (sin(rad) * outDist).toFloat(),
            )
            val params = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 6f
                zigZagReductionFactor = 0.12f
                fadeOutDist = 40f
                minFadeOutMult = 8f
                flickerRateMult = 0.32f
                movementDurOverride = 0.18f
                movementDurMin = 0.14f
                movementDurMax = 0.24f
            }
            val thickness = 3.8f + MathUtils.getRandomNumberInRange(0f, 2.4f) + 1.2f * s
            try {
                val arc = engine.spawnEmpArcVisual(from, null, to, null, thickness, arcFringe, arcCore, params)
                arc.setCoreWidthOverride(thickness * 0.46f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
                arc.setFadedOutAtStart(true)
            } catch (_: Throwable) {
            }
            if (it % 3 == 0) spawnLensFlare(engine, ship, to, 0.55f + 0.35f * s)
        }
    }

    private fun collectEscortCraft(engine: CombatEngineAPI, carrier: ShipAPI): List<EscortShift> {
        val out = ArrayList<EscortShift>()
        for (candidate in engine.ships) {
            val escort = candidate as? ShipAPI ?: continue
            if (escort === carrier || escort.owner != carrier.owner || escort.isHulk || !escort.isAlive) continue
            if (!escort.isFighter && !escort.isDrone) continue
            if (!isCraftFromCarrier(escort, carrier)) continue
            out += EscortShift(escort, Vector2f.sub(escort.location, carrier.location, null), escort.facing, Vector2f(escort.velocity), escort.angularVelocity)
        }
        return out
    }

    private fun isCraftFromCarrier(craft: ShipAPI, carrier: ShipAPI): Boolean {
        val wingSource = try { craft.wing?.sourceShip } catch (_: Throwable) { null }
        if (wingSource === carrier) return true
        val mothership = try { craft.aiFlags?.getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP) as? ShipAPI } catch (_: Throwable) { null }
        return mothership === carrier
    }

    private fun scheduleEscortShift(engine: CombatEngineAPI, carrier: ShipAPI, escorts: List<EscortShift>, to: Vector2f, oldFacing: Float) {
        if (escorts.isEmpty()) return
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var done = false

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (done) return
                if (engine.isPaused) return
                done = true
                if (engine.isEntityInPlay(carrier) && carrier.isAlive && !carrier.isHulk) {
                    shiftEscortCraft(engine, carrier, escorts, to, oldFacing)
                }
                engine.removePlugin(this)
            }
        })
    }

    private fun prepareDroneSubsystems(ship: ShipAPI) {
        try {
            val subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship) ?: return
            for (subsystem in subsystems) {
                (subsystem as? ASTDNegentropyEdgeDroneSubsystem)?.prepareForCollapseShift()
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleDroneSubsystemSync(engine: CombatEngineAPI, ship: ShipAPI) {
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var done = false

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (done) return
                if (engine.isPaused) return
                done = true
                if (engine.isEntityInPlay(ship) && ship.isAlive && !ship.isHulk) {
                    try {
                        val subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship) ?: return
                        for (subsystem in subsystems) {
                            (subsystem as? ASTDNegentropyEdgeDroneSubsystem)?.syncDronesAfterCollapseShift()
                        }
                    } catch (_: Throwable) {
                    }
                }
                engine.removePlugin(this)
            }
        })
    }

    private fun shiftEscortCraft(engine: CombatEngineAPI, carrier: ShipAPI, escorts: List<EscortShift>, to: Vector2f, oldFacing: Float) {
        if (escorts.isEmpty()) return
        val deltaFacing = Misc.getAngleDiff(oldFacing, carrier.facing)
        val shifted = HashSet<ShipAPI>()
        for (entry in escorts) {
            val craft = entry.ship
            if (!shifted.add(craft)) continue
            if (!engine.isEntityInPlay(craft) || craft.isHulk || !craft.isAlive) continue
            val rotatedOffset = VectorUtils.rotate(entry.offset, deltaFacing, Vector2f())
            val oldCraftLoc = Vector2f(craft.location)
            val newCraftLoc = Vector2f(to.x + rotatedOffset.x, to.y + rotatedOffset.y)
            craft.location.set(newCraftLoc)
            craft.facing = normalizeAngle(entry.facing + deltaFacing)
            craft.velocity.set(VectorUtils.rotate(entry.velocity, deltaFacing, Vector2f()))
            craft.angularVelocity = entry.angularVelocity
            ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(engine, oldCraftLoc, 0.35f)
            ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(engine, newCraftLoc, 0.45f)
        }
    }

    private fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = try { ship.exactBounds } catch (_: Throwable) { null }
        if (bounds != null) {
            try {
                bounds.update(ship.location, ship.facing)
                val segments = bounds.segments
                if (segments.isNotEmpty()) {
                    val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                    val t = MathUtils.getRandomNumberInRange(0f, 1f)
                    return Vector2f(
                        seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                        seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                    )
                }
            } catch (_: Throwable) {
            }
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 0.90f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    private fun spawnLensFlare(engine: CombatEngineAPI, ship: ShipAPI, loc: Vector2f, level: Float) {
        val s = level.coerceIn(0f, 1f)
        val fringe = Color(100, 205, 255, (150f + 60f * s).toInt().coerceIn(0, 255))
        val core = Color(245, 252, 255, (190f + 45f * s).toInt().coerceIn(0, 255))
        try {
            MagicLensFlare.createSharpFlare(
                engine,
                ship,
                loc,
                3.2f + 2.4f * s,
                34f + 54f * s,
                MathUtils.getRandomNumberInRange(0f, 360f),
                fringe,
                core,
            )
        } catch (_: Throwable) {
        }
    }

    private data class PendingShift(
        val ship: ShipAPI,
        val from: Vector2f,
        val to: Vector2f,
        val chargeRatio: Float,
        var delay: Float,
    )

    private data class EscortShift(
        val ship: ShipAPI,
        val offset: Vector2f,
        val facing: Float,
        val velocity: Vector2f,
        val angularVelocity: Float,
    )

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        return when (index) {
            0 -> ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.collapse_shift.status.0"], false)
            1 -> ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.collapse_shift.status.1"], false)
            else -> null
        }
    }
}

/** 旧 `.system` 文件的兼容类引用，供写回前的 class ref 校验通过。 */
class OverloadSpikeSystemStats : CollapseShiftSystemStats()
