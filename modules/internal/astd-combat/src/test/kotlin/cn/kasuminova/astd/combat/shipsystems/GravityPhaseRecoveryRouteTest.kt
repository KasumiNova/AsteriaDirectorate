package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravityPhaseRecoveryRouteTest {
    private var previousSettings: com.fs.starfarer.api.SettingsAPI? = null

    @kotlin.test.BeforeTest
    fun installSettings() {
        previousSettings = com.fs.starfarer.api.Global.getSettings()
        val settings = mock(com.fs.starfarer.api.SettingsAPI::class.java)
        `when`(settings.getFloat("minRefireDelay")).thenReturn(0.05f)
        `when`(settings.getFloat("minVentTimeForWeaponReload")).thenReturn(2f)
        com.fs.starfarer.api.Global.setSettings(settings)
    }

    @kotlin.test.AfterTest
    fun restoreSettings() {
        com.fs.starfarer.api.Global.setSettings(previousSettings)
    }

    @Test
    fun `恢复路线完成后不会在下个规划周期重新创建`() {
        val ship = ship("player", 0, 0f, 0f)
        val enemy = ship("enemy", 1, 1000f, 0f)
        val cloak = mock(ShipSystemAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val customData = mutableMapOf<String, Any>()
        val flux = ship.fluxTracker
        `when`(flux.currFlux).thenReturn(6500f)
        `when`(flux.hardFlux).thenReturn(6000f)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.system).thenReturn(null)
        `when`(ship.mutableStats.fluxDissipation.modifiedValue).thenReturn(200f)
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        `when`(cloak.fluxPerUse).thenReturn(100f)
        `when`(cloak.fluxPerSecond).thenReturn(50f)
        `when`(engine.ships).thenReturn(listOf(ship, enemy))
        `when`(engine.projectiles).thenReturn(emptyList())
        `when`(engine.missiles).thenReturn(emptyList())
        `when`(engine.beams).thenReturn(emptyList())
        `when`(engine.asteroids).thenReturn(emptyList())
        `when`(engine.mapWidth).thenReturn(20000f)
        `when`(engine.mapHeight).thenReturn(20000f)
        `when`(engine.customData).thenReturn(customData)

        val ai = GravityPhaseCloakAI()
        ai.init(ship, cloak, ship.aiFlags, engine)
        var completed = false
        val history = mutableListOf<Map<String, *>>()
        for (step in 0 until 30) {
            val diagnostics = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as? Map<String, *>
            if ((diagnostics?.get("pathStage") as? Int ?: -1) >= 0) {
                `when`(ship.location).thenReturn(
                    Vector2f(diagnostics!!["waypointX"] as Float, diagnostics["waypointY"] as Float),
                )
            }
            ai.advance(0.2f, null, null, enemy)
            val next = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as? Map<String, *>
            next?.let(history::add)
            if (next?.get("routeCompleted") == true) {
                completed = true
                break
            }
        }
        assertTrue(completed, "恢复路线没有到达完成状态：$history")

        ai.advance(0.2f, null, null, enemy)
        val diagnostics = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        assertEquals(true, diagnostics["routeCompleted"])
        assertEquals(0, diagnostics["pathStage"])
        verify(ship.shipAI, times(1)).cancelCurrentManeuver()
        verify(ship.shipAI, times(1)).forceCircumstanceEvaluation()

        `when`(enemy.id).thenReturn("enemyB")
        ai.advance(0.2f, null, null, enemy)
        val changedTarget = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        assertEquals(false, changedTarget["routeCompleted"])
        assertEquals("enemyB", changedTarget["targetId"])
    }

    @Test
    fun `必然超过相位时限的绕后路线会被拒绝并记录失败`() {
        val ship = ship("player", 0, 1000f, 0f)
        val enemy = ship("enemy", 1, 0f, 0f)
        val cloak = mock(ShipSystemAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val customData = mutableMapOf<String, Any>()
        val weapon = mock(WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        val spec = mock(ProjectileWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.system).thenReturn(null)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(80f)
        `when`(ship.mutableStats.fluxDissipation.modifiedValue).thenReturn(200f)
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(ship.fluxTracker.currFlux).thenReturn(100f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(100f)
        `when`(weapon.id).thenReturn("test_gun")
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.originalSpec).thenReturn(spec)
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(spec.burstSize).thenReturn(1)
        `when`(spec.aiHints).thenReturn(java.util.EnumSet.noneOf(WeaponAPI.AIHints::class.java))
        `when`(spec.turretFireOffsets).thenReturn(listOf(Vector2f()))
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        `when`(cloak.canBeActivated()).thenReturn(true)
        `when`(cloak.fluxPerUse).thenReturn(1f)
        `when`(cloak.fluxPerSecond).thenReturn(1f)
        `when`(enemy.allWeapons).thenReturn(listOf(weapon))
        `when`(engine.ships).thenReturn(listOf(ship, enemy))
        `when`(engine.projectiles).thenReturn(emptyList())
        `when`(engine.missiles).thenReturn(emptyList())
        `when`(engine.beams).thenReturn(emptyList())
        `when`(engine.asteroids).thenReturn(emptyList())
        `when`(engine.mapWidth).thenReturn(20000f)
        `when`(engine.mapHeight).thenReturn(20000f)
        `when`(engine.customData).thenReturn(customData)

        val ai = GravityPhaseCloakAI()
        ai.init(ship, cloak, ship.aiFlags, engine)
        ai.advance(0.2f, null, null, enemy)

        val diagnostics = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        assertEquals("APPROACH", diagnostics["action"])
        assertEquals(true, diagnostics["routeFailed"])
        assertEquals("TIME_LIMIT", diagnostics["routeFailureReason"])
        assertEquals(-1, diagnostics["pathStage"])
        verify(ship, times(0)).giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }

    @Test
    fun `非相位恢复路线切相位暂停后保留原进度和累计预算`() {
        val ship = ship("player", 0, 0f, 0f)
        val enemy = ship("enemy", 1, 1000f, 0f)
        val cloak = mock(ShipSystemAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val data = mutableMapOf<String, Any>()
        val weapon = mock(WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        val spec = mock(ProjectileWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.system).thenReturn(null)
        `when`(ship.fluxTracker.currFlux).thenReturn(6500f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(ship.mutableStats.fluxDissipation).thenReturn(com.fs.starfarer.api.combat.MutableStat(200f))
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        `when`(cloak.canBeActivated()).thenReturn(true)
        `when`(cloak.fluxPerUse).thenReturn(100f)
        `when`(cloak.fluxPerSecond).thenReturn(50f)
        `when`(cloak.chargeUpDur).thenReturn(0.5f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(weapon.id).thenReturn("cooling")
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.originalSpec).thenReturn(spec)
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.location).thenReturn(Vector2f())
        `when`(weapon.range).thenReturn(2000f)
        `when`(weapon.cooldown).thenReturn(4f)
        `when`(weapon.refireDelay).thenReturn(4f)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(spec.burstSize).thenReturn(1)
        `when`(spec.refireDelay).thenReturn(4f)
        `when`(spec.turretFireOffsets).thenReturn(listOf(Vector2f()))
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(engine.ships).thenReturn(listOf(ship, enemy))
        `when`(engine.projectiles).thenReturn(emptyList())
        `when`(engine.missiles).thenReturn(emptyList())
        `when`(engine.beams).thenReturn(emptyList())
        `when`(engine.asteroids).thenReturn(emptyList())
        `when`(engine.mapWidth).thenReturn(20000f)
        `when`(engine.mapHeight).thenReturn(20000f)
        `when`(engine.customData).thenReturn(data)
        val ai = GravityPhaseCloakAI()
        ai.init(ship, cloak, ship.aiFlags, engine)
        fun tick(): Map<*, *> {
            ai.advance(0.2f, null, null, enemy)
            return data[GravityPhaseCloakAI.diagnosticsKey(ship.id)] as Map<*, *>
        }
        tick()
        val before = tick()
        assertEquals("RECOVERY", before["routeStage"])
        `when`(ship.fluxTracker.currFlux).thenReturn(1000f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(100f)
        val selected = tick()
        assertEquals("PHASE_COOLDOWN", selected["recoverMode"], selected.toString())
        assertEquals(before["routeVersion"], selected["routeVersion"])
        val elapsed = selected["routeElapsed"] as Float
        assertTrue(elapsed > 0f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.isPhased).thenReturn(true)
        repeat(3) {
            val paused = tick()
            assertEquals(elapsed, paused["routeElapsed"])
            assertEquals(selected["routeProgress"], paused["routeProgress"])
            assertEquals(selected["routeVersion"], paused["routeVersion"])
            assertEquals(12f, paused["routeMaxElapsed"])
        }
        `when`(ship.fluxTracker.currFlux).thenReturn(6500f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(6000f)
        assertEquals("HARD_FLUX_LIMIT", tick()["recoveryReason"])
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        assertEquals(elapsed, tick()["routeElapsed"])
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        `when`(cloak.effectLevel).thenReturn(0f)
        `when`(ship.isPhased).thenReturn(false)
        tick()
        val resumed = tick()
        assertEquals(selected["routeVersion"], resumed["routeVersion"])
        assertTrue((resumed["routeElapsed"] as Float) > elapsed)
        assertEquals(12f, resumed["routeMaxElapsed"])
    }

    private fun ship(id: String, owner: Int, x: Float, y: Float): ShipAPI {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val flux = mock(FluxTrackerAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.name).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f(x, y))
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.collisionRadius).thenReturn(50f)
        `when`(ship.hitpoints).thenReturn(5000f)
        `when`(ship.maxHitpoints).thenReturn(5000f)
        `when`(ship.maxFlux).thenReturn(10000f)
        `when`(ship.allWeapons).thenReturn(emptyList())
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(flux.maxFlux).thenReturn(10000f)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(200f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.energyRoFMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.mutableStats.acceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.deceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.turnAcceleration.modifiedValue).thenReturn(90f)
        `when`(ship.mutableStats.maxTurnRate.modifiedValue).thenReturn(90f)
        return ship
    }
}
