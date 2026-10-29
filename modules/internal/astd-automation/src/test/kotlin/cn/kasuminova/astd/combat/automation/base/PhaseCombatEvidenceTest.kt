package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.scenario.system.phaseai.*
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.*
import kotlin.test.*

class PhaseCombatEvidenceTest {
    private class Battle(private val suppliedEvidence: PhaseCombatEvidence? = null) {
        val engine = mock(CombatEngineAPI::class.java)
        val player = mock(ShipAPI::class.java)
        val enemy = mock(ShipAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val weapons = ArrayList<WeaponAPI>()
        val projectiles = ArrayList<DamagingProjectileAPI>()
        val missiles = ArrayList<MissileAPI>()
        val enemyListeners = ArrayList<DamageListener>()
        val playerListeners = ArrayList<DamageListener>()
        val location = Vector2f()
        var time = 0f
        var phased = false
        var hardFlux = 0f
        var hp = 1000f
        var dead = false
        val evidence = suppliedEvidence ?: PhaseCombatEvidence()
        val diag = mutableMapOf<String, Any>(
            "sampleTime" to 0f, "activeBurstCost" to 0f, "remainingBurstCost" to 900f,
            "routeVersion" to 0, "pathStage" to -1, "routeReplans" to 0, "routeProgress" to 0f,
            "routeLateralTravel" to 0f, "routeStageTravel" to 0f,
            "waypointX" to 0f, "waypointY" to 0f, "targetDistance" to 1000f, "effectiveWeaponRange" to 1000f,
            "hardFlux" to 0f, "peakHardFlux" to 0f, "softFlux" to 0f, "surfaceLoss" to 0f,
            "action" to "ATTACK", "reason" to "test", "targetId" to "enemy",
            "routeStage" to "NONE", "routeReplanReason" to "NONE", "routeFailureReason" to "NONE",
            "costUncertainties" to "", "routeCompleted" to false, "routeFailed" to false,
            "attackInRange" to true, "attackCanFire" to true,
        )
        init {
            `when`(engine.getTotalElapsedTime(false)).thenAnswer { time }
            `when`(engine.projectiles).thenReturn(projectiles)
            `when`(engine.missiles).thenReturn(missiles)
            `when`(engine.isEntityInPlay(player)).thenReturn(true)
            `when`(engine.isEntityInPlay(enemy)).thenReturn(true)
            `when`(player.id).thenReturn("player")
            `when`(player.location).thenReturn(location)
            `when`(player.velocity).thenReturn(Vector2f())
            `when`(player.isPhased).thenAnswer { phased }
            `when`(player.allWeapons).thenReturn(weapons)
            `when`(player.shipTarget).thenReturn(enemy)
            `when`(player.fluxTracker).thenReturn(flux)
            `when`(player.maxFlux).thenReturn(1000f)
            val stats = mock(MutableShipStatsAPI::class.java)
            `when`(stats.timeMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats).thenReturn(stats)
            `when`(flux.hardFlux).thenAnswer { hardFlux }
            `when`(enemy.id).thenReturn("enemy")
            `when`(enemy.owner).thenReturn(1)
            `when`(enemy.location).thenReturn(Vector2f(1000f, 0f))
            `when`(enemy.collisionRadius).thenReturn(50f)
            `when`(enemy.hitpoints).thenAnswer { hp }
            `when`(enemy.isHulk).thenAnswer { dead }
            doAnswer { enemyListeners.add(it.getArgument(0)); null }.`when`(enemy).addListener(any())
            doAnswer { playerListeners.add(it.getArgument(0)); null }.`when`(player).addListener(any())
            evidence.bind(engine, player, listOf(enemy))
        }
        fun weapon(burst: Int = 1): WeaponAPI {
            val weapon = mock(WeaponAPI::class.java)
            val spec = mock(ProjectileWeaponSpecAPI::class.java)
            `when`(weapon.id).thenReturn("same-spec")
            `when`(weapon.ship).thenReturn(player)
            `when`(weapon.spec).thenReturn(spec)
            `when`(spec.burstSize).thenReturn(burst)
            `when`(weapon.cooldownRemaining).thenReturn(1f)
            weapons.add(weapon)
            return weapon
        }
        fun shot(weapon: WeaponAPI): DamagingProjectileAPI {
            val shot = mock(DamagingProjectileAPI::class.java)
            `when`(shot.source).thenReturn(player)
            `when`(shot.weapon).thenReturn(weapon)
            projectiles.add(shot)
            return shot
        }
        fun hit(source: Any, amount: Float = 100f, param: Any? = source, armor: Float = 0f, shield: Float = 0f) {
            val result = mock(ApplyDamageResultAPI::class.java)
            `when`(result.damageToHull).thenReturn(amount)
            `when`(result.totalDamageToArmor).thenReturn(armor)
            `when`(result.damageToShields).thenReturn(shield)
            `when`(enemy.paramAboutToApplyDamage).thenReturn(param)
            enemyListeners.forEach { it.reportDamageApplied(source, enemy, result) }
        }
        fun playerHit(param: Any, amount: Float = 100f) {
            val result = mock(ApplyDamageResultAPI::class.java)
            `when`(result.damageToHull).thenReturn(amount)
            `when`(player.paramAboutToApplyDamage).thenReturn(param)
            playerListeners.forEach { it.reportDamageApplied(player, player, result) }
        }
        fun frame(at: Float) {
            time = at
            diag["sampleTime"] = time
            evidence.update(engine, time, player, listOf(enemy), diag)
        }
    }

    @Test fun `same spec weapons and firing intent cannot create shots`() {
        val b = Battle()
        val a = b.weapon()
        b.weapon()
        `when`(a.isFiring).thenReturn(true)
        repeat(5) { b.frame(it.toFloat()) }
        assertTrue(b.evidence.fireEvents.isEmpty())
    }

    @Test fun `fire evidence uses actual autofire target rather than planner target`() {
        val b = Battle()
        val weapon = b.weapon()
        val group = mock(WeaponGroupAPI::class.java)
        val autofire = mock(AutofireAIPlugin::class.java)
        `when`(b.player.getWeaponGroupFor(weapon)).thenReturn(group)
        `when`(group.isAutofiring).thenReturn(true)
        `when`(group.getAutofirePlugin(weapon)).thenReturn(autofire)
        `when`(autofire.targetShip).thenReturn(b.enemy)
        b.diag["targetId"] = "different-planned-target"
        b.shot(weapon)
        b.frame(1f)
        assertEquals("enemy", b.evidence.fireEvents.single().targetId)
        `when`(autofire.targetShip).thenReturn(null)
        b.diag["targetId"] = "enemy"
        b.shot(weapon)
        b.frame(2f)
        assertNull(b.evidence.fireEvents.last().targetId)
    }

    private class Seed : AbstractPhaseAiSeedScenario() {
        override val seed = 7950L
        override val scenarioId = "test_seed"
        val tracker get() = evidence
    }

    @Test fun `seed detects stagnation after previously firing`() {
        val seed = Seed()
        val b = Battle(seed.tracker)
        val ctx = mock(cn.kasuminova.astd.combat.automation.api.AutomationCombatContext::class.java)
        `when`(ctx.engine).thenReturn(b.engine)
        b.shot(b.weapon()); b.frame(1f)
        seed.verifyEvidence(ctx, 1f, b.player, listOf(b.enemy))
        b.phased = true; b.frame(2f)
        seed.verifyEvidence(ctx, 2f, b.player, listOf(b.enemy))
        b.frame(63f)
        assertEquals(AbstractPhaseAICombatScenario.Result.Failed, seed.verifyEvidence(ctx, 63f, b.player, listOf(b.enemy)))
    }

    @Test fun `seed requires full cycle and sustained observation not early cumulative success`() {
        val seed = Seed()
        val b = Battle(seed.tracker)
        val ctx = mock(cn.kasuminova.astd.combat.automation.api.AutomationCombatContext::class.java)
        `when`(ctx.engine).thenReturn(b.engine)
        val w = b.weapon()
        b.phased = true; b.frame(1f)
        seed.verifyEvidence(ctx, 1f, b.player, listOf(b.enemy))
        b.phased = false; b.frame(2f)
        val shot = b.shot(w); b.frame(3f); b.hit(shot, 600f)
        assertEquals(AbstractPhaseAICombatScenario.Result.Observing, seed.verifyEvidence(ctx, 3f, b.player, listOf(b.enemy)))
        for (time in listOf(30f, 60f, 90f, 120f, 150f)) {
            val continuedShot = b.shot(w); b.frame(time); b.hit(continuedShot)
            assertEquals(AbstractPhaseAICombatScenario.Result.Observing, seed.verifyEvidence(ctx, time, b.player, listOf(b.enemy)))
        }
        val finalShot = b.shot(w); b.frame(180f); b.hit(finalShot)
        assertEquals(AbstractPhaseAICombatScenario.Result.Completed, seed.verifyEvidence(ctx, 180f, b.player, listOf(b.enemy)))
    }

    @Test fun `each projectile is counted once and same spec instances stay independent`() {
        val b = Battle()
        val a = b.weapon()
        val c = b.weapon()
        b.shot(a); b.shot(c)
        b.frame(1f); b.frame(2f); b.frame(3f)
        assertEquals(2, b.evidence.fireEvents.size)
        b.shot(a); b.frame(4f)
        assertEquals(3, b.evidence.fireEvents.size)
    }

    @Test fun `actual beam interval is counted once not once per frame`() {
        val b = Battle()
        val w = b.weapon()
        val beam = mock(BeamAPI::class.java)
        val damage = mock(DamageAPI::class.java)
        `when`(w.isBeam).thenReturn(true)
        `when`(w.beams).thenReturn(listOf(beam))
        `when`(beam.source).thenReturn(b.player)
        `when`(beam.damage).thenReturn(damage)
        `when`(damage.damage).thenReturn(100f)
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.from).thenReturn(Vector2f())
        `when`(beam.to).thenReturn(Vector2f(500f, 0f))
        b.frame(1f); b.frame(2f)
        assertEquals(1, b.evidence.fireEvents.size)
        `when`(beam.brightness).thenReturn(0f)
        b.frame(3f)
        `when`(beam.brightness).thenReturn(1f)
        b.frame(4f)
        assertEquals(2, b.evidence.fireEvents.size)
    }

    @Test fun `lethal hp is settled exactly once even with absent player`() {
        val b = Battle()
        b.hp = 600f; b.frame(1f)
        b.dead = true
        b.evidence.update(b.engine, 2f, null, listOf(b.enemy), null)
        b.evidence.update(b.engine, 3f, null, listOf(b.enemy), null)
        assertEquals(1000f, b.evidence.totalEnemyHpDrop())
        assertEquals(1, b.evidence.enemyDestroyedCount())
    }

    @Test fun `friendly damage and old projectiles do not count as new output`() {
        val b = Battle()
        val w = b.weapon()
        val old = b.shot(w); b.frame(1f)
        b.hit(b.player, 999f, mock(ShipAPI::class.java))
        b.frame(3f); b.hit(b.player, 100f, old)
        assertEquals(100f, b.evidence.playerHullDamage())
        assertEquals(0f, b.evidence.playerHullDamage(2f))
        val fresh = b.shot(w); b.frame(4f); b.hit(b.player, 200f, fresh, armor = 50f, shield = 25f)
        assertEquals(200f, b.evidence.playerHullDamage(4f))
        assertEquals(275f, b.evidence.playerEffectiveDamage(4f))
    }

    @Test fun `missing fields invalid types and stale snapshots fail closed`() {
        for (key in Battle().diag.keys) {
            val b = Battle()
            b.time = 4f; b.diag["sampleTime"] = 4f; b.diag.remove(key)
            b.evidence.update(b.engine, 4f, b.player, listOf(b.enemy), b.diag)
            assertFalse(b.evidence.diagnosticsHealthy, key)
        }
        for (time in listOf(0f, 10f, Float.NaN)) {
            val b = Battle()
            b.time = 4f; b.diag["sampleTime"] = time
            b.evidence.update(b.engine, 4f, b.player, listOf(b.enemy), b.diag)
            assertFalse(b.evidence.diagnosticsHealthy)
        }
        val b = Battle()
        b.diag["routeCompleted"] = "false"
        b.frame(4f)
        assertFalse(b.evidence.diagnosticsHealthy)
    }

    @Test fun `recovery uses normal to threat axis and requires firing after exit`() {
        val b = Battle()
        `when`(b.enemy.location).thenReturn(Vector2f(0f, 1000f))
        val w = b.weapon()
        b.shot(w); b.frame(1f)
        b.hardFlux = 700f; b.diag["action"] = "RECOVER"; b.frame(2f)
        b.location.y = 500f; b.hardFlux = 500f; b.frame(3f)
        b.diag["action"] = "ATTACK"; b.frame(4f)
        assertEquals(0f, b.evidence.recoveryPhases.single().lateralDisplacement)
        assertFalse(b.evidence.recoveryPhases.single().newFireAfterRecovery)
        b.hardFlux = 700f; b.diag["action"] = "RECOVER"; b.frame(5f)
        b.location.x = 450f; b.hardFlux = 400f; b.frame(6f)
        b.diag["action"] = "ATTACK"; b.frame(7f)
        assertEquals(450f, b.evidence.recoveryPhases.last().lateralDisplacement)
        assertFalse(b.evidence.recoveryPhases.last().newFireAfterRecovery)
        val burst = b.weapon(3)
        b.shot(burst); b.shot(burst)
        val recoveredShot = b.shot(burst); b.frame(8f); b.hit(b.player, 100f, recoveredShot)
        b.frame(8.1f)
        assertTrue(b.evidence.recoveryPhases.last().newFireAfterRecovery)
    }

    @Test fun `idle future budget is not an interrupted burst`() {
        val b = Battle()
        b.frame(1f); b.phased = true; b.frame(2f)
        assertEquals(0, b.evidence.burstInterruptionCount)
        assertEquals(0, b.evidence.completedValidBurstCount)
    }

    @Test fun `single shot is not evidence of protecting a real burst`() {
        val b = Battle()
        val w = b.weapon(1)
        b.diag["activeBurstCost"] = 100f
        val shot = b.shot(w); b.frame(1f)
        `when`(w.isInBurst).thenReturn(false)
        b.frame(2f); b.hit(b.player, 100f, shot); b.frame(3f)
        assertEquals(0, b.evidence.completedValidBurstCount)
    }

    @Test fun `full real volley and its own delayed hit prove completed burst`() {
        val b = Battle()
        val w = b.weapon(3)
        `when`(w.isInBurst).thenReturn(true)
        b.diag["activeBurstCost"] = 300f
        b.shot(w); b.frame(1f)
        b.shot(w); b.frame(2f)
        val last = b.shot(w); b.frame(3f)
        `when`(w.isInBurst).thenReturn(false)
        b.diag["activeBurstCost"] = 0f
        b.frame(4f)
        assertEquals(0, b.evidence.completedValidBurstCount)
        b.hit(last); b.frame(5f)
        assertEquals(1, b.evidence.completedValidBurstCount)
        b.frame(6f)
        assertEquals(1, b.evidence.completedValidBurstCount)
    }

    @Test fun `routine dive cancels an incomplete real volley`() {
        val b = Battle()
        val w = b.weapon(3)
        `when`(w.isInBurst).thenReturn(true)
        b.diag["activeBurstCost"] = 300f
        b.shot(w); b.frame(1f)
        b.phased = true; b.frame(2f)
        assertEquals(1, b.evidence.burstInterruptionCount)
        assertEquals(0, b.evidence.completedValidBurstCount)
    }

    @Test fun `dem sequence survives missile removal and needs later surface and shot`() {
        val b = Battle()
        val missile = mock(MissileAPI::class.java)
        val ai = mock(MissileAIPlugin::class.java, withSettings().extraInterfaces(GuidedMissileAI::class.java))
        `when`(missile.projectileSpecId).thenReturn("dragon")
        `when`(missile.owner).thenReturn(1)
        `when`(missile.location).thenReturn(Vector2f(500f, 0f))
        `when`(missile.velocity).thenReturn(Vector2f(-200f, 0f))
        `when`(missile.missileAI).thenReturn(ai)
        `when`((ai as GuidedMissileAI).target).thenReturn(b.player)
        `when`(b.engine.isEntityInPlay(missile)).thenAnswer { missile in b.missiles }
        b.missiles.add(missile); b.frame(1f)
        assertEquals(1, b.evidence.demThreats.size)
        b.phased = true; b.diag["action"] = "EVADE"; b.frame(2f)
        b.missiles.clear(); b.frame(3f)
        b.phased = false; b.frame(5f)
        val event = b.evidence.demThreats.single()
        assertEquals(2f, event.defensiveDiveTimeSec)
        assertEquals(3f, event.threatClearedTimeSec)
        assertEquals(5f, event.resurfaceTimeSec)
        assertEquals(-1f, event.newFireTimeSec)
        b.shot(b.weapon()); b.frame(6f)
        assertEquals(6f, event.newFireTimeSec)
        assertFalse(event.hit)
    }

    @Test fun `same waypoint samples do not establish arrival or three stages`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f); b.frame(2f); b.frame(3f)
        assertEquals(1, b.evidence.routeWaypoints.size)
        assertFalse(b.evidence.routeWaypoints.single().arrived)
        assertFalse(b.evidence.completeRoute(b.evidence.routes.single()))
    }

    @Test fun `route tracks actual three stage arrival and target relative rear`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 600f
        b.diag["routeLateralTravel"] = 600f
        b.frame(2f)
        b.diag.putAll(mapOf("routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 500f))
        b.frame(3f)
        b.diag["waypointX"] = 550f
        `when`(b.enemy.facing).thenReturn(30f)
        b.frame(3.5f)
        assertEquals(1, b.evidence.routes.size)
        assertEquals(3.5f, b.evidence.routes.single().targetResponseAt)
        b.location.x = 550f
        b.diag["routeStageTravel"] = 50f
        b.frame(4f)
        b.diag.putAll(mapOf("routeStage" to "ATTACK", "pathStage" to 2, "waypointX" to 800f, "waypointY" to 200f))
        b.frame(5f)
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true; b.frame(6f)
        assertTrue(b.evidence.completeRoute(b.evidence.routes.single()))
    }

    @Test fun `area based stage transitions do not require chasing moving waypoint coordinates`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 500f
        b.diag.putAll(mapOf("routeLateralTravel" to 500f,
            "routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 2000f, "waypointY" to 2000f))
        b.frame(2f)
        b.location.set(100f, 600f)
        b.diag.putAll(mapOf("routeStageTravel" to 50f, "routeStage" to "ATTACK", "pathStage" to 2))
        b.frame(3f)
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true
        b.frame(4f)

        assertEquals(listOf(0, 1, 2), b.evidence.routeWaypoints.filter { it.arrived }.map { it.pathStage })
        assertTrue(b.evidence.completeRoute(b.evidence.routes.single()))
    }

    @Test fun `replan preserves moved stages even when transition sample is unphased`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 600f
        b.phased = false
        b.diag.putAll(mapOf("routeVersion" to 2, "routeReplans" to 1, "routeLateralTravel" to 600f,
            "routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 500f))
        b.frame(2f)
        b.location.x = 550f
        b.diag.putAll(mapOf("routeVersion" to 3, "routeReplans" to 2, "routeStageTravel" to 50f,
            "routeStage" to "ATTACK", "pathStage" to 2, "waypointX" to 800f, "waypointY" to 200f))
        b.frame(3f)
        b.phased = true
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true
        b.frame(4f)

        assertEquals(listOf(0, 1, 2), b.evidence.routeWaypoints.filter { it.arrived }.map { it.pathStage })
        assertTrue(b.evidence.completeRoute(b.evidence.routes.single()))
    }

    @Test fun `lateral arrival accumulates actual movement across replans`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 250f
        b.diag.putAll(mapOf("routeVersion" to 2, "routeReplans" to 1, "routeLateralTravel" to 250f))
        b.frame(2f)
        b.location.y = 500f
        b.diag.putAll(mapOf("routeVersion" to 3, "routeReplans" to 2, "routeLateralTravel" to 500f,
            "routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 500f))
        b.frame(3f)
        b.location.x = 550f
        b.diag.putAll(mapOf("routeVersion" to 4, "routeReplans" to 3, "routeStageTravel" to 50f,
            "routeStage" to "ATTACK", "pathStage" to 2, "waypointX" to 800f, "waypointY" to 200f))
        b.frame(4f)
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true
        b.frame(5f)

        val route = b.evidence.routes.single()
        assertEquals(500f, route.lateralTravel)
        assertEquals(listOf(0, 1, 2), route.waypoints.filter { it.arrived }.map { it.pathStage })
        assertTrue(b.evidence.completeRoute(route))
    }

    @Test fun `completed route survives waiting states and resets only on replan`() {
        val b = Battle()
        b.phased = true
        b.location.set(800f, 200f)
        b.diag.putAll(mapOf(
            "routeVersion" to 3, "routeReplans" to 2, "routeStage" to "ATTACK", "pathStage" to 2,
            "waypointX" to 800f, "waypointY" to 200f, "routeCompleted" to true,
        ))
        b.frame(1f)
        val route = b.evidence.routes.single()
        assertEquals(1f, route.completedAt)
        b.diag["routeCompleted"] = false
        b.frame(2f)
        assertEquals(1, b.evidence.routes.size)
        assertEquals(1f, route.completedAt)
        assertTrue(route.rearGeometry)
        b.diag["routeVersion"] = 4
        b.frame(3f)
        assertSame(route, b.evidence.routes.single())
        assertEquals(-1f, route.completedAt)
        assertFalse(route.rearGeometry)
        b.diag["routeCompleted"] = true
        b.frame(4f)
        assertEquals(1, b.evidence.routes.size)
        assertEquals(2, b.evidence.routes.sumOf { it.replans })
        assertEquals(4f, route.completedAt)
    }

    @Test fun `post completion timeout failure does not erase legal completion evidence`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 3, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 600f
        b.diag["routeLateralTravel"] = 600f
        b.frame(2f)
        b.diag.putAll(mapOf("routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 500f))
        b.frame(3f)
        b.location.x = 500f
        b.diag["routeStageTravel"] = 50f
        b.frame(4f)
        b.diag.putAll(mapOf("routeStage" to "ATTACK", "pathStage" to 2, "waypointX" to 800f, "waypointY" to 200f))
        b.frame(5f)
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true
        b.frame(6f)
        val route = b.evidence.routes.single()
        assertTrue(b.evidence.completeRoute(route))
        b.diag.putAll(mapOf(
            "routeCompleted" to false, "routeFailed" to true, "routeFailureReason" to "TIME_LIMIT",
        ))
        b.frame(7f)
        assertTrue(route.failed)
        assertEquals(6f, route.completedAt)
        assertTrue(b.evidence.completeRoute(route))
    }

    @Test fun `late surface after route completion cannot prove flank attack`() {
        val b = Battle()
        b.phased = true
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0, "waypointY" to 600f))
        b.frame(1f)
        b.location.y = 600f
        b.diag["routeLateralTravel"] = 600f
        b.frame(2f)
        b.diag.putAll(mapOf("routeStage" to "SIDE_ENTRY", "pathStage" to 1, "waypointX" to 500f))
        b.frame(3f)
        b.location.x = 500f
        b.diag["routeStageTravel"] = 50f
        b.frame(4f)
        b.diag.putAll(mapOf("routeStage" to "ATTACK", "pathStage" to 2, "waypointX" to 800f, "waypointY" to 200f))
        b.frame(5f)
        b.location.set(800f, 200f)
        b.diag["routeCompleted"] = true; b.frame(6f)
        val route = b.evidence.routes.single()
        assertTrue(b.evidence.completeRoute(route))
        b.phased = false; b.frame(10f)
        val lateShot = b.shot(b.weapon()); b.frame(11f); b.hit(lateShot, 1000f)
        assertNull(b.evidence.surfaceFireAfter(route.completedAt, route.targetId))
    }

    @Test fun `dem early surface cannot be repaired by a later phase cycle`() {
        val b = Battle()
        val missile = mock(MissileAPI::class.java)
        val ai = mock(MissileAIPlugin::class.java, withSettings().extraInterfaces(GuidedMissileAI::class.java))
        `when`(missile.projectileSpecId).thenReturn("dragon")
        `when`(missile.owner).thenReturn(1)
        `when`(missile.location).thenReturn(Vector2f(500f, 0f))
        `when`(missile.velocity).thenReturn(Vector2f(-200f, 0f))
        `when`(missile.missileAI).thenReturn(ai)
        `when`((ai as GuidedMissileAI).target).thenReturn(b.player)
        `when`(b.engine.isEntityInPlay(missile)).thenAnswer { missile in b.missiles }
        b.missiles.add(missile); b.frame(1f)
        b.phased = true; b.diag["action"] = "EVADE"; b.frame(2f)
        b.missiles.clear(); b.frame(3f)
        b.phased = false; b.frame(3.5f)
        val firstShot = b.shot(b.weapon()); b.frame(4f); b.hit(firstShot, 600f)
        assertEquals(0, b.evidence.validDemSequenceCount())
        b.phased = true; b.frame(8f)
        b.phased = false; b.frame(10f)
        val laterShot = b.shot(b.weapon()); b.frame(11f); b.hit(laterShot, 600f)
        assertEquals(0, b.evidence.validDemSequenceCount())
        assertEquals(3.5f, b.evidence.demThreats.single().resurfaceTimeSec)
    }

    @Test fun `replan reason alone cannot prove target rotation and limit fails`() {
        val b = Battle()
        b.diag.putAll(mapOf("routeVersion" to 1, "routeStage" to "LATERAL", "pathStage" to 0))
        b.frame(1f)
        b.diag.putAll(mapOf("routeVersion" to 2, "routeReplans" to 1, "routeReplanReason" to "TARGET_TURNED"))
        b.frame(2f)
        assertEquals(-1f, b.evidence.routes.single().turnReplanAt)
        `when`(b.enemy.facing).thenReturn(30f)
        b.diag.putAll(mapOf("routeVersion" to 3, "routeReplans" to 2)); b.frame(3f)
        assertEquals(3f, b.evidence.routes.single().turnReplanAt)
        b.diag["routeReplans"] = 3; b.frame(4f)
        assertTrue(b.evidence.routeLoopFailure)
    }

    @Test fun `seed mission fleet is exact and duplicate hulls have distinct ids`() {
        val scenarios = listOf(PhaseAiSeed1Scenario(), PhaseAiSeed2Scenario(), PhaseAiSeed3Scenario(), PhaseAiSeed4Scenario(), PhaseAiSeed5Scenario(), PhaseAiSeed6Scenario())
        for (scenario in scenarios) {
            val fleet = scenario.buildFleet()
            assertEquals(fleet.size, fleet.map { it.uniqueId }.distinct().size)
            assertEquals(fleet, scenario.buildFleet())
            val api = mock(MissionDefinitionAPI::class.java)
            val members = ArrayList<Pair<String, FleetMemberAPI>>()
            `when`(api.addToFleet(any(FleetSide::class.java), anyString(), eq(FleetMemberType.SHIP), anyBoolean())).thenAnswer {
                val member = mock(FleetMemberAPI::class.java)
                members.add(it.getArgument<String>(1) to member)
                member
            }
            AbstractPhaseAICombatScenario.addMissionFleet(api, scenario)
            assertEquals(fleet.map { it.variantId }, members.map { it.first })
            fleet.zip(members).forEach { (entry, pair) -> verify(pair.second).id = entry.uniqueId }
        }
        assertEquals(2, scenarios[0].buildFleet().count { it.side == FleetSide.ENEMY })
        assertEquals(2, scenarios[2].buildFleet().count { it.side == FleetSide.ENEMY })
        assertEquals(3, scenarios[5].buildFleet().count { it.side == FleetSide.ENEMY })
    }

    @Test fun `flank locks first surface even when only a later surface has a shot`() {
        for (rearAtFirstSurface in listOf(false, true)) {
            val b = Battle()
            b.phased = true; b.frame(1f)
            b.location.y = if (rearAtFirstSurface) 200f else 0f
            b.phased = false; b.frame(2f)
            b.phased = true; b.frame(2.5f)
            b.location.y = 200f
            b.phased = false; b.frame(3f)
            b.shot(b.weapon()); b.frame(3.5f)
            assertNull(b.evidence.surfaceFireAfter(1f, "enemy"))
            assertNotNull(b.evidence.completeSurfaceFire())
        }
    }

    @Test fun `flank checks target geometry in the first all target surface cycle`() {
        val b = Battle()
        b.phased = true; b.frame(1f)
        b.location.y = 200f
        b.phased = false; b.frame(2f)
        b.evidence.surfaceEvents.add(0, PhaseCombatEvidence.SurfaceEvent(2f, "other", false))
        b.shot(b.weapon()); b.frame(3f)
        assertSame(b.evidence.fireEvents.single(), b.evidence.surfaceFireAfter(1f, "enemy"))
        b.evidence.surfaceEvents.add(0, PhaseCombatEvidence.SurfaceEvent(1.5f, "other", true))
        assertNull(b.evidence.surfaceFireAfter(1f, "enemy"))
    }

    @Test fun `surface firing windows include exact boundaries and reject late shots`() {
        for (delay in listOf(2f, 2.01f)) {
            val b = Battle()
            b.phased = true; b.frame(1f)
            b.location.y = 200f
            b.phased = false; b.frame(4f)
            b.shot(b.weapon()); b.frame(4f + delay)
            assertEquals(delay == 2f, b.evidence.surfaceFireAfter(1f, "enemy") != null)
            assertEquals(delay == 2f, b.evidence.completeSurfaceFire() != null)
        }
    }

    @Test fun `later fire in the same surface window contributes to aggregate damage`() {
        val b = Battle()
        val weapon = b.weapon()
        b.phased = true; b.frame(1f)
        b.location.y = 200f
        b.phased = false; b.frame(2f)
        val weak = b.shot(weapon); b.frame(2f)
        val strong = b.shot(weapon); b.frame(2.5f)
        b.hit(weak, 100f)
        b.hit(strong, 900f)

        val fires = b.evidence.surfaceFiresAfter(1f, "enemy")
        assertEquals(2, fires.size)
        assertEquals(100f, b.evidence.fireEffectiveDamage(fires[0], "enemy"))
        assertEquals(900f, b.evidence.fireEffectiveDamage(fires[1], "enemy"))
        assertEquals(1000f, b.evidence.surfaceEffectiveDamage(1f, "enemy"))
    }

    @Test fun `complete surface firing has no three second deadline from battle start`() {
        val b = Battle()
        b.shot(b.weapon()); b.frame(1f)
        assertNull(b.evidence.completeSurfaceFire())
        b.phased = true; b.frame(20f)
        b.phased = false; b.frame(30f)
        val shot = b.shot(b.weapon())
        b.time = 30f; b.hit(shot)
        b.frame(30f)
        val fire = b.evidence.completeSurfaceFire()
        assertNotNull(fire)
        assertEquals(30f, fire.timeSec)
        assertEquals(30f, fire.surfaceTime)
        assertNull(b.evidence.surfaceFireAfter(0f, "enemy"))
    }

    @Test fun `route damage is limited to the bound fire target and ten second hit window`() {
        val b = Battle()
        val w = b.weapon()
        b.phased = true; b.frame(1f)
        b.location.y = 200f
        b.phased = false; b.frame(2f)
        val shot = b.shot(w); b.frame(3f)
        val fire = assertNotNull(b.evidence.surfaceFireAfter(1f, "enemy"))
        b.hit(shot, 100f, armor = 50f, shield = 25f)
        val firstHit = b.evidence.hitEvents.single()
        b.evidence.hitEvents.add(firstHit.copy(targetId = "other", hullDamage = 2000f))
        b.evidence.hitEvents.add(firstHit.copy(fire = fire.copy(), hullDamage = 2000f))
        b.phased = true; b.frame(4f)
        b.phased = false; b.frame(5f)
        val laterShot = b.shot(w); b.frame(6f); b.hit(laterShot, 2000f)
        assertEquals(175f, b.evidence.fireEffectiveDamage(fire, "enemy"))
        b.frame(13f); b.hit(shot, 100f)
        assertEquals(275f, b.evidence.fireEffectiveDamage(fire, "enemy"))
        b.frame(13.01f); b.hit(shot, 2000f)
        assertEquals(275f, b.evidence.fireEffectiveDamage(fire, "enemy"))
    }

    @Test fun `recovery requires a hit by ten seconds after exit not just a timely launch`() {
        for (hitTime in listOf(13f, 13.01f)) {
            val b = Battle()
            b.hardFlux = 700f; b.diag["action"] = "RECOVER"; b.frame(1f)
            b.hardFlux = 400f; b.location.y = 450f; b.frame(2f)
            b.diag["action"] = "ATTACK"; b.frame(3f)
            val burst = b.weapon(3)
            b.shot(burst); b.shot(burst)
            val shot = b.shot(burst); b.frame(4f); b.frame(4.1f)
            assertFalse(b.evidence.recoveryPhases.single().newFireAfterRecovery)
            b.frame(hitTime); b.hit(shot, 100f); b.frame(hitTime + 0.1f)
            assertEquals(hitTime == 13f, b.evidence.recoveryPhases.single().newFireAfterRecovery)
        }
    }

    @Test fun `three distinct projectiles in one frame complete a real burst without duplicate counting`() {
        val b = Battle()
        val w = b.weapon(3)
        `when`(w.isInBurst).thenReturn(true)
        b.diag["activeBurstCost"] = 300f
        val first = b.shot(w)
        b.shot(w)
        val last = b.shot(w)
        b.projectiles.add(first)
        b.frame(1f)
        b.hit(last)
        assertEquals(3, b.evidence.fireEvents.single().shots)
        `when`(w.isInBurst).thenReturn(false)
        b.diag["activeBurstCost"] = 0f
        b.frame(2f)
        assertEquals(1, b.evidence.completedValidBurstCount)
        b.phased = true; b.frame(3f)
        assertEquals(0, b.evidence.burstInterruptionCount)
        assertEquals(3, b.evidence.fireEvents.single().shots)
        assertEquals(1, b.evidence.completedValidBurstCount)
    }

    private fun demBattle(): Battle {
        val b = Battle()
        val missile = mock(MissileAPI::class.java)
        `when`(missile.projectileSpecId).thenReturn("dragon")
        `when`(missile.owner).thenReturn(1)
        `when`(missile.location).thenReturn(Vector2f(500f, 0f))
        `when`(missile.velocity).thenReturn(Vector2f(-200f, 0f))
        `when`(b.engine.isEntityInPlay(missile)).thenAnswer { missile in b.missiles }
        b.missiles.add(missile); b.frame(1f)
        b.phased = true; b.diag["action"] = "EVADE"; b.frame(2f)
        return b
    }

    @Test fun `dem surface before missile disappears is permanently bound and invalid`() {
        val b = demBattle()
        b.phased = false; b.frame(3f)
        val event = b.evidence.demThreats.single()
        assertEquals(3f, event.resurfaceTimeSec)
        assertEquals(-1f, event.threatClearedTimeSec)
        b.phased = true; b.frame(4f)
        b.missiles.clear(); b.frame(5f)
        b.phased = false; b.frame(7f)
        val shot = b.shot(b.weapon()); b.frame(7.5f); b.hit(shot, 600f)
        assertEquals(3f, event.resurfaceTimeSec)
        assertNull(event.newFire)
        assertEquals(0, b.evidence.validDemSequenceCount())
    }

    @Test fun `dem threat ends when missile has passed without requiring despawn`() {
        val b = demBattle()
        `when`(b.missiles.single().location).thenReturn(Vector2f(-500f, 0f))
        b.frame(3f)
        val event = b.evidence.demThreats.single()
        assertEquals(3f, event.threatClearedTimeSec)
        assertTrue(b.engine.isEntityInPlay(b.missiles.single()))
        b.phased = false; b.frame(5f)
        val shot = b.shot(b.weapon()); b.frame(6f); b.hit(shot, 600f)
        assertEquals(1, b.evidence.validDemSequenceCount())
    }

    @Test fun `dem first surface accepts same frame firing and timely actual damage`() {
        val b = demBattle()
        b.missiles.clear(); b.frame(3f)
        b.phased = false
        val shot = b.shot(b.weapon())
        b.time = 5f; b.hit(shot, 600f)
        b.frame(5f)
        val event = b.evidence.demThreats.single()
        assertEquals(5f, event.resurfaceTimeSec)
        assertEquals(5f, event.newFireTimeSec)
        assertSame(b.evidence.fireEvents.single(), event.newFire)
        assertEquals(1, b.evidence.validDemSequenceCount())
    }

    @Test fun `dem rejects late hits and damage from other fire events`() {
        for (hitTime in listOf(10f, 10.01f)) {
            val b = demBattle()
            b.missiles.clear(); b.frame(3f)
            b.phased = false; b.frame(5f)
            val w = b.weapon()
            val shot = b.shot(w); b.frame(6f); b.hit(shot, 100f)
            val otherShot = b.shot(w); b.frame(7f); b.hit(otherShot, 2000f)
            assertEquals(0, b.evidence.validDemSequenceCount())
            b.frame(hitTime); b.hit(shot, 401f)
            assertEquals(if (hitTime == 10f) 1 else 0, b.evidence.validDemSequenceCount())
        }
    }

    @Test fun `dem first fire must occur within five seconds of first surface`() {
        for (fireTime in listOf(10f, 10.01f)) {
            val b = demBattle()
            b.missiles.clear(); b.frame(3f)
            b.phased = false; b.frame(5f)
            val shot = b.shot(b.weapon()); b.frame(fireTime); b.hit(shot, 600f)
            assertEquals(if (fireTime == 10f) 1 else 0, b.evidence.validDemSequenceCount())
        }
    }

    @Test fun `seed fails continuous firing with over sixty seconds without effective hits`() {
        val seed = Seed()
        val b = Battle(seed.tracker)
        val ctx = mock(cn.kasuminova.astd.combat.automation.api.AutomationCombatContext::class.java)
        `when`(ctx.engine).thenReturn(b.engine)
        val w = b.weapon()
        b.phased = true; b.frame(1f)
        seed.verifyEvidence(ctx, 1f, b.player, listOf(b.enemy))
        b.phased = false; b.frame(2f)
        val shot = b.shot(w); b.frame(3f); b.hit(shot, 600f)
        seed.verifyEvidence(ctx, 3f, b.player, listOf(b.enemy))
        for (time in listOf(30f, 60f, 63f)) {
            b.shot(w); b.frame(time)
            assertEquals(AbstractPhaseAICombatScenario.Result.Observing, seed.verifyEvidence(ctx, time, b.player, listOf(b.enemy)))
        }
        b.shot(w); b.frame(63.01f)
        assertEquals(AbstractPhaseAICombatScenario.Result.Failed, seed.verifyEvidence(ctx, 63.01f, b.player, listOf(b.enemy)))
        val json = StringBuilder()
        seed.appendScenarioEvidence(ctx, json)
        assertContains(json.toString(), "\"seedLongestFireGapSec\":")
        assertContains(json.toString(), "\"seedLongestEffectiveHitGapSec\":")
    }

    @Test fun `seed later hit cannot erase an already excessive hit gap`() {
        val seed = Seed()
        val b = Battle(seed.tracker)
        val ctx = mock(cn.kasuminova.astd.combat.automation.api.AutomationCombatContext::class.java)
        `when`(ctx.engine).thenReturn(b.engine)
        val w = b.weapon()
        for (time in listOf(1f, 30f, 60f)) {
            b.shot(w); b.frame(time)
            assertEquals(AbstractPhaseAICombatScenario.Result.Observing, seed.verifyEvidence(ctx, time, b.player, listOf(b.enemy)))
        }
        val shot = b.shot(w); b.frame(61f); b.hit(shot, 600f)
        assertEquals(AbstractPhaseAICombatScenario.Result.Failed, seed.verifyEvidence(ctx, 61f, b.player, listOf(b.enemy)))
    }

    @Test fun `seed uses event times across delayed verification and nonzero combat origin`() {
        val seed = Seed()
        val b = Battle(seed.tracker)
        val ctx = mock(cn.kasuminova.astd.combat.automation.api.AutomationCombatContext::class.java)
        `when`(ctx.engine).thenReturn(b.engine)
        val w = b.weapon()
        b.phased = true; b.frame(101f)
        seed.verifyEvidence(ctx, 1f, b.player, listOf(b.enemy))
        b.phased = false; b.frame(120f)
        seed.verifyEvidence(ctx, 20f, b.player, listOf(b.enemy))
        for (time in listOf(121f, 151f, 181f, 211f, 241f, 271f)) {
            val shot = b.shot(w); b.frame(time); b.hit(shot, 101f)
        }
        b.frame(280f)
        assertEquals(AbstractPhaseAICombatScenario.Result.Completed, seed.verifyEvidence(ctx, 180f, b.player, listOf(b.enemy)))
    }
}
