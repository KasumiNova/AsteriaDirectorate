package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AutomationEvidence
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingShot
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.roundToInt

internal class StarwingImpactProbe(
    private val ctx: AutomationCombatContext,
    private val player: ShipAPI,
    private val enemy: ShipAPI,
    private val weapon: WeaponAPI,
) {
    private enum class Kind { RED, RED_SHIELD, RED_OVERLOAD, GOLD, GOLD_CAP, GOLD_OVERLOAD, GOLD_FULL, BLUE, RADIUS }
    private class Case(val name: String, val scale: Float, val type: StarfallWingTuning.ShuttleType, val power: Float, val kind: Kind)

    private val originalScale = DifficultyTuningImpl.playerFixedScale
    private val cases = buildList {
        for (scale in listOf(1f, 2f, 5f)) {
            for (power in listOf(0.5f, 2f)) {
                add(Case("red-d${scale.toInt()}-p$power", scale, StarfallWingTuning.ShuttleType.RED, power, Kind.RED))
                add(Case("gold-d${scale.toInt()}-p$power", scale, StarfallWingTuning.ShuttleType.GOLD, power, Kind.GOLD))
                add(Case("blue-d${scale.toInt()}-p$power", scale, StarfallWingTuning.ShuttleType.BLUE, power, Kind.BLUE))
            }
            add(Case("red-shield-d${scale.toInt()}", scale, StarfallWingTuning.ShuttleType.RED, 1f, Kind.RED_SHIELD))
            add(Case("red-native-overload-d${scale.toInt()}", scale, StarfallWingTuning.ShuttleType.RED, 1f, Kind.RED_OVERLOAD))
            add(Case("gold-cap-d${scale.toInt()}", scale, StarfallWingTuning.ShuttleType.GOLD, 1f, Kind.GOLD_CAP))
            add(Case("gold-native-overload-d${scale.toInt()}", scale, StarfallWingTuning.ShuttleType.GOLD, 1f, Kind.GOLD_OVERLOAD))
            add(Case("gold-full-d${scale.toInt()}", scale, StarfallWingTuning.ShuttleType.GOLD, 1f, Kind.GOLD_FULL))
        }
        add(Case("blue-radius", originalScale, StarfallWingTuning.ShuttleType.BLUE, 1f, Kind.RADIUS))
        add(Case("purple-radius", originalScale, StarfallWingTuning.ShuttleType.PURPLE, 1f, Kind.RADIUS))
    }
    private var index = 0
    private var startedAt = -1f
    private var shieldReadyAt = -1f
    private var recoveryFrames = 0
    private var launchedAt = -1f
    private var flightLogged = false
    private var projectile: DamagingProjectileAPI? = null
    private var closed = false
    private var nativePending = false
    private var nativeHits = 0
    private var nativeShieldHit = false
    private var nativeAt = -1f
    private var nativeHullDamage = 0f
    private var nativeShieldDamage = 0f
    private var nativeEmp = 0f
    private val arcEmp = ArrayList<Float>()
    private var nativeOverloaded = false
    private var nativeCurrent = 0f
    private var nativeHard = 0f
    private var bonusRaw = 0f
    private var bonusHits = 0
    private var bonusType: DamageType? = null
    private var bonusApplied = 0f
    private var bonusHull = 0f
    private var bonusShield = 0f
    private var overloadReleased = 0f
    private val inside = spawnWitness("inside")
    private val outside = spawnWitness("outside")
    private var insideAnchor = Vector2f(-200f, 1000f)
    private var outsideAnchor = Vector2f(200f, 1000f)
    private var insideHits = 0
    private var outsideHits = 0
    private var insideRaw = 0f
    private var outsideRaw = 0f

    val completedCases: Int get() = index
    val caseName: String get() = cases.getOrNull(index)?.name ?: "done"

    private val listener = object : DamageTakenModifier, DamageListener {
        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (damage == null || projectile == null) return null
            nativePending = param === projectile
            if (nativePending) {
                logProjectile("NATIVE", projectile!!)
                nativeHits++
                nativeShieldHit = shieldHit
                point?.let { positionWitnesses(it) }
            } else if (cases[index].type == StarfallWingTuning.ShuttleType.RED) {
                bonusHits++
                bonusRaw += damage.damage
                bonusType = damage.type
            }
            return null
        }

        override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
            if (result == null || projectile == null) return
            if (!nativePending) {
                if (cases[index].kind == Kind.BLUE && result.empDamage > 0f &&
                    result.damageToHull == 0f && result.totalDamageToArmor == 0f &&
                    result.damageToShields == 0f && result.overMaxDamageToShields == 0f
                ) arcEmp += result.empDamage
                if (cases[index].type == StarfallWingTuning.ShuttleType.RED) {
                    bonusHull += result.damageToHull + result.totalDamageToArmor
                    bonusShield += result.damageToShields + result.overMaxDamageToShields
                    bonusApplied = bonusHull + bonusShield
                }
                return
            }
            nativePending = false
            nativeAt = ctx.elapsed
            nativeHullDamage = result.damageToHull + result.totalDamageToArmor
            nativeShieldDamage = result.damageToShields + result.overMaxDamageToShields
            nativeEmp = result.empDamage
            val tracker = enemy.fluxTracker
            nativeOverloaded = tracker.isOverloaded
            when (cases[index].kind) {
                Kind.GOLD_OVERLOAD -> if (nativeOverloaded) {
                    // 原生直击已触发过载，腾出容量后才能观测到被过载拒绝的硬辐能增加。
                    val before = tracker.currFlux
                    tracker.decreaseFlux(goldBonus() * 2f)
                    overloadReleased = before - tracker.currFlux
                }
                Kind.GOLD_FULL -> if (nativeOverloaded) {
                    tracker.hardFlux = tracker.maxFlux * 0.2f
                    tracker.currFlux = tracker.maxFlux
                }
                else -> Unit
            }
            nativeCurrent = tracker.currFlux
            nativeHard = tracker.hardFlux
        }
    }
    private val insideListener = witnessListener(true)
    private val outsideListener = witnessListener(false)

    init {
        enemy.addListener(listener)
        inside.addListener(insideListener)
        outside.addListener(outsideListener)
        enemy.mutableStats.fluxDissipation.modifyMult(STAT_ID, 0f)
        enemy.mutableStats.shieldUpkeepMult.modifyMult(STAT_ID, 0f)
        clearShuttles()
    }

    fun advance(): Boolean {
        pin(player, Vector2f(-800f, 300f), 0f)
        pin(enemy, Vector2f(-300f, 300f), 180f)
        pin(inside, insideAnchor, 180f)
        pin(outside, outsideAnchor, 180f)
        player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
        weapon.setForceNoFireOneFrame(true)
        if (index == cases.size) return true
        if (startedAt < 0f) prepare()
        val case = cases[index]
        if (ctx.failureReason != null) return false
        if (projectile == null) prepareLaunch(case)
        if (ctx.failureReason != null) return false
        if (nativeAt >= 0f) {
            evaluate(case)
            if (ctx.failureReason != null) return false
            projectile?.let { if (ctx.engine.isEntityInPlay(it)) ctx.engine.removeEntity(it) }
            projectile = null
            index++
            startedAt = -1f
            ctx.writeDiagnostics("CombatReady", player)
            ctx.writeTelemetry("CombatReady", player, weapon)
        } else {
            val active = projectile
            if (active != null && !ctx.engine.isEntityInPlay(active)) {
                logProjectile("LOST", active)
                fail("native collision missing case=${case.name} damageTarget=${entityId(active.damageTarget)} didDamage=${active.didDamage()} elapsed=${active.elapsed} loc=${active.location}")
            } else if (active != null && !flightLogged && ctx.elapsed - launchedAt >= 0.1f) {
                flightLogged = true
                logProjectile("FLIGHT", active)
            }
            if (ctx.failureReason == null && ctx.elapsed - startedAt > 4f) {
                active?.let { logProjectile("TIMEOUT", it) }
                fail("case timeout case=${case.name} recoveryFrames=$recoveryFrames shieldReadyAt=$shieldReadyAt overloaded=${enemy.fluxTracker.isOverloaded} shieldOn=${enemy.shield?.isOn}")
            }
        }
        return index == cases.size
    }

    private fun prepare() {
        val case = cases[index]
        startedAt = ctx.elapsed
        shieldReadyAt = -1f
        recoveryFrames = 0
        launchedAt = -1f
        flightLogged = false
        nativePending = false
        nativeHits = 0
        nativeShieldHit = false
        nativeAt = -1f
        nativeHullDamage = 0f
        nativeShieldDamage = 0f
        nativeEmp = 0f
        arcEmp.clear()
        nativeOverloaded = false
        bonusRaw = 0f
        bonusHits = 0
        bonusType = null
        bonusApplied = 0f
        bonusHull = 0f
        bonusShield = 0f
        overloadReleased = 0f
        insideHits = 0
        outsideHits = 0
        insideRaw = 0f
        outsideRaw = 0f
        insideAnchor = Vector2f(-300f, 1800f)
        outsideAnchor = Vector2f(-300f, -1800f)
        inside.location.set(insideAnchor)
        outside.location.set(outsideAnchor)
        inside.hitpoints = inside.maxHitpoints
        outside.hitpoints = outside.maxHitpoints
        ctx.engine.projectiles.toList().forEach(ctx.engine::removeEntity)
        ctx.engine.missiles.toList().filter(ctx.engine::isEntityInPlay).forEach(ctx.engine::removeEntity)
        DifficultyTuningImpl.applyResolvedPlayerScale(case.scale, "automation-${case.name}")
        val tracker = enemy.fluxTracker
        tracker.hardFlux = 0f
        tracker.currFlux = 0f
        tracker.stopOverload()
        tracker.stopVenting()
        enemy.shield?.toggleOff()
        enemy.hitpoints = enemy.maxHitpoints
        ctx.log.info("[ASTD-Automation] XC2_IMPACT_BEGIN case=${case.name} difficulty=${case.scale} power=${case.power} maxHp=${enemy.maxHitpoints} maxFlux=${tracker.maxFlux} curr=${tracker.currFlux} hard=${tracker.hardFlux} overloaded=${tracker.isOverloaded} shieldOn=${enemy.shield?.isOn}")
    }

    private fun prepareLaunch(case: Case) {
        val tracker = enemy.fluxTracker
        if (tracker.isOverloadedOrVenting) {
            recoveryFrames = 0
            return
        }
        recoveryFrames++
        if (recoveryFrames < 3 || ctx.elapsed - startedAt < 0.1f) return
        val shield = enemy.shield
        val shieldCase = case.type == StarfallWingTuning.ShuttleType.GOLD || case.kind == Kind.RED_SHIELD || case.kind == Kind.RED_OVERLOAD
        if (shieldCase && shield == null) {
            fail("shield missing case=${case.name}")
            return
        }
        if (shieldReadyAt < 0f) {
            if (shieldCase) {
                shield!!.toggleOn()
                shield.forceFacing(180f)
                shield.activeArc = shield.arc
            }
            shieldReadyAt = ctx.elapsed
            ctx.log.info("[ASTD-Automation] XC2_IMPACT_RECOVERED case=${case.name} recoveryFrames=$recoveryFrames curr=${tracker.currFlux} hard=${tracker.hardFlux} overloaded=${tracker.isOverloaded} shieldOn=${shield?.isOn}")
            return
        }
        if (ctx.elapsed - shieldReadyAt < 0.3f) return
        if (shieldCase && shield?.isOn != true) {
            fail("shield closed during recovery case=${case.name} overloaded=${tracker.isOverloaded} curr=${tracker.currFlux} hard=${tracker.hardFlux}")
            return
        }
        tracker.hardFlux = tracker.maxFlux * 0.1f
        tracker.currFlux = when (case.kind) {
            Kind.GOLD_CAP -> tracker.maxFlux - goldBonus() * 0.25f
            Kind.RED_OVERLOAD, Kind.GOLD_OVERLOAD, Kind.GOLD_FULL -> tracker.maxFlux - 1f
            else -> tracker.maxFlux * 0.3f
        }
        launch(case)
    }

    private fun launch(case: Case) {
        val offset = maxOf(enemy.collisionRadius, enemy.shield?.radius ?: 0f) + 100f
        val spawned = ctx.engine.spawnProjectile(
            player, weapon, StarfallWingTuning.SHUTTLE_LAUNCHER_ID,
            Vector2f(enemy.location.x - offset, enemy.location.y), 0f, Vector2f(),
        ) as? DamagingProjectileAPI
        if (spawned == null) {
            fail("carrier spawn failed case=${case.name}")
            return
        }
        spawned.damage.type = case.type.damageType
        spawned.setDamageAmount(if (case.kind == Kind.GOLD_CAP) 0.1f else 40f * case.power)
        spawned.damage.fluxComponent = if (case.kind == Kind.BLUE) spawned.damageAmount * 2f else 0f
        spawned.velocity.set(800f, 0f)
        spawned.setCustomData(StarfallWingTuning.SHOT_STATE_KEY, StarfallWingShot(case.type, case.power, 800f, weapon.range, 0f))
        projectile = spawned
        launchedAt = ctx.elapsed
        logProjectile("LAUNCH", spawned)
        ProjectileVfxDriverPlugin.track(ctx.engine, spawned, case.type.vfxId)
    }

    private fun evaluate(case: Case) {
        if (!expect(nativeHits == 1, "nativeHits=$nativeHits case=${case.name}")) return
        val radius = if (case.type == StarfallWingTuning.ShuttleType.PURPLE) 300f else 150f
        ctx.log.info("[ASTD-Automation] XC2_RADIUS_RESULT case=${case.name} expectedRadius=$radius configuredRadius=${case.type.radius} innerSurface=${radius - 10f} outerSurface=${radius + 10f} innerHits=$insideHits outerHits=$outsideHits innerRaw=$insideRaw outerRaw=$outsideRaw")
        if (!expect(insideHits == 1 && outsideHits == 0, "radius boundary case=${case.name} inside=$insideHits outside=$outsideHits")) return
        if (case.type == StarfallWingTuning.ShuttleType.RED) {
            val expected = enemy.maxHitpoints * case.power * when (case.scale) { 1f -> 0.01f; 2f -> 0.02f; else -> 0.05f }
            ctx.log.info("[ASTD-Automation] XC2_RED_PERCENT_RESULT case=${case.name} power=${case.power} expectedRaw=$expected actualRaw=$bonusRaw bonusHits=$bonusHits type=$bonusType applied=$bonusApplied bonusHull=$bonusHull bonusShield=$bonusShield nativeHull=$nativeHullDamage nativeShield=$nativeShieldDamage shieldHit=$nativeShieldHit shieldOn=${enemy.shield?.isOn} nativeOverloaded=$nativeOverloaded")
            if (!expect(bonusHits == 1 && bonusType == DamageType.HIGH_EXPLOSIVE && near(bonusRaw, expected) && bonusApplied > 0f,
                    "red percent case=${case.name} expected=$expected raw=$bonusRaw hits=$bonusHits type=$bonusType applied=$bonusApplied shield=$nativeShieldHit")) return
            expect(nativeShieldHit == (case.kind != Kind.RED), "red native shield state case=${case.name} shield=$nativeShieldHit")
            if (case.kind == Kind.RED_SHIELD) expect(bonusShield > 0f && near(bonusHull, 0f), "red shield bonus bypassed shield case=${case.name} hull=$bonusHull shield=$bonusShield")
            if (case.kind == Kind.RED_OVERLOAD) expect(nativeOverloaded && bonusApplied > 0f, "red native overload bonus missing case=${case.name} overloaded=$nativeOverloaded applied=$bonusApplied shieldOn=${enemy.shield?.isOn}")
        } else if (case.type == StarfallWingTuning.ShuttleType.GOLD) {
            val tracker = enemy.fluxTracker
            val expectedBonus = tracker.maxFlux * case.power * when (case.scale) { 1f -> 0.005f; 2f -> 0.01f; else -> 0.025f }
            val expectedCurrent = minOf(tracker.maxFlux, nativeCurrent + expectedBonus)
            val expectedHard = minOf(tracker.maxFlux, nativeHard + expectedBonus)
            val beforeSoft = nativeCurrent - nativeHard
            val afterSoft = tracker.currFlux - tracker.hardFlux
            ctx.log.info("[ASTD-Automation] XC2_GOLD_HARD_FLUX_RESULT case=${case.name} power=${case.power} bonus=$expectedBonus nativeShield=$nativeShieldDamage nativeOverloaded=$nativeOverloaded released=$overloadReleased baselineCurr=$nativeCurrent baselineHard=$nativeHard expectedCurr=$expectedCurrent actualCurr=${tracker.currFlux} expectedHard=$expectedHard actualHard=${tracker.hardFlux} softBefore=$beforeSoft softAfter=$afterSoft overloaded=${tracker.isOverloaded}")
            if (!expect(nativeShieldHit && nativeShieldDamage > 0f && near(tracker.currFlux, expectedCurrent) && near(tracker.hardFlux, expectedHard),
                    "gold flux case=${case.name} expected=($expectedCurrent,$expectedHard) actual=(${tracker.currFlux},${tracker.hardFlux}) nativeShield=$nativeShieldHit")) return
            when (case.kind) {
                Kind.GOLD -> expect(near(beforeSoft, afterSoft), "gold soft flux changed case=${case.name} before=$beforeSoft after=$afterSoft")
                Kind.GOLD_CAP -> expect(near(tracker.currFlux, tracker.maxFlux), "gold capacity not reached case=${case.name}")
                Kind.GOLD_OVERLOAD -> expect(nativeOverloaded && overloadReleased > expectedBonus && tracker.isOverloaded,
                    "native overload evidence missing case=${case.name} overloaded=$nativeOverloaded released=$overloadReleased")
                Kind.GOLD_FULL -> expect(nativeOverloaded && near(nativeCurrent, tracker.maxFlux) && tracker.hardFlux > nativeHard,
                    "full flux hard increase missing case=${case.name}")
                else -> Unit
            }
        }
        if (case.kind == Kind.BLUE) {
            val expectedEmp = 80f * case.power
            val minimum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MIN, true) * case.power).roundToInt()
            val maximum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MAX, true) * case.power).roundToInt()
            ctx.log.info("[ASTD-Automation] XC2_BLUE_POWER_RESULT case=${case.name} power=${case.power} expectedEmp=$expectedEmp nativeEmp=$nativeEmp arcEmp=$arcEmp arcs=${arcEmp.size} expectedArcs=[$minimum,$maximum]")
            if (!expect(!nativeShieldHit && near(nativeEmp, expectedEmp), "blue native EMP case=${case.name} expected=$expectedEmp actual=$nativeEmp shield=$nativeShieldHit")) return
            if (!expect(arcEmp.size in minimum..maximum, "blue arc count case=${case.name} expected=[$minimum,$maximum] actual=${arcEmp.size}")) return
            if (!expect(arcEmp.all { near(it, expectedEmp) }, "blue arc EMP case=${case.name} expected=$expectedEmp actual=$arcEmp")) return
        }
        if (ctx.failureReason == null) ctx.log.info("[ASTD-Automation] XC2_IMPACT_PASS case=${case.name}")
    }

    private fun positionWitnesses(point: Vector2f) {
        val radius = if (cases[index].type == StarfallWingTuning.ShuttleType.PURPLE) 300f else 150f
        insideAnchor = Vector2f(point.x, point.y + radius - 10f + inside.collisionRadius)
        outsideAnchor = Vector2f(point.x, point.y - radius - 10f - outside.collisionRadius)
        inside.location.set(insideAnchor)
        outside.location.set(outsideAnchor)
    }

    private fun spawnWitness(name: String): ShipAPI {
        val manager = ctx.engine.getFleetManager(enemy.owner)
        manager.isSuppressDeploymentMessages = true
        return manager.spawnShipOrWing("kite_Stock", Vector2f(0f, 1000f), 180f, 0f).also { ship ->
            ship.setCustomData(STAT_ID, name)
            ship.shield?.toggleOff()
            ship.mutableStats.hullBonus.modifyFlat(STAT_ID, 100000f)
            ship.hitpoints = ship.maxHitpoints
        }
    }

    private fun witnessListener(isInside: Boolean) = object : DamageTakenModifier {
        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (projectile != null && damage != null) {
                if (param === projectile) {
                    logProjectile("WITNESS_HIT", projectile!!)
                    fail("carrier hit witness case=$caseName witness=${entityId(target)} point=$point")
                }
                if (isInside) { insideHits++; insideRaw += damage.damage } else { outsideHits++; outsideRaw += damage.damage }
            }
            return null
        }
    }

    private fun pin(ship: ShipAPI, anchor: Vector2f, facing: Float) {
        AutomationEvidence.stabilizeShip(ship, anchor, facing, false)
        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
    }

    private fun entityId(entity: CombatEntityAPI?): String = when (entity) {
        null -> "none"
        is ShipAPI -> "${entity.hullSpec.hullId}:${entity.id}"
        is DamagingProjectileAPI -> "${entity.projectileSpecId}:${System.identityHashCode(entity)}"
        else -> "entity:${System.identityHashCode(entity)}"
    }

    private fun logProjectile(event: String, active: DamagingProjectileAPI) {
        val tracker = enemy.fluxTracker
        val shield = enemy.shield
        val nearby = ctx.engine.ships.filter {
            val dx = it.location.x - active.location.x
            val dy = it.location.y - active.location.y
            dx * dx + dy * dy <= 500f * 500f
        }.joinToString { "${entityId(it)}:loc=${it.location}:radius=${it.collisionRadius}:phased=${it.isPhased}:collision=${it.collisionClass}" }
        ctx.log.info(
            "[ASTD-Automation] XC2_IMPACT_PROJECTILE event=$event case=$caseName age=${ctx.elapsed - launchedAt} " +
                "inPlay=${ctx.engine.isEntityInPlay(active)} elapsed=${active.elapsed} didDamage=${active.didDamage()} " +
                "damageTarget=${entityId(active.damageTarget)} source=${entityId(active.source)} owner=${active.owner} " +
                "loc=${active.location} tail=${active.tailEnd} velocity=${active.velocity} facing=${active.facing} " +
                "fading=${active.isFading} collision=${active.collisionClass} " +
                "enemyLoc=${enemy.location} enemyPhased=${enemy.isPhased} enemyCollision=${enemy.collisionClass} " +
                "curr=${tracker.currFlux} hard=${tracker.hardFlux} overloaded=${tracker.isOverloaded} " +
                "overloadRemaining=${tracker.overloadTimeRemaining} venting=${tracker.isVenting} " +
                "shieldOn=${shield?.isOn} shieldArc=${shield?.activeArc} shieldFacing=${shield?.facing} " +
                "shieldLoc=${shield?.location} shieldRadius=${shield?.radius} nearby=[$nearby]",
        )
    }

    private fun goldBonus() = enemy.fluxTracker.maxFlux * cases[index].power *
        DifficultyTuningImpl.valueFor(StarfallWingTuning.GOLD_HARD_FLUX, true)
    private fun near(actual: Float, expected: Float) = abs(actual - expected) <= maxOf(0.1f, abs(expected) * 0.0001f)
    private fun expect(condition: Boolean, message: String): Boolean {
        if (!condition) fail(message)
        return condition
    }
    private fun fail(message: String) {
        ctx.failureReason = "xc2 impact $message"
        ctx.log.error("[ASTD-Automation] XC2_IMPACT_FAIL $message")
    }
    private fun clearShuttles() {
        ctx.engine.projectiles.toList().filter { it.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot }
            .forEach(ctx.engine::removeEntity)
    }

    fun close() {
        if (closed) return
        closed = true
        enemy.removeListener(listener)
        inside.removeListener(insideListener)
        outside.removeListener(outsideListener)
        enemy.mutableStats.fluxDissipation.unmodify(STAT_ID)
        enemy.mutableStats.shieldUpkeepMult.unmodify(STAT_ID)
        enemy.fluxTracker.stopOverload()
        enemy.fluxTracker.currFlux = 0f
        enemy.fluxTracker.hardFlux = 0f
        ctx.engine.removeEntity(inside)
        ctx.engine.removeEntity(outside)
        clearShuttles()
        DifficultyTuningImpl.applyResolvedPlayerScale(originalScale, "automation-restore")
    }

    private companion object {
        const val STAT_ID = "astd_xc2_impact_probe"
    }
}
