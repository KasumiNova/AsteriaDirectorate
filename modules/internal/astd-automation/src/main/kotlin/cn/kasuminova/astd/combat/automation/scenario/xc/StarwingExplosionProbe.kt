package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AutomationEvidence
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingShot
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingVfx
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EngineSlotAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.combat.entities.Missile
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class StarwingExplosionProbe(
    private val ctx: AutomationCombatContext,
    private val player: ShipAPI,
    private val enemy: ShipAPI,
    private val weapon: WeaponAPI,
) {
    private enum class Mode { HIT, SHIELD_HIT, EXPIRE }
    private class Sample(val projectile: DamagingProjectileAPI, val shot: StarfallWingShot) {
        val launchDamage = projectile.damageAmount
        val explosionOrigin = Vector2f(projectile.location)
        val flares = ArrayList<FlareObservation>()
    }
    private class Pending(val baseline: Set<DamagingProjectileAPI>, val frame: Int, val requestedAt: Float)
    private class FlareReference(val projectile: MissileAPI) {
        val specId = projectile.projectileSpecId
        val damage = projectile.damageAmount
        val damageType = projectile.damage.type
        val emp = projectile.damage.fluxComponent
        val engineColors = flareColors(projectile)
    }
    private class FlareObservation(
        val projectile: MissileAPI,
        val mode: Mode,
        val type: StarfallWingTuning.ShuttleType,
        val observedAt: Float,
        val explosionOrigin: Vector2f,
    ) {
        val birthLocation = Vector2f(projectile.spawnLocation)
        var colorSamples = 0
        var lastSampleAge = 0f
        var earlyDispersed = false
        var lateSampled = false
        var maximumDisplacement = 0f
        var maximumExplosionDistance = 0f
    }

    private val originalPhases = ctx.engine.ships.associateWith { it.isPhased }
    private val inside = spawnWitness()
    private val outside = spawnWitness()
    private val coveredTypes = Mode.entries.associateWith { linkedSetOf<StarfallWingTuning.ShuttleType>() }
    private val flares = linkedSetOf<MissileAPI>()
    private val flareObservations = ArrayList<FlareObservation>()
    private val lateColorTypes = Mode.entries.associateWith { linkedSetOf<StarfallWingTuning.ShuttleType>() }
    private var endedFlares = 0
    private var flareBaseline: Set<MissileAPI> = emptySet()
    private var nativeFlare: FlareReference? = null
    private var flareBatches = 0
    private var mode = Mode.HIT
    private var sample: Sample? = null
    private var pending: Pending? = null
    private var frame = 0
    private var attempts = 0
    private var initialized = false
    private var closed = false
    private var readyAt = ctx.elapsed + 0.5f
    private var launchedAt = -1f
    private var fadeAt = -1f
    private var fadeFrames = 0
    private var terminalAt = -1f
    private var removedAt = -1f
    private var nativeHits = 0
    private var nativeShieldHit = false
    private var nativeShieldDamage = 0f
    private var nativePending = false
    private var shieldReadyAt = -1f
    private var insideHits = 0
    private var outsideHits = 0
    private var insideApplied = 0f
    private var outsideApplied = 0f
    private var insideRaw = 0f
    private var prematureDamage = 0
    private var prematureFlares = 0
    private var explosionAt = -1f
    private var enemyAnchor = Vector2f(-300f, 300f)
    private var insideAnchor = Vector2f(-300f, 1500f)
    private var outsideAnchor = Vector2f(-300f, -1500f)

    val completedCases: Int get() = coveredTypes.values.sumOf { it.size }
    private val requiredCases = Mode.entries.size * StarfallWingTuning.ShuttleType.entries.size
    val caseName: String get() = when {
        completedCases == requiredCases -> "drain-flares"
        sample != null -> "${mode.name.lowercase()}-${sample!!.shot.type.name.lowercase()}"
        else -> "${mode.name.lowercase()}-collecting"
    }

    private val impactListener = object : DamageTakenModifier, DamageListener {
        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val active = sample ?: return null
            nativePending = param === active.projectile
            if (!nativePending) return null
            nativeHits++
            nativeShieldHit = shieldHit
            if (mode == Mode.EXPIRE) {
                fail("unexpected native hit case=$caseName")
                return null
            }
            expect(shieldHit == (mode == Mode.SHIELD_HIT), "native shield mismatch case=$caseName shieldHit=$shieldHit")
            active.explosionOrigin.set(point ?: active.projectile.location)
            positionWitnesses(active.explosionOrigin)
            ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_NATIVE case=$caseName point=$point hits=$nativeHits shieldHit=$shieldHit")
            return null
        }

        override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
            if (!nativePending || result == null) return
            nativePending = false
            nativeShieldDamage += result.damageToShields + result.overMaxDamageToShields
        }
    }
    private val insideListener = WitnessListener(true)
    private val outsideListener = WitnessListener(false)

    init {
        enemy.addListener(impactListener)
        inside.addListener(insideListener)
        outside.addListener(outsideListener)
    }

    fun advance(): Boolean {
        frame++
        if (ctx.failureReason != null) return false
        isolateShips()
        pin(player, PLAYER_ANCHOR)
        pin(enemy, enemyAnchor)
        pin(inside, insideAnchor)
        pin(outside, outsideAnchor)
        weapon.setForceNoFireOneFrame(true)
        observeFlareColors()
        if (ctx.failureReason != null) return false
        if (completedCases == requiredCases) return finishFlareObservations()
        if (!initialized) {
            if (!register()) return false
        }
        if (pending != null) collect()
        val active = sample
        if (active == null) {
            if (pending == null && ctx.elapsed >= readyAt && prepareShield()) request()
            return false
        }
        observe(active)
        if (ctx.failureReason != null) return false
        val lastFlareAt = active.flares.maxOfOrNull { it.observedAt } ?: removedAt
        if (removedAt >= 0f && ctx.elapsed - removedAt >= 0.6f && ctx.elapsed - lastFlareAt >= 1.1f) {
            evaluate(active)
            if (ctx.failureReason != null) return false
            finishCase(active)
        } else if (ctx.elapsed - launchedAt > 12f) {
            fail("lifecycle timeout case=$caseName fadeAt=$fadeAt terminalAt=$terminalAt removedAt=$removedAt nativeHits=$nativeHits inPlay=${ctx.engine.isEntityInPlay(active.projectile)} expired=${active.projectile.isExpired} wasRemoved=${active.projectile.wasRemoved()}")
        }
        return false
    }

    private fun register(): Boolean {
        if (!ctx.engine.isEntityInPlay(inside) || !ctx.engine.isEntityInPlay(outside) || inside !in ctx.engine.ships || outside !in ctx.engine.ships) {
            if (ctx.elapsed > readyAt + 2f) fail("witness registration timeout")
            return false
        }
        if (ctx.elapsed < readyAt) return false
        if (ctx.engine.projectiles.any { it.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot }) {
            if (ctx.elapsed > readyAt + 12f) fail("prior stage shuttles still active")
            return false
        }
        val reference = ctx.engine.spawnProjectile(player, null, FLARE_WEAPON_ID, Vector2f(-5000f, -5000f), 0f, Vector2f()) as? MissileAPI
        if (reference == null || !reference.isFlare) {
            fail("native flare spawn failed weapon=$FLARE_WEAPON_ID")
            return false
        }
        nativeFlare = FlareReference(reference)
        if (nativeFlare!!.engineColors.isEmpty()) {
            fail("native flare engine slots missing weapon=$FLARE_WEAPON_ID")
            return false
        }
        initialized = true
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_REFERENCE weapon=$FLARE_WEAPON_ID spec=${reference.projectileSpecId} damage=${reference.damageAmount} type=${reference.damage.type} emp=${reference.damage.fluxComponent} engineColors=${nativeFlare!!.engineColors}")
        return true
    }

    private fun isolateShips() {
        for (ship in ctx.engine.ships) {
            if (ship === player || ship === enemy || ship === inside || ship === outside) continue
            ship.isPhased = true
            ship.isHoldFireOneFrame = true
            ship.allWeapons.forEach { it.setForceNoFireOneFrame(true) }
        }
    }

    private fun prepareShield(): Boolean {
        if (mode != Mode.SHIELD_HIT) return true
        val shield = enemy.shield
        if (shield == null) {
            fail("shield missing case=$caseName")
            return false
        }
        if (shieldReadyAt < 0f) {
            enemy.fluxTracker.stopOverload()
            enemy.fluxTracker.stopVenting()
            enemy.fluxTracker.hardFlux = 0f
            enemy.fluxTracker.currFlux = 0f
            enemyAnchor = Vector2f(ORIGIN.x + maxOf(enemy.collisionRadius, shield.radius) + 400f, ORIGIN.y)
            enemy.location.set(enemyAnchor)
            enemy.isPhased = false
            shield.toggleOn()
            shield.forceFacing(180f)
            shieldReadyAt = ctx.elapsed
            return false
        }
        if (ctx.elapsed - shieldReadyAt < 0.3f || shield.activeArc < shield.arc * 0.95f) {
            if (ctx.elapsed - shieldReadyAt > 3f) fail("shield arc timeout case=$caseName activeArc=${shield.activeArc} arc=${shield.arc}")
            return false
        }
        if (!shield.isOn || enemy.fluxTracker.isOverloadedOrVenting) {
            fail("shield preparation failed case=$caseName on=${shield.isOn} overloaded=${enemy.fluxTracker.isOverloadedOrVenting}")
            return false
        }
        return true
    }

    private fun request() {
        if (attempts >= 96) {
            fail("random color coverage exhausted attempts=$attempts coverage=$coveredTypes")
            return
        }
        attempts++
        enemy.isPhased = mode != Mode.SHIELD_HIT
        inside.isPhased = true
        outside.isPhased = true
        player.shipTarget = null
        enemy.hitpoints = enemy.maxHitpoints
        inside.hitpoints = inside.maxHitpoints
        outside.hitpoints = outside.maxHitpoints
        fadeAt = -1f
        fadeFrames = 0
        terminalAt = -1f
        removedAt = -1f
        nativeHits = 0
        nativeShieldHit = false
        nativeShieldDamage = 0f
        nativePending = false
        insideHits = 0
        outsideHits = 0
        insideApplied = 0f
        outsideApplied = 0f
        insideRaw = 0f
        prematureDamage = 0
        prematureFlares = 0
        explosionAt = -1f
        flares.clear()
        flareBatches = 0
        flareBaseline = ctx.engine.missiles.filter { it.isFlare }.toSet() + checkNotNull(nativeFlare).projectile
        pending = Pending(ctx.engine.projectiles.toSet(), frame, ctx.elapsed)
        val raw = ctx.engine.spawnProjectile(player, weapon, weapon.id, Vector2f(ORIGIN), 0f, Vector2f()) as? DamagingProjectileAPI
        if (raw == null) {
            fail("raw spawn failed attempt=$attempts")
            return
        }
        StarfallWingOnFireEffect().onFire(raw, weapon, ctx.engine)
        launchedAt = ctx.elapsed
    }

    private fun collect() {
        val request = pending ?: return
        if (frame <= request.frame) return
        val created = ctx.engine.projectiles.filter {
            it !in request.baseline && it.source === player &&
                it.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot
        }
        if (created.size != 1) {
            if (ctx.elapsed - request.requestedAt > 1f) fail("guided registration expected=1 actual=${created.size} attempt=$attempts")
            return
        }
        val projectile = created.single()
        val shot = projectile.customData[StarfallWingTuning.SHOT_STATE_KEY] as StarfallWingShot
        sample = Sample(projectile, shot)
        pending = null
        if (mode != Mode.EXPIRE) {
            val targetRadius = if (mode == Mode.SHIELD_HIT) maxOf(enemy.collisionRadius, checkNotNull(enemy.shield).radius) else enemy.collisionRadius
            enemyAnchor = Vector2f(projectile.location.x + targetRadius + 160f, projectile.location.y)
            enemy.location.set(enemyAnchor)
            enemy.isPhased = false
        }
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_BEGIN case=$caseName attempt=$attempts shot=${System.identityHashCode(projectile)} damage=${sample!!.launchDamage} power=${shot.power} radius=${shot.type.radius} coverage=$coveredTypes shieldOn=${enemy.shield?.isOn}")
    }

    private fun observe(active: Sample) {
        val projectile = active.projectile
        val terminal = projectile.wasRemoved() || projectile.isExpired
        if (mode == Mode.EXPIRE && !terminal) active.explosionOrigin.set(projectile.location)
        if (terminal && terminalAt < 0f) terminalAt = ctx.elapsed
        if (!ctx.engine.isEntityInPlay(projectile) && removedAt < 0f) {
            removedAt = ctx.elapsed
            ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_REMOVED case=$caseName terminalAt=$terminalAt fadeAt=$fadeAt fadeFrames=$fadeFrames insideHits=$insideHits flares=${flares.size}")
        }
        if (mode == Mode.EXPIRE && !terminal && projectile.isFading) {
            if (fadeAt < 0f) {
                fadeAt = ctx.elapsed
                ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_FADE case=$caseName damage=${projectile.damageAmount} location=${projectile.location}")
            }
            fadeFrames++
            positionWitnesses(projectile.location)
            if (insideHits > 0 || outsideHits > 0) fail("damage during fade case=$caseName inside=$insideHits outside=$outsideHits")
        }
        var newFlares = 0
        for (flare in ctx.engine.missiles) {
            if (!flare.isFlare || flare.source !== player || flare in flareBaseline || !flares.add(flare)) continue
            newFlares++
            if (mode == Mode.EXPIRE && !terminal) prematureFlares++
            verifyFlare(flare, active)
            val observation = FlareObservation(flare, mode, active.shot.type, ctx.elapsed, Vector2f(active.explosionOrigin))
            flareObservations += observation
            active.flares += observation
        }
        if (newFlares > 0) flareBatches++
    }

    private fun verifyFlare(flare: MissileAPI, active: Sample) {
        val reference = checkNotNull(nativeFlare)
        val expectedColor = StarfallWingVfx.color(active.shot.type)
        val engineColors = flareColors(flare)
        val destroyed = flare.destroyedExplosionColorOverride
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_FLARE case=$caseName flare=${System.identityHashCode(flare)} spec=${flare.projectileSpecId} damage=${flare.damageAmount} type=${flare.damage.type} emp=${flare.damage.fluxComponent} engineColors=$engineColors destroyedRgb=${destroyed?.rgb} expectedRgb=${expectedColor.rgb}")
        expect(flare.projectileSpecId == reference.specId && near(flare.damageAmount, reference.damage) &&
            flare.damage.type == reference.damageType && near(flare.damage.fluxComponent, reference.emp),
            "native flare mismatch case=$caseName spec=${flare.projectileSpecId} damage=${flare.damageAmount} type=${flare.damage.type} emp=${flare.damage.fluxComponent}")
        expect(engineColors.isNotEmpty() && engineColors.all { it == expectedColor } && destroyed == expectedColor,
            "flare color mismatch case=$caseName engines=$engineColors destroyed=$destroyed expected=$expectedColor")
        expect(flareColors(reference.projectile) == reference.engineColors,
            "native flare instance color changed case=$caseName")
    }

    private fun observeFlareColors() {
        val iterator = flareObservations.iterator()
        while (iterator.hasNext()) {
            val observation = iterator.next()
            val flare = observation.projectile
            val age = ctx.elapsed - observation.observedAt
            if (flare.isExpired || !ctx.engine.isEntityInPlay(flare)) {
                endedFlares++
                ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_FLARE_END mode=${observation.mode} type=${observation.type} flare=${System.identityHashCode(flare)} age=$age samples=${observation.colorSamples} lastSampleAge=${observation.lastSampleAge} lateSampled=${observation.lateSampled} earlyDispersed=${observation.earlyDispersed} maximumDisplacement=${observation.maximumDisplacement} maximumExplosionDistance=${observation.maximumExplosionDistance} inPlay=${ctx.engine.isEntityInPlay(flare)} expired=${flare.isExpired} wasRemoved=${flare.wasRemoved()} hitpoints=${flare.hitpoints} didDamage=${flare.didDamage()} damageTarget=${flare.damageTarget} flightTime=${flare.flightTime} timeSinceFizzling=${flare.timeSinceFizzling}")
                iterator.remove()
                continue
            }
            val expected = StarfallWingVfx.color(observation.type)
            val colors = flareColors(flare)
            expect(colors.isNotEmpty() && colors.all { it == expected } && flare.destroyedExplosionColorOverride == expected,
                "flare lifetime color changed mode=${observation.mode} type=${observation.type} flare=${System.identityHashCode(flare)} age=$age colors=$colors destroyed=${flare.destroyedExplosionColorOverride} expected=$expected")
            observation.colorSamples++
            observation.lastSampleAge = age
            val displacement = distance(flare.location, observation.birthLocation)
            val explosionDistance = distance(flare.location, observation.explosionOrigin)
            observation.maximumDisplacement = maxOf(observation.maximumDisplacement, displacement)
            observation.maximumExplosionDistance = maxOf(observation.maximumExplosionDistance, explosionDistance)
            val dispersed = displacement > 50f && explosionDistance > 50f
            if (age >= 0.25f && dispersed && !observation.earlyDispersed) {
                observation.earlyDispersed = true
                ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_FLARE_DISPERSED mode=${observation.mode} type=${observation.type} flare=${System.identityHashCode(flare)} age=$age displacement=$displacement explosionDistance=$explosionDistance birth=${observation.birthLocation} explosion=${observation.explosionOrigin}")
            }
            if (age >= 1f && dispersed && !observation.lateSampled) {
                observation.lateSampled = true
                lateColorTypes.getValue(observation.mode) += observation.type
                ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_FLARE_LATE mode=${observation.mode} type=${observation.type} flare=${System.identityHashCode(flare)} age=$age displacement=$displacement explosionDistance=$explosionDistance colors=$colors samples=${observation.colorSamples}")
            }
        }
    }

    private fun finishFlareObservations(): Boolean {
        if (flareObservations.isNotEmpty()) return false
        expect(lateColorTypes.values.all { it.size == StarfallWingTuning.ShuttleType.entries.size },
            "late flare color coverage missing types=$lateColorTypes ended=$endedFlares")
        if (ctx.failureReason != null) return false
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_LIFETIME_PASS cases=$completedCases lateColors=$lateColorTypes ended=$endedFlares")
        return true
    }

    private fun positionWitnesses(point: Vector2f) {
        val active = sample ?: return
        val radius = active.shot.type.radius
        val radians = Math.toRadians(active.projectile.facing.toDouble())
        val normalX = -sin(radians).toFloat()
        val normalY = cos(radians).toFloat()
        val innerDistance = radius * 0.5f + inside.collisionRadius
        val outerDistance = radius + 200f + outside.collisionRadius
        insideAnchor = Vector2f(point.x + normalX * innerDistance, point.y + normalY * innerDistance)
        outsideAnchor = Vector2f(point.x - normalX * outerDistance, point.y - normalY * outerDistance)
        inside.location.set(insideAnchor)
        outside.location.set(outsideAnchor)
        inside.isPhased = false
        outside.isPhased = false
        inside.exactBounds?.update(inside.location, inside.facing)
        outside.exactBounds?.update(outside.location, outside.facing)
    }

    private inner class WitnessListener(private val isInside: Boolean) : DamageTakenModifier, DamageListener {
        private var pendingRangeDamage = false
        private var hpBefore = 0f

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            pendingRangeDamage = false
            val witness = if (isInside) inside else outside
            hpBefore = witness.hitpoints
            val active = sample ?: return null
            if (damage == null || param is MissileAPI && param.isFlare) return null
            if (param === active.projectile) {
                fail("carrier struck witness case=$caseName inside=$isInside")
                return null
            }
            pendingRangeDamage = true
            if (isInside) {
                insideHits++
                insideRaw += damage.damage
            } else outsideHits++
            val terminal = active.projectile.wasRemoved() || active.projectile.isExpired
            if (mode == Mode.EXPIRE && !terminal) prematureDamage++
            if (explosionAt < 0f) explosionAt = ctx.elapsed
            expect(damage.type == active.shot.type.damageType, "range damage type case=$caseName actual=${damage.type} expected=${active.shot.type.damageType}")
            val relativePoint = point?.let { Vector2f(it.x - witness.location.x, it.y - witness.location.y) }
            val gridCell = point?.let { witness.armorGrid.getCellAtLocation(it)?.contentToString() }
            ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_DAMAGE case=$caseName inside=$isInside raw=${damage.damage} type=${damage.type} terminal=$terminal inPlay=${ctx.engine.isEntityInPlay(active.projectile)} fading=${active.projectile.isFading} expired=${active.projectile.isExpired} wasRemoved=${active.projectile.wasRemoved()} point=$point relativePoint=$relativePoint gridCell=$gridCell witnessCenter=${witness.location} witnessFacing=${witness.facing} witnessRadius=${witness.collisionRadius} witnessHp=$hpBefore witnessPhased=${witness.isPhased}")
            return null
        }

        override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
            val witness = if (isInside) inside else outside
            ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_APPLIED case=$caseName inside=$isInside pendingRange=$pendingRangeDamage targetMatches=${target === witness} hpBefore=$hpBefore hpAfter=${witness.hitpoints} phased=${witness.isPhased} hull=${result?.damageToHull} armor=${result?.totalDamageToArmor} shield=${result?.damageToShields} overMaxShield=${result?.overMaxDamageToShields} emp=${result?.empDamage}")
            if (!pendingRangeDamage || result == null) return
            pendingRangeDamage = false
            val applied = result.damageToHull + result.totalDamageToArmor + result.damageToShields + result.overMaxDamageToShields
            if (isInside) insideApplied += applied else outsideApplied += applied
        }
    }

    private fun evaluate(active: Sample) {
        val earlySurvivors = active.flares.count { it.earlyDispersed }
        val lateSurvivors = active.flares.count { it.lateSampled }
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_RESULT case=$caseName nativeHits=$nativeHits nativeShieldHit=$nativeShieldHit nativeShieldDamage=$nativeShieldDamage earlySurvivors=$earlySurvivors lateSurvivors=$lateSurvivors fadeFrames=$fadeFrames fadeAt=$fadeAt terminalAt=$terminalAt removedAt=$removedAt explosionAt=$explosionAt insideHits=$insideHits outsideHits=$outsideHits insideRaw=$insideRaw launchDamage=${active.launchDamage} insideApplied=$insideApplied outsideApplied=$outsideApplied prematureDamage=$prematureDamage prematureFlares=$prematureFlares flares=${flares.size} postRemoval=${ctx.elapsed - removedAt}")
        expect(insideHits == 1 && insideApplied > 0f && outsideHits == 0 && outsideApplied == 0f,
            "range damage count case=$caseName inside=$insideHits applied=$insideApplied outside=$outsideHits appliedOutside=$outsideApplied")
        expect(flares.size in 3..6 && flareBatches == 1, "flare burst case=$caseName count=${flares.size} batches=$flareBatches")
        expect(earlySurvivors >= 3, "flare survival and dispersion case=$caseName age=0.25 minimum=3 actual=$earlySurvivors")
        expect(lateSurvivors >= 1, "flare late survival and dispersion case=$caseName age=1 minimum=1 actual=$lateSurvivors")
        if (mode != Mode.EXPIRE) {
            expect(nativeHits == 1 && active.projectile.didDamage(), "native hit missing case=$caseName hits=$nativeHits didDamage=${active.projectile.didDamage()}")
            expect(nativeShieldHit == (mode == Mode.SHIELD_HIT), "native shield mismatch case=$caseName shieldHit=$nativeShieldHit")
            if (mode == Mode.SHIELD_HIT) expect(nativeShieldDamage > 0f, "native shield damage missing case=$caseName damage=$nativeShieldDamage")
        } else {
            expect(nativeHits == 0 && !active.projectile.didDamage(), "natural expiry hit target case=$caseName hits=$nativeHits")
            expect(fadeFrames > 0 && terminalAt >= fadeAt && removedAt >= fadeAt,
                "natural fade evidence missing case=$caseName frames=$fadeFrames fadeAt=$fadeAt terminalAt=$terminalAt removedAt=$removedAt")
            expect(prematureDamage == 0 && prematureFlares == 0,
                "explosion before terminal state case=$caseName damage=$prematureDamage flares=$prematureFlares")
        }
    }

    private fun finishCase(active: Sample) {
        val passedCase = caseName
        val covered = coveredTypes.getValue(mode)
        val fresh = covered.add(active.shot.type)
        ctx.log.info("[ASTD-Automation] XC2_EXPLOSION_PASS case=$passedCase newColor=$fresh completed=$completedCases attempts=$attempts")
        sample = null
        inside.isPhased = true
        outside.isPhased = true
        enemy.isPhased = true
        if (covered.size == StarfallWingTuning.ShuttleType.entries.size && completedCases < requiredCases) {
            mode = Mode.entries[mode.ordinal + 1]
        }
        shieldReadyAt = -1f
        readyAt = ctx.elapsed + 0.1f
        ctx.writeDiagnostics("CombatReady", player)
        ctx.writeTelemetry("CombatReady", player, weapon)
    }

    private fun spawnWitness(): ShipAPI {
        val manager = ctx.engine.getFleetManager(enemy.owner)
        manager.isSuppressDeploymentMessages = true
        return manager.spawnShipOrWing("kite_Stock", Vector2f(-300f, 1500f), 180f, 0f).also {
            it.isPhased = true
            it.shield?.toggleOff()
            it.mutableStats.hullBonus.modifyFlat(STAT_ID, 1000000f)
            it.hitpoints = it.maxHitpoints
            it.armorGrid.grid.forEach { row -> row.fill(0f) }
        }
    }

    private fun pin(ship: ShipAPI, anchor: Vector2f) {
        AutomationEvidence.stabilizeShip(ship, anchor, 180f, false)
        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
        if (ship === enemy && mode == Mode.SHIELD_HIT && shieldReadyAt >= 0f) {
            ship.shield?.forceFacing(180f)
        } else ship.shield?.toggleOff()
        ship.allWeapons.forEach { it.setForceNoFireOneFrame(true) }
    }

    private fun distance(from: Vector2f, to: Vector2f): Float = sqrt(AutomationEvidence.distanceSquared(from, to))

    private fun near(actual: Float, expected: Float): Boolean = abs(actual - expected) <= maxOf(0.01f, abs(expected) * 0.0001f)

    private fun expect(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    private fun fail(message: String) {
        if (ctx.failureReason == null) ctx.failureReason = "xc2 explosion $message"
        ctx.log.error("[ASTD-Automation] XC2_EXPLOSION_FAIL $message")
    }

    fun close() {
        if (closed) return
        closed = true
        enemy.removeListener(impactListener)
        inside.removeListener(insideListener)
        outside.removeListener(outsideListener)
        ctx.engine.removeEntity(inside)
        ctx.engine.removeEntity(outside)
        originalPhases.forEach { (ship, phased) -> ship.isPhased = phased }
    }

    private companion object {
        const val STAT_ID = "astd_xc2_explosion_probe"
        const val FLARE_WEAPON_ID = "flarelauncher1"
        val ORIGIN = Vector2f(-650f, 300f)
        val PLAYER_ANCHOR = Vector2f(-1200f, -900f)

        fun flareColors(flare: MissileAPI): List<Color> {
            val shifter = flare.engineController.flameColorShifter
            // 导弹喷口只登记在实体的 engineLocations，shipEngines 仅包含舰船与战机的引擎。
            val slots: List<EngineSlotAPI> = (flare as Missile).engineLocations
            return slots.map { shifter.getCurrForBase(it.color) }
        }
    }
}
