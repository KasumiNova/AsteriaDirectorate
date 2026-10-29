package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.SettingsAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.ProjectileSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.EnumSet
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseOpportunityTest {
    @Test
    fun `compare uses parallel readiness and completes every included weapon`() {
        val first = PhaseOpportunities.Timing(4f, 3f, 1f, 1f, 1f)
        val second = PhaseOpportunities.Timing(1f, 0f, 0f, 0f, 0f)
        val result = compare(listOf(first, second), window = 8f, surface = 2f)
        assertEquals(7f, result.attackImpact)
        assertEquals(8f, result.ventImpact)
        assertTrue(result.attack)
        assertFalse(result.vent)
    }

    @Test
    fun `window equality is rejected and attack wins whenever affordable`() {
        val timings = listOf(PhaseOpportunities.Timing(0f, 0f, 1f, 1f, 1f))
        assertFalse(compare(timings, window = 3f).attack)
        assertTrue(compare(timings, window = 3.01f).attack)
        assertFalse(compare(timings, window = 20f).vent)
        assertTrue(compare(timings, window = 20f, affordable = false).vent)
        assertFalse(compare(timings, window = 20f, affordable = false, safe = false).vent)
    }

    @Test
    fun `empty invalid and nonfinite timing cannot claim an opportunity`() {
        assertFalse(compare(emptyList()).attack)
        assertFalse(compare(emptyList()).vent)
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = listOf(PhaseOpportunities.Timing(value, 0f, 0f, 0f, 0f))
            assertFalse(compare(invalid).attack)
            assertFalse(compare(invalid).vent)
        }
    }

    @Test
    fun `sample applies real projectile rof without capping bonuses at one`() = withSettings {
        val f = Fixture()
        f.projectile(shots = 3, charge = 1f, delay = 0.5f).also {
            `when`(it.cooldownRemaining).thenReturn(4f)
        }
        f.rof.modifyMult("skill", 2f)
        val result = f.sample()
        assertEquals(3.1f, result.attackImpact, 0.0001f)
        assertTrue(result.attack)
    }

    @Test
    fun `projectile burst respects actual minimum refire interval`() = withSettings {
        val f = Fixture()
        f.projectile(shots = 3, delay = 0.02f)
        f.rof.modifyMult("skill", 10f)
        assertEquals(0.14f, f.sample().attackImpact, 0.0001f)
    }

    @Test
    fun `precharged projectile accounts only for remaining charge`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(shots = 3, charge = 2f, delay = 0.5f)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.75f)
        assertEquals(1.6f, f.sample(cost = 100f).attackImpact, 0.0001f)
        assertFalse(f.sample(cost = 100f).vent)
    }

    @Test
    fun `active burst uses remaining shots and actual gap without rebuying volley`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(shots = 5, charge = 1f, delay = 0.2f)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.isInBurst).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(1f)
        `when`(weapon.burstFireTimeRemaining).thenReturn(0.4f)
        `when`(weapon.cooldownRemaining).thenReturn(5f)
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammo).thenReturn(2)
        `when`(f.flux.currFlux).thenReturn(950f)
        val result = f.sample(cost = 0f)
        assertTrue(result.attack)
        assertEquals(0.4f, result.attackImpact, 0.0001f)
        assertFalse(result.vent)
        `when`(weapon.ammo).thenReturn(1)
        assertFalse(f.sample(cost = 0f).attack)
    }

    @Test
    fun `new volley needs actual full ammo even with regeneration`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(shots = 5)
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammoPerSecond).thenReturn(100f)
        `when`(weapon.ammo).thenReturn(4)
        assertFalse(f.sample().attack)
        assertFalse(f.sample().vent)
        `when`(weapon.ammo).thenReturn(5)
        assertTrue(f.sample().attack)
    }

    @Test
    fun `beam cooldown scales with rof but its charge and output do not`() = withSettings {
        val f = Fixture()
        val beam = f.beam(burst = true, charge = 1f, output = 2f)
        f.rof.modifyMult("skill", 2f)
        `when`(beam.cooldownRemaining).thenReturn(4f)
        assertEquals(5f, f.sample().attackImpact, 0.0001f)
        `when`(beam.cooldownRemaining).thenReturn(0f)
        `when`(beam.isFiring).thenReturn(true)
        `when`(beam.chargeLevel).thenReturn(1f)
        `when`(beam.burstFireTimeRemaining).thenReturn(0.4f)
        `when`(beam.usesAmmo()).thenReturn(true)
        `when`(beam.ammo).thenReturn(0)
        assertEquals(0.4f, f.sample(cost = 0f).attackImpact, 0.0001f)
        assertFalse(f.sample(cost = 1001f).vent)
    }

    @Test
    fun `continuous beam and pulse do not receive projectile rof acceleration`() = withSettings {
        val beam = Fixture()
        beam.beam(burst = false, charge = 1f)
        beam.rof.modifyMult("skill", 3f)
        assertEquals(3f, beam.sample().attackImpact, 0.0001f)
        val pulse = Fixture()
        val weapon = pulse.projectile(charge = 1f)
        `when`(weapon.isPulse).thenReturn(true)
        pulse.rof.modifyMult("skill", 3f)
        assertEquals(1.1f, pulse.sample().attackImpact, 0.0001f)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        assertFalse(pulse.sample().attack)
        `when`(weapon.chargeLevel).thenReturn(1f)
        assertEquals(0.1f, pulse.sample().attackImpact, 0.0001f)
    }

    @Test
    fun `unknown beam charge direction does not claim a remaining volley`() = withSettings {
        val f = Fixture()
        val weapon = f.beam(burst = false)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        assertFalse(f.sample().attack)
        assertEquals(Float.POSITIVE_INFINITY, f.sample().attackImpact)
    }

    @Test
    fun `different ship and enemy clocks convert each duration once`() = withSettings {
        val f = Fixture()
        f.projectile(shots = 3, charge = 1f, delay = 0.5f)
        f.ourTime.modifyMult("our_time", 2f)
        f.enemyTime.modifyMult("enemy_time", 4f)
        `when`(f.enemyFlux.overloadTimeRemaining).thenReturn(4f)
        val result = f.sample()
        assertEquals(1f, result.window)
        assertEquals(1.1f, result.attackImpact, 0.0001f)
        assertFalse(result.attack)
        assertFalse(result.vent)
    }

    @Test
    fun `ongoing enemy vent ignores fresh vent minimum getter`() = withSettings {
        val f = Fixture()
        f.projectile()
        `when`(f.enemyFlux.isOverloaded).thenReturn(false)
        `when`(f.enemyFlux.isVenting).thenReturn(true)
        `when`(f.enemyFlux.currFlux).thenReturn(20f)
        `when`(f.enemyFlux.timeToVent).thenReturn(50f)
        val result = f.sample()
        assertEquals(0.1f, result.window, 0.0001f)
        assertFalse(result.attack)
        `when`(f.enemyFlux.isVenting).thenReturn(false)
        assertEquals(0f, f.sample().window)
    }

    @Test
    fun `vent is allowed only with unaffordable attack and multi enemy safety`() = withSettings {
        val f = Fixture()
        f.projectile()
        `when`(f.flux.currFlux).thenReturn(900f)
        `when`(f.flux.timeToVent).thenReturn(4.5f)
        val unsafe = f.sample(cost = 200f, safe = false)
        assertFalse(unsafe.attack)
        assertFalse(unsafe.vent)
        val safe = f.sample(cost = 200f, safe = true)
        assertTrue(safe.vent)
        assertEquals(4.6f, safe.ventImpact, 0.0001f)
        `when`(f.flux.currFlux).thenReturn(750f)
        val affordable = f.sample(cost = 200f, safe = true)
        assertTrue(affordable.attack)
        assertFalse(affordable.vent)
        assertFalse(f.sample(cost = 1001f, safe = true).vent)
    }

    @Test
    fun `vent minimum and reload pause run concurrently with cooldown`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        `when`(weapon.cooldownRemaining).thenReturn(3f)
        `when`(f.flux.currFlux).thenReturn(50f)
        `when`(f.flux.timeToVent).thenReturn(2f)
        val result = f.sample(cost = 920f, safe = true)
        assertEquals(4.1f, result.ventImpact, 0.0001f)
        assertFalse(result.attack)
        assertTrue(result.vent)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        assertEquals(2.1f, f.sample(cost = 920f, safe = true).ventImpact, 0.0001f)
    }

    @Test
    fun `vent reload freeze differs for fading burst beam and idle pulse`() = withSettings {
        val beam = Fixture()
        val weapon = beam.beam(burst = true)
        `when`(weapon.cooldownRemaining).thenReturn(3f)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        `when`((weapon.spec as BeamWeaponSpecAPI).chargedownTime).thenReturn(1f)
        `when`(beam.flux.currFlux).thenReturn(50f)
        `when`(beam.flux.timeToVent).thenReturn(2f)
        val fading = beam.sample(cost = 960f, safe = true)
        assertEquals(Float.POSITIVE_INFINITY, fading.ventImpact)
        assertFalse(fading.vent)
        `when`(weapon.isFiring).thenReturn(false)
        `when`(weapon.chargeLevel).thenReturn(0f)
        assertEquals(8f, beam.sample(cost = 960f, safe = true).ventImpact, 0.0001f)

        val pulse = Fixture()
        val pulseWeapon = pulse.projectile(charge = 1f)
        `when`(pulseWeapon.isPulse).thenReturn(true)
        `when`(pulse.flux.currFlux).thenReturn(50f)
        `when`(pulse.flux.timeToVent).thenReturn(2f)
        assertEquals(3.1f, pulse.sample(cost = 960f, safe = true).ventImpact, 0.0001f)
    }

    @Test
    fun `sample combines weapon readiness in parallel but waits for entire attack`() = withSettings {
        val f = Fixture()
        f.projectile()
        val slow = f.projectile(shots = 3, charge = 1f, delay = 0.5f)
        `when`(slow.cooldownRemaining).thenReturn(2f)
        `when`(slow.currAngle).thenReturn(90f)
        assertEquals(4.1f, f.sample().attackImpact, 0.0001f)
        `when`(f.enemyFlux.overloadTimeRemaining).thenReturn(3f)
        assertFalse(f.sample().attack)
        `when`(f.flux.currFlux).thenReturn(900f)
        `when`(f.flux.timeToVent).thenReturn(4.5f)
        assertFalse(f.sample(cost = 200f, safe = true).vent)
    }

    @Test
    fun `time to vent denominator floor is inverted before removing phase anchor`() = withSettings {
        val f = Fixture()
        f.projectile()
        f.dissipation.modifyMult("very_low", 0.001f)
        `when`(f.flux.currFlux).thenReturn(50f)
        `when`(f.flux.timeToVent).thenReturn(200f)
        assertEquals(1000.1f, f.sample(cost = 960f, safe = true).ventImpact, 0.001f)
        assertFalse(f.sample(cost = 960f, safe = true).vent)
    }

    @Test
    fun `phase out removes anchor reload and vent bonuses without mutating stats`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(charge = 1f)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        f.rof.modifyMult("skill", 2f)
        f.rof.modifyMult("phase_anchor_modifier", 2f)
        f.dissipation.modifyMult("phase_anchor_modifier", 2f)
        f.phase(level = 1f, baseTime = 1f, duration = 1f)
        `when`(f.flux.currFlux).thenReturn(900f)
        `when`(f.flux.timeToVent).thenReturn(2.25f)
        val result = f.sample(cost = 200f, safe = true)
        val surface = ln(3f) / 2f
        assertEquals(surface + 1f + 0.5f + 0.1f, result.attackImpact, 0.0001f)
        assertEquals(surface + 4.5f + 0.5f + 0.1f, result.ventImpact, 0.0001f)
        assertTrue(result.vent)
        assertEquals(4f, f.rof.modifiedValue)
        assertEquals(200f, f.dissipation.modifiedValue)
    }

    @Test
    fun `known outgoing phase transition shares cooling instead of serial waits`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        `when`(weapon.cooldownRemaining).thenReturn(0.5f)
        f.phase(level = 1f, baseTime = 2f, duration = 1f)
        val result = f.sample()
        assertEquals(ln(3f) / 4f + 0.1f, result.attackImpact, 0.0001f)
        assertTrue(result.attack)
    }

    @Test
    fun `outside range or arc is not treated as instant approach`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        `when`(weapon.range).thenReturn(50f)
        assertFalse(f.sample().attack)
        assertFalse(f.sample(safe = true).vent)
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.distanceFromArc(f.enemy.location)).thenReturn(10f)
        assertFalse(f.sample().attack)
    }

    @Test
    fun `future firing position outside weapon arc rejects opportunity`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        `when`(f.enemy.collisionRadius).thenReturn(40f)
        f.enemy.velocity.y = 100f
        f.phase(level = 1f, baseTime = 1f, duration = 1f)
        `when`(weapon.distanceFromArc(any())).thenAnswer { invocation ->
            val point = invocation.getArgument<org.lwjgl.util.vector.Vector2f>(0)
            if (kotlin.math.abs(point.y) <= 0.001f) 0f else 20f
        }

        assertFalse(f.sample().attack)
    }

    @Test
    fun `relative transverse velocity contributes to interception time`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        f.enemy.location.x = 600f
        f.enemy.velocity.y = 800f
        `when`(weapon.currAngle).thenReturn(Math.toDegrees(kotlin.math.atan2(800.0, 600.0)).toFloat())
        `when`(weapon.range).thenReturn(5000f)
        val result = f.sample()
        assertEquals(600f / sqrt(1000f * 1000f - 800f * 800f), result.attackImpact, 0.0001f)
        assertTrue(result.attack)
        f.enemy.velocity.y = 1100f
        assertFalse(f.sample().attack)
    }

    @Test
    fun `target displacement during charge is included in flight`() = withSettings {
        val f = Fixture()
        f.projectile(charge = 1f)
        f.enemy.location.x = 600f
        f.enemy.velocity.x = 100f
        assertEquals(1f + 700f / 900f, f.sample().attackImpact, 0.0001f)
    }

    @Test
    fun `projectile inherits raw ship velocity while ship displacement uses its clock`() = withSettings {
        val f = Fixture()
        f.projectile(charge = 2f)
        f.ourTime.modifyMult("time", 2f)
        f.ship.velocity.x = 100f
        f.enemy.location.x = 600f
        assertEquals(1f + 400f / 1100f, f.sample().attackImpact, 0.0001f)
    }

    @Test
    fun `fast closing target can leave range before a charged shot`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(charge = 1f)
        `when`(weapon.range).thenReturn(150f)
        f.enemy.velocity.x = 100f
        assertFalse(f.sample().attack)
    }

    @Test
    fun `unsafe unknown costs reject while controllable turning retains opportunities`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile()
        assertFalse(f.sample(cost = Float.NaN).attack)
        assertFalse(f.sample(cost = -1f).vent)
        `when`(f.ship.angularVelocity).thenReturn(1f)
        assertTrue(f.sample().attack)
        `when`(f.ship.angularVelocity).thenReturn(0f)
        `when`(weapon.spec.projectileSpec).thenReturn(null)
        assertFalse(f.sample().attack)
    }

    @Test
    fun `invalid target and own vent never advertise an attack`() = withSettings {
        val f = Fixture()
        f.projectile()
        assertEquals(PhaseOpportunity(), PhaseOpportunities.sample(
            f.ship, f.cloak, null, PhaseRecovery(false), 100f,
        ))
        `when`(f.enemy.isPhased).thenReturn(true)
        assertFalse(f.sample().attack)
        `when`(f.enemy.isPhased).thenReturn(false)
        `when`(f.flux.isOverloadedOrVenting).thenReturn(true)
        assertFalse(f.sample().attack)
        assertFalse(f.sample(safe = true).vent)
    }

    @Test
    fun `phase exit accounts for charge out and cannot preserve a currently active salvo`() = withSettings {
        val f = Fixture()
        val weapon = f.projectile(charge = 1f)
        f.phase(level = 1f, baseTime = 1f, duration = 1f)
        `when`(f.cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        assertTrue(f.sample().attack)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        assertFalse(f.sample().attack)
    }

    private fun compare(
        timings: List<PhaseOpportunities.Timing>, window: Float = 10f, surface: Float = 0f,
        affordable: Boolean = true, safe: Boolean = true,
    ) = PhaseOpportunities.compare(timings, window, surface, affordable, 3f, 1f, true, safe)

    private class Fixture {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val enemyFlux = mock(FluxTrackerAPI::class.java)
        val ourTime = MutableStat(1f)
        val enemyTime = MutableStat(1f)
        val rof = MutableStat(1f)
        val dissipation = MutableStat(100f)
        private val weapons = ArrayList<WeaponAPI>()

        init {
            `when`(ship.owner).thenReturn(0)
            `when`(enemy.owner).thenReturn(1)
            `when`(enemy.isAlive).thenReturn(true)
            `when`(ship.location).thenReturn(Vector2f())
            `when`(enemy.location).thenReturn(Vector2f(100f, 0f))
            `when`(ship.velocity).thenReturn(Vector2f())
            `when`(enemy.velocity).thenReturn(Vector2f())
            `when`(ship.phaseCloak).thenReturn(cloak)
            `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
            `when`(ship.fluxTracker).thenReturn(flux)
            `when`(enemy.fluxTracker).thenReturn(enemyFlux)
            `when`(flux.maxFlux).thenReturn(1000f)
            `when`(flux.timeToVent).thenReturn(1f)
            `when`(enemyFlux.isOverloaded).thenReturn(true)
            `when`(enemyFlux.overloadTimeRemaining).thenReturn(20f)
            `when`(ship.mutableStats.timeMult).thenReturn(ourTime)
            `when`(enemy.mutableStats.timeMult).thenReturn(enemyTime)
            `when`(ship.mutableStats.energyRoFMult).thenReturn(rof)
            `when`(ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus())
            `when`(ship.mutableStats.beamWeaponFluxCostMult).thenReturn(MutableStat(1f))
            `when`(ship.mutableStats.fluxDissipation).thenReturn(dissipation)
            `when`(ship.mutableStats.ventRateMult).thenReturn(MutableStat(1f))
            `when`(enemy.mutableStats.fluxDissipation).thenReturn(MutableStat(100f))
            `when`(enemy.mutableStats.ventRateMult).thenReturn(MutableStat(1f))
            `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
            `when`(ship.allWeapons).thenReturn(weapons)
        }

        fun phase(level: Float, baseTime: Float, duration: Float) {
            `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
            `when`(cloak.effectLevel).thenReturn(level)
            `when`(cloak.chargeDownDur).thenReturn(duration)
            ourTime.modifyMult("phase", baseTime * (1f + 2f * level))
        }

        fun sample(cost: Float = 100f, safe: Boolean = false) = PhaseOpportunities.sample(
            ship, cloak, enemy, PhaseRecovery(required = false, ventSafe = safe), cost,
        )

        fun projectile(shots: Int = 1, charge: Float = 0f, delay: Float = 0.2f): WeaponAPI {
            val spec = mock(ProjectileWeaponSpecAPI::class.java)
            val derived = mock(WeaponAPI.DerivedWeaponStatsAPI::class.java)
            val projectile = mock(ProjectileSpecAPI::class.java)
            `when`(spec.projectileSpec).thenReturn(projectile)
            `when`(spec.burstSize).thenReturn(shots)
            `when`(spec.burstDelay).thenReturn(delay)
            `when`(spec.chargeTime).thenReturn(charge)
            `when`(spec.refireDelay).thenReturn(10f)
            `when`(spec.energyPerShot).thenReturn(100f)
            `when`(spec.turretFireOffsets).thenReturn(listOf(Vector2f()))
            `when`(spec.derivedStats).thenReturn(derived)
            `when`(derived.damagePerShot).thenReturn(100f)
            `when`(derived.fluxPerDam).thenReturn(1f)
            return weapon(spec).also {
                `when`(it.projectileSpeed).thenReturn(1000f)
                `when`(it.cooldown).thenReturn(10f)
            }
        }

        fun beam(burst: Boolean, charge: Float = 1f, output: Float = 2f): WeaponAPI {
            val spec = mock(BeamWeaponSpecAPI::class.java)
            `when`(spec.chargeupTime).thenReturn(charge)
            `when`(spec.burstDuration).thenReturn(output)
            `when`(spec.burstSize).thenReturn(1)
            return weapon(spec).also {
                `when`(it.isBeam).thenReturn(true)
                `when`(it.isBurstBeam).thenReturn(burst)
            }
        }

        private fun weapon(spec: WeaponSpecAPI): WeaponAPI {
            val weapon = mock(WeaponAPI::class.java)
            `when`(spec.aiHints).thenReturn(EnumSet.noneOf(WeaponAPI.AIHints::class.java))
            `when`(weapon.id).thenReturn("test")
            `when`(weapon.ship).thenReturn(ship)
            `when`(weapon.spec).thenReturn(spec)
            `when`(weapon.originalSpec).thenReturn(spec)
            `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
            `when`(weapon.location).thenReturn(Vector2f())
            `when`(weapon.range).thenReturn(1000f)
            `when`(weapon.turnRate).thenReturn(90f)
            weapons += weapon
            return weapon
        }
    }

    private fun withSettings(block: () -> Unit) {
        val previous = Global.getSettings()
        val settings = mock(SettingsAPI::class.java)
        `when`(settings.getFloat("minRefireDelay")).thenReturn(0.05f)
        `when`(settings.getFloat("minVentTimeForWeaponReload")).thenReturn(1f)
        Global.setSettings(settings)
        try {
            block()
        } finally {
            Global.setSettings(previous)
        }
    }
}
