package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.StarfallEchoStageAccess
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.mission.FleetSide
import org.apache.log4j.Logger
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.*
import kotlin.test.*

class PhaseScenarioLifecycleTest {
    private class Context(override val engine: CombatEngineAPI) : AutomationCombatContext {
        override val log: Logger = Logger.getLogger("phase-test")
        override var elapsed = 0f
        override var failureReason: String? = null
        override var completed = false
        override var completedAt = -1f
        override var lastWriteAt = -1f
        override var visualFramesWritten = 0
        override var lastVisualFrameAt = 0f
        override val starfallEcho: StarfallEchoStageAccess get() = error("unused")
        val states = ArrayList<String>()
        override fun markCompleted() { completed = true; completedAt = elapsed }
        override fun writeDiagnostics(state: String, ship: ShipAPI?) { states.add(state) }
        override fun writeTelemetry(state: String, ship: ShipAPI?, weapon: WeaponAPI?) {}
    }
    private class Scenario : AbstractPhaseAICombatScenario() {
        override val scenarioId = "test"
        override fun buildFleet() = listOf(
            FleetEntry("player", FleetSide.PLAYER, "phase_Standard", "phase", Vector2f(), 0f, true),
            FleetEntry("enemy_0", FleetSide.ENEMY, "eagle_Assault", "eagle", Vector2f(1000f, 0f), 180f),
            FleetEntry("enemy_1", FleetSide.ENEMY, "eagle_Assault", "eagle", Vector2f(1000f, 500f), 180f),
        )
        override fun stageCamera(engine: CombatEngineAPI, player: ShipAPI?) {}
        var verification = Result.Observing
        override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>) = verification
    }
    private class Battle {
        val scenario = Scenario()
        val engine = mock(CombatEngineAPI::class.java)
        val context = Context(engine)
        val ships = ArrayList<ShipAPI>()
        val planned = LinkedHashMap<String, ShipAPI>()
        val reserves = mutableMapOf(FleetSide.PLAYER to ArrayList<FleetMemberAPI>(), FleetSide.ENEMY to ArrayList())
        var now = 0f
        init {
            `when`(engine.ships).thenReturn(ships)
            `when`(engine.getTotalElapsedTime(false)).thenAnswer { now }
            `when`(engine.isEntityInPlay(any())).thenAnswer { it.getArgument<CombatEntityAPI>(0) in ships }
            for (entry in scenario.buildFleet()) {
                val ship = mock(ShipAPI::class.java)
                val member = mock(FleetMemberAPI::class.java)
                val hull = mock(ShipHullSpecAPI::class.java)
                val variant = mock(ShipVariantAPI::class.java)
                val flux = mock(FluxTrackerAPI::class.java)
                `when`(ship.id).thenReturn("runtime-${entry.uniqueId}")
                `when`(ship.fleetMember).thenReturn(member)
                `when`(member.id).thenReturn(entry.uniqueId)
                `when`(ship.hullSpec).thenReturn(hull)
                `when`(hull.hullId).thenReturn(entry.hullId)
                `when`(ship.variant).thenReturn(variant)
                `when`(variant.hullVariantId).thenReturn(entry.variantId)
                `when`(ship.owner).thenReturn(if (entry.side == FleetSide.PLAYER) 0 else 1)
                `when`(ship.shipAI).thenReturn(mock(ShipAIPlugin::class.java))
                `when`(ship.fluxTracker).thenReturn(flux)
                `when`(ship.hitpoints).thenReturn(1000f)
                `when`(ship.location).thenReturn(Vector2f(entry.anchor))
                planned[entry.uniqueId] = ship
                reserves.getValue(entry.side).add(member)
            }
            for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
                val manager = mock(CombatFleetManagerAPI::class.java)
                `when`(engine.getFleetManager(side)).thenReturn(manager)
                `when`(manager.getTaskManager(anyBoolean())).thenReturn(mock(CombatTaskManagerAPI::class.java))
                `when`(manager.reservesCopy).thenAnswer { ArrayList(reserves.getValue(side)) }
                `when`(manager.spawnFleetMember(any(), any(), anyFloat(), anyFloat())).thenAnswer {
                    planned.getValue(it.getArgument<FleetMemberAPI>(0).id).also { ship -> ships.add(ship) }
                }
                doAnswer { reserves.getValue(side).remove(it.getArgument<FleetMemberAPI>(0)); null }.`when`(manager).removeFromReserves(any())
            }
            scenario.init(context)
        }
        fun frame(time: Float) {
            now = time; context.elapsed = time
            scenario.advance(context, 0.1f)
        }
    }

    @Test fun `same hull fleet deploys each exact member once`() {
        val b = Battle()
        b.frame(0f); b.frame(1f)
        assertEquals(3, b.ships.size)
        assertEquals(3, b.ships.map { it.id }.distinct().size)
        assertTrue(b.reserves.values.all { it.isEmpty() })
        verify(b.engine, atLeastOnce()).setDoNotEndCombat(true)
        verify(b.engine, never()).setDoNotEndCombat(false)
        assertNull(b.context.failureReason)
    }

    @Test fun `missing member cannot start observation or evade deployment timeout`() {
        val b = Battle()
        b.reserves.getValue(FleetSide.ENEMY).removeLast()
        b.frame(0f); b.frame(16f)
        assertFalse(b.context.completed)
        assertEquals("Failed", b.context.states.last())
        assertTrue(b.context.failureReason!!.contains("完整部署"))
    }

    @Test fun `player death is settled outside live ship gate`() {
        val b = Battle()
        b.frame(0f)
        `when`(b.planned.getValue("player").isHulk).thenReturn(true)
        b.frame(1f)
        assertEquals("Failed", b.context.states.last())
        assertTrue(b.context.failureReason!!.contains("正常结束"))
    }

    @Test fun `player removal while enemies remain cannot hang`() {
        val b = Battle()
        b.frame(0f)
        b.ships.remove(b.planned.getValue("player"))
        b.frame(1f)
        assertEquals("Failed", b.context.states.last())
        assertTrue(b.context.failureReason!!.contains("正常结束"))
    }

    @Test fun `qualified evidence completes immediately when final enemy dies`() {
        val b = Battle()
        b.frame(0f)
        b.scenario.verification = AbstractPhaseAICombatScenario.Result.Completed
        b.planned.filterKeys { it.startsWith("enemy") }.values.forEach {
            `when`(it.isHulk).thenReturn(true)
        }
        b.frame(1f)
        assertTrue(b.context.completed)
        assertEquals("Completed", b.context.states.last())
        verify(b.engine, atLeastOnce()).setDoNotEndCombat(false)
    }

    @Test fun `fx drone without fleet member is not an unexpected combat ship`() {
        val b = Battle()
        b.frame(0f)
        val drone = mock(ShipAPI::class.java)
        b.ships.add(drone)
        b.frame(1f)
        assertFalse(b.context.completed)
        assertNotEquals("Failed", b.context.states.last())
    }

    @Test fun `diagnostics distinguish ordinary retreat from phase anchor emergency`() {
        val b = Battle()
        b.frame(0f)
        val player = b.planned.getValue("player")
        val anchor = com.fs.starfarer.api.impl.hullmods.PhaseAnchor.PhaseAnchorScript(player)
        `when`(player.getListeners(com.fs.starfarer.api.impl.hullmods.PhaseAnchor.PhaseAnchorScript::class.java))
            .thenReturn(listOf(anchor))
        `when`(player.isRetreating).thenReturn(true)
        for (emergency in listOf(false, true)) {
            anchor.emergencyDive = emergency
            val text = StringBuilder("{\n")
            b.scenario.appendDiagnostics(b.context, text,
                cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot(0, null, 0f))
            text.append("\"complete\":true}")
            val result = org.json.JSONObject(text.toString())
            assertEquals(emergency, result.getBoolean("paiPhaseAnchorEmergency"))
            assertTrue(result.getBoolean("paiPlayerRetreating"))
            assertTrue(result.getBoolean("paiPlayerInPlay"))
            assertEquals(1000.0, result.getDouble("paiPlayerHull"))
        }
    }

    @Test fun `weapon gate diagnostics preserve actual targets and typed values`() {
        val b = Battle()
        b.frame(0f)
        val player = b.planned.getValue("player")
        val diagnosticsKey = "astd_phase_diagnostics_${player.id}"
        `when`(b.engine.customData).thenReturn(mutableMapOf<String, Any>(
            diagnosticsKey to mapOf(
                "shipTarget" to "enemy-a", "handoffTarget" to "enemy-b",
                "weaponGates" to listOf(
                    mapOf("weapon" to "ionpulser", "blocked" to true, "charge" to 0.4f,
                        "autofireTarget" to "enemy-a"),
                    mapOf("weapon" to "amblaster", "blocked" to false, "charge" to Float.NaN),
                ),
            ),
        ))
        val text = StringBuilder("{\n")
        b.scenario.appendDiagnostics(b.context, text,
            cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot(0, null, 0f))
        text.append("\"complete\":true}")
        val result = org.json.JSONObject(text.toString())
        assertEquals("enemy-a", result.getString("paiShipTarget"))
        assertEquals("enemy-b", result.getString("paiHandoffTarget"))
        val gates = result.getJSONArray("paiWeaponGates")
        assertEquals(2, gates.length())
        assertTrue(gates.getJSONObject(0).getBoolean("blocked"))
        assertEquals(0.4, gates.getJSONObject(0).getDouble("charge"), 0.0001)
        assertEquals("enemy-a", gates.getJSONObject(0).getString("autofireTarget"))
        assertFalse(gates.getJSONObject(1).getBoolean("blocked"))
        assertTrue(gates.getJSONObject(1).isNull("charge"))
    }

    @Test fun `unselected reserve is a failure not silently removed`() {
        val b = Battle()
        val unexpected = mock(FleetMemberAPI::class.java)
        `when`(unexpected.id).thenReturn("extra")
        b.reserves.getValue(FleetSide.ENEMY).add(unexpected)
        b.frame(0f)
        assertEquals("Failed", b.context.states.last())
        assertTrue(unexpected in b.reserves.getValue(FleetSide.ENEMY))
    }
}
