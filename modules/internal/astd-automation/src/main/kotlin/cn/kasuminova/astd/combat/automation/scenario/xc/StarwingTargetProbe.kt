package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AutomationEvidence
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingShot
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

internal class StarwingTargetProbe(
    private val ctx: AutomationCombatContext,
    private val player: ShipAPI,
    private val enemy: ShipAPI,
    private val weapon: WeaponAPI,
) {
    private enum class Stage { SHIP_PRIORITY, FIGHTER_PRIORITY, OUTSIDE_SHIP, TURN_COST, INTERCEPT_COST, EXCLUSIVE, RELEASE, EXHAUSTED, FIGHTER_AVOIDANCE, DONE }
    private class Sample(val projectile: DamagingProjectileAPI, val shot: StarfallWingShot)
    private class PendingBatch(
        val baseline: Set<DamagingProjectileAPI>,
        val origins: List<Vector2f>,
        val requestedFrame: Int,
        val requestedAt: Float,
    )
    private var frame = 0
    private var pendingBatch: PendingBatch? = null
    private val origin = Vector2f(-650f, 300f)
    private val range = weapon.range
    private val shipsBefore = ctx.engine.ships.toSet()
    private val secondShip = ctx.engine.getFleetManager(enemy.owner).spawnShipOrWing("kite_Stock", Vector2f(), 180f, 0f)
    private val wingLead = ctx.engine.getFleetManager(enemy.owner).spawnShipOrWing("talon_wing", Vector2f(), 180f, 0f)
    private val spawnedShips = linkedSetOf(secondShip, wingLead)
    private var fighters: List<ShipAPI> = emptyList()
    private var initialized = false
    private val registrationStartedAt = ctx.elapsed
    private var registrationLoggedAt = -1f
    private val anchors = linkedMapOf<ShipAPI, Vector2f>()
    private val isolatedShips = linkedMapOf<ShipAPI, Boolean>()
    private val samples = ArrayList<Sample>()
    private var stage = Stage.SHIP_PRIORITY
    private var stageStartedAt = ctx.elapsed
    private var armed = false
    private var avoidanceFrames = 0
    private var maximumAvoidanceAngle = 0f
    private var releasedFighter: ShipAPI? = null
    private var closed = false
    var passedStages = 0
        private set
    val stageName: String get() = if (initialized) stage.name else "REGISTERING"

    init {
        ctx.log.info("[ASTD-Automation] XC2_TARGET_SPAWN_REQUESTED second=${id(secondShip)} lead=${id(wingLead)}")
    }

    fun advance(): Boolean {
        frame++
        if (ctx.failureReason != null) return false
        AutomationEvidence.stabilizeShip(player, Vector2f(-1000f, 300f), 0f, false)
        player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
        player.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
        weapon.setForceNoFireOneFrame(true)
        if (!initialized && !registerShips()) return false
        for (ship in ctx.engine.ships) {
            if (ship === player || ship in anchors) continue
            isolatedShips.putIfAbsent(ship, ship.isPhased)
            ship.isPhased = true
        }
        for (sample in samples) {
            if (!ctx.engine.isEntityInPlay(sample.projectile)) continue
            sample.projectile.location.set(origin)
            sample.projectile.velocity.set(0f, 0f)
            sample.projectile.facing = 0f
        }
        for ((ship, anchor) in anchors) {
            val velocity = if (stage == Stage.INTERCEPT_COST && ship === secondShip) Vector2f(2200f, 0f) else Vector2f()
            AutomationEvidence.stabilizeShip(ship, anchor, 180f, false)
            ship.velocity.set(velocity)
            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
            ship.shield?.toggleOff()
        }
        if (stage == Stage.DONE) return true
        if (pendingBatch != null) {
            collectBatch()?.let(::evaluateStage)
            return stage == Stage.DONE
        }
        if (!armed && ctx.elapsed - stageStartedAt >= 0.15f) {
            armed = true
            val count = when (stage) {
                Stage.EXCLUSIVE, Stage.EXHAUSTED -> 3
                Stage.FIGHTER_AVOIDANCE -> 2
                else -> 1
            }
            requestBatch(count)
        } else if (armed && stage == Stage.FIGHTER_AVOIDANCE && samples.isNotEmpty()) {
            val sample = samples.last()
            val angle = abs(sample.shot.avoidanceDeflection)
            maximumAvoidanceAngle = maxOf(maximumAvoidanceAngle, angle)
            avoidanceFrames++
            if (!expect(sample.shot.target === fighters[1], "fighter avoidance target changed target=${id(sample.shot.target)}")) return false
            if (!expect(angle <= 0.1f, "fighter avoidance deflection=$angle with fighter directly ahead")) return false
            if (avoidanceFrames >= 2) {
                ctx.log.info("[ASTD-Automation] XC2_FIGHTER_AVOIDANCE_RESULT frames=$avoidanceFrames maxAngle=$maximumAvoidanceAngle target=${id(sample.shot.target)} obstacle=${id(fighters[0])}")
                pass()
            } else {
                sample.projectile.location.set(origin)
                sample.projectile.facing = 0f
            }
        }
        if (ctx.elapsed - stageStartedAt > 4f) fail("stage timeout stage=$stage armed=$armed")
        return stage == Stage.DONE
    }

    private fun registerShips(): Boolean {
        if (!wingLead.isFighter) {
            fail("talon wing leader must be a fighter: lead=${id(wingLead)}")
            return false
        }
        val members = wingLead.wing?.wingMembers.orEmpty()
        spawnedShips += members
        spawnedShips += ctx.engine.ships.filter { it !in shipsBefore }
        val registered = members.filter { it.isFighter && ctx.engine.isEntityInPlay(it) && it in ctx.engine.ships }
        val secondRegistered = ctx.engine.isEntityInPlay(secondShip) && secondShip in ctx.engine.ships
        if (registered.size >= 2 && registered.size == members.size && secondRegistered) {
            fighters = registered.take(2)
            spawnedShips.filter { it !== secondShip && it !in fighters }.forEach(ctx.engine::removeEntity)
            initialized = true
            ctx.log.info("[ASTD-Automation] XC2_TARGET_REGISTERED elapsed=${ctx.elapsed - registrationStartedAt} members=${members.size} fighters=${fighters.map(::id)} second=${id(secondShip)}")
            setup()
            return true
        }
        if (registrationLoggedAt < 0f || ctx.elapsed - registrationLoggedAt >= 0.25f) {
            registrationLoggedAt = ctx.elapsed
            ctx.log.info("[ASTD-Automation] XC2_TARGET_REGISTERING elapsed=${ctx.elapsed - registrationStartedAt} members=${members.size} registered=${registered.size} secondRegistered=$secondRegistered")
        }
        if (ctx.elapsed - registrationStartedAt >= 2f) {
            fail("ship registration timeout members=${members.size} registered=${registered.size} secondRegistered=$secondRegistered")
        }
        return false
    }

    private fun setup() {
        anchors.clear()
        anchors[enemy] = Vector2f(origin.x + range + enemy.collisionRadius + 300f, origin.y)
        anchors[secondShip] = Vector2f(origin.x + range * 0.6f, origin.y + 100f)
        for ((index, fighter) in fighters.withIndex()) anchors[fighter] = Vector2f(origin.x + range * 0.45f, origin.y + index * 80f)
        enemy.isPhased = false
        secondShip.isPhased = true
        fighters.forEach { it.isPhased = true }
        when (stage) {
            Stage.SHIP_PRIORITY -> {
                anchors[enemy] = Vector2f(origin.x + range * 0.65f, origin.y)
                anchors[fighters[0]] = Vector2f(origin.x + range * 0.2f, origin.y + 120f)
                fighters[0].isPhased = false
            }
            Stage.FIGHTER_PRIORITY -> fighters[0].isPhased = false
            Stage.OUTSIDE_SHIP -> Unit
            Stage.TURN_COST -> {
                anchors[enemy] = Vector2f(origin.x + range * 0.65f, origin.y)
                anchors[secondShip] = Vector2f(origin.x - range * 0.2f, origin.y)
                secondShip.isPhased = false
            }
            Stage.INTERCEPT_COST -> {
                anchors[enemy] = Vector2f(origin.x + range * 0.65f, origin.y + 100f)
                anchors[secondShip] = Vector2f(origin.x + range * 0.35f, origin.y)
                secondShip.isPhased = false
            }
            Stage.EXCLUSIVE, Stage.RELEASE -> fighters.forEach { it.isPhased = false }
            Stage.EXHAUSTED -> {
                enemy.isPhased = true
                fighters.forEach { it.isPhased = false }
            }
            Stage.FIGHTER_AVOIDANCE -> {
                enemy.isPhased = true
                fighters.forEach { it.isPhased = false }
                anchors[fighters[0]] = Vector2f(origin.x + 250f, origin.y)
                anchors[fighters[1]] = Vector2f(origin.x + 450f, origin.y)
            }
            Stage.DONE -> Unit
        }
        stageStartedAt = ctx.elapsed
        armed = false
        ctx.log.info("[ASTD-Automation] XC2_TARGET_BEGIN stage=$stage range=$range origin=$origin anchors=${anchors.entries.joinToString { "${id(it.key)}=${it.value}:phased=${it.key.isPhased}" }}")
    }

    private fun evaluateStage(batch: List<Sample>) {
        when (stage) {
            Stage.SHIP_PRIORITY, Stage.TURN_COST, Stage.INTERCEPT_COST, Stage.OUTSIDE_SHIP -> {
                if (expectTarget(batch[0], enemy)) pass()
            }
            Stage.FIGHTER_PRIORITY -> {
                if (expectTarget(batch[0], fighters[0])) pass()
            }
            Stage.EXCLUSIVE -> {
                val first = batch[0]
                val second = batch[1]
                val third = batch[2]
                val firstTarget = first.shot.target
                val secondTarget = second.shot.target
                ctx.log.info("[ASTD-Automation] XC2_FIGHTER_ASSIGNMENT_RESULT first=${id(firstTarget)} second=${id(secondTarget)} third=${id(third.shot.target)} fighters=${fighters.map(::id)}")
                if (!expect(firstTarget in fighters && secondTarget in fighters && firstTarget !== secondTarget && third.shot.target === enemy,
                        "fighter assignment first=${id(firstTarget)} second=${id(secondTarget)} third=${id(third.shot.target)}")) return
                ctx.log.info("[ASTD-Automation] XC2_TARGET_PASS stage=$stage")
                releasedFighter = firstTarget
                ctx.engine.removeEntity(first.projectile)
                ctx.engine.removeEntity(third.projectile)
                stage = Stage.RELEASE
                stageStartedAt = ctx.elapsed
                armed = true
                passedStages++
                requestBatch(1)
            }
            Stage.RELEASE -> {
                val replacement = batch[0]
                val expected = releasedFighter
                ctx.log.info("[ASTD-Automation] XC2_FIGHTER_RELEASE_RESULT released=${id(expected)} replacement=${id(replacement.shot.target)} retained=${id(samples[1].shot.target)}")
                if (expect(replacement.shot.target === expected && samples[1].shot.target !== expected, "fighter claim not released expected=${id(expected)} actual=${id(replacement.shot.target)}")) pass()
            }
            Stage.EXHAUSTED -> {
                val first = batch[0]
                val second = batch[1]
                val third = batch[2]
                ctx.log.info("[ASTD-Automation] XC2_FIGHTER_EXHAUSTED_RESULT first=${id(first.shot.target)} second=${id(second.shot.target)} third=${id(third.shot.target)} availability=${third.shot.availability}")
                if (expect(first.shot.target in fighters && second.shot.target in fighters && first.shot.target !== second.shot.target && third.shot.target == null && third.shot.availability == 5,
                        "fighter claims exhausted first=${id(first.shot.target)} second=${id(second.shot.target)} third=${id(third.shot.target)} availability=${third.shot.availability}")) pass()
            }
            Stage.FIGHTER_AVOIDANCE -> {
                if (!expectTarget(batch[0], fighters[0])) return
                if (!expectTarget(batch[1], fighters[1])) return
            }
            Stage.DONE -> Unit
        }
    }

    private fun requestBatch(count: Int) {
        player.shipTarget = null
        val origins = List(count) { index -> Vector2f(origin.x, origin.y + index * 0.25f) }
        pendingBatch = PendingBatch(ctx.engine.projectiles.toSet(), origins, frame, ctx.elapsed)
        for ((order, launchAt) in origins.withIndex()) {
            val raw = ctx.engine.spawnProjectile(player, weapon, weapon.id, launchAt, 0f, Vector2f()) as? DamagingProjectileAPI
            if (raw == null) {
                fail("primary spawn failed stage=$stage order=$order")
                return
            }
            StarfallWingOnFireEffect().onFire(raw, weapon, ctx.engine)
        }
        ctx.log.info("[ASTD-Automation] XC2_TARGET_BATCH_REQUESTED stage=$stage count=$count frame=$frame origins=$origins")
    }

    private fun collectBatch(): List<Sample>? {
        val pending = pendingBatch ?: return null
        if (frame <= pending.requestedFrame) return null
        val created = ctx.engine.projectiles.filter {
            it !in pending.baseline && it.source === player && ctx.engine.isEntityInPlay(it) &&
                it.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot
        }
        if (created.size != pending.origins.size) {
            if (ctx.elapsed - pending.requestedAt >= 1f) {
                fail("guided registration timeout stage=$stage expected=${pending.origins.size} actual=${created.size}")
            }
            return null
        }
        val ordered = ArrayList<Sample>()
        for ((order, launchAt) in pending.origins.withIndex()) {
            val matches = created.filter {
                abs(it.spawnLocation.x - launchAt.x) < 0.01f && abs(it.spawnLocation.y - launchAt.y) < 0.01f
            }
            if (matches.size != 1) {
                fail("guided registration order ambiguous stage=$stage order=$order origin=$launchAt matches=${matches.size}")
                return null
            }
            val guided = matches[0]
            guided.collisionClass = CollisionClass.NONE
            guided.location.set(origin)
            guided.velocity.set(0f, 0f)
            guided.facing = 0f
            val shot = guided.customData[StarfallWingTuning.SHOT_STATE_KEY] as StarfallWingShot
            ordered += Sample(guided, shot)
            ctx.log.info("[ASTD-Automation] XC2_TARGET_SAMPLE stage=$stage order=$order shot=${System.identityHashCode(guided)} spawn=${guided.spawnLocation} target=${id(shot.target)} availability=${shot.availability} aimScore=${shot.aimScore} avoidance=${shot.avoidanceDeflection} locked=${id(player.shipTarget)} power=${shot.power} range=${shot.range}")
        }
        pendingBatch = null
        samples += ordered
        return ordered
    }

    private fun expectTarget(sample: Sample, expected: ShipAPI): Boolean {
        val expectedAvailability = when {
            expected.isFighter -> 2
            stage == Stage.OUTSIDE_SHIP -> 3
            else -> 1
        }
        ctx.log.info("[ASTD-Automation] XC2_TARGET_RESULT stage=$stage expected=${id(expected)} actual=${id(sample.shot.target)} availability=${sample.shot.availability} aimScore=${sample.shot.aimScore} locked=${id(player.shipTarget)}")
        return expect(sample.shot.target === expected && sample.shot.availability == expectedAvailability && player.shipTarget == null, "selection stage=$stage expected=${id(expected)} actual=${id(sample.shot.target)} availability=${sample.shot.availability} expectedAvailability=$expectedAvailability")
    }

    private fun pass() {
        ctx.log.info("[ASTD-Automation] XC2_TARGET_PASS stage=$stage")
        passedStages++
        samples.forEach { if (ctx.engine.isEntityInPlay(it.projectile)) ctx.engine.removeEntity(it.projectile) }
        samples.clear()
        stage = Stage.entries[stage.ordinal + 1]
        setup()
    }

    private fun id(ship: ShipAPI?): String = ship?.let { "${it.hullSpec.hullId}:${System.identityHashCode(it)}" } ?: "none"
    private fun expect(condition: Boolean, message: String): Boolean {
        if (!condition) fail(message)
        return condition
    }
    private fun fail(message: String) {
        ctx.failureReason = "xc2 target $message"
        ctx.log.error("[ASTD-Automation] XC2_TARGET_FAIL $message")
    }

    fun close() {
        if (closed) return
        closed = true
        samples.forEach { if (ctx.engine.isEntityInPlay(it.projectile)) ctx.engine.removeEntity(it.projectile) }
        pendingBatch?.let { pending ->
            ctx.engine.projectiles.filter {
                it !in pending.baseline && it.source === player &&
                    it.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot
            }.forEach(ctx.engine::removeEntity)
        }
        pendingBatch = null
        spawnedShips += wingLead.wing?.wingMembers.orEmpty()
        spawnedShips += ctx.engine.ships.filter { it !in shipsBefore }
        isolatedShips.forEach { (ship, phased) -> ship.isPhased = phased }
        spawnedShips.forEach { if (ctx.engine.isEntityInPlay(it)) ctx.engine.removeEntity(it) }
        enemy.isPhased = false
    }
}
