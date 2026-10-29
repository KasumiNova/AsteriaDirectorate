package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseFriendlyFireTest {
    @Test
    fun `projectile expiring after own interception cannot transfer to a later friendly`() {
        val projectile = projectile()
        `when`(projectile.elapsed).thenReturn(0.8f)
        assertEquals(0f, transfer(projectile))
        `when`(projectile.elapsed).thenReturn(0f)
        assertEquals(100f, transfer(projectile), 0.001f)
    }

    @Test
    fun `missile remaining flight lifetime bounds friendly interception`() {
        val missile = mock(MissileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(missile.owner).thenReturn(1)
        `when`(missile.location).thenReturn(Vector2f())
        `when`(missile.velocity).thenReturn(Vector2f(100f, 0f))
        `when`(missile.explosionSpecIfExplosion).thenReturn(null)
        `when`(missile.maxFlightTime).thenReturn(1f)
        `when`(missile.flightTime).thenReturn(0.8f)
        assertEquals(0f, transfer(missile))
        `when`(missile.flightTime).thenReturn(0f)
        assertEquals(100f, transfer(missile), 0.001f)
    }

    @Test
    fun `explosion is not damage transferred by removing the intercepting ship`() {
        val projectile = projectile()
        assertEquals(100f, transfer(projectile), 0.001f)
        assertEquals(0f, transfer(projectile, stage = PhaseThreat.Stage.EXPLOSION))
    }

    @Test
    fun `transfer window starts now rather than restarting at own interception`() {
        val projectile = projectile()
        `when`(projectile.projectileSpec.maxRange).thenReturn(1000f)
        assertEquals(0f, transfer(projectile, ownHitTime = 1.1f, friendlyX = 180f))
        assertEquals(100f, transfer(projectile, ownHitTime = 1.1f, friendlyX = 150f), 0.001f)
    }

    @Test
    fun `expired projectile and own hit beyond remaining life do not transfer`() {
        val projectile = projectile()
        `when`(projectile.elapsed).thenReturn(0.8f)
        assertEquals(0f, transfer(projectile, ownHitTime = 0.3f))
        `when`(projectile.elapsed).thenReturn(0f)
        `when`(projectile.isExpired).thenReturn(true)
        assertEquals(0f, transfer(projectile))
    }

    private fun transfer(
        projectile: DamagingProjectileAPI,
        stage: PhaseThreat.Stage = PhaseThreat.Stage.DIRECT,
        ownHitTime: Float = 0.1f,
        friendlyX: Float = 100f,
    ): Float {
        val ship = ship(20f)
        val friendly = ship(friendlyX)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(ship, friendly))
        val forecast = PhaseThreat.estimate(PhaseThreat.Defense(1000f), listOf(
            PhaseThreat.Hit(projectile, ownHitTime, 100f, DamageType.ENERGY, stage = stage),
        ), PhaseThreat.Window(0f, 1.2f))
        return PhaseFriendlyFire.transferredLoss(engine, ship, forecast)
    }

    private fun projectile(): DamagingProjectileAPI {
        val projectile = mock(DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(projectile.owner).thenReturn(1)
        `when`(projectile.location).thenReturn(Vector2f())
        `when`(projectile.velocity).thenReturn(Vector2f(100f, 0f))
        `when`(projectile.moveSpeed).thenReturn(100f)
        `when`(projectile.weapon).thenReturn(null)
        `when`(projectile.explosionSpecIfExplosion).thenReturn(null)
        `when`(projectile.projectileSpec.maxRange).thenReturn(100f)
        return projectile
    }

    private fun ship(x: Float): ShipAPI {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f(x, 0f))
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.phaseCloak).thenReturn(null)
        `when`(ship.shield).thenReturn(null)
        `when`(ship.collisionRadius).thenReturn(10f)
        `when`(ship.hitpoints).thenReturn(1000f)
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
        return ship
    }
}
