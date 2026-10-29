package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.SettingsAPI
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.spi.LoggingEvent
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseThreatSampleTest {
    @Test
    fun `sample finds projectile hit at three times local speed without scaling the window`() {
        val ship = ship(3f)
        val projectile = projectile(200f, -100f)
        val engine = engine(ship, listOf(projectile))
        val forecast = PhaseThreat().requireSample(engine, ship, PhaseThreat.Window(0.2f, 0.4f))
        assertEquals(0.475f, forecast.impacts.single().hit.time, 0.0001f)
        assertEquals(100f, forecast.total.raw)
        assertTrue(PhaseThreat().requireSample(engine, ship, PhaseThreat.Window(0.2f, 0.25f)).impacts.isEmpty())
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        assertTrue(PhaseThreat().requireSample(engine, ship, PhaseThreat.Window(0.2f, 0.4f)).impacts.isEmpty())
    }

    @Test
    fun `sample accepts a projectile without a damaged entity list`() {
        val ship = ship(1f)
        val incoming = projectile(100f, -100f)
        `when`(incoming.damagedAlready).thenReturn(null)

        val forecast = PhaseThreat().requireSample(engine(ship, listOf(incoming)), ship, PhaseThreat.Window(0f, 1f))

        assertEquals(0.45f, forecast.impacts.single().hit.time, 0.0001f)
    }

    @Test
    fun `已经错过的非制导直射弹体解除活动威胁`() {
        val ship = ship(1f)
        val incoming = projectile(200f, -100f)
        val engine = engine(ship, listOf(incoming))
        val threat = PhaseThreat()
        val initial = threat.requireSample(engine, ship, PhaseThreat.Window(0f, 1f))
        assertTrue(incoming in initial.activeThreatKeys)

        `when`(incoming.location).thenReturn(Vector2f(-200f, 0f))
        val cleared = threat.requireSample(engine, ship, PhaseThreat.Window(0f, 1f))

        assertTrue(cleared.activeThreatKeys.isEmpty())
    }

    @Test
    fun `sample integrates candidate IN exit and existing OUT through the transition boundary`() {
        val incoming = projectile(110f, 0f)
        val cases = listOf(
            Triple(ShipSystemAPI.SystemState.IN, 0.25f, ln(1.5f) / 2f + 0.75f),
            Triple(ShipSystemAPI.SystemState.OUT, 0.75f, ln(2.5f) / 2f + 0.25f),
        )
        for ((state, level, expected) in cases) {
            val ship = ship(1f + 2f * level)
            phase(ship, state, level)
            val hit = PhaseThreat().requireSample(engine(ship, listOf(incoming)), ship, PhaseThreat.Window(0.3f, 0.8f)).impacts.single().hit
            assertEquals(expected, hit.time, 0.0001f, state.name)
            assertEquals(1f, PhaseThreat.Motion(ship, surface = true).localTime(hit.time), 0.0001f)
        }
    }

    @Test
    fun `sample phase transition predicts armor rotation before the transition finishes`() {
        for (state in listOf(ShipSystemAPI.SystemState.IN, ShipSystemAPI.SystemState.OUT)) {
            val ship = ship(2.5f)
            phase(ship, state, 0.75f)
            `when`(ship.angularVelocity).thenReturn(90f)
            val hit = PhaseThreat().requireSample(engine(ship, listOf(projectile(50f, 0f))), ship,
                PhaseThreat.Window(0f, 0.3f)).impacts.single().hit
            assertEquals(ln(2.5f / 1.7f) / 2f, hit.time, 0.0001f, state.name)
            val location = ArgumentCaptor.forClass(Vector2f::class.java)
            verify(ship.armorGrid).getCellAtLocation(location.capture())
            val radians = Math.toRadians(-36.0)
            assertEquals(10f * cos(radians).toFloat(), location.value.x, 0.002f)
            assertEquals(10f * sin(radians).toFloat(), location.value.y, 0.002f)
        }
    }

    @Test
    fun `active candidate travels ninety units over charge out and reaction`() {
        val ship = ship(3f)
        phase(ship, ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(ship.phaseCloak.chargeDownDur).thenReturn(0.5f)
        val motion = PhaseThreat.Motion(ship, surface = true)
        val chargeOut = ln(3f) / 4f
        assertEquals(0.274653f, motion.chargeOutTime!!, 0.000001f)
        assertEquals(0.5f, motion.localTime(chargeOut), 0.000001f)
        assertEquals(90f, motion.position(chargeOut + 0.4f).x, 0.0001f)
        assertEquals(chargeOut + 0.4f, motion.battleTime(0.9f), 0.000001f)
        assertEquals(300f * (chargeOut + 0.4f), PhaseThreat.Motion(ship).position(chargeOut + 0.4f).x, 0.0001f)
        val near = projectile(90f, 0f)
        val far = projectile(150f, 0f)
        val forecast = PhaseThreat().requireSample(engine(ship, listOf(near, far)), ship, PhaseThreat.Window(chargeOut, 0.4f))
        assertEquals(near, forecast.impacts.single().hit.key)
        assertEquals(chargeOut + 0.3f, forecast.impacts.single().hit.time, 0.0001f)
    }

    @Test
    fun `candidate window and loss buckets preserve base time modifiers and current IN level`() {
        for (base in listOf(0.5f, 1f, 2f)) {
            for ((state, level) in listOf(ShipSystemAPI.SystemState.ACTIVE to 1f,
                ShipSystemAPI.SystemState.IN to 0.25f, ShipSystemAPI.SystemState.OUT to 0.75f)) {
                val ship = ship(base * (1f + 2f * level))
                phase(ship, state, level)
                `when`(ship.phaseCloak.chargeDownDur).thenReturn(0.5f)
                val localExit = 0.5f * level
                val chargeOut = ln(1f + 2f * level) / (4f * base)
                val motion = PhaseThreat.Motion(ship, surface = true)
                assertEquals(chargeOut, motion.chargeOutTime!!, 0.000001f)
                assertEquals(localExit + base * 0.4f, motion.localTime(chargeOut + 0.4f), 0.000001f)
                assertEquals(chargeOut + 0.4f, motion.battleTime(localExit + base * 0.4f), 0.000001f)
                val duringExit = projectile(localExit * 50f + 10f, 0f)
                val duringReaction = projectile((localExit + base * 0.2f) * 100f + 10f, 0f)
                val tooLate = projectile((localExit + base * 0.5f) * 100f + 10f, 0f)
                val forecast = PhaseThreat().requireSample(engine(ship, listOf(duringExit, duringReaction, tooLate)), ship,
                    PhaseThreat.Window(ln(3f) / 4f, 0.4f))
                assertEquals(listOf(duringExit, duringReaction), forecast.impacts.map { it.hit.key })
                assertEquals(chargeOut + 0.2f, forecast.impacts.last().hit.time, 0.0001f)
                assertEquals(100f, forecast.duringChargeOut.raw)
                assertEquals(100f, forecast.duringReaction.raw)
            }
        }
    }

    @Test
    fun `sample armor cell uses integrated local angular displacement`() {
        val ship = ship(3f)
        `when`(ship.angularVelocity).thenReturn(90f)
        `when`(ship.armorGrid.getCellAtLocation(any(Vector2f::class.java))).thenReturn(intArrayOf(2, 1))
        val hit = PhaseThreat().requireSample(engine(ship, listOf(projectile(200f, -100f))), ship,
            PhaseThreat.Window(0f, 0.6f)).impacts.single().hit
        val location = ArgumentCaptor.forClass(Vector2f::class.java)
        verify(ship.armorGrid).getCellAtLocation(location.capture())
        val radians = Math.toRadians((-90f * 3f * 0.475f).toDouble())
        assertEquals(10f * cos(radians).toFloat(), location.value.x, 0.001f)
        assertEquals(10f * sin(radians).toFloat(), location.value.y, 0.001f)
        assertEquals(2, hit.cellX)
        assertEquals(1, hit.cellY)
    }

    @Test
    fun `phase clock retains unrelated time modifiers and slow time`() {
        val ship = ship(5f)
        phase(ship, ShipSystemAPI.SystemState.OUT, 0.75f)
        val clock = PhaseThreat.Motion(ship)
        val transition = ln(2.5f) / 4f
        assertEquals(0.75f + (0.6f - transition) * 2f, clock.localTime(0.6f), 0.00001f)
        assertEquals(0.6f, clock.battleTime(clock.localTime(0.6f)), 0.00001f)
        `when`(ship.phaseCloak).thenReturn(null)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(0.5f)
        assertEquals(0.3f, PhaseThreat.Motion(ship).localTime(0.6f), 0.00001f)
    }

    @Test
    fun `sample moving beam target enters beam only under accelerated local motion`() {
        withSettings {
            val target = ship(3f)
            `when`(target.location).thenReturn(Vector2f(100f, 150f))
            `when`(target.velocity).thenReturn(Vector2f(0f, -100f))
            val source = ship(1f, 1)
            `when`(source.velocity).thenReturn(Vector2f())
            val beam = beam(source)
            val engine = engine(target, beams = listOf(beam))
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.6f)).total.raw > 0f)
            `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.6f)).impacts.isEmpty())
        }
    }

    @Test
    fun `sample beam source translation and rotation use its own time flow`() {
        withSettings {
            val target = ship(1f)
            `when`(target.location).thenReturn(Vector2f(300f, 100f))
            `when`(target.velocity).thenReturn(Vector2f())
            val source = ship(3f, 1)
            `when`(source.velocity).thenReturn(Vector2f(0f, 100f))
            val beam = beam(source)
            val engine = engine(target, beams = listOf(beam))
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.4f)).total.raw > 0f)
            `when`(source.velocity).thenReturn(Vector2f())
            `when`(source.angularVelocity).thenReturn(30f)
            `when`(target.location).thenReturn(Vector2f(100f, 100f))
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.5f)).total.raw > 0f)
            `when`(source.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.5f)).impacts.isEmpty())
        }
    }

    @Test
    fun `sample beam damage integrates OUT source time without multiplying the window`() {
        withSettings {
            val target = ship(1f)
            `when`(target.location).thenReturn(Vector2f(100f, 0f))
            `when`(target.velocity).thenReturn(Vector2f())
            val source = ship(2.5f, 1)
            `when`(source.velocity).thenReturn(Vector2f())
            phase(source, ShipSystemAPI.SystemState.OUT, 0.75f)
            val beam = beam(source)
            val forecast = PhaseThreat().requireSample(engine(target, beams = listOf(beam)), target, PhaseThreat.Window(0.3f, 0.3f))
            val localTime = 0.75f + 0.6f - ln(2.5f) / 2f
            assertEquals(100f * localTime, forecast.total.raw, 0.001f)
            assertTrue(forecast.impacts.all { it.hit.time <= 0.6f })
        }
    }

    @Test
    fun `sample charging beam waits for integrated source local charge time`() {
        withSettings {
            val target = ship(1f)
            `when`(target.location).thenReturn(Vector2f(100f, 0f))
            `when`(target.velocity).thenReturn(Vector2f())
            val source = ship(1.5f, 1)
            `when`(source.velocity).thenReturn(Vector2f())
            phase(source, ShipSystemAPI.SystemState.OUT, 0.25f)
            val weapon = beam(source).weapon
            `when`(weapon.beams).thenReturn(emptyList())
            `when`(weapon.isBeam).thenReturn(true)
            `when`(weapon.isFiring).thenReturn(true)
            `when`(weapon.location).thenReturn(Vector2f())
            `when`(weapon.chargeLevel).thenReturn(0.7f)
            `when`(weapon.damage.damage).thenReturn(100f)
            `when`(weapon.damageType).thenReturn(DamageType.ENERGY)
            `when`((weapon.spec as BeamWeaponSpecAPI).chargeupTime).thenReturn(1f)
            `when`(source.allWeapons).thenReturn(listOf(weapon))
            val engine = engine(target)
            `when`(engine.ships).thenReturn(listOf(target, source))
            val threat = PhaseThreat()
            assertTrue(threat.requireSample(engine, target, PhaseThreat.Window(0f, 0.2f)).impacts.isEmpty())
            `when`(engine.getTotalElapsedTime(false)).thenReturn(0.1f)
            `when`(weapon.chargeLevel).thenReturn(0.8f)
            assertTrue(threat.requireSample(engine, target, PhaseThreat.Window(0f, 0.15f)).impacts.isEmpty())
            assertTrue(threat.requireSample(engine, target, PhaseThreat.Window(0f, 0.2f)).total.raw > 0f)
        }
    }

    @Test
    fun `active candidate beam exposure uses the same outgoing motion as projectiles`() {
        withSettings {
            val target = ship(3f)
            phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
            `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
            `when`(target.location).thenReturn(Vector2f(100f, 150f))
            `when`(target.velocity).thenReturn(Vector2f(0f, -100f))
            val source = ship(1f, 1)
            `when`(source.velocity).thenReturn(Vector2f())
            val engine = engine(target, beams = listOf(beam(source)))
            val window = PhaseThreat.Window(ln(3f) / 4f, 0.4f)
            assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
            `when`(target.location).thenReturn(Vector2f(100f, 80f))
            val forecast = PhaseThreat().requireSample(engine, target, window)
            assertTrue(forecast.duringReaction.raw > 0f)
            assertEquals(0f, forecast.duringChargeOut.raw)
        }
    }

    @Test
    fun `sample lateral missile needs tracking turn capability and remaining life`() {
        val target = ship(3f)
        phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
        `when`(target.velocity).thenReturn(Vector2f())
        val missile = missile(target)
        val engine = engine(target, listOf(missile))
        `when`(engine.missiles).thenReturn(listOf(missile))
        val window = PhaseThreat.Window(ln(3f) / 4f, 0.4f)
        val expected = PhaseThreat.guidedApproach(PhaseThreat.Point(60f, 0f), PhaseThreat.Point(0f, 300f),
            20f, 1f, window.total, PhaseThreat.Guidance(true, 90f, 1080f)).hitTime!!
        val forecast = PhaseThreat().requireSample(engine, target, window)
        assertEquals(expected, forecast.impacts.single().hit.time, 0.0001f)
        assertEquals(100f, forecast.total.raw)

        `when`(missile.maxTurnRate).thenReturn(10f)
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
        `when`(missile.maxTurnRate).thenReturn(1080f)
        `when`(missile.flightTime).thenReturn(0.9f)
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
        `when`(missile.flightTime).thenReturn(0f)
        val other = ship(1f)
        `when`((missile.unwrappedMissileAI as GuidedMissileAI).target).thenReturn(other)
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
        `when`((missile.unwrappedMissileAI as GuidedMissileAI).target).thenReturn(target)
        `when`(missile.location).thenReturn(Vector2f(1000f, 0f))
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
        `when`(missile.location).thenReturn(Vector2f(60f, 0f))
        `when`(missile.isGuided).thenReturn(false)
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
        `when`(missile.isGuided).thenReturn(true)
        `when`(missile.isFizzling).thenReturn(true)
        assertTrue(PhaseThreat().requireSample(engine, target, window).impacts.isEmpty())
    }

    @Test
    fun `guidance does not turn target velocity into missile propulsion`() {
        val target = ship(1f)
        `when`(target.velocity).thenReturn(Vector2f(-1000f, 0f))
        val missile = missile(target)
        val forecast = PhaseThreat().requireSample(engine(target, listOf(missile)), target, PhaseThreat.Window(0f, 0.675f))
        assertTrue(forecast.impacts.isEmpty())
    }

    @Test
    fun `surface clock handles zero phase bonus and instantaneous exit`() {
        val ship = ship(2f)
        phase(ship, ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(0f)
        val plain = PhaseThreat.Motion(ship, surface = true)
        assertEquals(0.5f, plain.chargeOutTime)
        assertEquals(1.8f, plain.localTime(0.9f), 0.0001f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(6f)
        `when`(ship.phaseCloak.chargeDownDur).thenReturn(0f)
        val instant = PhaseThreat.Motion(ship, surface = true)
        assertEquals(0f, instant.chargeOutTime)
        assertEquals(0.8f, instant.localTime(0.4f), 0.0001f)
        assertEquals(0.4f, instant.battleTime(0.8f), 0.0001f)
    }

    @Test
    fun `surface exposure includes full or remaining cooldown at unphased base time`() {
        for (base in listOf(0.5f, 1f, 2f)) {
            for ((state, level) in listOf(
                ShipSystemAPI.SystemState.IN to 0.25f,
                ShipSystemAPI.SystemState.ACTIVE to 1f,
                ShipSystemAPI.SystemState.OUT to 0.75f,
                ShipSystemAPI.SystemState.COOLDOWN to 0f,
                ShipSystemAPI.SystemState.IDLE to 0f,
            )) {
                val target = ship(base * (1f + 2f * level))
                phase(target, state, level)
                `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
                `when`(target.phaseCloak.cooldown).thenReturn(2f)
                `when`(target.phaseCloak.cooldownRemaining).thenReturn(0.75f)
                val motion = PhaseThreat.Motion(target, surface = true)
                val window = motion.requireExposure(PhaseThreat.Window(99f, 0.4f))
                val exit = if (level > 0f) ln(1f + 2f * level) / (4f * base) else 0f
                val cooldown = when (state) {
                    ShipSystemAPI.SystemState.COOLDOWN -> 0.75f / base
                    ShipSystemAPI.SystemState.IDLE -> 0f
                    else -> 2f / base
                }
                assertEquals(exit, window.chargeOut, 0.000001f, "$state/$base")
                assertEquals(cooldown, window.cooldown, 0.000001f, "$state/$base")
                assertEquals(exit + cooldown + 0.4f, window.total, 0.000001f)
                val within = projectile(motion.position(window.total - 0.1f).x + 10f, 0f)
                val outside = projectile(motion.position(window.total + 0.1f).x + 10f, 0f)
                val forecast = PhaseThreat().requireSample(engine(target, listOf(within, outside)), target,
                    PhaseThreat.Window(99f, 0.4f))
                assertEquals(within, forecast.impacts.single().hit.key, "$state/$base")
                assertEquals(window.total - 0.1f, forecast.impacts.single().hit.time, 0.0001f)
            }
        }
    }

    @Test
    fun `lethal projectile beyond old reaction window is counted while cloak cannot reactivate`() {
        val target = ship(3f)
        phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
        `when`(target.phaseCloak.cooldown).thenReturn(2f)
        `when`(target.velocity).thenReturn(Vector2f())
        val incoming = projectile(110f, -100f)
        `when`(incoming.damageAmount).thenReturn(1500f)
        val forecast = PhaseThreat().requireSample(engine(target, listOf(incoming)), target, PhaseThreat.Window(0f, 0.4f))
        assertTrue(forecast.lethal)
        assertEquals(1f, forecast.lethalAt!!, 0.0001f)
        assertEquals(1500f, forecast.duringReaction.raw)
    }

    @Test
    fun `beam entering during phase cooldown contributes real exposure damage`() {
        withSettings {
            val target = ship(3f)
            phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
            `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
            `when`(target.location).thenReturn(Vector2f(100f, 150f))
            `when`(target.velocity).thenReturn(Vector2f(0f, -100f))
            val source = ship(1f, 1)
            `when`(source.velocity).thenReturn(Vector2f())
            val engine = engine(target, beams = listOf(beam(source)))
            assertTrue(PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.4f)).impacts.isEmpty())
            `when`(target.phaseCloak.cooldown).thenReturn(2f)
            val forecast = PhaseThreat().requireSample(engine, target, PhaseThreat.Window(0f, 0.4f))
            assertTrue(forecast.duringReaction.raw > 0f)
            assertEquals(0f, forecast.duringChargeOut.raw)
            assertTrue(forecast.impacts.all { it.hit.time > ln(3f) / 4f + 0.4f })
        }
    }

    @Test
    fun `cooldown exposure survives zero phase bonus and instantaneous charge out`() {
        val target = ship(2f)
        phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(target.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(0f)
        `when`(target.phaseCloak.cooldown).thenReturn(2f)
        val plain = PhaseThreat.Motion(target, surface = true).requireExposure(PhaseThreat.Window(0f, 0.4f))
        assertEquals(0.5f, plain.chargeOut)
        assertEquals(1f, plain.cooldown)
        `when`(target.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(6f)
        `when`(target.phaseCloak.chargeDownDur).thenReturn(0f)
        val instant = PhaseThreat.Motion(target, surface = true).requireExposure(PhaseThreat.Window(99f, 0.4f))
        assertEquals(0f, instant.chargeOut)
        assertEquals(1f, instant.cooldown)
        assertEquals(1.4f, instant.total, 0.000001f)
    }

    @Test
    fun `zero own clock rejects all phase states before reading threats and recovers`() {
        for ((state, level) in listOf(
            ShipSystemAPI.SystemState.IN to 0.25f,
            ShipSystemAPI.SystemState.ACTIVE to 1f,
            ShipSystemAPI.SystemState.OUT to 0.75f,
            ShipSystemAPI.SystemState.COOLDOWN to 0f,
        )) {
            val target = ship(0f)
            phase(target, state, level)
            `when`(target.phaseCloak.cooldown).thenReturn(2f)
            `when`(target.phaseCloak.cooldownRemaining).thenReturn(0.75f)
            val incoming = projectile(10f, 0f)
            val engine = engine(target, listOf(incoming))
            val threat = PhaseThreat()
            val window = PhaseThreat.Window(0f, 0.4f)
            val motion = PhaseThreat.Motion(target, surface = true)
            assertNull(motion.exposure(window), state.name)
            assertEquals("non-positive or non-finite time multiplier", motion.rejectionReason)
            repeat(2) { assertNull(threat.sample(engine, target, window), state.name) }
            verifyNoThreatReads(engine)

            `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(1f + 2f * level)
            val forecast = requireNotNull(threat.sample(engine, target, window))
            assertEquals(incoming, forecast.impacts.single().hit.key, state.name)
            assertEquals(100f, forecast.total.raw, state.name)
        }
    }

    @Test
    fun `nonpositive and nonfinite own multipliers reject even without a cloak`() {
        for (mult in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val target = ship(mult)
            val engine = engine(target)
            assertNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 0.4f)), mult.toString())
            verifyNoThreatReads(engine)
        }
    }

    @Test
    fun `rejection logs only first occurrence and reason changes until recovery`() {
        val logger = Global.getLogger(PhaseThreat::class.java)
        val previousLevel = logger.level
        val messages = mutableListOf<String>()
        val appender = object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) { messages += event.renderedMessage }
            override fun close() {}
            override fun requiresLayout() = false
        }
        logger.level = Level.WARN
        logger.addAppender(appender)
        try {
            val target = ship(0f)
            phase(target, ShipSystemAPI.SystemState.COOLDOWN, 0f)
            val engine = engine(target)
            val threat = PhaseThreat()
            val window = PhaseThreat.Window(0f, 0.4f)
            repeat(2) { assertNull(threat.sample(engine, target, window)) }
            assertEquals(1, messages.size)
            assertTrue(messages.single().contains("non-positive or non-finite time multiplier"))
            `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(Float.MIN_VALUE)
            `when`(target.phaseCloak.cooldownRemaining).thenReturn(1f)
            repeat(2) { assertNull(threat.sample(engine, target, window)) }
            assertEquals(2, messages.size)
            assertTrue(messages.last().contains("non-finite battle cooldown"))
            verifyNoThreatReads(engine)
            `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            requireNotNull(threat.sample(engine, target, window))
            `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(Float.MIN_VALUE)
            assertNull(threat.sample(engine, target, window))
            assertEquals(3, messages.size)
            assertTrue(messages.last().contains("non-finite battle cooldown"))
        } finally {
            logger.removeAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `nonfinite derived charge out and cooldown reject before threat reads`() {
        for (state in listOf(ShipSystemAPI.SystemState.OUT, ShipSystemAPI.SystemState.COOLDOWN)) {
            val target = ship(Float.MIN_VALUE)
            phase(target, state, if (state == ShipSystemAPI.SystemState.OUT) 1f else 0f)
            `when`(target.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(0f)
            `when`(target.phaseCloak.cooldownRemaining).thenReturn(1f)
            val motion = PhaseThreat.Motion(target, surface = true)
            val window = PhaseThreat.Window(0f, 0.4f)
            assertNull(motion.exposure(window))
            assertEquals(if (state == ShipSystemAPI.SystemState.OUT) "non-finite charge-out time"
                else "non-finite battle cooldown", motion.rejectionReason)
            val engine = engine(target)
            assertNull(PhaseThreat().sample(engine, target, window))
            verifyNoThreatReads(engine)
        }
    }

    @Test
    fun `invalid derived multiplier and overflowing local integration reject before threat reads`() {
        val target = ship(3f)
        phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
        val engine = engine(target)
        `when`(target.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(Float.NaN)
        assertNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 0.4f)))
        `when`(target.phaseCloak).thenReturn(null)
        `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(Float.MAX_VALUE)
        assertNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 2f)))
        verifyNoThreatReads(engine)
    }

    @Test
    fun `invalid phase durations and cooldowns reject without constructing a window`() {
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val target = ship(3f)
            phase(target, ShipSystemAPI.SystemState.ACTIVE, 1f)
            val engine = engine(target)
            val window = PhaseThreat.Window(0f, 0.4f)
            `when`(target.phaseCloak.chargeDownDur).thenReturn(invalid)
            assertNull(PhaseThreat().sample(engine, target, window))
            `when`(target.phaseCloak.chargeDownDur).thenReturn(0.5f)
            `when`(target.phaseCloak.cooldown).thenReturn(invalid)
            assertNull(PhaseThreat().sample(engine, target, window))
            verifyNoThreatReads(engine)
        }
    }

    @Test
    fun `overflowed requested and derived window totals reject before threat reads`() {
        val target = ship(1f)
        val engine = engine(target)
        val threat = PhaseThreat()
        assertNull(threat.sample(engine, target, PhaseThreat.Window(Float.MAX_VALUE, Float.MAX_VALUE)))
        phase(target, ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(target.phaseCloak.cooldownRemaining).thenReturn(Float.MAX_VALUE)
        assertNull(threat.sample(engine, target, PhaseThreat.Window(0f, Float.MAX_VALUE)))
        verifyNoThreatReads(engine)
    }

    @Test
    fun `finite window with unrepresentable motion steps rejects before threat reads`() {
        val target = ship(1f)
        val engine = engine(target)
        assertNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 100000000f)))
        verifyNoThreatReads(engine)
    }

    @Test
    fun `guided sampling failure discards partial forecast instead of reporting a complete miss`() {
        val target = ship(1f)
        `when`(target.velocity).thenReturn(Vector2f())
        val missile = missile(target)
        `when`(missile.maxTurnRate).thenReturn(Float.MAX_VALUE)
        val engine = engine(target, listOf(projectile(10f, 0f), missile))
        assertNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 0.5f)))
    }

    @Test
    fun `shared zero multiplier motion remains stationary for friendly interception`() {
        val target = ship(0f)
        `when`(target.angularVelocity).thenReturn(90f)
        val motion = PhaseThreat.Motion(target)
        assertEquals(0f, motion.localTime(1f))
        assertEquals(motion.origin, motion.position(1f))
        assertEquals(0f, motion.rotation(1f))
        val hit = requireNotNull(PhaseThreat.intercept(PhaseThreat.Point(100f, 0f),
            PhaseThreat.Point(-100f, 0f), motion, 10f, 0f, 1f))
        assertEquals(0.9f, hit, 0.0001f)
    }

    @Test
    fun `stopped unrelated enemy and beam source do not cancel a projectile forecast`() {
        val target = ship(1f)
        val source = ship(0f, 1)
        val incoming = projectile(100f, -100f)
        val engine = engine(target, listOf(incoming), listOf(beam(source)))
        `when`(engine.ships).thenReturn(listOf(target, source))
        val forecast = requireNotNull(PhaseThreat().sample(engine, target, PhaseThreat.Window(0f, 1f)))
        assertEquals(incoming, forecast.impacts.single().hit.key)
        assertEquals(100f, forecast.total.raw)
    }

    private fun PhaseThreat.requireSample(engine: CombatEngineAPI, ship: ShipAPI, window: PhaseThreat.Window) =
        requireNotNull(sample(engine, ship, window))

    private fun PhaseThreat.Motion.requireExposure(window: PhaseThreat.Window) = requireNotNull(exposure(window))

    private fun verifyNoThreatReads(engine: CombatEngineAPI) {
        verify(engine, never()).projectiles
        verify(engine, never()).missiles
        verify(engine, never()).ships
        verify(engine, never()).beams
    }

    private interface TrackingAI : MissileAIPlugin, GuidedMissileAI

    private fun missile(target: ShipAPI): MissileAPI {
        val missile = mock(MissileAPI::class.java, RETURNS_DEEP_STUBS)
        val ai = mock(TrackingAI::class.java)
        `when`(ai.target).thenReturn(target)
        `when`(missile.unwrappedMissileAI).thenReturn(ai)
        `when`(missile.location).thenReturn(Vector2f(60f, 0f))
        `when`(missile.velocity).thenReturn(Vector2f(0f, 300f))
        `when`(missile.facing).thenReturn(90f)
        `when`(missile.maxTurnRate).thenReturn(1080f)
        `when`(missile.maxFlightTime).thenReturn(1f)
        `when`(missile.isGuided).thenReturn(true)
        `when`(missile.isArmed).thenReturn(true)
        `when`(missile.collisionRadius).thenReturn(10f)
        `when`(missile.owner).thenReturn(1)
        `when`(missile.collisionClass).thenReturn(CollisionClass.MISSILE_FF)
        `when`(missile.explosionSpecIfExplosion).thenReturn(null)
        `when`(missile.spec.behaviorJSON).thenReturn(null)
        `when`(missile.damageAmount).thenReturn(100f)
        `when`(missile.damageType).thenReturn(DamageType.ENERGY)
        `when`(missile.damage.stats).thenReturn(null)
        return missile
    }

    private fun phase(ship: ShipAPI, state: ShipSystemAPI.SystemState, level: Float) {
        val cloak = mock(ShipSystemAPI::class.java)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(cloak.state).thenReturn(state)
        `when`(cloak.effectLevel).thenReturn(level)
        `when`(cloak.chargeUpDur).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(1f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
    }

    private fun ship(mult: Float, owner: Int = 0): ShipAPI {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.location).thenReturn(Vector2f())
        `when`(ship.velocity).thenReturn(Vector2f(100f, 0f))
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.phaseCloak).thenReturn(null)
        `when`(ship.shield).thenReturn(null)
        `when`(ship.exactBounds).thenReturn(null)
        `when`(ship.collisionRadius).thenReturn(10f)
        `when`(ship.hitpoints).thenReturn(1000f)
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(mult)
        `when`(ship.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.beamDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
        return ship
    }

    private fun projectile(x: Float, speed: Float): DamagingProjectileAPI {
        val projectile = mock(DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(projectile.location).thenReturn(Vector2f(x, 0f))
        `when`(projectile.velocity).thenReturn(Vector2f(speed, 0f))
        `when`(projectile.owner).thenReturn(1)
        `when`(projectile.collisionClass).thenReturn(CollisionClass.PROJECTILE_FF)
        `when`(projectile.explosionSpecIfExplosion).thenReturn(null)
        `when`(projectile.projectileSpec).thenReturn(null)
        `when`(projectile.damageAmount).thenReturn(100f)
        `when`(projectile.damageType).thenReturn(DamageType.ENERGY)
        `when`(projectile.damage.stats).thenReturn(null)
        return projectile
    }

    private fun beam(source: ShipAPI): BeamAPI {
        val beam = mock(BeamAPI::class.java, RETURNS_DEEP_STUBS)
        val weapon = mock(WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        val spec = mock(BeamWeaponSpecAPI::class.java)
        `when`(weapon.ship).thenReturn(source)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.beams).thenReturn(listOf(beam))
        `when`(spec.collisionClass).thenReturn(CollisionClass.PROJECTILE_FF)
        `when`(beam.weapon).thenReturn(weapon)
        `when`(beam.source).thenReturn(source)
        `when`(beam.from).thenReturn(Vector2f())
        `when`(beam.to).thenReturn(Vector2f(1000f, 0f))
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(beam.damage.type).thenReturn(DamageType.ENERGY)
        `when`(beam.damageTarget).thenReturn(null)
        return beam
    }

    private fun engine(ship: ShipAPI, projectiles: List<DamagingProjectileAPI> = emptyList(), beams: List<BeamAPI> = emptyList()): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(ship))
        `when`(engine.projectiles).thenReturn(projectiles)
        `when`(engine.missiles).thenReturn(emptyList())
        `when`(engine.beams).thenReturn(beams)
        return engine
    }

    private fun withSettings(block: () -> Unit) {
        val previous = Global.getSettings()
        val settings = mock(SettingsAPI::class.java)
        `when`(settings.getFloat("dpsToHitStrengthMult")).thenReturn(1f)
        Global.setSettings(settings)
        try {
            block()
        } finally {
            Global.setSettings(previous)
        }
    }
}
