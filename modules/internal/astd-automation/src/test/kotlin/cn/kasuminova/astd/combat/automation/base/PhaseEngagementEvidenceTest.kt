package cn.kasuminova.astd.combat.automation.base

import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.*
import kotlin.test.*

class PhaseEngagementEvidenceTest {
    private class Observation {
        val engine = mock(CombatEngineAPI::class.java)
        val player = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val data = mutableMapOf<String, Any>()
        val ships = arrayListOf(player, enemy)
        val projectiles = ArrayList<DamagingProjectileAPI>()
        val weapons = ArrayList<WeaponAPI>()
        val listeners = ArrayList<Any>()
        val evidence = PhaseCombatEvidence()
        val location = Vector2f()
        var time = 0f
        var state = ShipSystemAPI.SystemState.IDLE
        var level = 0f
        var phased = false
        var hardFlux = 100f
        var overloaded = false
        var venting = false
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
            data["astd_phase_diagnostics_player"] = diag
            `when`(engine.customData).thenReturn(data)
            `when`(engine.getTotalElapsedTime(false)).thenAnswer { time }
            `when`(engine.ships).thenReturn(ships)
            `when`(engine.projectiles).thenReturn(projectiles)
            `when`(engine.missiles).thenReturn(emptyList())
            `when`(engine.isEntityInPlay(any())).thenAnswer { it.getArgument<Any>(0) in ships }
            `when`(player.id).thenReturn("player")
            `when`(player.location).thenReturn(location)
            `when`(player.velocity).thenReturn(Vector2f())
            `when`(player.collisionRadius).thenReturn(50f)
            `when`(player.allWeapons).thenReturn(weapons)
            `when`(player.phaseCloak).thenReturn(cloak)
            `when`(cloak.state).thenAnswer { state }
            `when`(cloak.effectLevel).thenAnswer { level }
            `when`(player.isPhased).thenAnswer { phased }
            `when`(player.fluxTracker.hardFlux).thenAnswer { hardFlux }
            `when`(player.maxFlux).thenReturn(1000f)
            `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
            `when`(player.mutableStats.energyRoFMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.ballisticRoFMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.missileRoFMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.energyAmmoRegenMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.ballisticAmmoRegenMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.missileAmmoRegenMult).thenReturn(MutableStat(1f))
            `when`(player.mutableStats.timeMult.modifiedValue).thenAnswer { 1f + 2f * level }
            `when`(enemy.id).thenReturn("enemy")
            `when`(enemy.location).thenReturn(Vector2f(1000f, 0f))
            `when`(enemy.velocity).thenReturn(Vector2f())
            `when`(enemy.collisionRadius).thenReturn(50f)
            `when`(enemy.hitpoints).thenReturn(10000f)
            `when`(enemy.fluxTracker.isOverloaded).thenAnswer { overloaded }
            `when`(enemy.fluxTracker.isVenting).thenAnswer { venting }
            doAnswer { listeners += it.getArgument<Any>(0); null }.`when`(enemy).addListener(any())
            evidence.bind(engine, player, listOf(enemy))
        }

        fun weapon(ship: ShipAPI = player, burst: Int = 3): WeaponAPI {
            val weapon = mock(WeaponAPI::class.java)
            val spec = mock(ProjectileWeaponSpecAPI::class.java)
            val ammo = mock(AmmoTrackerAPI::class.java)
            `when`(weapon.ammoTracker).thenReturn(ammo)
            `when`(weapon.usesAmmo()).thenReturn(false)
            `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
            `when`(weapon.ammo).thenReturn(0)
            `when`(ammo.maxAmmo).thenReturn(12)
            `when`(ammo.reloadProgress).thenReturn(0f)
            `when`(ammo.reloadSize).thenReturn(3f)
            `when`(ammo.ammoPerSecond).thenReturn(0.5f)
            `when`(weapon.ship).thenReturn(ship)
            `when`(weapon.spec).thenReturn(spec)
            `when`(weapon.range).thenReturn(1000f)
            `when`(spec.burstSize).thenReturn(burst)
            `when`(spec.refireDelay).thenReturn(8f)
            `when`(weapon.cooldown).thenReturn(8f)
            `when`(weapon.refireDelay).thenReturn(8f)
            `when`(weapon.cooldownRemaining).thenReturn(6f)
            if (ship === player) {
                val group = mock(WeaponGroupAPI::class.java)
                val autofire = mock(AutofireAIPlugin::class.java)
                `when`(ship.getWeaponGroupFor(weapon)).thenReturn(group)
                `when`(group.isAutofiring).thenReturn(true)
                `when`(group.getAutofirePlugin(weapon)).thenReturn(autofire)
                `when`(autofire.targetShip).thenReturn(enemy)
            }
            if (ship === player) weapons += weapon else `when`(ship.allWeapons).thenReturn(listOf(weapon))
            return weapon
        }

        fun shot(weapon: WeaponAPI, age: Float = 0f): DamagingProjectileAPI {
            val projectile = mock(DamagingProjectileAPI::class.java)
            val source = weapon.ship
            `when`(projectile.source).thenReturn(source)
            `when`(projectile.weapon).thenReturn(weapon)
            `when`(projectile.elapsed).thenReturn(age)
            projectiles += projectile
            return projectile
        }

        fun frame(now: Float) {
            time = now
            diag["sampleTime"] = now
            evidence.update(engine, now, player, listOf(enemy), diag)
        }

        fun hit(param: Any, amount: Float, afterPreDamage: () -> Unit = {}) {
            val result = mock(ApplyDamageResultAPI::class.java)
            `when`(result.damageToHull).thenReturn(amount)
            `when`(enemy.paramAboutToApplyDamage).thenReturn(param)
            listeners.filterIsInstance<DamageTakenModifier>().forEach {
                it.modifyDamageTaken(param, enemy, mock(DamageAPI::class.java), enemy.location, false)
            }
            afterPreDamage()
            listeners.filterIsInstance<DamageListener>().forEach { it.reportDamageApplied(player, enemy, result) }
        }

        fun volley(weapon: WeaponAPI, start: Float): DamagingProjectileAPI {
            `when`(weapon.isInBurst).thenReturn(true)
            val shots = (weapon.spec as ProjectileWeaponSpecAPI).burstSize.coerceAtLeast(1)
            var last = shot(weapon)
            for (index in 0 until shots) {
                if (index > 0) last = shot(weapon)
                frame(start + index * 0.1f)
            }
            `when`(weapon.isInBurst).thenReturn(false)
            frame(start + shots * 0.1f)
            return last
        }

        fun command(mode: String = "PHASE_COOLDOWN", action: String = "RECOVER") {
            data["astd_phase_activation_player"] = mapOf(
                "time" to time, "action" to action, "recoverMode" to mode, "reason" to "相位加速关键武器冷却",
                "hardFlux" to hardFlux, "maxFlux" to 1000f, "timeMult" to 1f, "targetId" to "enemy",
                "weapons" to weapons.map {
                    mapOf(
                        "weapon" to it, "remaining" to it.cooldownRemaining,
                        "cooldown" to it.cooldown, "refireDelay" to it.refireDelay, "inBurst" to it.isInBurst,
                        "naturalCooldownRate" to 1f, "usesAmmo" to it.usesAmmo(), "ammo" to it.ammo,
                        "requiredAmmo" to ((it.spec as? ProjectileWeaponSpecAPI)?.burstSize ?: 1),
                        "maxAmmo" to it.ammoTracker.maxAmmo, "reloadProgress" to it.ammoTracker.reloadProgress,
                        "reloadSize" to it.ammoTracker.reloadSize, "ammoPerSecond" to it.ammoTracker.ammoPerSecond,
                        "naturalAmmoRegenMult" to 1f,
                    )
                },
            )
        }

        fun enter(now: Float) {
            state = ShipSystemAPI.SystemState.IN; level = 0.5f; phased = true; frame(now)
        }

        fun exit(now: Float) {
            state = ShipSystemAPI.SystemState.OUT; level = 0.2f; frame(now - 0.1f)
            state = ShipSystemAPI.SystemState.COOLDOWN; level = 0f; phased = false; frame(now)
        }
    }

    @Test fun `phase entry is a real transition and locks preactivation flux and purpose`() {
        val b = Observation()
        b.frame(1f); b.command()
        b.diag["reason"] = "等待当前模拟满足完整攻击条件"
        b.hardFlux = 200f; b.enter(1.1f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f; b.hardFlux = 700f; b.frame(2f)
        val cycle = b.evidence.phaseCycles.cycles.single()
        assertEquals(100f, cycle.entryHardFlux)
        assertEquals("PHASE_COOLDOWN", cycle.recoverMode)
        assertEquals("相位加速关键武器冷却", cycle.reason)
        assertTrue(cycle.attributed)
        b.exit(3f)
        b.state = ShipSystemAPI.SystemState.IDLE; b.frame(4f)
        b.enter(4.1f)
        assertFalse(b.evidence.phaseCycles.cycles.last().attributed)
    }

    @Test fun `command without observed entry cannot establish phase cooldown`() {
        val b = Observation()
        b.frame(1f); b.command(); b.frame(2f)
        assertTrue(b.evidence.phaseCycles.cycles.isEmpty())
    }

    @Test fun `high flux emergency is attributed to its own actual entry`() {
        val b = Observation()
        b.hardFlux = 700f; b.frame(1f); b.command("NONE", "EVADE"); b.enter(1.1f)
        val cycle = b.evidence.phaseCycles.cycles.single()
        assertEquals(700f, cycle.entryHardFlux)
        assertEquals("EVADE", cycle.action)
    }

    @Test fun `distance stays at launch and delayed impact is bound to that target`() {
        val b = Observation()
        val shot = b.shot(b.weapon()); b.frame(1f)
        val fire = b.evidence.fireEvents.single()
        b.location.x = 950f; b.frame(2f); b.hit(shot, 600f)
        assertEquals(1000f, fire.distance)
        assertEquals("enemy", fire.targetId)
        assertEquals(1, PhaseEngagementEvidence.rangedAttacks(b.evidence, 0.65f).size)
        b.diag["targetId"] = "missing"
        val unknownWeapon = b.weapon()
        val unknownTarget = mock(ShipAPI::class.java)
        `when`(unknownTarget.id).thenReturn("missing")
        `when`(b.player.getWeaponGroupFor(unknownWeapon).getAutofirePlugin(unknownWeapon).targetShip)
            .thenReturn(unknownTarget)
        val unknown = b.shot(unknownWeapon); b.frame(3f)
        b.diag["targetId"] = "enemy"; b.location.x = 0f; b.frame(4f); b.hit(unknown, 2000f)
        assertNull(b.evidence.fireEvents.last().targetId)
        assertEquals(1, PhaseEngagementEvidence.rangedAttacks(b.evidence, 0.65f).size)
    }

    @Test fun `close hits and late first observations cannot become ranged evidence`() {
        val b = Observation()
        b.location.x = 950f
        val close = b.shot(b.weapon()); b.frame(1f); b.hit(close, 1000f)
        assertTrue(PhaseEngagementEvidence.rangedAttacks(b.evidence, 0.5f).isEmpty())
        b.location.x = 0f
        val old = b.shot(b.weapon(), 3f); b.frame(4f); b.hit(old, 1000f)
        assertEquals(1, b.evidence.fireEvents.size)
        assertTrue(PhaseEngagementEvidence.rangedAttacks(b.evidence, 0.65f).isEmpty())
    }

    @Test fun `wing damage has carrier attribution and cannot borrow mother ship damage`() {
        val b = Observation()
        val fighter = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(fighter.id).thenReturn("fighter")
        `when`(fighter.isFighter).thenReturn(true)
        `when`(fighter.wing.sourceShip).thenReturn(b.player)
        `when`(fighter.shipTarget).thenReturn(null)
        `when`(fighter.location).thenReturn(Vector2f(800f, 0f))
        b.ships += fighter
        val weapon = b.weapon(fighter, 1)
        b.frame(1f)
        assertTrue(PhaseEngagementEvidence.carrierAttacks(b.evidence).isEmpty())
        val wingShot = b.shot(weapon); b.frame(2f)
        val motherShot = b.shot(b.weapon()); b.frame(3f); b.hit(motherShot, 1000f)
        assertTrue(PhaseEngagementEvidence.carrierAttacks(b.evidence).isEmpty())
        b.location.x = 950f; b.frame(4f); b.hit(wingShot, 600f)
        val attack = PhaseEngagementEvidence.carrierAttacks(b.evidence).single()
        assertNull(attack.fire.targetId)
        assertEquals("enemy", attack.targetId)
        assertEquals(1000f, attack.distance)
        assertEquals("player", attack.fire.carrierId)
        assertEquals(600f, attack.damage)
        assertEquals(1000f, b.evidence.playerEffectiveDamage())
    }

    @Test fun `window uses predamage state not launch time or state caused by this hit`() {
        val b = Observation()
        val shot = b.shot(b.weapon()); b.frame(1f)
        b.overloaded = true; b.frame(2f); b.overloaded = false; b.hit(shot, 1000f)
        assertEquals(0f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.OVERLOAD))
        b.hit(shot, 1000f) { b.overloaded = true }
        assertEquals(0f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.OVERLOAD))
        b.hit(shot, 400f)
        assertEquals(400f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.OVERLOAD))
        b.overloaded = false; b.venting = true; b.hit(shot, 500f)
        assertEquals(500f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.VENT))
    }

    @Test fun `continuous beam already firing can deal damage in both real windows`() {
        val b = Observation()
        val weapon = b.weapon(burst = 1)
        val beam = mock(BeamAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(weapon.isBeam).thenReturn(true)
        `when`(weapon.beams).thenReturn(listOf(beam))
        `when`(beam.source).thenReturn(b.player)
        `when`(beam.weapon).thenReturn(weapon)
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(beam.from).thenReturn(Vector2f())
        `when`(beam.to).thenReturn(Vector2f(1000f, 0f))
        b.frame(1f)
        b.overloaded = true; b.frame(2f); b.hit(beam, 400f)
        b.overloaded = false; b.venting = true; b.frame(3f); b.hit(beam, 500f)
        assertEquals(1, b.evidence.fireEvents.size)
        assertEquals(400f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.OVERLOAD))
        assertEquals(500f, PhaseEngagementEvidence.windowDamage(b.evidence, PhaseCombatEvidence.EnemyWindow.VENT))
    }

    private fun cooldownObservation(
        mode: String = "PHASE_COOLDOWN",
        accelerated: Boolean = true,
        action: String = "RECOVER",
        burst: Int = 3,
    ): Observation {
        val b = Observation()
        val weapon = b.weapon(burst = burst)
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.4f); b.command(mode, action); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(if (accelerated) 0f else 4.4f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)
        b.frame(3.5f)
        return b
    }

    private fun ammoObservation(
        exitAmmo: Int = 3,
        ammoPerSecond: Float = 0.5f,
        useOtherWeapon: Boolean = false,
        entryCooldown: Float = 0f,
    ): Observation {
        val b = Observation()
        val weapon = b.weapon()
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammo).thenReturn(3)
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        `when`(weapon.ammo).thenReturn(0)
        `when`(weapon.cooldownRemaining).thenReturn(entryCooldown)
        `when`(weapon.ammoTracker.ammoPerSecond).thenReturn(ammoPerSecond)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f)
        `when`(weapon.ammo).thenReturn(exitAmmo)
        b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val afterWeapon = if (useOtherWeapon) b.weapon() else weapon
        val second = b.volley(afterWeapon, 3.1f); b.hit(second, 200f)
        b.frame(3.5f)
        return b
    }

    @Test fun `ammo recovery requires restored full burst ammunition and real acceleration`() {
        val valid = PhaseEngagementEvidence.evaluateCooldownAttacks(ammoObservation().evidence)
        assertEquals(1, valid.passed.size, valid.rejections.toString())
        val sequence = valid.passed.single()
        assertTrue(sequence.savedSeconds >= 0.5f)
        assertTrue(sequence.damage >= 100f)
        val sample = sequence.sample
        assertSame(sample, sequence.cycle.cooldowns.single { it.resource == PhaseCycleEvidence.Resource.AMMO })
        assertEquals(0, sample.entryAmmo)
        assertEquals(3, sample.requiredAmmo)
        assertEquals(3, sample.ammoAtExit)

        val dual = PhaseEngagementEvidence.evaluateCooldownAttacks(ammoObservation(entryCooldown = 4f).evidence)
        assertEquals(2, dual.passed.size, dual.rejections.toString())
        assertEquals(
            setOf(PhaseCycleEvidence.Resource.COOLDOWN, PhaseCycleEvidence.Resource.AMMO),
            dual.passed.map { it.sample.resource }.toSet(),
        )

        val notRestored = PhaseEngagementEvidence.evaluateCooldownAttacks(ammoObservation(exitAmmo = 0).evidence)
        assertTrue(notRestored.passed.isEmpty())
        assertTrue(notRestored.rejections.any { it.second.code == PhaseEngagementEvidence.RejectionCode.WEAPON_AMMO_NOT_RESTORED })

        val notAccelerated = PhaseEngagementEvidence.evaluateCooldownAttacks(ammoObservation(ammoPerSecond = 3f).evidence)
        assertTrue(notAccelerated.passed.isEmpty())
        assertTrue(notAccelerated.rejections.any {
            it.second.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INSUFFICIENT_EXTRA_PROGRESS ||
                it.second.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INSUFFICIENT_TIME_SAVED
        })

        val borrowed = PhaseEngagementEvidence.evaluateCooldownAttacks(ammoObservation(useOtherWeapon = true).evidence)
        assertTrue(borrowed.passed.isEmpty())
    }

    @Test fun `cooldown requires full volleys real remaining cooldown progress and the bound purpose`() {
        for (mode in listOf("PHASE_COOLDOWN", "PHASE_FLUX")) {
            val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(cooldownObservation(mode).evidence)
            assertEquals(1, eval.passed.size, "mode $mode should produce one valid sequence")
            val sequence = eval.passed.single()
            assertTrue(sequence.savedSeconds > 0.5f)
            assertTrue(sequence.before.fullBurst && sequence.after.fullBurst)
        }
        val noAccelEval = PhaseEngagementEvidence.evaluateCooldownAttacks(cooldownObservation(accelerated = false).evidence)
        assertTrue(noAccelEval.passed.isEmpty())
        val progressRej = noAccelEval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INSUFFICIENT_EXTRA_PROGRESS
        }
        assertNotNull(progressRej, "should reject for insufficient extra progress")
        assertTrue(progressRej!!.second.floatValues.containsKey("extraProgress"))

        val wrongActionEval = PhaseEngagementEvidence.evaluateCooldownAttacks(cooldownObservation(action = "APPROACH").evidence)
        assertTrue(wrongActionEval.passed.isEmpty())
        val actionRej = wrongActionEval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.CYCLE_INVALID_ACTION
        }
        assertNotNull(actionRej, "should reject for invalid action")
        assertEquals("APPROACH", actionRej!!.second.detail)
    }

    @Test fun `single shot weapon can prove a complete cooldown attack`() {
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(cooldownObservation(burst = 1).evidence)
        assertEquals(1, eval.passed.size, eval.rejections.toString())
        val attack = eval.passed.single()
        assertTrue(attack.before.complete && attack.after.complete)
        assertFalse(attack.before.fullBurst || attack.after.fullBurst)
        assertTrue(attack.savedSeconds >= 0.5f && attack.damage >= 100f)
    }

    @Test fun `cooldown rejects another phase cycle between the adjacent full volleys`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.35f); b.command("NONE", "EVADE"); b.enter(1.4f); b.exit(1.6f)
        b.state = ShipSystemAPI.SystemState.IDLE; b.frame(1.65f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        b.frame(1.7f); b.command(); b.enter(1.8f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)

        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        assertTrue(eval.rejections.any {
            it.second.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INTERMEDIATE_CYCLE
        }, eval.rejections.toString())
    }

    @Test fun `cooldown rejects early fire during OUT period invalidating stable weapon`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f; b.frame(2f)
        b.state = ShipSystemAPI.SystemState.OUT; b.level = 0.5f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        `when`(weapon.isInBurst).thenReturn(true)
        b.shot(weapon); b.frame(2.5f)
        b.state = ShipSystemAPI.SystemState.COOLDOWN; b.level = 0f; b.phased = false
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        `when`(weapon.isInBurst).thenReturn(false)
        b.frame(2.8f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val unstableRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_UNSTABLE
        }
        assertNotNull(unstableRej)
        assertTrue(unstableRej!!.second.detail.contains("inBurst"))
    }

    @Test fun `cooldown rejects incomplete cycle without attributed command`() {
        val b = Observation()
        val weapon = b.weapon()
        b.volley(weapon, 1f)
        b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val notAttributedRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.CYCLE_NOT_ATTRIBUTED
        }
        assertNotNull(notAttributedRej)
    }

    @Test fun `cooldown rejects unstable timeMult during phase period`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(b.player.mutableStats.timeMult.modifiedValue).thenReturn(5f)
        b.frame(2f)
        `when`(b.player.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val unstableMultRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.CYCLE_UNSTABLE_TIME_MULT
        }
        assertNotNull(unstableMultRej)
    }

    @Test fun `cooldown rejects missing before volley with candidate`() {
        val b = Observation()
        val weapon = b.weapon()
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 200f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val noBeforeRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_NO_BEFORE_VOLLEY
        }
        assertNotNull(noBeforeRej)
        assertNotNull(noBeforeRej!!.second.beforeCandidate)
        assertNull(noBeforeRej.second.beforeCandidate!!.volley)
    }

    @Test fun `cooldown rejects insufficient advance time saved threshold`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        `when`(weapon.cooldownRemaining).thenReturn(2.6f)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.6f); b.hit(second, 200f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val insufficientTimeRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INSUFFICIENT_TIME_SAVED
        }
        assertNotNull(insufficientTimeRej)
        val saved = insufficientTimeRej!!.second.floatValues["saved"] ?: Float.NaN
        assertTrue(saved < 0.5f)
    }

    @Test fun `cooldown rejects insufficient damage from after volley`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val second = b.volley(weapon, 3.1f); b.hit(second, 50f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val insufficientDmgRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_INSUFFICIENT_DAMAGE
        }
        assertNotNull(insufficientDmgRej)
        val damage = insufficientDmgRej!!.second.floatValues["damage"] ?: Float.NaN
        assertTrue(damage < 100f)
    }

    @Test fun `cooldown rejects no after volley or after volley too late`() {
        val b = Observation()
        val weapon = b.weapon()
        val first = b.volley(weapon, 1f); b.hit(first, 200f)
        b.frame(1.4f); b.command(); b.enter(1.5f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        b.frame(2.8f); b.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        b.frame(6f)
        val evalNoAfter = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(evalNoAfter.passed.isEmpty())
        val noAfterRej = evalNoAfter.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_NO_AFTER_VOLLEY
        }
        assertNotNull(noAfterRej)

        val late = b.volley(weapon, 10f); b.hit(late, 200f)
        val evalTooLate = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(evalTooLate.passed.isEmpty())
        val tooLateRej = evalTooLate.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.WEAPON_AFTER_VOLLEY_TOO_LATE
        }
        assertNotNull(tooLateRej)
        val delta = tooLateRej!!.second.floatValues["delta"] ?: Float.NaN
        assertTrue(delta > 2f)
    }

    @Test fun `cooldown rejects cycles with no cooldown samples`() {
        val b = Observation()
        b.frame(1f); b.command("PHASE_COOLDOWN", "RECOVER")
        b.enter(1.1f)
        b.state = ShipSystemAPI.SystemState.ACTIVE; b.level = 1f; b.frame(2f)
        b.exit(3f)
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(b.evidence)
        assertTrue(eval.passed.isEmpty())
        val noSamplesRej = eval.rejections.firstOrNull { (_, r) ->
            r.code == PhaseEngagementEvidence.RejectionCode.CYCLE_NO_COOLDOWN_SAMPLES
        }
        assertNotNull(noSamplesRej)
    }



    @Test fun `burst beam needs full charge emission duration and completed charge down`() {
        for (interrupt in listOf(false, true)) {
            val b = Observation()
            val weapon = b.weapon(burst = 1)
            val spec = mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java)
            val beam = mock(BeamAPI::class.java, RETURNS_DEEP_STUBS)
            `when`(weapon.spec).thenReturn(spec)
            `when`(spec.burstDuration).thenReturn(0.5f)
            `when`(weapon.isBeam).thenReturn(true)
            `when`(weapon.isBurstBeam).thenReturn(true)
            `when`(weapon.isInBurst).thenReturn(true)
            `when`(weapon.beams).thenReturn(listOf(beam))
            `when`(beam.source).thenReturn(b.player)
            `when`(beam.weapon).thenReturn(weapon)
            `when`(beam.damage.damage).thenReturn(100f)
            `when`(beam.brightness).thenReturn(1f)
            `when`(beam.from).thenReturn(Vector2f())
            `when`(beam.to).thenReturn(Vector2f(1000f, 0f))
            `when`(weapon.chargeLevel).thenReturn(0.5f)
            b.frame(1f)
            `when`(weapon.chargeLevel).thenReturn(1f)
            b.frame(1.1f); b.hit(beam, 200f)
            if (interrupt) {
                b.command("NONE", "EVADE"); b.enter(1.2f)
            } else {
                b.frame(1.7f)
                `when`(weapon.chargeLevel).thenReturn(0.5f); b.frame(1.8f)
            }
            `when`(weapon.chargeLevel).thenReturn(0f)
            `when`(beam.brightness).thenReturn(0f)
            `when`(weapon.isInBurst).thenReturn(false)
            b.frame(2f)
            assertEquals(!interrupt, b.evidence.volleys.volleys.single().fullBurst)
        }
    }

    @Test fun `distance uncertainty cannot cross the accepted standoff boundary`() {
        val b = Observation()
        b.location.x = 345f
        `when`(b.player.velocity).thenReturn(Vector2f(200f, 0f))
        b.frame(1f)
        val shot = b.shot(b.weapon(), 0.05f); b.frame(1.1f); b.hit(shot, 600f)
        assertEquals(655f, b.evidence.fireEvents.single().distance)
        assertTrue(b.evidence.fireEvents.single().distanceError >= 10f)
        assertTrue(PhaseEngagementEvidence.rangedAttacks(b.evidence, 0.65f).isEmpty())
    }

    @Test fun `multiple pellets at one instant cannot replace a timed burst`() {
        val b = Observation()
        val weapon = b.weapon()
        `when`((weapon.spec as ProjectileWeaponSpecAPI).burstDelay).thenReturn(0.1f)
        b.shot(weapon); b.shot(weapon)
        val last = b.shot(weapon); b.frame(1f); b.hit(last, 500f); b.frame(1.3f)
        assertFalse(b.evidence.volleys.volleys.single().fullBurst)
        val full = b.volley(weapon, 2f); b.hit(full, 200f)
        assertTrue(b.evidence.volleys.volleys.last().fullBurst)
    }

    @Test fun `burst timing uses fire clocks and allows one simulation frame`() {
        val b = Observation()
        val weapon = b.weapon()
        `when`((weapon.spec as ProjectileWeaponSpecAPI).burstDelay).thenReturn(0.1f)
        `when`(weapon.isInBurst).thenReturn(true)
        b.shot(weapon); b.frame(2f)
        b.shot(weapon); b.frame(2.1f)
        b.shot(weapon); b.frame(2.19f)
        `when`(b.player.mutableStats.timeMult.modifiedValue).thenReturn(0.5f)
        `when`(weapon.isInBurst).thenReturn(false)
        b.frame(2.3f)
        assertTrue(b.evidence.volleys.volleys.single().fullBurst)
    }

    @Test fun `burst timing integrates adjacent fire clocks and scales frame tolerance`() {
        val integrated = Observation()
        val weapon = integrated.weapon()
        `when`((weapon.spec as ProjectileWeaponSpecAPI).burstDelay).thenReturn(0.4f)
        `when`(weapon.isInBurst).thenReturn(true)
        `when`(integrated.player.mutableStats.timeMult.modifiedValue).thenReturn(5f)
        integrated.shot(weapon); integrated.frame(2f)
        integrated.shot(weapon); integrated.frame(2.2f)
        `when`(integrated.player.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        integrated.shot(weapon); integrated.frame(2.4f)
        `when`(weapon.isInBurst).thenReturn(false); integrated.frame(2.5f)
        assertTrue(integrated.evidence.volleys.volleys.single().fullBurst)

        val slow = Observation()
        val slowWeapon = slow.weapon()
        `when`((slowWeapon.spec as ProjectileWeaponSpecAPI).burstDelay).thenReturn(0.1f)
        `when`(slowWeapon.isInBurst).thenReturn(true)
        `when`(slow.player.mutableStats.timeMult.modifiedValue).thenReturn(0.5f)
        slow.shot(slowWeapon); slow.frame(2f)
        slow.shot(slowWeapon); slow.frame(2.19f)
        slow.shot(slowWeapon); slow.frame(2.38f)
        `when`(slowWeapon.isInBurst).thenReturn(false); slow.frame(2.5f)
        assertFalse(slow.evidence.volleys.volleys.single().fullBurst)
    }

    @Test fun `cooldown volleys use fire time rather than observation order`() {
        val reversed = cooldownObservation()
        reversed.evidence.volleys.volleys.reverse()
        assertEquals(1, PhaseEngagementEvidence.cooldownAttacks(reversed.evidence).size)

        val interrupted = cooldownObservation()
        val cycle = interrupted.evidence.phaseCycles.cycles.single()
        val weapon = cycle.cooldowns.single().weapon
        val start = cycle.end + 0.05f
        val fire = PhaseCombatEvidence.FireEvent(
            start, weapon, 0f, targetId = "enemy", timeMult = 1f, refireDelay = 8f, cooldown = 8f,
        )
        interrupted.evidence.volleys.volleys += PhaseVolleyEvidence.Volley(
            weapon, mutableListOf(fire), 3, false, end = start + 0.3f,
        )
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(interrupted.evidence)
        assertTrue(eval.passed.isEmpty())
        assertTrue(eval.rejections.any {
            it.second.code == PhaseEngagementEvidence.RejectionCode.WEAPON_AFTER_VOLLEY_NOT_FULL_BURST
        })
    }

    @Test fun `launch cannot adopt a diagnostic target published after it fired`() {
        val b = Observation()
        b.frame(1f)
        b.diag["targetId"] = "different"
        val shot = b.shot(b.weapon(), 0.05f); b.frame(1.1f); b.hit(shot, 600f)
        assertEquals("enemy", b.evidence.fireEvents.single().targetId)
        assertEquals(1.05f, b.evidence.fireEvents.single().timeSec, 0.0001f)
    }

    @Test fun `changing nonphase time multiplier invalidates cooldown attribution`() {
        val b = cooldownObservation()
        assertEquals(1, PhaseEngagementEvidence.cooldownAttacks(b.evidence).size)
        val other = Observation()
        val weapon = other.weapon()
        other.volley(weapon, 1f)
        other.frame(1.4f); other.command(); other.enter(1.5f)
        `when`(other.player.mutableStats.timeMult.modifiedValue).thenReturn(10f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        other.exit(3f)
        `when`(weapon.cooldownRemaining).thenReturn(6f)
        val shot = other.volley(weapon, 3.1f); other.hit(shot, 200f)
        assertTrue(PhaseEngagementEvidence.cooldownAttacks(other.evidence).isEmpty())
    }

    @Test fun `single shot with damage is not a complete recovery burst`() {
        val b = Observation()
        b.hardFlux = 700f; b.diag["action"] = "RECOVER"; b.frame(1f)
        b.location.y = 450f; b.hardFlux = 400f; b.frame(2f)
        b.diag["action"] = "ATTACK"; b.frame(3f)
        val shot = b.shot(b.weapon(burst = 1)); b.frame(4f); b.hit(shot, 1000f); b.frame(4.1f)
        assertFalse(b.evidence.recoveryPhases.single().newFireAfterRecovery)
        assertNull(b.evidence.recoveryPhases.single().resumedVolley)
    }

    @Test fun `interrupted volley and a single successful shot cannot prove full recovery burst`() {
        val b = Observation()
        b.hardFlux = 700f; b.diag["action"] = "RECOVER"; b.frame(1f)
        b.location.y = 450f; b.hardFlux = 400f; b.frame(2f)
        b.diag["action"] = "ATTACK"; b.frame(3f)
        val weapon = b.weapon()
        `when`(weapon.isInBurst).thenReturn(true)
        val shot = b.shot(weapon); b.frame(4f); b.hit(shot, 1000f)
        b.command("NONE", "EVADE"); b.enter(4.1f); b.exit(5f)
        assertFalse(b.evidence.recoveryPhases.single().newFireAfterRecovery)
        `when`(weapon.isInBurst).thenReturn(false)
        val complete = b.volley(weapon, 6f); b.hit(complete, 200f); b.frame(6.5f)
        assertTrue(b.evidence.recoveryPhases.single().newFireAfterRecovery)
    }
}
