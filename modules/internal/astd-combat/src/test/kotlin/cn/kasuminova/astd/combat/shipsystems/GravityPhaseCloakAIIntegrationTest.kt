package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test

class GravityPhaseCloakAIIntegrationTest {
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
    fun `切换保护阻止下潜时不建立恢复承诺`() {
        val f = RecoveryFixture()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(3000f, 0f))
        f.advance()
        kotlin.test.assertEquals("APPROACH", f.diagnostics()["action"])
        verify(f.ship, org.mockito.Mockito.times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        `when`(f.enemy.location).thenReturn(Vector2f(500f, 0f))
        `when`(f.weapon.cooldownRemaining).thenReturn(4f)
        f.advance()
        kotlin.test.assertEquals("PHASE_COOLDOWN", f.diagnostics()["recoverMode"])
        kotlin.test.assertEquals("NONE", f.diagnostics()["recoveryState"])
        f.advance()
        kotlin.test.assertEquals("NONE", f.diagnostics()["recoveryState"])
        f.advance()
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        verify(f.ship, org.mockito.Mockito.times(2)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }

    @Test
    fun `已承诺绕后在相位恢复继续推进而退出过渡暂停`() {
        val f = RecoveryFixture()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.mutableStats.maxSpeed.modifiedValue).thenReturn(700f)
        `when`(f.ship.mutableStats.acceleration.modifiedValue).thenReturn(150f)
        `when`(f.ship.mutableStats.deceleration.modifiedValue).thenReturn(150f)
        `when`(f.ship.mutableStats.turnAcceleration.modifiedValue).thenReturn(90f)
        `when`(f.ship.mutableStats.maxTurnRate.modifiedValue).thenReturn(90f)
        `when`(f.ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        f.advance()
        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])
        val version = f.diagnostics()["routeVersion"]
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(3100f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(3200f)
        repeat(3) { f.advance() }
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        `when`(f.weapon.cooldownRemaining).thenReturn(4f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(100f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(1000f)
        f.state(ShipSystemAPI.SystemState.IDLE, 0f)
        repeat(2) { f.advance() }
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        f.advance()
        val start = f.diagnostics()["routeElapsed"] as Float
        f.advance()
        kotlin.test.assertTrue((f.diagnostics()["routeElapsed"] as Float) > start)
        kotlin.test.assertEquals(version, f.diagnostics()["routeVersion"])
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        val stop = f.diagnostics()["routeElapsed"]
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        repeat(2) { f.advance() }
        kotlin.test.assertEquals(stop, f.diagnostics()["routeElapsed"])
        kotlin.test.assertEquals(version, f.diagnostics()["routeVersion"])
    }

    @Test
    fun `普通暂停路线重验证不可完成时不会支付下潜费用`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])
        verify(f.ship, times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.IDLE, 0f)
        `when`(f.ship.mutableStats.maxSpeed.modifiedValue).thenReturn(1f)

        f.advance()

        kotlin.test.assertEquals("暂停绕后路线仍需恢复，取消本次下潜", f.diagnostics()["reason"])
        kotlin.test.assertEquals(false, f.diagnostics()["routeFailed"])
        verify(f.ship, times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }

    @Test
    fun `重叠脱离期间目标转向不会重规划并清空穿后进度`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 400f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 400f))
        f.advance()
        f.advance()
        kotlin.test.assertEquals("SIDE_ENTRY", f.diagnostics()["routeStage"])
        val version = f.diagnostics()["routeVersion"]
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.enemy.facing).thenReturn(60f)
        `when`(f.ship.location).thenReturn(Vector2f())
        `when`(f.weapon.location).thenReturn(Vector2f())

        f.advance()

        kotlin.test.assertEquals("EVADE", f.diagnostics()["action"])
        kotlin.test.assertEquals(version, f.diagnostics()["routeVersion"])
        kotlin.test.assertEquals(false, f.diagnostics()["routeFailed"])
    }

    @Test
    fun `恢复连续执行跨射界变化切换保护及OUT后才允许完整攻击`() {
        val f = RecoveryFixture()
        f.advance()
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        kotlin.test.assertEquals("PHASE_COOLDOWN", f.diagnostics()["recoverMode"])
        verify(f.ship, org.mockito.Mockito.times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        f.state(ShipSystemAPI.SystemState.IN, 0.5f)
        `when`(f.weapon.distanceFromArc(f.enemy.location)).thenReturn(10000f)
        f.advance()
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        kotlin.test.assertEquals("NONE", f.diagnostics()["recoveryCandidateMode"])
        kotlin.test.assertEquals("COOLDOWN_PENDING", f.diagnostics()["recoveryReason"])
        kotlin.test.assertEquals(4f, f.diagnostics()["recoveryCooldownRemaining"])
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        org.mockito.Mockito.clearInvocations(f.weapon)
        f.advance()
        kotlin.test.assertEquals("EXITING", f.diagnostics()["recoveryState"])
        kotlin.test.assertEquals("OBJECTIVES_MET", f.diagnostics()["recoveryReason"])
        verify(f.ship, org.mockito.Mockito.times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        verify(f.weapon).setForceNoFireOneFrame(true)
        f.advance()
        verify(f.ship, org.mockito.Mockito.times(2)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        org.mockito.Mockito.clearInvocations(f.weapon)
        f.advance()
        kotlin.test.assertEquals("EXITING", f.diagnostics()["recoveryState"])
        verify(f.weapon).setForceNoFireOneFrame(true)
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(f.weapon.distanceFromArc(f.enemy.location)).thenReturn(0f)
        org.mockito.Mockito.clearInvocations(f.weapon)
        f.advance()
        kotlin.test.assertEquals("COMPLETED", f.diagnostics()["recoveryState"])
        kotlin.test.assertEquals("ATTACK", f.diagnostics()["action"])
        verify(f.ship).setShipTarget(f.enemy)
        verify(f.ship.shipAI).setTargetOverride(f.enemy)
        verify(f.ship.shipAI, org.mockito.Mockito.atLeastOnce()).forceCircumstanceEvaluation()
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        `when`(f.weapon.isInBurst).thenReturn(true)
        `when`(f.weapon.isFiring).thenReturn(true)
        `when`(f.weapon.chargeLevel).thenReturn(1f)
        `when`(f.weapon.burstFireTimeRemaining).thenReturn(0.6f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(6500f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        repeat(20) { f.advance() }
        kotlin.test.assertEquals("ATTACK", f.diagnostics()["action"])
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.VENT_FLUX), any(), anyInt())
    }

    @Test
    fun `恢复交接短暂等待额外武器进入射界并按时释放`() {
        fun handoff(): Pair<RecoveryFixture, WeaponAPI> {
            val f = RecoveryFixture()
            val extra = f.gun("extra")
            `when`(extra.distanceFromArc(f.enemy.location)).thenReturn(10000f)
            `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, extra))
            f.advance()
            f.state(ShipSystemAPI.SystemState.IN, 0.5f)
            f.advance()
            f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
            `when`(f.weapon.cooldownRemaining).thenReturn(0f)
            f.advance()
            f.advance()
            f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
            f.advance()
            f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
            org.mockito.Mockito.clearInvocations(f.weapon, extra)
            f.advance()
            kotlin.test.assertEquals("COMPLETED", f.diagnostics()["recoveryState"])
            verify(f.weapon).setForceNoFireOneFrame(true)
            verify(extra).setForceNoFireOneFrame(true)
            return f to extra
        }

        val (ready, extra) = handoff()
        `when`(extra.distanceFromArc(ready.enemy.location)).thenReturn(0f)
        org.mockito.Mockito.clearInvocations(ready.weapon, extra)
        ready.advance()
        verify(ready.weapon, never()).setForceNoFireOneFrame(true)
        verify(extra, never()).setForceNoFireOneFrame(true)

        val (timeout, delayed) = handoff()
        org.mockito.Mockito.clearInvocations(timeout.weapon, delayed)
        repeat(6) { timeout.advance() }
        org.mockito.Mockito.clearInvocations(timeout.weapon, delayed)
        timeout.advance()
        verify(timeout.weapon, never()).setForceNoFireOneFrame(true)
        verify(delayed, never()).setForceNoFireOneFrame(true)
    }

    @Test
    fun `OUT只阻止新开火而不切断既有爆发或点防`() {
        val f = RecoveryFixture()
        val burst = f.gun("active_burst")
        val pd = f.gun("pd")
        `when`(burst.isInBurst).thenReturn(true)
        `when`(burst.isFiring).thenReturn(true)
        `when`(burst.chargeLevel).thenReturn(1f)
        `when`(burst.burstFireTimeRemaining).thenReturn(0.6f)
        `when`(pd.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, burst, pd))
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        verify(f.weapon).setForceNoFireOneFrame(true)
        verify(burst, never()).setForceNoFireOneFrame(true)
        verify(pd, never()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).blockCommandForOneFrame(ShipCommand.FIRE)
        verify(f.ship.aiFlags, never()).setFlag(eq(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS), org.mockito.ArgumentMatchers.anyFloat())
    }

    @Test
    fun `非自动武器组在统一目标进入真实射界后收到开火命令`() {
        val f = RecoveryFixture()
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(group.isAutofiring).thenReturn(false)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        verify(f.ship).giveCommand(ShipCommand.FIRE, f.enemy.location, 0)

        org.mockito.Mockito.clearInvocations(f.ship)
        `when`(f.enemy.location).thenReturn(Vector2f(2000f, 0f))
        f.advance()
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), anyInt())
    }

    @Test
    fun `混编手动组只放行已就绪武器且撤退相位中不补火`() {
        val f = RecoveryFixture()
        val cooling = f.gun("cooling")
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon, cooling))
        `when`(group.isAutofiring).thenReturn(false)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, cooling))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        `when`(f.ship.getWeaponGroupFor(cooling)).thenReturn(group)
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(f.ship.shipTarget).thenReturn(null)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(cooling.cooldownRemaining).thenReturn(0f)
        `when`(cooling.distanceFromArc(f.enemy.location)).thenReturn(100f)
        f.advance()

        verify(f.ship).giveCommand(ShipCommand.FIRE, f.enemy.location, 0)
        verify(f.ship).setShipTarget(f.enemy)
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(cooling).setForceNoFireOneFrame(true)

        org.mockito.Mockito.clearInvocations(f.ship, f.weapon, cooling)
        `when`(f.ship.isRetreating).thenReturn(true)
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        f.advance()
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), anyInt())
    }

    @Test
    fun `普通空间接近不阻止原版自动武器开始攻击`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(2000f, 0f))
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val autofire = mock(com.fs.starfarer.api.combat.AutofireAIPlugin::class.java)
        `when`(autofire.shouldFire()).thenReturn(false)
        `when`(group.isAutofiring).thenReturn(true)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(group.getAutofirePlugin(f.weapon)).thenReturn(autofire)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        f.advance()
        kotlin.test.assertEquals("APPROACH", f.diagnostics()["action"])
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).blockCommandForOneFrame(ShipCommand.FIRE)
    }

    @Test
    fun `绕后周期间的非相位恢复允许真实射界内的武器开火`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])

        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(4000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(4200f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.IDLE, 0f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        org.mockito.Mockito.clearInvocations(f.ship, f.weapon)
        f.advance()

        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(f.ship).giveCommand(ShipCommand.FIRE, f.enemy.location, 0)
    }

    @Test
    fun `自然恢复期间安全且可负担的真实射界允许开火`() {
        val f = RecoveryFixture()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(6000f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        f.advance()

        kotlin.test.assertEquals("RECOVER", f.diagnostics()["action"])
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(f.ship).giveCommand(ShipCommand.FIRE, f.enemy.location, 0)
    }

    @Test
    fun `自动武器组按组合费用阻止恢复期间过载齐射`() {
        val f = RecoveryFixture()
        val extra = f.gun("extra")
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(extra.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, extra))
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(9000f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val autofire = mock(com.fs.starfarer.api.combat.AutofireAIPlugin::class.java)
        `when`(autofire.shouldFire()).thenReturn(true)
        `when`(autofire.targetShip).thenReturn(f.enemy)
        `when`(group.isAutofiring).thenReturn(true)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon, extra))
        `when`(group.getAutofirePlugin(f.weapon)).thenReturn(autofire)
        `when`(group.getAutofirePlugin(extra)).thenReturn(autofire)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        `when`(f.ship.getWeaponGroupFor(extra)).thenReturn(group)
        f.advance()

        kotlin.test.assertEquals("RECOVER", f.diagnostics()["action"])
        verify(f.weapon, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(extra, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), anyInt())
    }

    @Test
    fun `自动武器攻击其他目标同样计入下潜保留预算`() {
        val f = RecoveryFixture()
        val extra = f.gun("extra")
        val other = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(other.id).thenReturn("other_enemy")
        `when`(other.owner).thenReturn(1)
        `when`(other.isAlive).thenReturn(true)
        `when`(other.location).thenReturn(Vector2f(0f, 500f))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(extra.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, extra))
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(9000f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val autofire = mock(com.fs.starfarer.api.combat.AutofireAIPlugin::class.java)
        `when`(autofire.shouldFire()).thenReturn(true)
        `when`(autofire.targetShip).thenReturn(other)
        `when`(group.isAutofiring).thenReturn(true)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon, extra))
        `when`(group.getAutofirePlugin(f.weapon)).thenReturn(autofire)
        `when`(group.getAutofirePlugin(extra)).thenReturn(autofire)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        `when`(f.ship.getWeaponGroupFor(extra)).thenReturn(group)
        f.advance()

        verify(f.weapon, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(extra, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        val gates = f.diagnostics()["weaponGates"] as List<Map<String, Any>>
        kotlin.test.assertTrue(gates.all { it["fireBlockReason"] == "BUDGET" }, gates.toString())
        kotlin.test.assertTrue(gates.all { it["autofireTarget"] == "other_enemy" }, gates.toString())

        `when`(f.enemy.isAlive).thenReturn(false)
        `when`(f.ship.shipTarget).thenReturn(null)
        org.mockito.Mockito.clearInvocations(f.weapon, extra)
        f.advance()

        verify(f.weapon, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(extra, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        val withoutUnifiedTarget = f.diagnostics()["weaponGates"] as List<Map<String, Any>>
        kotlin.test.assertTrue(
            withoutUnifiedTarget.all { it["fireBlockReason"] == "BUDGET" },
            withoutUnifiedTarget.toString(),
        )
    }

    @Test
    fun `多个手动武器组共享同一开火预算`() {
        val f = RecoveryFixture()
        val extra = f.gun("extra")
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(extra.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, extra))
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(9000f)
        val first = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val second = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(first.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(second.weaponsCopy).thenReturn(listOf(extra))
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(first, second))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(first)
        `when`(f.ship.getWeaponGroupFor(extra)).thenReturn(second)
        f.advance()

        verify(f.ship).giveCommand(ShipCommand.FIRE, f.enemy.location, 0)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), eq(1))
        verify(extra, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        val gates = f.diagnostics()["weaponGates"] as List<Map<String, Any>>
        val blocked = gates.single { it["weapon"] == "extra" }
        kotlin.test.assertEquals(true, blocked["blocked"])
        kotlin.test.assertEquals("BUDGET", blocked["fireBlockReason"])
    }

    @Test
    fun `保留爆发的剩余费用先于新武器预算`() {
        val f = RecoveryFixture()
        val extra = f.gun("extra")
        val beam = mock(WeaponAPI::class.java)
        val beamSpec = mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(beam.ship).thenReturn(f.ship)
        `when`(beam.id).thenReturn("active_beam")
        `when`(beam.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(beam.spec).thenReturn(beamSpec)
        `when`(beam.originalSpec).thenReturn(beamSpec)
        val derived = beamSpec.derivedStats
        `when`(beam.derivedStats).thenReturn(derived)
        `when`(beam.location).thenReturn(Vector2f())
        `when`(beam.range).thenReturn(1000f)
        `when`(beam.isBeam).thenReturn(true)
        `when`(beam.isBurstBeam).thenReturn(true)
        `when`(beam.isFiring).thenReturn(false)
        `when`(beam.isInBurst).thenReturn(false)
        `when`(beam.burstFireTimeRemaining).thenReturn(0f)
        `when`(beam.chargeLevel).thenReturn(0f)
        `when`(beam.distanceFromArc(f.enemy.location)).thenReturn(0f)
        `when`(beamSpec.burstSize).thenReturn(1)
        `when`(beamSpec.burstDuration).thenReturn(1f)
        `when`(beamSpec.chargeupTime).thenReturn(0.5f)
        `when`(beamSpec.fluxPerSecond).thenReturn(1000f)
        `when`(beamSpec.aiHints).thenReturn(java.util.EnumSet.noneOf(WeaponAPI.AIHints::class.java))
        `when`(extra.cooldownRemaining).thenReturn(0f)
        `when`(f.ship.allWeapons).thenReturn(listOf(beam, extra))
        `when`(f.ship.mutableStats.beamWeaponFluxCostMult.modifiedValue).thenReturn(1f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(8800f)
        val beamGroup = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val attackGroup = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(beamGroup.isAutofiring).thenReturn(true)
        `when`(beamGroup.weaponsCopy).thenReturn(listOf(beam))
        `when`(attackGroup.weaponsCopy).thenReturn(listOf(extra))
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(beamGroup, attackGroup))
        `when`(f.ship.getWeaponGroupFor(beam)).thenReturn(beamGroup)
        `when`(f.ship.getWeaponGroupFor(extra)).thenReturn(attackGroup)
        f.advance()
        org.mockito.Mockito.clearInvocations(f.ship, beam, extra)
        `when`(beam.isFiring).thenReturn(true)
        `when`(beam.isInBurst).thenReturn(true)
        `when`(beam.burstFireTimeRemaining).thenReturn(0.6f)
        `when`(beam.chargeLevel).thenReturn(1f)
        f.advance(0.01f)

        verify(beam, never()).setForceNoFireOneFrame(true)
        verify(extra, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), anyInt())
    }

    @Test
    fun `持续光束续射费用只扣除一次`() {
        val f = RecoveryFixture()
        val beam = mock(WeaponAPI::class.java)
        val spec = mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(beam.ship).thenReturn(f.ship)
        `when`(beam.id).thenReturn("continuous_beam")
        `when`(beam.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(beam.spec).thenReturn(spec)
        `when`(beam.originalSpec).thenReturn(spec)
        val derived = spec.derivedStats
        `when`(beam.derivedStats).thenReturn(derived)
        `when`(beam.location).thenReturn(Vector2f())
        `when`(beam.range).thenReturn(1000f)
        `when`(beam.isBeam).thenReturn(true)
        `when`(beam.isBurstBeam).thenReturn(false)
        `when`(beam.isFiring).thenReturn(true)
        `when`(beam.isInBurst).thenReturn(true)
        `when`(beam.chargeLevel).thenReturn(1f)
        `when`(beam.distanceFromArc(f.enemy.location)).thenReturn(0f)
        `when`(spec.chargeupTime).thenReturn(0.5f)
        `when`(spec.fluxPerSecond).thenReturn(1000f)
        `when`(spec.aiHints).thenReturn(java.util.EnumSet.noneOf(WeaponAPI.AIHints::class.java))
        `when`(f.ship.allWeapons).thenReturn(listOf(beam))
        `when`(f.ship.mutableStats.beamWeaponFluxCostMult.modifiedValue).thenReturn(1f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(6000f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        val autofire = mock(com.fs.starfarer.api.combat.AutofireAIPlugin::class.java)
        `when`(autofire.shouldFire()).thenReturn(true)
        `when`(autofire.targetShip).thenReturn(f.enemy)
        `when`(group.isAutofiring).thenReturn(true)
        `when`(group.weaponsCopy).thenReturn(listOf(beam))
        `when`(group.getAutofirePlugin(beam)).thenReturn(autofire)
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(beam)).thenReturn(group)

        f.advance()

        verify(beam, never()).setForceNoFireOneFrame(true)
        kotlin.test.assertEquals(0f, f.diagnostics()["reservedOutputCost"] as Float, 0.001f)
    }

    @Test
    fun `绕后等待恢复时齐射费用突破安全余量则禁止开火`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(4000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(9900f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.IDLE, 0f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        val group = mock(com.fs.starfarer.api.combat.WeaponGroupAPI::class.java)
        `when`(group.weaponsCopy).thenReturn(listOf(f.weapon))
        `when`(f.ship.weaponGroupsCopy).thenReturn(listOf(group))
        `when`(f.ship.getWeaponGroupFor(f.weapon)).thenReturn(group)
        org.mockito.Mockito.clearInvocations(f.ship, f.weapon)
        f.advance()

        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])
        verify(f.weapon, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.FIRE), any(), anyInt())
    }

    @Test
    fun `恢复充出中既有充能不因统一目标瞬时越界被取消`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.weapon.isFiring).thenReturn(true)
        `when`(f.weapon.chargeLevel).thenReturn(0.5f)
        `when`(f.weapon.distanceFromArc(f.enemy.location)).thenReturn(10000f)
        f.advance()
        verify(f.weapon, never()).setForceNoFireOneFrame(true)
        verify(f.ship, never()).blockCommandForOneFrame(ShipCommand.FIRE)
    }

    @Test
    fun `零时流拒绝预测时不发出上浮或下潜命令`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(0f)
        f.ai.advance(0f, null, null, f.enemy)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        verify(f.engine, never()).getProjectiles()
        verify(f.weapon).setForceNoFireOneFrame(true)
        verify(f.ship).blockCommandForOneFrame(ShipCommand.VENT_FLUX)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        f.advance()
        org.mockito.Mockito.verify(f.engine, org.mockito.Mockito.atLeastOnce()).getProjectiles()
    }

    @Test
    fun `当前相位接受恢复候选后直接承诺而不重复下潜`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        f.advance()
        kotlin.test.assertEquals("RECOVER", f.diagnostics()["action"])
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        kotlin.test.assertEquals("PHASE_COOLDOWN", f.diagnostics()["recoverMode"])
        verify(f.ship, never()).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        kotlin.test.assertEquals("EXITING", f.diagnostics()["recoveryState"])
        verify(f.ship).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }

    @Test
    fun `当前相位恢复取消后仍可在后续周期重新承诺`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        f.advance()
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"])
        `when`(f.enemy.isAlive).thenReturn(false)
        f.advance()
        kotlin.test.assertEquals("CANCELLED", f.diagnostics()["recoveryState"])
        `when`(f.enemy.isAlive).thenReturn(true)
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.ACTIVE, 1f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        f.advance()
        f.advance()
        kotlin.test.assertEquals("RUNNING", f.diagnostics()["recoveryState"], f.diagnostics().toString())
        kotlin.test.assertEquals("PHASE_COOLDOWN", f.diagnostics()["recoverMode"])
    }

    @Test
    fun `短冷却在常规规划间隔外出现也会立即评估`() {
        val f = RecoveryFixture()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        kotlin.test.assertEquals("ATTACK", f.diagnostics()["action"])

        `when`(f.weapon.cooldownRemaining).thenReturn(0.6f)
        f.advance(0.01f)

        kotlin.test.assertEquals("PHASE_COOLDOWN", f.diagnostics()["recoverMode"], f.diagnostics().toString())
        verify(f.ship).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }

    @Test
    fun `普通状态预测失败同样禁止开始新排散`() {
        val f = RecoveryFixture()
        f.state(ShipSystemAPI.SystemState.IDLE, 0f)
        `when`(f.ship.mutableStats.timeMult.modifiedValue).thenReturn(0f)
        f.ai.advance(0f, null, null, f.enemy)
        verify(f.ship).blockCommandForOneFrame(ShipCommand.VENT_FLUX)
        verify(f.ship, never()).giveCommand(eq(ShipCommand.VENT_FLUX), any(), anyInt())
    }

    @Test
    fun `恢复禁火不会永久豁免持续光束或点防持续光束`() {
        val f = RecoveryFixture()
        val continuous = f.gun("continuous")
        `when`(continuous.isBeam).thenReturn(true)
        `when`(continuous.isBurstBeam).thenReturn(false)
        `when`(continuous.isFiring).thenReturn(true)
        `when`(continuous.isInBurst).thenReturn(true)
        `when`(continuous.chargeLevel).thenReturn(1f)
        `when`(continuous.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        `when`(f.ship.allWeapons).thenReturn(listOf(f.weapon, continuous))
        f.advance()
        kotlin.test.assertEquals("RECOVER", f.diagnostics()["action"])
        verify(continuous).setForceNoFireOneFrame(true)
        `when`(continuous.chargeLevel).thenReturn(0.5f)
        f.advance()
        verify(continuous, times(2)).setForceNoFireOneFrame(true)
    }

    @Test
    fun `真正发出接近下潜命令当帧不允许新齐射`() {
        val f = RecoveryFixture()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(3000f, 0f))
        f.advance()
        verify(f.ship).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        verify(f.weapon).setForceNoFireOneFrame(true)
    }

    @Test
    fun `普通攻击与充出冷却状态都持续执行自定义移动`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(2000f, 0f))
        f.advance()
        kotlin.test.assertEquals("APPROACH", f.diagnostics()["action"])
        verify(f.ship, org.mockito.Mockito.atLeastOnce()).giveCommand(eq(ShipCommand.ACCELERATE), any(), anyInt())

        f.state(ShipSystemAPI.SystemState.OUT, 0.5f)
        f.advance()
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        f.advance()
        verify(f.ship, org.mockito.Mockito.atLeastOnce()).blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
        verify(f.ship, org.mockito.Mockito.atLeastOnce()).blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
        verify(f.ship, org.mockito.Mockito.atLeastOnce()).blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
    }

    @Test
    fun `非规划子步紧急下潜同步绑定真实威胁且不重复切换`() {
        val f = RecoveryFixture()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance(0.5f)
        kotlin.test.assertEquals("ATTACK", f.diagnostics()["action"])
        val projectile = mock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(projectile.location).thenReturn(Vector2f(200f, 0f))
        `when`(projectile.velocity).thenReturn(Vector2f(-1000f, 0f))
        `when`(projectile.owner).thenReturn(1)
        `when`(projectile.source).thenReturn(f.enemy)
        `when`(projectile.collisionClass).thenReturn(com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_FF)
        `when`(projectile.collisionRadius).thenReturn(10f)
        `when`(projectile.damageAmount).thenReturn(50000f)
        `when`(projectile.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
        `when`(projectile.damage.stats).thenReturn(null)
        `when`(projectile.explosionSpecIfExplosion).thenReturn(null)
        `when`(projectile.projectileSpec).thenReturn(null)
        val weak = mock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(weak.location).thenReturn(Vector2f(200f, 20f))
        `when`(weak.velocity).thenReturn(Vector2f(-1000f, 0f))
        `when`(weak.owner).thenReturn(1)
        `when`(weak.source).thenReturn(f.enemy)
        `when`(weak.collisionClass).thenReturn(com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_FF)
        `when`(weak.collisionRadius).thenReturn(10f)
        `when`(weak.damageAmount).thenReturn(1f)
        `when`(weak.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
        `when`(weak.damage.stats).thenReturn(null)
        `when`(weak.explosionSpecIfExplosion).thenReturn(null)
        `when`(weak.projectileSpec).thenReturn(null)
        `when`(f.engine.projectiles).thenReturn(listOf(projectile, weak))

        val forecast = cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat().sample(
            f.engine, f.ship, cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Window(0f, 0.4f),
        )
        kotlin.test.assertTrue(forecast?.lethal == true, forecast.toString())
        f.advance(0.01f)

        kotlin.test.assertEquals("EVADE", f.diagnostics()["action"], f.diagnostics().toString())
        kotlin.test.assertEquals(1, f.diagnostics()["committedThreatCount"], f.diagnostics().toString())
        verify(f.ship, times(1)).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }

    @Test
    fun `射程外紧急规避保持侧移并按距离缺口接近`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(1400f, 0f))
        val projectile = mock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(projectile.location).thenReturn(Vector2f(200f, 0f))
        `when`(projectile.velocity).thenReturn(Vector2f(-1000f, 0f))
        `when`(projectile.owner).thenReturn(1)
        `when`(projectile.source).thenReturn(f.enemy)
        `when`(projectile.collisionClass).thenReturn(com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_FF)
        `when`(projectile.collisionRadius).thenReturn(10f)
        `when`(projectile.damageAmount).thenReturn(50000f)
        `when`(projectile.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
        `when`(projectile.damage.stats).thenReturn(null)
        `when`(projectile.explosionSpecIfExplosion).thenReturn(null)
        `when`(projectile.projectileSpec).thenReturn(null)
        `when`(f.engine.projectiles).thenReturn(listOf(projectile))

        f.advance(0.01f)

        kotlin.test.assertEquals("EVADE", f.diagnostics()["action"], f.diagnostics().toString())
        val movement = f.data["astd_phase_movement_${f.ship.id}"] as Map<*, *>
        kotlin.test.assertEquals(500f, movement["x"] as Float, 0.001f, movement.toString())
        kotlin.test.assertEquals(400f, kotlin.math.abs(movement["y"] as Float), 0.001f, movement.toString())
    }

    @Test
    fun `正面射程外紧急规避按距离缺口接近并保持目标局部侧向`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.enemy.location).thenReturn(Vector2f(1400f, 0f))
        `when`(f.enemy.facing).thenReturn(180f)
        val projectile = mock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(projectile.location).thenReturn(Vector2f(200f, 0f))
        `when`(projectile.velocity).thenReturn(Vector2f(-1000f, 0f))
        `when`(projectile.owner).thenReturn(1)
        `when`(projectile.source).thenReturn(f.enemy)
        `when`(projectile.collisionClass).thenReturn(com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_FF)
        `when`(projectile.collisionRadius).thenReturn(10f)
        `when`(projectile.damageAmount).thenReturn(50000f)
        `when`(projectile.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
        `when`(projectile.damage.stats).thenReturn(null)
        `when`(projectile.explosionSpecIfExplosion).thenReturn(null)
        `when`(projectile.projectileSpec).thenReturn(null)
        `when`(f.engine.projectiles).thenReturn(listOf(projectile))

        f.advance(0.01f)

        val movement = f.data["astd_phase_movement_${f.ship.id}"] as Map<*, *>
        kotlin.test.assertEquals(500f, movement["x"] as Float, 0.001f, movement.toString())
        kotlin.test.assertEquals(-400f, movement["y"] as Float, 0.001f, movement.toString())
    }

    @Test
    fun `其他目标的爆发不阻止统一目标所需的高辐能恢复`() {
        val f = RecoveryFixture()
        val other = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(other.location).thenReturn(Vector2f(500f, 200f))
        `when`(other.owner).thenReturn(1)
        `when`(other.isAlive).thenReturn(true)
        `when`(f.ship.shipTarget).thenReturn(other)
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        `when`(f.weapon.isInBurst).thenReturn(true)
        `when`(f.weapon.isFiring).thenReturn(true)
        `when`(f.weapon.chargeLevel).thenReturn(1f)
        `when`(f.weapon.burstFireTimeRemaining).thenReturn(0.6f)
        `when`(f.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(6500f)

        f.advance()

        kotlin.test.assertEquals("RECOVER", f.diagnostics()["action"], f.diagnostics().toString())
    }

    @Test
    fun `高硬辐能即使保护攻击也主动后撤或长侧移`() {
        val recovering = RecoveryFixture()
        recovering.mobile()
        `when`(recovering.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(recovering.ship.fluxTracker.currFlux).thenReturn(6500f)
        recovering.advance()
        kotlin.test.assertEquals("RECOVER", recovering.diagnostics()["action"])
        verify(recovering.ship, org.mockito.Mockito.atLeastOnce())
            .giveCommand(eq(ShipCommand.ACCELERATE_BACKWARDS), any(), anyInt())

        val bursting = RecoveryFixture()
        bursting.mobile()
        `when`(bursting.weapon.cooldownRemaining).thenReturn(0f)
        `when`(bursting.weapon.isInBurst).thenReturn(true)
        `when`(bursting.weapon.isFiring).thenReturn(true)
        `when`(bursting.weapon.chargeLevel).thenReturn(1f)
        `when`(bursting.weapon.burstFireTimeRemaining).thenReturn(0.6f)
        `when`(bursting.ship.fluxTracker.hardFlux).thenReturn(6000f)
        `when`(bursting.ship.fluxTracker.currFlux).thenReturn(6500f)
        bursting.advance()
        kotlin.test.assertEquals("ATTACK", bursting.diagnostics()["action"])
        verify(bursting.ship, org.mockito.Mockito.atLeastOnce()).giveCommand(
            org.mockito.ArgumentMatchers.argThat { it == ShipCommand.STRAFE_LEFT || it == ShipCommand.STRAFE_RIGHT },
            any(),
            anyInt(),
        )
    }

    @Test
    fun `绕后非相位等待始终侧向拉开而不重新接近正面射程`() {
        val f = RecoveryFixture()
        f.mobile()
        `when`(f.ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.weapon.location).thenReturn(Vector2f(1000f, 0f))
        `when`(f.enemy.location).thenReturn(Vector2f())
        `when`(f.enemy.allWeapons).thenReturn(listOf(f.weapon))
        `when`(f.weapon.cooldownRemaining).thenReturn(0f)
        f.advance()
        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])

        `when`(f.ship.fluxTracker.hardFlux).thenReturn(3100f)
        `when`(f.ship.fluxTracker.currFlux).thenReturn(3200f)
        f.state(ShipSystemAPI.SystemState.COOLDOWN, 0f)
        f.advance()

        kotlin.test.assertEquals("FLANK", f.diagnostics()["action"])
        val movement = f.data["astd_phase_movement_${f.ship.id}"] as Map<*, *>
        kotlin.test.assertTrue((movement["x"] as Float) > 1500f, movement.toString())
    }

    @Test
    fun `威胁预测失效与边界候选受阻时仍发布安全移动`() {
        val invalid = RecoveryFixture()
        invalid.mobile()
        `when`(invalid.weapon.cooldownRemaining).thenReturn(0f)
        `when`(invalid.enemy.location).thenReturn(Vector2f(2000f, 0f))
        `when`(invalid.ship.mutableStats.timeMult.modifiedValue).thenReturn(0f)
        invalid.ai.advance(0f, null, null, invalid.enemy)
        verify(invalid.ship, org.mockito.Mockito.atLeastOnce()).giveCommand(eq(ShipCommand.ACCELERATE), any(), anyInt())

        val boundary = RecoveryFixture()
        boundary.mobile()
        `when`(boundary.ship.location).thenReturn(Vector2f(9900f, 0f))
        `when`(boundary.ship.velocity).thenReturn(Vector2f(200f, 0f))
        `when`(boundary.enemy.location).thenReturn(Vector2f(10000f, 0f))
        `when`(boundary.weapon.location).thenReturn(Vector2f(9900f, 0f))
        `when`(boundary.weapon.cooldownRemaining).thenReturn(0f)
        boundary.advance()
        verify(boundary.ship, org.mockito.Mockito.atLeastOnce())
            .giveCommand(eq(ShipCommand.ACCELERATE_BACKWARDS), any(), anyInt())
    }

    private class RecoveryFixture {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val data = mutableMapOf<String, Any>()
        val ai = GravityPhaseCloakAI()
        val weapon: WeaponAPI
        private var now = 0f

        init {
            `when`(ship.id).thenReturn("recovery_player")
            `when`(ship.name).thenReturn("recovery_player")
            `when`(ship.owner).thenReturn(0)
            `when`(enemy.id).thenReturn("enemy")
            `when`(enemy.owner).thenReturn(1)
            for (s in listOf(ship, enemy)) {
                `when`(s.isAlive).thenReturn(true)
                `when`(s.hitpoints).thenReturn(5000f)
                `when`(s.maxHitpoints).thenReturn(5000f)
                `when`(s.collisionRadius).thenReturn(50f)
                `when`(s.velocity).thenReturn(Vector2f())
                `when`(s.armorGrid.grid).thenReturn(emptyArray())
                `when`(s.mutableStats.timeMult.modifiedValue).thenReturn(1f)
                `when`(s.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
                `when`(s.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
                `when`(s.mutableStats.beamDamageTakenMult.modifiedValue).thenReturn(1f)
                `when`(s.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
            }
            `when`(ship.location).thenReturn(Vector2f())
            `when`(ship.shipTarget).thenReturn(enemy)
            `when`(enemy.location).thenReturn(Vector2f(500f, 0f))
            `when`(ship.maxFlux).thenReturn(10000f)
            `when`(ship.fluxTracker.maxFlux).thenReturn(10000f)
            `when`(ship.fluxTracker.currFlux).thenReturn(1000f)
            `when`(ship.fluxTracker.hardFlux).thenReturn(100f)
            `when`(ship.mutableStats.fluxDissipation).thenReturn(com.fs.starfarer.api.combat.MutableStat(200f))
            `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
            `when`(ship.mutableStats.energyWeaponFluxCostMod).thenReturn(com.fs.starfarer.api.combat.StatBonus())
            `when`(ship.mutableStats.energyRoFMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
            `when`(ship.mutableStats.ballisticRoFMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
            `when`(ship.mutableStats.missileRoFMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
            `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
            `when`(ship.phaseCloak).thenReturn(cloak)
            `when`(ship.system).thenReturn(null)
            `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
            `when`(cloak.canBeActivated()).thenReturn(true)
            `when`(cloak.fluxPerUse).thenReturn(100f)
            `when`(cloak.fluxPerSecond).thenReturn(50f)
            `when`(cloak.chargeUpDur).thenReturn(0.5f)
            `when`(cloak.chargeDownDur).thenReturn(0.5f)
            `when`(cloak.cooldown).thenReturn(1f)
            weapon = gun("cooling")
            `when`(weapon.cooldownRemaining).thenReturn(4f)
            `when`(ship.allWeapons).thenReturn(listOf(weapon))
            `when`(enemy.allWeapons).thenReturn(emptyList())
            `when`(engine.ships).thenReturn(listOf(ship, enemy))
            `when`(engine.projectiles).thenReturn(emptyList())
            `when`(engine.missiles).thenReturn(emptyList())
            `when`(engine.beams).thenReturn(emptyList())
            `when`(engine.asteroids).thenReturn(emptyList())
            `when`(engine.mapWidth).thenReturn(20000f)
            `when`(engine.mapHeight).thenReturn(20000f)
            `when`(engine.customData).thenReturn(data)
            ai.init(ship, cloak, ship.aiFlags, engine)
        }

        fun gun(id: String): WeaponAPI {
            val weapon = mock(WeaponAPI::class.java)
            val spec = mock(ProjectileWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
            `when`(weapon.ship).thenReturn(ship)
            `when`(weapon.id).thenReturn(id)
            `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
            `when`(weapon.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
            `when`(weapon.spec).thenReturn(spec)
            `when`(weapon.originalSpec).thenReturn(spec)
            `when`(weapon.location).thenReturn(Vector2f())
            `when`(weapon.range).thenReturn(1000f)
            `when`(weapon.cooldown).thenReturn(4f)
            `when`(weapon.refireDelay).thenReturn(4f)
            `when`(spec.refireDelay).thenReturn(4f)
            `when`(spec.burstSize).thenReturn(3)
            `when`(spec.burstDelay).thenReturn(0.2f)
            `when`(spec.energyPerShot).thenReturn(100f)
            `when`(spec.turretFireOffsets).thenReturn(listOf(Vector2f()))
            `when`(spec.derivedStats.damagePerShot).thenReturn(100f)
            `when`(spec.derivedStats.fluxPerDam).thenReturn(1f)
            val derived = spec.derivedStats
            `when`(weapon.derivedStats).thenReturn(derived)
            return weapon
        }

        fun mobile() {
            `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(300f)
            `when`(ship.mutableStats.acceleration.modifiedValue).thenReturn(150f)
            `when`(ship.mutableStats.deceleration.modifiedValue).thenReturn(150f)
            `when`(ship.mutableStats.turnAcceleration.modifiedValue).thenReturn(90f)
            `when`(ship.mutableStats.maxTurnRate.modifiedValue).thenReturn(90f)
            `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        }

        fun state(state: ShipSystemAPI.SystemState, level: Float) {
            `when`(cloak.state).thenReturn(state)
            `when`(cloak.effectLevel).thenReturn(level)
            `when`(ship.isPhased).thenReturn(state == ShipSystemAPI.SystemState.IN || state == ShipSystemAPI.SystemState.ACTIVE)
        }

        fun advance(amount: Float = 0.2f) {
            now += amount
            `when`(engine.getTotalElapsedTime(false)).thenReturn(now)
            ai.advance(amount, null, null, enemy)
        }

        fun diagnostics(): Map<*, *> = data[GravityPhaseCloakAI.diagnosticsKey(ship.id)] as Map<*, *>
    }

    @Test
    fun `充出过渡不规划新动作也不切换相位`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(ship.id).thenReturn("player")
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.hitpoints).thenReturn(5000f)
        `when`(ship.maxHitpoints).thenReturn(5000f)
        `when`(ship.maxFlux).thenReturn(10000f)
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        `when`(cloak.effectLevel).thenReturn(0.5f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(flux.hardFlux).thenReturn(100f)
        `when`(flux.currFlux).thenReturn(500f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(engine.ships).thenReturn(listOf(ship))
        `when`(engine.projectiles).thenReturn(emptyList())
        `when`(engine.missiles).thenReturn(emptyList())
        `when`(engine.beams).thenReturn(emptyList())
        `when`(engine.customData).thenReturn(mutableMapOf())

        val ai = GravityPhaseCloakAI()
        ai.init(ship, cloak, ship.aiFlags, engine)
        ai.advance(0.2f, null, null, null)
        ai.advance(0.2f, null, null, null)

        verify(ship, never()).giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
        verify(ship, never()).giveCommand(eq(ShipCommand.VENT_FLUX), any(), anyInt())
    }

    @Test
    fun `静止于长侧移起点时不因可以开火而提前上浮`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val weapon = mock(WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        val spec = mock(ProjectileWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        val customData = mutableMapOf<String, Any>()
        `when`(ship.id).thenReturn("player")
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.collisionRadius).thenReturn(50f)
        `when`(ship.hitpoints).thenReturn(5000f)
        `when`(ship.maxHitpoints).thenReturn(5000f)
        `when`(ship.maxFlux).thenReturn(10000f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.system).thenReturn(null)
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(700f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.mutableStats.acceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.deceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.turnAcceleration.modifiedValue).thenReturn(90f)
        `when`(ship.mutableStats.maxTurnRate.modifiedValue).thenReturn(90f)
        `when`(ship.mutableStats.fluxDissipation.modifiedValue).thenReturn(200f)
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(flux.maxFlux).thenReturn(10000f)
        `when`(flux.currFlux).thenReturn(200f)
        `when`(flux.hardFlux).thenReturn(100f)
        `when`(enemy.id).thenReturn("enemy")
        `when`(enemy.owner).thenReturn(1)
        `when`(enemy.isAlive).thenReturn(true)
        `when`(enemy.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(enemy.location).thenReturn(Vector2f())
        `when`(enemy.velocity).thenReturn(Vector2f())
        `when`(enemy.collisionRadius).thenReturn(50f)
        `when`(enemy.facing).thenReturn(0f)
        `when`(enemy.hitpoints).thenReturn(5000f)
        `when`(enemy.maxHitpoints).thenReturn(5000f)
        `when`(enemy.allWeapons).thenReturn(listOf(weapon))
        `when`(enemy.armorGrid.grid).thenReturn(emptyArray())
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
        `when`(cloak.fluxPerSecond).thenReturn(3000f)
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
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.isPhased).thenReturn(true)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        ai.advance(0.2f, null, null, enemy)
        ai.advance(0.2f, null, null, enemy)
        val diagnostics = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        kotlin.test.assertEquals("FLANK", diagnostics["action"], diagnostics.toString())
        kotlin.test.assertEquals(false, diagnostics["routeCompleted"])
        kotlin.test.assertEquals(1000f, diagnostics["effectiveWeaponRange"])
    }

    @Test
    fun `上浮命令发出后等待实际充出且保留路线`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val cloak = mock(ShipSystemAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val engine = mock(CombatEngineAPI::class.java)
        val weapon = mock(WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        val spec = mock(ProjectileWeaponSpecAPI::class.java, RETURNS_DEEP_STUBS)
        val customData = mutableMapOf<String, Any>()
        `when`(ship.id).thenReturn("player")
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f(1000f, 0f))
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.collisionRadius).thenReturn(50f)
        `when`(ship.hitpoints).thenReturn(5000f)
        `when`(ship.maxHitpoints).thenReturn(5000f)
        `when`(ship.maxFlux).thenReturn(10000f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.phaseCloak).thenReturn(cloak)
        `when`(ship.system).thenReturn(null)
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(700f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.mutableStats.acceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.deceleration.modifiedValue).thenReturn(150f)
        `when`(ship.mutableStats.turnAcceleration.modifiedValue).thenReturn(90f)
        `when`(ship.mutableStats.maxTurnRate.modifiedValue).thenReturn(90f)
        `when`(ship.mutableStats.fluxDissipation.modifiedValue).thenReturn(200f)
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(flux.maxFlux).thenReturn(10000f)
        `when`(flux.currFlux).thenReturn(200f)
        `when`(flux.hardFlux).thenReturn(100f)
        `when`(enemy.id).thenReturn("enemy")
        `when`(enemy.owner).thenReturn(1)
        `when`(enemy.isAlive).thenReturn(true)
        `when`(enemy.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(enemy.location).thenReturn(Vector2f())
        `when`(enemy.velocity).thenReturn(Vector2f())
        `when`(enemy.collisionRadius).thenReturn(50f)
        `when`(enemy.facing).thenReturn(0f)
        `when`(enemy.hitpoints).thenReturn(5000f)
        `when`(enemy.maxHitpoints).thenReturn(5000f)
        `when`(enemy.allWeapons).thenReturn(listOf(weapon))
        `when`(enemy.armorGrid.grid).thenReturn(emptyArray())
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
        `when`(cloak.fluxPerSecond).thenReturn(3000f)
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
        verify(ship.aiFlags).setFlag(
            eq(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS),
            eq(0.5f),
        )
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.isPhased).thenReturn(true)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        ai.advance(0.2f, null, null, enemy)
        verify(weapon, org.mockito.Mockito.atLeastOnce()).setForceNoFireOneFrame(true)
        verify(ship, never()).blockCommandForOneFrame(ShipCommand.FIRE)

        `when`(flux.hardFlux).thenReturn(4000f)
        `when`(flux.currFlux).thenReturn(4200f)
        ai.advance(0.2f, null, null, enemy)
        val surfacing = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        val elapsed = surfacing["routeElapsed"]
        val version = surfacing["routeVersion"]

        ai.advance(0.2f, null, null, enemy)
        val waiting = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        kotlin.test.assertEquals(elapsed, waiting["routeElapsed"])
        kotlin.test.assertEquals(version, waiting["routeVersion"])
        kotlin.test.assertEquals(false, waiting["routeFailed"])
        verify(ship, org.mockito.Mockito.times(2))
            .giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())

        repeat(6) { ai.advance(0.2f, null, null, enemy) }
        val pending = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        kotlin.test.assertEquals(elapsed, pending["routeElapsed"])
        kotlin.test.assertEquals(version, pending["routeVersion"])
        kotlin.test.assertEquals(false, pending["routeFailed"])
        verify(ship, org.mockito.Mockito.times(3))
            .giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())

        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        `when`(cloak.effectLevel).thenReturn(0.5f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(ship.isPhased).thenReturn(false)
        `when`(engine.getTotalElapsedTime(false)).thenReturn(10f)
        ai.advance(0.2f, null, null, enemy)
        val transition = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        kotlin.test.assertEquals(10f, transition["sampleTime"])
        kotlin.test.assertEquals("充出过渡，等待普通战斗状态", transition["reason"])
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(flux.hardFlux).thenReturn(100f)
        `when`(flux.currFlux).thenReturn(200f)
        `when`(ship.hitpoints).thenReturn(500f)
        `when`(weapon.derivedStats.dps).thenReturn(1000f)
        `when`(weapon.derivedStats.damagePerShot).thenReturn(100f)
        `when`(weapon.distanceFromArc(any())).thenReturn(0f)
        `when`(ship.mutableStats.hitStrengthBonus.computeEffective(100f)).thenReturn(100f)
        ai.advance(0.2f, null, null, enemy)
        ai.advance(0.2f, null, null, enemy)

        val resumed = customData[GravityPhaseCloakAI.diagnosticsKey("player")] as Map<String, *>
        kotlin.test.assertEquals("FLANK", resumed["action"], resumed.toString())
        kotlin.test.assertEquals(false, resumed["routeFailed"])
        verify(ship, org.mockito.Mockito.times(4))
            .giveCommand(eq(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK), any(), anyInt())
    }
}
