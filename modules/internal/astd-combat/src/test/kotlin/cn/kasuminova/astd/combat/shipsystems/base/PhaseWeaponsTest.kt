package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.loading.WeaponSlotAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseWeaponsTest {
    @Test
    fun `burst beam charge output and cooldown reset follow engine trajectory`() {
        val weapon = beam(burst = true)
        val tracker = WeaponTracker()
        fun sample() = tracker.sample(listOf(weapon)).single()
        assertEquals(WeaponOutputState.COOLDOWN, sample().outputState)
        firing(weapon, charge = 0.5f, remaining = 3f)
        val charging = sample()
        assertEquals(WeaponOutputState.CHARGING, charging.outputState)
        assertCost(2640f, WeaponCost.continueCost(charging, 2f))
        assertCost(2640f, WeaponCost.interruptNowCost(charging))
        firing(weapon, charge = 1f, remaining = 1f)
        assertCost(880f, WeaponCost.continueCost(sample(), 2f))
        firing(weapon, charge = 0.8f, remaining = 3.5f, cooldown = 3.4f)
        val finishing = sample()
        assertEquals(WeaponOutputState.FINISHING, finishing.outputState)
        assertCost(0f, WeaponCost.continueCost(finishing, 2f))
        assertCost(0f, WeaponCost.interruptNowCost(finishing))
        `when`(weapon.isFiring).thenReturn(false)
        assertEquals(WeaponOutputState.COOLDOWN, sample().outputState)
    }

    @Test
    fun `noninterruptible projectile prepays only at first shot not during charge or cooldown`() {
        val weapon = projectile(chargeTime = 0.5f, chargeRate = 200f, shots = 5)
        val tracker = WeaponTracker()
        firing(weapon, charge = 0.6f)
        assertCost(540f, WeaponCost.continueCost(tracker.sample(listOf(weapon)).single(), 1f))
        firing(weapon, charge = 1f, remaining = 0.8f, inBurst = true, cooldown = 0.2f)
        val burst = tracker.sample(listOf(weapon)).single()
        assertEquals(WeaponOutputState.OUTPUTTING, burst.outputState)
        assertTrue(burst.prepaid)
        assertCost(0f, WeaponCost.continueCost(burst, 1f))
        firing(weapon, charge = 0.8f, cooldown = 2f)
        val cooldown = tracker.sample(listOf(weapon)).single()
        assertEquals(WeaponOutputState.COOLDOWN, cooldown.outputState)
        assertCost(0f, WeaponCost.continueCost(cooldown, 1f))
    }

    @Test
    fun `interruptible charge includes every planned shot and output uses integer remaining shots`() {
        val weapon = projectile(chargeTime = 0.5f, chargeRate = 200f, shots = 5, interruptible = true)
        val tracker = WeaponTracker()
        firing(weapon, charge = 0.5f)
        assertCost(550f, WeaponCost.continueCost(tracker.sample(listOf(weapon)).single(), 1f))
        firing(weapon, charge = 1f, remaining = 0.6000001f, inBurst = true)
        val burst = tracker.sample(listOf(weapon)).single()
        assertEquals(3, burst.remainingShots)
        assertCost(300f, WeaponCost.continueCost(burst, 1f))
        assertCost(0f, WeaponCost.interruptNowCost(burst))
    }

    @Test
    fun `snapshot reads projectile charge rate and actual ship cost modifiers`() {
        val weapon = projectile(chargeTime = 0.5f, chargeRate = 200f, shots = 3)
        val stat = StatBonus().apply { modifyMult("test", 0.5f) }
        `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(stat)
        val snap = WeaponSnapshot.from(weapon)
        assertEquals(200f, snap.fluxPerSecond)
        assertCost(200f, WeaponCost.nextVolleyCost(snap, 1f))
    }

    @Test
    fun `linked and dual groups are inferred from original derived costs`() {
        val linked = projectile(shots = 3, barrels = 4, turretOffsets = 4, mountedOffsets = 4)
        val dual = projectile(shots = 3, barrels = 2, turretOffsets = 4, mountedOffsets = 4)
        `when`(linked.ammo).thenReturn(20)
        assertEquals(4, WeaponSnapshot.from(linked).barrels)
        assertEquals(2, WeaponSnapshot.from(dual).barrels)
        assertCost(1200f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(linked), 1f))
        assertCost(600f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(dual), 1f))
    }

    @Test
    fun `ambiguous hardpoint mode uses marked upper bound`() {
        val weapon = projectile(barrels = 2, turretOffsets = 2, mountedOffsets = 4)
        val snapshot = WeaponSnapshot.from(weapon)
        assertEquals(4, snapshot.barrels)
        assertTrue(WeaponCostUncertainty.BARREL_MODE in WeaponCost.nextVolleyCost(snapshot, 1f).uncertainties)
        `when`(weapon.ammo).thenReturn(2)
        val withOnlyDualAmmo = WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 1f)
        assertCost(400f, withOnlyDualAmmo)
        assertTrue(WeaponCostUncertainty.BARREL_MODE in withOnlyDualAmmo.uncertainties)
    }

    @Test
    fun `continuous beams integrate explicit local output duration and charge separately`() {
        val weapon = beam(burst = false)
        val tracker = WeaponTracker()
        assertCost(1200f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 3f))
        firing(weapon, charge = 1f)
        val output = tracker.sample(listOf(weapon)).single()
        assertCost(900f, WeaponCost.continueCost(output, 3f))
        assertCost(150f, WeaponCost.continueCost(output, 0.5f))
        assertCost(0f, WeaponCost.interruptNowCost(output))
        firing(weapon, charge = 0.8f)
        val down = tracker.sample(listOf(weapon)).single()
        assertEquals(WeaponOutputState.FINISHING, down.outputState)
        assertCost(0f, WeaponCost.continueCost(down, 3f))
        assertCost(0f, WeaponCost.continueCost(tracker.sample(listOf(weapon)).single(), 3f))
    }

    @Test
    fun `beam type and beam multiplier both affect integrated cost`() {
        val weapon = beam(burst = false)
        `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus().apply { modifyMult("e", 0.5f) })
        `when`(weapon.ship.mutableStats.beamWeaponFluxCostMult).thenReturn(MutableStat(1f).apply { modifyMult("b", 0.8f) })
        firing(weapon, charge = 1f)
        assertCost(360f, WeaponCost.continueCost(WeaponTracker().sample(listOf(weapon)).single(), 3f))
    }

    @Test
    fun `flat shot modifier is applied once per debit not once per projectile for prepaid volleys`() {
        val prepaid = projectile(shots = 3)
        val perShot = projectile(shots = 3, interruptible = true)
        val stat = StatBonus().apply { modifyFlat("flat", 10f) }
        for (weapon in listOf(prepaid, perShot)) `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(stat)
        assertCost(310f, WeaponCost.projectileShotCost(WeaponSnapshot.from(prepaid), 3))
        assertCost(330f, WeaponCost.projectileShotCost(WeaponSnapshot.from(perShot), 3))
    }

    @Test
    fun `positive flat continuous debit is marked unbounded without a future frame schedule`() {
        val weapon = beam(burst = false)
        `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus().apply { modifyFlat("flat", 1f) })
        val estimate = WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 2f)
        assertEquals(Float.POSITIVE_INFINITY, estimate.amount)
        assertTrue(WeaponCostUncertainty.FRAME_DEPENDENT_FLUX in estimate.uncertainties)
    }

    @Test
    fun `same spec instances keep separate zero delay ammo histories and clear removed weapons`() {
        val first = projectile(shots = 5, delay = 0f, interruptible = true)
        val second = projectile(shots = 5, delay = 0f, interruptible = true)
        val tracker = WeaponTracker()
        tracker.sample(listOf(first, second))
        firing(first, charge = 1f, inBurst = true)
        `when`(first.ammo).thenReturn(7)
        firing(second, charge = 1f, inBurst = true)
        `when`(second.ammo).thenReturn(9)
        val states = tracker.sample(listOf(first, second))
        assertEquals(2, states[0].remainingShots)
        assertEquals(4, states[1].remainingShots)
        assertCost(200f, WeaponCost.continueCost(states[0], 1f))
        assertCost(400f, WeaponCost.continueCost(states[1], 1f))
        tracker.sample(listOf(second))
        assertEquals(1, tracker.trackedWeaponCount)
    }

    @Test
    fun `unobserved or regenerating zero delay burst retains marked conservative remaining count`() {
        val weapon = projectile(shots = 5, delay = 0f, interruptible = true)
        `when`(weapon.ammoPerSecond).thenReturn(2f)
        firing(weapon, charge = 1f, inBurst = true)
        val state = WeaponTracker().sample(listOf(weapon)).single()
        assertEquals(5, state.remainingShots)
        assertTrue(WeaponCostUncertainty.UNOBSERVED_SHOTS in WeaponCost.continueCost(state, 1f).uncertainties)
    }

    @Test
    fun `new volley is recognized after cooldown even when isFiring never sampled false`() {
        val weapon = projectile(shots = 3)
        val tracker = WeaponTracker()
        firing(weapon, charge = 0.4f, cooldown = 1f)
        val old = tracker.sample(listOf(weapon)).single()
        firing(weapon, charge = 0.2f)
        val charging = tracker.sample(listOf(weapon)).single()
        assertEquals(old.volley + 1, charging.volley)
        assertEquals(WeaponOutputState.CHARGING, charging.outputState)
    }

    @Test
    fun `exactly one volley ammo does not erase current burst and linked ammo counts all barrels`() {
        val weapon = projectile(shots = 5)
        `when`(weapon.ammo).thenReturn(5)
        assertTrue(WeaponCost.hasAmmoForFullVolley(WeaponSnapshot.from(weapon)))
        `when`(weapon.ammo).thenReturn(4)
        val partial = WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 1f)
        assertCost(500f, partial)
        assertTrue(WeaponCostUncertainty.PARTIAL_AMMO_VOLLEY in partial.uncertainties)
        firing(weapon, charge = 1f, remaining = 0.8f, inBurst = true)
        assertFalse(WeaponCost.hasAmmoForFullVolley(WeaponSnapshot.from(weapon)))
        assertEquals(WeaponOutputState.OUTPUTTING, WeaponTracker().sample(listOf(weapon)).single().outputState)
        val linked = projectile(shots = 3, barrels = 2, turretOffsets = 2, mountedOffsets = 2)
        `when`(linked.ammo).thenReturn(5)
        assertFalse(WeaponCost.hasAmmoForFullVolley(WeaponSnapshot.from(linked)))
    }

    @Test
    fun `starfall echo fifth shot adds unmodified flux after base prepayment`() {
        val weapon = projectile(id = "astd_starfall_echo", shots = 5, shotCost = 1125f)
        val tracker = WeaponTracker()
        assertCost(7875f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 1f))
        `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus().apply { modifyMult("discount", 0.5f) })
        assertCost(5062.5f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(weapon), 1f))
        firing(weapon, charge = 1f, inBurst = true, remaining = 0.2f)
        assertCost(2250f, WeaponCost.continueCost(tracker.sample(listOf(weapon)).single(), 1f))
        firing(weapon, charge = 0.9f, cooldown = 4f)
        assertCost(0f, WeaponCost.continueCost(tracker.sample(listOf(weapon)).single(), 1f))
    }

    @Test
    fun `zero delay ammo history counts linked groups rather than individual barrels`() {
        val weapon = projectile(shots = 3, delay = 0f, interruptible = true, barrels = 2, turretOffsets = 2, mountedOffsets = 2)
        val tracker = WeaponTracker()
        tracker.sample(listOf(weapon))
        firing(weapon, charge = 1f, inBurst = true)
        `when`(weapon.ammo).thenReturn(6)
        val state = tracker.sample(listOf(weapon)).single()
        assertEquals(1, state.remainingShots)
        assertCost(200f, WeaponCost.continueCost(state, 1f))
    }

    @Test
    fun `long sampling gaps cannot turn missed volleys into paid shots`() {
        val weapon = projectile(shots = 5, delay = 0f, interruptible = true)
        `when`(weapon.cooldown).thenReturn(1f)
        val tracker = WeaponTracker()
        tracker.sample(listOf(weapon))
        firing(weapon, charge = 1f, inBurst = true)
        `when`(weapon.ammo).thenReturn(3)
        val state = tracker.sample(listOf(weapon), elapsedLocalSec = 2f).single()
        assertEquals(5, state.remainingShots)
        assertTrue(WeaponCostUncertainty.UNOBSERVED_SHOTS in WeaponCost.continueCost(state, 1f).uncertainties)
    }

    @Test
    fun `nonintegral burst timing is a marked upper bound rather than fractional shots`() {
        val weapon = projectile(shots = 5, interruptible = true)
        firing(weapon, charge = 1f, inBurst = true, remaining = 0.3f)
        val state = WeaponTracker().sample(listOf(weapon)).single()
        assertEquals(5, state.remainingShots)
        assertTrue(WeaponCostUncertainty.UNOBSERVED_SHOTS in state.uncertainties)
    }

    @Test
    fun `real charge tracker pays charge full hold at half rate and one release debit`() {
        val pulse = pulseFixture(fullCharge = true)
        val tracker = WeaponTracker()
        assertCost(200f, WeaponCost.nextVolleyCost(WeaponSnapshot.from(pulse.weapon), 2f))
        pulse.engine.advance(true, 0.5f)
        val charging = tracker.sample(listOf(pulse.weapon)).single()
        assertEquals(WeaponOutputState.CHARGING, charging.outputState)
        assertCost(150f, WeaponCost.continueCost(charging, 2f))
        assertCost(0f, WeaponCost.interruptNowCost(charging))
        assertEquals(50f, pulse.paid, 0.001f)
        pulse.engine.advance(true, 0.5f)
        val holding = tracker.sample(listOf(pulse.weapon)).single()
        assertEquals(WeaponOutputState.HOLDING, holding.outputState)
        assertFalse(holding.prepaid)
        assertEquals(0, pulse.shots)
        assertCost(100f, WeaponCost.continueCost(holding, 2f))
        assertCost(100f, WeaponCost.interruptNowCost(holding))
        val planned = WeaponCost.pulseReleaseCost(holding, 0.4f)
        assertCost(120f, planned)
        val before = pulse.paid
        pulse.engine.advance(true, 0.4f)
        pulse.engine.advance(false, 0.01f)
        assertEquals(planned.amount, pulse.paid - before, 0.001f)
        assertEquals(1, pulse.shots)
        val released = tracker.sample(listOf(pulse.weapon)).single()
        assertTrue(released.pulseShotObserved)
        assertEquals(WeaponOutputState.COOLDOWN, released.outputState)
        assertCost(0f, WeaponCost.continueCost(released, 2f))
        assertCost(200f, WeaponCost.nextVolleyCost(released.snapshot, 2f))
    }

    @Test
    fun `partial charge release follows actual full-charge requirement`() {
        for (fullCharge in listOf(false, true)) {
            val pulse = pulseFixture(fullCharge)
            val tracker = WeaponTracker()
            pulse.engine.advance(true, 0.2f)
            val charging = tracker.sample(listOf(pulse.weapon)).single()
            val expected = if (fullCharge) 0f else 100f
            assertCost(expected, WeaponCost.interruptNowCost(charging))
            val before = pulse.paid
            pulse.engine.advance(false, 0.05f)
            assertEquals(expected, pulse.paid - before, 0.001f)
            val stopped = tracker.sample(listOf(pulse.weapon)).single()
            assertCost(0f, WeaponCost.continueCost(stopped, 2f))
            assertEquals(if (fullCharge) 0 else 1, pulse.shots)
            if (fullCharge) assertEquals(WeaponOutputState.FINISHING, stopped.outputState)
        }
    }

    @Test
    fun `pulse charge floor above one is not a fully charged or prepaid shot`() {
        val pulse = pulseFixture(fullCharge = true, chargeTime = 0.05f)
        pulse.engine.advance(true, 0.01f)
        assertEquals(2f, pulse.weapon.chargeLevel)
        val state = WeaponTracker().sample(listOf(pulse.weapon)).single()
        assertEquals(WeaponOutputState.CHARGING, state.outputState)
        assertTrue(WeaponCostUncertainty.PULSE_CHARGE_FLOOR in state.uncertainties)
        assertCost(107.5f, WeaponCost.continueCost(state, 1f))
        assertCost(0f, WeaponCost.interruptNowCost(state))
    }

    @Test
    fun `pulse minimum release interval is bounded without inventing a shot`() {
        val pulse = pulseFixture(fullCharge = false, chargeTime = 0.05f)
        val tracker = WeaponTracker()
        pulse.engine.advance(true, 0.01f)
        val charging = tracker.sample(listOf(pulse.weapon)).single()
        val upper = WeaponCost.interruptNowCost(charging)
        assertCost(100f, upper)
        assertTrue(WeaponCostUncertainty.UNOBSERVED_SHOTS in upper.uncertainties)
        pulse.engine.advance(false, 0.01f)
        assertEquals(0, pulse.shots)
        assertEquals(WeaponOutputState.FINISHING, tracker.sample(listOf(pulse.weapon)).single().outputState)
    }

    @Test
    fun `pulse release costs and ammo are once per release not burst size or barrel count`() {
        val weapon = projectile(shots = 5, barrels = 4, turretOffsets = 4, mountedOffsets = 4, chargeTime = 1f, chargeRate = 100f)
        `when`(weapon.isPulse).thenReturn(true)
        `when`(weapon.ammo).thenReturn(1)
        `when`(weapon.ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus().apply { modifyMult("half", 0.5f) })
        val snapshot = WeaponSnapshot.from(weapon)
        assertTrue(WeaponCost.hasAmmoForFullVolley(snapshot))
        assertCost(100f, WeaponCost.nextVolleyCost(snapshot, 2f))
        firing(weapon, charge = 1f)
        val holding = WeaponTracker().sample(listOf(weapon)).single()
        assertCost(50f, WeaponCost.interruptNowCost(holding))
        assertCost(100f, WeaponCost.pulseReleaseCost(holding, 2f))
    }

    @Test
    fun `short pulse waits for minimum fire interval at half rate before releasing`() {
        val pulse = pulseFixture(fullCharge = true, chargeTime = 0.05f)
        pulse.engine.advance(true, 0.05f)
        assertEquals(1, pulse.chargesStarted)
        assertEquals(1, pulse.chargesCompleted)
        val holding = WeaponTracker().sample(listOf(pulse.weapon)).single()
        val estimate = WeaponCost.pulseReleaseCost(holding, 0f)
        assertCost(102.5f, estimate)
        val before = pulse.paid
        pulse.engine.advance(true, 0.05f)
        pulse.engine.advance(false, 0.001f)
        assertEquals(estimate.amount, pulse.paid - before, 0.001f)
        assertEquals(1, pulse.shots)
    }

    @Test
    fun `failed pulse release enters unpaid cooldown rather than a new charging obligation`() {
        val pulse = pulseFixture(fullCharge = true)
        val tracker = WeaponTracker()
        pulse.engine.advance(true, 1f)
        tracker.sample(listOf(pulse.weapon))
        pulse.rejectDebit = true
        pulse.engine.advance(false, 0.05f)
        assertEquals(0, pulse.shots)
        assertEquals(100f, pulse.paid, 0.001f)
        val failed = tracker.sample(listOf(pulse.weapon)).single()
        assertEquals(WeaponOutputState.FINISHING, failed.outputState)
        assertFalse(failed.prepaid)
        assertCost(0f, WeaponCost.continueCost(failed, 1f))
        assertCost(0f, WeaponCost.interruptNowCost(failed))
    }

    private fun pulseFixture(fullCharge: Boolean, chargeTime: Float = 1f): PulseFixture {
        val weapon = projectile(chargeTime = chargeTime, chargeRate = 100f)
        `when`(weapon.isPulse).thenReturn(true)
        `when`((weapon.spec as ProjectileWeaponSpecAPI).isRequiresFullCharge).thenReturn(fullCharge)
        return PulseFixture(weapon, chargeTime, fullCharge)
    }

    private class PulseFixture(val weapon: WeaponAPI, chargeTime: Float, fullCharge: Boolean) {
        var paid = 0f
        var shots = 0
        var chargesStarted = 0
        var chargesCompleted = 0
        var rejectDebit = false
        val engine = com.fs.starfarer.combat.entities.ship.trackers.ChargeFireTracker(
            chargeTime, 0.5f, 100f, 100f, fullCharge,
            object : com.fs.starfarer.combat.entities.ship.trackers.ChargeFireTracker.ChargeFireCallbacks {
                override fun beganCharge() { chargesStarted++ }
                override fun finishedCharge() { chargesCompleted++ }
                override fun fire(level: Float) { shots++ }
                override fun deductEnergy(amount: Float): Boolean {
                    if (rejectDebit) return false
                    paid += amount
                    return true
                }
            },
        )

        init {
            `when`(weapon.chargeLevel).thenAnswer { engine.chargeProgress }
            `when`(weapon.isFiring).thenAnswer { engine.chargeState != com.fs.starfarer.combat.entities.ship.trackers.ChargeState.IDLE }
            `when`(weapon.ammo).thenAnswer { 10 - shots }
        }
    }

    @Test
    fun `partial beam direction uncertainty survives identical repeated samples`() {
        val weapon = beam(burst = false)
        firing(weapon, charge = 0.5f)
        val tracker = WeaponTracker()
        tracker.sample(listOf(weapon))
        val repeated = tracker.sample(listOf(weapon)).single()
        assertTrue(WeaponCostUncertainty.CHARGE_DIRECTION in WeaponCost.continueCost(repeated, 2f).uncertainties)
        firing(weapon, charge = 0.6f)
        assertFalse(WeaponCostUncertainty.CHARGE_DIRECTION in tracker.sample(listOf(weapon)).single().uncertainties)
    }

    private fun assertCost(expected: Float, actual: WeaponCostEstimate) = assertEquals(expected, actual.amount, 0.01f)

    private fun firing(weapon: WeaponAPI, charge: Float, remaining: Float = 0f, cooldown: Float = 0f, inBurst: Boolean = false) {
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(charge)
        `when`(weapon.burstFireTimeRemaining).thenReturn(remaining)
        `when`(weapon.cooldownRemaining).thenReturn(cooldown)
        `when`(weapon.isInBurst).thenReturn(inBurst)
    }

    private fun projectile(
        id: String = "test", shots: Int = 1, delay: Float = 0.2f, chargeTime: Float = 0f,
        chargeRate: Float = 0f, shotCost: Float = 100f, interruptible: Boolean = false,
        barrels: Int = 1, turretOffsets: Int = 1, mountedOffsets: Int = 1,
    ): WeaponAPI {
        val spec = mock(ProjectileWeaponSpecAPI::class.java)
        `when`(spec.burstSize).thenReturn(shots)
        `when`(spec.burstDelay).thenReturn(delay)
        `when`(spec.chargeTime).thenReturn(chargeTime)
        `when`(spec.energyPerSecond).thenReturn(chargeRate)
        `when`(spec.energyPerShot).thenReturn(shotCost)
        `when`(spec.isInterruptibleBurst).thenReturn(interruptible)
        `when`(spec.turretFireOffsets).thenReturn(List(turretOffsets) { Vector2f() })
        `when`(spec.hardpointFireOffsets).thenReturn(List(mountedOffsets) { Vector2f() })
        val derived = mock(WeaponAPI.DerivedWeaponStatsAPI::class.java)
        `when`(spec.derivedStats).thenReturn(derived)
        `when`(derived.damagePerShot).thenReturn(100f * barrels)
        `when`(derived.fluxPerDam).thenReturn((chargeTime * chargeRate + shotCost * shots * barrels) / (100f * barrels * shots))
        return weapon(id, spec).also {
            `when`(it.usesAmmo()).thenReturn(true)
            `when`(it.ammo).thenReturn(10)
            `when`(it.maxAmmo).thenReturn(10)
        }
    }

    private fun beam(burst: Boolean): WeaponAPI {
        val spec = mock(BeamWeaponSpecAPI::class.java)
        `when`(spec.chargeupTime).thenReturn(1f)
        `when`(spec.chargedownTime).thenReturn(0.5f)
        `when`(spec.burstDuration).thenReturn(if (burst) 2.5f else 0f)
        `when`(spec.fluxPerSecond).thenReturn(if (burst) 880f else 300f)
        return weapon("beam", spec).also {
            `when`(it.isBeam).thenReturn(true)
            `when`(it.isBurstBeam).thenReturn(burst)
            `when`(it.fluxCostToFire).thenReturn(if (burst) 3080f else 300f)
        }
    }

    private fun weapon(id: String, spec: WeaponSpecAPI): WeaponAPI {
        val weapon = mock(WeaponAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        val stats = mock(MutableShipStatsAPI::class.java)
        val slot = mock(WeaponSlotAPI::class.java)
        `when`(slot.isHardpoint).thenReturn(true)
        `when`(weapon.slot).thenReturn(slot)
        `when`(weapon.id).thenReturn(id)
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.originalSpec).thenReturn(spec)
        `when`(spec.aiHints).thenReturn(EnumSet.noneOf(WeaponAPI.AIHints::class.java))
        `when`(weapon.ship).thenReturn(ship)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(stats.energyWeaponFluxCostMod).thenReturn(StatBonus())
        `when`(stats.beamWeaponFluxCostMult).thenReturn(MutableStat(1f))
        return weapon
    }
}
