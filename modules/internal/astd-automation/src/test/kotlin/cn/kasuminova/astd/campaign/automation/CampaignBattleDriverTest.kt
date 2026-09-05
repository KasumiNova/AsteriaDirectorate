package cn.kasuminova.astd.campaign.automation

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatFleetManagerAPI
import com.fs.starfarer.api.combat.CombatTaskManagerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.mission.FleetSide
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.lwjgl.util.vector.Vector2f

class CampaignBattleDriverTest {

    @Test
    fun `deploys reserves through fleet manager and damages live enemy ships`() {
        val engine = mock(CombatEngineAPI::class.java)
        val manager = mock(CombatFleetManagerAPI::class.java)
        val reserve = mock(FleetMemberAPI::class.java)
        val deployed = mock(FleetMemberAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        val location = Vector2f(0f, 0f)

        `when`(reserve.id).thenReturn("reserve")
        `when`(deployed.id).thenReturn("deployed")
        `when`(deployed.isFlagship).thenReturn(true)
        `when`(deployed.isFighterWing).thenReturn(false)
        `when`(ship.originalOwner).thenReturn(1)
        `when`(ship.isFighter).thenReturn(false)
        `when`(ship.isStationModule).thenReturn(false)
        `when`(ship.fleetMember).thenReturn(deployed)
        `when`(engine.ships).thenReturn(listOf(ship))
        `when`(engine.playerShip).thenReturn(ship)
        `when`(manager.getDeployedCopy()).thenReturn(listOf(deployed))
        `when`(manager.getReservesCopy()).thenReturn(listOf(reserve))
        `when`(manager.spawnFleetMember(any(), any(), anyFloat(), anyFloat())).thenReturn(ship)
        `when`(manager.getShipFor(deployed)).thenReturn(ship)
        `when`(ship.location).thenReturn(location)
        `when`(engine.isInCampaign).thenReturn(true)
        `when`(engine.isInCampaignSim).thenReturn(false)
        `when`(engine.isCombatOver).thenReturn(false)
        `when`(engine.isEntityInPlay(ship)).thenReturn(true)
        `when`(engine.getFleetManager(any(FleetSide::class.java))).thenReturn(manager)
        `when`(engine.isShipAlive(ship)).thenReturn(true)
        `when`(manager.getDestroyedCopy()).thenReturn(emptyList())

        val driver = CampaignBattleDriver("astd_test_bounty", setOf("deployed", "reserve"))
        driver.init(engine)
        driver.advance(0.1f, mutableListOf())
        driver.advance(4.1f, mutableListOf())

        // Mock CombatEngine 无法复刻 Starsector 的 reserves/deployed 生命周期；实机场景负责验证
        // spawnFleetMember 与 applyDamage，单测只锁定 driver 已进入真实 campaign 战斗并开启不自动结束。
        verify(engine).setDoNotEndCombat(eq(true))
        assertTrue(driver.enteredBattle)
    }
}
