package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoverMode
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GravityPhaseRecoveryTest {
    private val danger = PhaseDanger(false, false, false, 0f, 0f)
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
    fun `辐能和武器均就绪时不制造恢复动作`() {
        val ship = ship(currFlux = 1000f, hardFlux = 0f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), null, danger)
        assertFalse(result.recovery.required)
        assertEquals(0f, result.remainingBurstCost)
    }

    @Test
    fun `高硬辐能选择非相位恢复`() {
        val ship = ship(currFlux = 6500f, hardFlux = 6000f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), null, danger)
        assertTrue(result.recovery.required)
        assertEquals(PhaseRecoverMode.NATURAL, bestMode(result.recovery))
        assertEquals(0f, result.recovery.phaseFluxDuration)
        assertEquals(0f, result.recovery.phaseCooldownDuration)
    }

    @Test
    fun `高硬辐能恢复到安全阈值前不会反复授权相位`() {
        val ship = ship(currFlux = 6500f, hardFlux = 6000f)
        val recovery = GravityPhaseRecovery()
        assertTrue(recovery.evaluate(mock(), ship, cloak(), null, danger).recovery.required)
        `when`(ship.fluxTracker.currFlux).thenReturn(5000f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(5000f)
        assertTrue(recovery.evaluate(mock(), ship, cloak(), null, danger).recovery.required)
        assertEquals(0f, recovery.evaluate(mock(), ship, cloak(), null, danger).recovery.phaseFluxDuration)
        `when`(ship.fluxTracker.currFlux).thenReturn(4000f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(4000f)
        assertFalse(recovery.evaluate(mock(), ship, cloak(), null, danger).recovery.required)
    }

    @Test
    fun `高硬辐能且环境危险时自然恢复而不是下潜`() {
        val ship = ship(currFlux = 6500f, hardFlux = 6000f)
        val result = GravityPhaseRecovery().evaluate(
            mock(),
            ship,
            cloak(),
            null,
            PhaseDanger(false, true, true, 1000f, 0f),
        )
        assertFalse(result.recovery.ventSafe)
        assertTrue(result.recovery.ventDuration > 0f)
        assertEquals(0f, result.recovery.phaseFluxDuration)
        assertTrue(result.recovery.naturalDuration > 0f)
    }

    @Test
    fun `低辐能排散使用原版最小起始辐能`() {
        val player = ship(currFlux = 1000f, hardFlux = 0f)
        `when`(player.fluxTracker.timeToVent).thenReturn(5f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player))

        val result = GravityPhaseRecovery().evaluate(engine, player, cloak(), null, danger)

        assertEquals(5f, result.recovery.ventDuration, 0.01f)
    }

    @Test
    fun `目标过载不能授权在其他敌舰覆盖下主动耗散`() {
        val player = ship(currFlux = 6000f, hardFlux = 6000f)
        `when`(player.location).thenReturn(Vector2f())
        `when`(player.owner).thenReturn(0)
        `when`(player.isAlive).thenReturn(true)
        val target = ship(currFlux = 0f, hardFlux = 0f)
        val other = ship(currFlux = 1000f, hardFlux = 0f)
        val enemies = listOf(target, other)
        for (enemy in enemies) {
            val enemyLocation = Vector2f(500f, 0f)
            `when`(enemy.location).thenReturn(enemyLocation)
            `when`(enemy.owner).thenReturn(1)
            `when`(enemy.isAlive).thenReturn(true)
            `when`(enemy.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            val weapon = projectile(enemy, "enemy_gun", 100f)
            `when`(weapon.usesAmmo()).thenReturn(false)
            `when`(weapon.range).thenReturn(1000f)
            `when`(weapon.location).thenReturn(enemyLocation)
            `when`(weapon.distanceFromArc(player.location)).thenReturn(0f)
            `when`(enemy.allWeapons).thenReturn(listOf(weapon))
        }
        `when`(target.fluxTracker.isOverloaded).thenReturn(true)
        `when`(target.fluxTracker.overloadTimeRemaining).thenReturn(30f)
        `when`(other.fluxTracker.isOverloaded).thenReturn(false, true)
        `when`(other.fluxTracker.overloadTimeRemaining).thenReturn(30f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target, other))
        val recovery = GravityPhaseRecovery()

        val covered = recovery.evaluate(engine, player, cloak(), target, danger)
        val allOpening = recovery.evaluate(engine, player, cloak(), target, danger)

        assertFalse(covered.recovery.ventSafe)
        assertTrue(covered.recovery.naturalDuration > 0f)
        assertTrue(allOpening.recovery.ventSafe)
        assertEquals(15f, allOpening.recovery.ventDuration, 0.01f)
    }

    @Test
    fun `其他武器就绪时仍为完整齐射加速剩余武器冷却`() {
        val player = ship(currFlux = 8500f, hardFlux = 0f)
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        val target = ship(currFlux = 0f, hardFlux = 0f)
        `when`(player.location).thenReturn(Vector2f())
        `when`(target.location).thenReturn(Vector2f(500f, 0f))
        `when`(player.owner).thenReturn(0)
        `when`(target.owner).thenReturn(1)
        `when`(player.isAlive).thenReturn(true)
        `when`(target.isAlive).thenReturn(true)
        val ready = projectile(player, "ready", 100f)
        val secondary = projectile(player, "secondary", 100f)
        val cooling = projectile(player, "cooling", 100f)
        for (weapon in listOf(ready, secondary, cooling)) {
            `when`(weapon.usesAmmo()).thenReturn(false)
            `when`(weapon.range).thenReturn(1000f)
            `when`(weapon.location).thenReturn(Vector2f())
            `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        }
        `when`(secondary.cooldownRemaining).thenReturn(0.25f)
        `when`(cooling.cooldownRemaining).thenReturn(4f)
        `when`(player.allWeapons).thenReturn(listOf(ready, secondary, cooling))
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target))

        val result = GravityPhaseRecovery().evaluate(engine, player, cloak(), target, danger)

        assertTrue(result.recovery.required)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, bestMode(result.recovery))
        assertTrue(result.recovery.phaseCooldownDuration > 0f)
        assertEquals(4f, result.recovery.naturalDuration, 0.01f)
    }

    @Test
    fun `零普通冷却但需补足整轮弹药时选择相位装填并完成恢复`() {
        val (player, target, weapon) = coolingPair()
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        `when`(weapon.ammo).thenReturn(0)
        `when`(weapon.ammoTracker.ammoPerSecond).thenReturn(1f)
        `when`(weapon.ammoTracker.reloadProgress).thenReturn(0f)
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()

        val selected = recovery.evaluate(mock(), player, cloak, target, danger)
        assertEquals(500f, selected.remainingBurstCost, 0.01f)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, selected.recovery.mode, selected.recovery.toString())
        assertEquals(5f, selected.recovery.naturalDuration, 0.01f)
        recovery.commit(player, cloak, target, selected.recovery.mode)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(player.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        recovery.advance(player, cloak, 0.2f)
        val reloading = recovery.evaluate(mock(), player, cloak, target, danger)
        assertEquals(PhaseRecoveryState.RUNNING, reloading.recovery.execution.state)
        assertEquals(PhaseRecoveryReason.COOLDOWN_PENDING, reloading.recovery.execution.reason)

        `when`(weapon.ammo).thenReturn(5)
        val complete = recovery.evaluate(mock(), player, cloak, target, danger)
        assertEquals(PhaseRecoveryState.EXITING, complete.recovery.execution.state)
        assertEquals(PhaseRecoveryReason.OBJECTIVES_MET, complete.recovery.execution.reason)
    }

    @Test
    fun `补弹按原版批次工作量规划且弹仓不足整轮时不承诺恢复`() {
        val (player, target, weapon) = coolingPair()
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(player.variant.hasHullMod("phase_anchor")).thenReturn(true)
        player.mutableStats.energyAmmoRegenMult.modifyMult("phase_anchor_modifier", 2f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        `when`(weapon.ammo).thenReturn(0)
        `when`(weapon.ammoTracker.ammoPerSecond).thenReturn(2f)
        `when`(weapon.ammoTracker.reloadSize).thenReturn(3f)
        `when`(weapon.ammoTracker.reloadProgress).thenReturn(0.25f)

        val selected = GravityPhaseRecovery().evaluate(mock(), player, cloak(), target, danger)
        assertEquals(2.625f, selected.recovery.naturalDuration, 0.001f)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, selected.recovery.mode)
        assertTrue(selected.recovery.phaseCandidateDuration < 1f)

        `when`(weapon.ammoTracker.reloadProgress).thenReturn(1f)
        val batchBoundary = GravityPhaseRecovery().evaluate(mock(), player, cloak(), target, danger)
        assertEquals(3f, batchBoundary.recovery.naturalDuration, 0.001f)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, batchBoundary.recovery.mode)

        `when`(weapon.ammoTracker.maxAmmo).thenReturn(4)
        val impossible = GravityPhaseRecovery().evaluate(mock(), player, cloak(), target, danger)
        assertFalse(impossible.recovery.required)
    }

    @Test
    fun `高硬辐能仍可由安全模拟选择冷却恢复但不能突破硬辐能上限`() {
        val (player, target, _) = coolingPair()
        `when`(player.fluxTracker.currFlux).thenReturn(5500f)
        `when`(player.fluxTracker.hardFlux).thenReturn(5500f)
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target))

        val accepted = GravityPhaseRecovery().evaluate(engine, player, cloak(), target, danger)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, accepted.recovery.mode)
        assertTrue(accepted.recovery.phaseCooldownDuration > 0f)

        `when`(player.fluxTracker.currFlux).thenReturn(6500f)
        `when`(player.fluxTracker.hardFlux).thenReturn(6500f)
        val rejected = GravityPhaseRecovery().evaluate(engine, player, cloak(), target, danger)
        assertEquals(PhaseRecoverMode.NATURAL, rejected.recovery.mode)
        assertEquals(0f, rejected.recovery.phaseCooldownDuration)
    }

    @Test
    fun `硬辐能规划为一个决策周期维护费预留上限余量`() {
        val (player, target, weapon) = coolingPair()
        `when`(player.fluxTracker.currFlux).thenReturn(5990f)
        `when`(player.fluxTracker.hardFlux).thenReturn(5990f)
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(player.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        `when`(weapon.cooldownRemaining).thenReturn(1f)
        val cloak = cloak()
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(2f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target))

        val result = GravityPhaseRecovery().evaluate(engine, player, cloak, target, danger)

        assertEquals(PhaseRecoverMode.NATURAL, result.recovery.mode)
        assertEquals(0f, result.recovery.phaseCooldownDuration)
    }

    @Test
    fun `当前相位可从受击后的预算线上方恢复到完整攻击预算`() {
        val (player, target, _) = coolingPair()
        `when`(player.fluxTracker.currFlux).thenReturn(9500f)
        `when`(player.fluxTracker.hardFlux).thenReturn(3000f)
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(player.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        val cloak = cloak()
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target))

        val result = GravityPhaseRecovery().evaluate(engine, player, cloak, target, danger)

        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, result.recovery.mode)
        assertTrue(result.recovery.phaseCooldownDuration > 0f)
    }

    @Test
    fun `冷却可在充出内完成时按模拟上浮而不额外停留`() {
        val (player, target, weapon) = coolingPair()
        `when`(player.fluxTracker.currFlux).thenReturn(5500f)
        `when`(player.fluxTracker.hardFlux).thenReturn(5500f)
        `when`(player.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(3f)
        `when`(player.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        `when`(weapon.cooldownRemaining).thenReturn(1f)
        val cloak = cloak()
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(2f)
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(engine.ships).thenReturn(listOf(player, target))
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(engine, player, cloak, target, danger)
        recovery.commit(player, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        recovery.advance(player, cloak, 0.2f)

        val result = recovery.evaluate(engine, player, cloak, target, danger)

        assertEquals(PhaseRecoveryState.EXITING, result.recovery.execution.state)
        assertEquals(PhaseRecoveryReason.OBJECTIVES_MET, result.recovery.execution.reason)
    }

    @Test
    fun `IN状态只计算剩余充出对应的实际冷却进度`() {
        val (player, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(mock(), player, cloak, target, danger)
        recovery.commit(player, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IN)
        `when`(cloak.effectLevel).thenReturn(0.5f)
        `when`(cloak.chargeUpDur).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(2f)
        `when`(weapon.cooldownRemaining).thenReturn(1.5f)
        recovery.advance(player, cloak, 0.2f)

        val result = recovery.evaluate(mock(), player, cloak, target, danger)

        assertEquals(PhaseRecoveryState.RUNNING, result.recovery.execution.state)
        assertEquals(PhaseRecoveryReason.COOLDOWN_PENDING, result.recovery.execution.reason)
        assertFalse(result.recovery.execution.awaitingSurface)
    }

    @Test
    fun `重复evaluate保留同型双实例的弹药历史`() {
        val ship = ship(1000f, 0f)
        val first = projectile(ship, "same", 100f, interruptible = true, delay = 0f)
        val second = projectile(ship, "same", 100f, interruptible = true, delay = 0f)
        `when`(ship.allWeapons).thenReturn(listOf(first, second))
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(mock(), ship, cloak(), null, danger)
        for (weapon in listOf(first, second)) {
            `when`(weapon.isFiring).thenReturn(true)
            `when`(weapon.isInBurst).thenReturn(true)
            `when`(weapon.chargeLevel).thenReturn(1f)
        }
        `when`(first.ammo).thenReturn(7)
        `when`(second.ammo).thenReturn(9)
        val result = recovery.evaluate(mock(), ship, cloak(), null, danger)
        assertEquals(600f, result.activeBurstCost, 0.01f)
        assertEquals(600f, result.remainingBurstCost, 0.01f)
    }

    @Test
    fun `继续爆发和立即下潜结算不能相加触发虚假恢复`() {
        val ship = ship(9200f, 0f)
        val beam = beam(ship, burst = true, rate = 100f)
        `when`(beam.burstFireTimeRemaining).thenReturn(1f)
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), null, danger)
        assertEquals(100f, result.activeBurstCost, 0.01f)
        assertFalse(result.recovery.required)
    }

    @Test
    fun `持续光束评估使用显式本地计划秒数而非一秒或零费用`() {
        val ship = ship(1000f, 0f)
        val beam = beam(ship, burst = false, rate = 300f)
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        val short = GravityPhaseRecovery(0.5f).evaluate(mock(), ship, cloak(), null, danger)
        val long = GravityPhaseRecovery(3f).evaluate(mock(), ship, cloak(), null, danger)
        assertEquals(150f, short.activeBurstCost, 0.01f)
        assertEquals(900f, long.activeBurstCost, 0.01f)
        assertEquals(900f, long.remainingBurstCost, 0.01f)
    }

    @Test
    fun `不足整轮弹药不选择新恢复但保留进行中爆发附加费`() {
        val ship = ship(1000f, 0f)
        val target = target()
        val weapon = projectile(ship, "astd_starfall_echo", 1125f)
        `when`(weapon.ammo).thenReturn(5)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        val recovery = GravityPhaseRecovery()
        val idle = recovery.evaluate(mock(), ship, cloak(), target, danger)
        assertEquals(7875f, idle.remainingBurstCost, 0.01f)
        assertEquals(0f, idle.activeBurstCost)
        `when`(weapon.ammo).thenReturn(4)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.isInBurst).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(1f)
        `when`(weapon.burstFireTimeRemaining).thenReturn(0.8f)
        val active = recovery.evaluate(mock(), ship, cloak(), target, danger)
        assertEquals(2250f, active.activeBurstCost, 0.01f)
        assertEquals(2250f, active.remainingBurstCost, 0.01f)
        `when`(weapon.isInBurst).thenReturn(false)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(weapon.chargeLevel).thenReturn(0.9f)
        val cooling = recovery.evaluate(mock(), ship, cloak(), target, danger)
        assertEquals(0f, cooling.activeBurstCost)
        assertEquals(0f, cooling.remainingBurstCost)
        assertFalse(cooling.recovery.required)
    }

    @Test
    fun `点防专用武器的真实辐能仍进入整舰费用账本`() {
        val ship = ship(1000f, 0f)
        val beam = beam(ship, burst = true, rate = 100f)
        `when`(beam.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        `when`(beam.burstFireTimeRemaining).thenReturn(1f)
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), null, danger)
        assertEquals(100f, result.activeBurstCost, 0.01f)
    }

    @Test
    fun `真实ionpulser单装和双装按三发预付轮计费不会锁死恢复`() {
        for (count in listOf(1, 2)) {
            val ship = ship(1000f, 0f)
            val weapons = List(count) {
                projectile(ship, "ionpulser", 100f, delay = 0.1f).also { weapon ->
                    val spec = weapon.spec as ProjectileWeaponSpecAPI
                    `when`(spec.burstSize).thenReturn(3)
                    `when`(spec.chargeTime).thenReturn(0.05f)
                    `when`(spec.energyPerSecond).thenReturn(0f)
                    `when`(weapon.cooldown).thenReturn(0.05f)
                    `when`(weapon.ammo).thenReturn(20)
                }
            }
            `when`(ship.allWeapons).thenReturn(weapons)
            val recovery = GravityPhaseRecovery()
            val idle = recovery.evaluate(mock(), ship, cloak(), target(), danger)
            assertEquals(300f * count, idle.remainingBurstCost, 0.01f)
            assertFalse(idle.recovery.required)
            for (weapon in weapons) {
                `when`(weapon.isFiring).thenReturn(true)
                `when`(weapon.isInBurst).thenReturn(true)
                `when`(weapon.chargeLevel).thenReturn(1f)
                `when`(weapon.burstFireTimeRemaining).thenReturn(0.2f)
                `when`(weapon.ammo).thenReturn(19)
            }
            val prepaid = recovery.evaluate(mock(), ship, cloak(), null, danger)
            assertEquals(0f, prepaid.activeBurstCost)
            assertFalse(prepaid.recovery.required)
        }
    }

    @Test
    fun `Pulse充能满充保持和释放跨轮都有有限费用且不会永久恢复`() {
        val ship = ship(1000f, 0f)
        val weapon = projectile(ship, "pulse", 100f)
        val spec = weapon.spec as ProjectileWeaponSpecAPI
        `when`(weapon.isPulse).thenReturn(true)
        `when`(spec.chargeTime).thenReturn(1f)
        `when`(spec.energyPerSecond).thenReturn(100f)
        `when`(spec.isRequiresFullCharge).thenReturn(true)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        val recovery = GravityPhaseRecovery()
        repeat(3) { cycle ->
            `when`(weapon.isFiring).thenReturn(false)
            `when`(weapon.chargeLevel).thenReturn(0f)
            `when`(weapon.ammo).thenReturn(10 - cycle)
            val idle = recovery.evaluate(mock(), ship, cloak(), target(), danger)
            assertEquals(200f, idle.remainingBurstCost, 0.01f)
            for (charge in listOf(0.25f, 0.75f, 1f, 1f)) {
                `when`(weapon.isFiring).thenReturn(true)
                `when`(weapon.chargeLevel).thenReturn(charge)
                val state = recovery.evaluate(mock(), ship, cloak(), null, danger)
                assertEquals(100f * (1f - charge) + 100f, state.activeBurstCost, 0.01f)
                assertTrue(state.remainingBurstCost.isFinite())
                assertFalse(state.recovery.required)
            }
        }
    }

    @Test
    fun `不可观测费用保留诊断但恢复目标有限并能在低辐能退出`() {
        val ship = ship(2000f, 0f)
        val beam = beam(ship, burst = false, rate = 300f)
        `when`(ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus().apply { modifyFlat("perFrame", 1f) })
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        val recovery = GravityPhaseRecovery()
        val dangerous = PhaseDanger(false, true, true, 0f, 0f)
        val recovering = recovery.evaluate(mock(), ship, cloak(), null, dangerous)
        assertEquals(Float.POSITIVE_INFINITY, recovering.remainingBurstCost)
        assertTrue(recovering.recovery.required)
        assertEquals(7.5f, recovering.recovery.naturalDuration, 0.01f)
        assertTrue(cn.kasuminova.astd.combat.shipsystems.base.WeaponCostUncertainty.CAPACITY_LIMITED_PLAN in recovering.costUncertainties)
        `when`(ship.fluxTracker.currFlux).thenReturn(700f)
        assertTrue(recovery.evaluate(mock(), ship, cloak(), null, dangerous).recovery.required)
        `when`(ship.fluxTracker.currFlux).thenReturn(500f)
        val recovered = recovery.evaluate(mock(), ship, cloak(), null, dangerous)
        assertFalse(recovered.recovery.required)
        assertEquals(Float.POSITIVE_INFINITY, recovered.remainingBurstCost)
        assertTrue(cn.kasuminova.astd.combat.shipsystems.base.WeaponCostUncertainty.FRAME_DEPENDENT_FLUX in recovered.costUncertainties)
        `when`(ship.fluxTracker.currFlux).thenReturn(900f)
        assertFalse(recovery.evaluate(mock(), ship, cloak(), null, dangerous).recovery.required)
        `when`(ship.fluxTracker.currFlux).thenReturn(1100f)
        assertTrue(recovery.evaluate(mock(), ship, cloak(), null, dangerous).recovery.required)
    }

    @Test
    fun `暂失射界和候选排名改变不会完成已承诺冷却`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        val selected = recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, selected.recovery.mode)
        assertEquals("NONE", recovery.execution.state.name)
        recovery.commit(ship, cloak, target, selected.recovery.mode)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        recovery.advance(ship, cloak, 0.2f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(10000f)
        val changed = recovery.evaluate(engine, ship, cloak, target, danger)
        assertTrue(changed.recovery.required)
        assertEquals("RUNNING", changed.recovery.execution.state.name)
        assertEquals("COOLDOWN_PENDING", changed.recovery.execution.reason.name)
        assertEquals(4f, changed.recovery.execution.cooldownRemaining)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        val complete = recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals("EXITING", complete.recovery.execution.state.name)
        assertEquals("OBJECTIVES_MET", complete.recovery.execution.reason.name)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        recovery.advance(ship, cloak, 0.2f)
        assertEquals("EXITING", recovery.execution.state.name)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        recovery.advance(ship, cloak, 0.2f)
        assertEquals("COMPLETED", recovery.execution.state.name)
        assertFalse(recovery.execution.awaitingSurface)
    }

    @Test
    fun `规避暂停保留累计本地预算并明确超时退出`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        recovery.evaluate(engine, ship, cloak, target, danger)
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        recovery.advance(ship, cloak, 1f)
        recovery.evaluate(engine, ship, cloak, target, PhaseDanger(true, true, true, 5000f, 0f))
        assertEquals("PAUSED", recovery.execution.state.name)
        assertEquals("EVADE", recovery.execution.reason.name)
        `when`(weapon.cooldownRemaining).thenReturn(0.1f)
        recovery.advance(ship, cloak, 10.2f)
        recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals("RUNNING", recovery.execution.state.name)
        assertEquals(11.2f, recovery.execution.elapsedLocal, 0.001f)
        recovery.advance(ship, cloak, 0.8f)
        recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals("TIME_LIMIT", recovery.execution.reason.name)
        assertEquals("EXITING", recovery.execution.state.name)
    }

    @Test
    fun `系统冷却承诺不随标记消失并在真实目标失效时取消`() {
        val (ship, target, weapon) = coolingPair()
        val system = mock(ShipSystemAPI::class.java)
        val cloak = cloak()
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(ship.system).thenReturn(system)
        `when`(system.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        `when`(system.cooldownRemaining).thenReturn(5f)
        `when`(ship.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)).thenReturn(true)
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(engine, ship, cloak, target, danger)
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        `when`(ship.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)).thenReturn(false)
        recovery.advance(ship, cloak, 0.2f)
        recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals(5f, recovery.execution.cooldownRemaining)
        assertEquals("COOLDOWN_PENDING", recovery.execution.reason.name)
        `when`(target.isAlive).thenReturn(false)
        recovery.evaluate(engine, ship, cloak, null, danger)
        assertEquals("TARGET_INVALID", recovery.execution.reason.name)
        assertEquals("CANCELLED", recovery.execution.state.name)
        assertTrue(recovery.execution.awaitingSurface)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        recovery.advance(ship, cloak, 0.2f)
        assertFalse(recovery.execution.awaitingSurface)
    }

    @Test
    fun `硬辐能安全上限和撤退都明确取消执行`() {
        for (retreat in listOf(false, true)) {
            val (ship, target, _) = coolingPair()
            val cloak = cloak()
            val recovery = GravityPhaseRecovery()
            val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
            recovery.evaluate(engine, ship, cloak, target, danger)
            recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
            `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
            `when`(cloak.effectLevel).thenReturn(1f)
            `when`(ship.isRetreating).thenReturn(retreat)
            `when`(ship.fluxTracker.currFlux).thenReturn(6000f)
            `when`(ship.fluxTracker.hardFlux).thenReturn(6000f)
            recovery.advance(ship, cloak, 0.2f)
            recovery.evaluate(engine, ship, cloak, target, danger)
            assertEquals(if (retreat) "RETREAT" else "HARD_FLUX_LIMIT", recovery.execution.reason.name)
        }
    }

    @Test
    fun `相位锚自然候选包含真实充出且不借用相位散耗`() {
        val ship = ship(6500f, 6000f)
        val stat = ship.mutableStats.fluxDissipation
        stat.modifyMult("phase_anchor_modifier", 2f)
        `when`(ship.variant.hasHullMod("phase_anchor")).thenReturn(true)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        val cloak = cloak()
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        val assessment = GravityPhaseRecovery().evaluate(mock(), ship, cloak, null, danger)
        val outBattle = (0.5f * kotlin.math.ln(3f) / 2f)
        assertEquals(9.5f + outBattle, assessment.recovery.naturalDuration, 0.01f)
        assertEquals(400f, stat.modifiedValue)
        assertTrue(assessment.recovery.ventDuration > 16f)
    }

    @Test
    fun `完整攻击预算不可达时明确安全退出且不重置执行`() {
        val (ship, target, _) = coolingPair()
        val cloak = cloak()
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(engine, ship, cloak, target, danger)
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.fluxTracker.currFlux).thenReturn(9900f)
        `when`(ship.fluxTracker.hardFlux).thenReturn(5000f)
        `when`(ship.mutableStats.fluxDissipation).thenReturn(MutableStat(10f))
        recovery.advance(ship, cloak, 0.3f)
        recovery.evaluate(engine, ship, cloak, target, danger)
        assertEquals("UNSAFE_SIMULATION", recovery.execution.reason.name)
        assertEquals("EXITING", recovery.execution.state.name)
        assertEquals(0.3f, recovery.execution.elapsedLocal)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        recovery.advance(ship, cloak, 0.2f)
        assertEquals("CANCELLED", recovery.execution.state.name)
        assertFalse(recovery.execution.awaitingSurface)
        assertEquals("UNSAFE_SIMULATION", recovery.execution.reason.name)
    }

    @Test
    fun `实际下潜未发生一秒后取消未确认承诺`() {
        val (ship, target, _) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        recovery.advance(ship, cloak, 1f)
        assertEquals("CANCELLED", recovery.execution.state.name)
        assertEquals("ACTIVATION_UNCONFIRMED", recovery.execution.reason.name)
        assertFalse(recovery.execution.awaitingSurface)
    }

    @Test
    fun `ACTIVE候选不重复收激活费且IN只模拟剩余充入`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        val engine = mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        `when`(weapon.cooldownRemaining).thenReturn(1f)
        `when`(cloak.fluxPerUse).thenReturn(4000f)
        `when`(cloak.chargeUpDur).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        val active = recovery.evaluate(engine, ship, cloak, target, danger).recovery
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, active.mode)
        assertTrue(active.plannedPhaseCost in 20f..35f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IN)
        `when`(cloak.effectLevel).thenReturn(0.75f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(2.5f)
        val entering = recovery.evaluate(engine, ship, cloak, target, danger).recovery
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, entering.mode)
        assertTrue(entering.plannedPhaseCost < 40f)
    }

    @Test
    fun `自然候选从当前OUT剩余进度并按真实非相位时间换算`() {
        val ship = ship(6500f, 6000f)
        val cloak = cloak()
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        `when`(cloak.effectLevel).thenReturn(0.25f)
        `when`(cloak.chargeDownDur).thenReturn(0.8f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak, null, danger).recovery
        val remainingOutBattle = 0.2f * kotlin.math.ln(1.5f)
        assertEquals(remainingOutBattle + 1960f / 200f / 2f, result.naturalDuration, 0.01f)
        assertEquals("CLOAK_UNAVAILABLE", result.phaseCandidateReason)
    }

    @Test
    fun `无关射程射界下一轮不计费但既有攻击必须继续支付`() {
        val (ship, target, cooling) = coolingPair()
        val distant = projectile(ship, "expensive", 1000f, interruptible = true)
        `when`(distant.range).thenReturn(100f)
        val turned = projectile(ship, "turned", 1000f)
        `when`(turned.distanceFromArc(target.location)).thenReturn(1000f)
        `when`(ship.allWeapons).thenReturn(listOf(cooling, distant, turned))
        val recovery = GravityPhaseRecovery()
        val idle = recovery.evaluate(mock(), ship, cloak(), target, danger)
        assertEquals(500f, idle.remainingBurstCost)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, idle.recovery.mode)
        `when`(distant.isInBurst).thenReturn(true)
        `when`(distant.isFiring).thenReturn(true)
        `when`(distant.chargeLevel).thenReturn(1f)
        `when`(distant.burstFireTimeRemaining).thenReturn(0.6f)
        val active = recovery.evaluate(mock(), ship, cloak(), target, danger)
        assertEquals(3000f, active.activeBurstCost, 0.01f)
        assertEquals(3500f, active.remainingBurstCost, 0.01f)
        val noTarget = recovery.evaluate(mock(), ship, cloak(), null, danger)
        assertEquals(3000f, noTarget.remainingBurstCost, 0.01f)
    }

    @Test
    fun `原目标和所有承诺武器上浮后保留到各自有效输出`() {
        val (ship, target, first) = coolingPair()
        val second = projectile(ship, "cooling", 100f)
        val unrelated = projectile(ship, "unrelated", 100f)
        `when`(unrelated.range).thenReturn(100f)
        `when`(ship.allWeapons).thenReturn(listOf(first, second, unrelated))
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(first, second))
        kotlin.test.assertSame(target, recovery.committedTarget(ship))
        `when`(unrelated.isInBurst).thenReturn(true)
        `when`(unrelated.isFiring).thenReturn(true)
        `when`(unrelated.chargeLevel).thenReturn(1f)
        `when`(unrelated.burstFireTimeRemaining).thenReturn(0.4f)
        val otherTarget = this.target()
        `when`(otherTarget.id).thenReturn("other_enemy")
        val waiting = recovery.evaluate(mock(), ship, cloak, otherTarget, danger)
        assertEquals(RecoveredAttackState.WAITING, waiting.recoveredAttack.state)
        assertTrue(waiting.recoveredAttack.canAttack)
        assertTrue(waiting.recoveredAttack.budgetSufficient)
        assertTrue(waiting.recoveredAttack.fullAmmo)
        assertTrue(waiting.recovery.execution.awaitingAttack)
        assertFalse(waiting.recovery.required)
        assertEquals("ATTACK_HANDOFF", waiting.recovery.phaseCandidateReason)
        kotlin.test.assertSame(target, waiting.recoveredAttack.target)
        assertEquals(listOf(first, second), waiting.recoveredAttack.weapons)
        `when`(first.isInBurst).thenReturn(true)
        `when`(first.isFiring).thenReturn(true)
        `when`(first.chargeLevel).thenReturn(1f)
        `when`(first.burstFireTimeRemaining).thenReturn(0.8f)
        val partial = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(listOf(second), partial.recoveredAttack.pendingWeapons)
        assertTrue(partial.recovery.execution.awaitingAttack)
        `when`(second.isFiring).thenReturn(true)
        `when`(second.chargeLevel).thenReturn(0.5f)
        val charging = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackState.WAITING, charging.recoveredAttack.state)
        `when`(second.isInBurst).thenReturn(true)
        `when`(second.chargeLevel).thenReturn(1f)
        `when`(second.burstFireTimeRemaining).thenReturn(0.8f)
        val started = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackState.STARTED, started.recoveredAttack.state)
        assertFalse(started.recovery.execution.awaitingAttack)
        kotlin.test.assertNull(recovery.committedTarget(ship))
    }

    @Test
    fun `上浮后暂失射界只等待有限交接窗口而不生成新相位恢复`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(weapon))
        `when`(weapon.distanceFromArc(target.location)).thenReturn(1000f)
        val blocked = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackReason.OUT_OF_ARC, blocked.recoveredAttack.reason)
        assertFalse(blocked.recoveredAttack.canAttack)
        assertTrue(blocked.recoveredAttack.budgetSufficient)
        assertFalse(blocked.recovery.required)
        recovery.advance(ship, cloak, 3f)
        val expired = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackState.EXPIRED, expired.recoveredAttack.state)
        assertFalse(expired.recovery.execution.awaitingAttack)
        assertFalse(expired.recovery.required)
        kotlin.test.assertNull(recovery.committedTarget(ship))
        assertEquals(RecoveredAttackState.EXPIRED, recovery.evaluate(mock(), ship, cloak, null, danger).recoveredAttack.state)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        repeat(3) {
            val retry = recovery.evaluate(mock(), ship, cloak, target, danger)
            assertEquals(PhaseRecoverMode.NATURAL, retry.recovery.mode)
            assertEquals("UNFULFILLED_ATTACK", retry.recovery.phaseCandidateReason)
            assertEquals(Float.POSITIVE_INFINITY, retry.recovery.phaseCandidateDuration)
        }
        val replaced = recovery.evaluate(mock(), ship, cloak, this.target(), danger)
        assertEquals(RecoveredAttackState.NONE, replaced.recoveredAttack.state)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, replaced.recovery.mode)
    }

    @Test
    fun `恢复攻击交接仍严格拒绝不足弹药费用和真实目标失效`() {
        for (failure in listOf("ammo", "flux", "target", "retreat")) {
            val (ship, target, weapon) = coolingPair()
            val cloak = cloak()
            val recovery = GravityPhaseRecovery()
            completeRecovery(recovery, ship, cloak, target, listOf(weapon))
            when (failure) {
                "ammo" -> `when`(weapon.ammo).thenReturn(4)
                "flux" -> `when`(ship.fluxTracker.currFlux).thenReturn(9300f)
                "target" -> `when`(target.isAlive).thenReturn(false)
                else -> `when`(ship.isRetreating).thenReturn(true)
            }
            val result = recovery.evaluate(mock(), ship, cloak, target, danger)
            assertEquals(RecoveredAttackState.CANCELLED, result.recoveredAttack.state)
            assertEquals(when (failure) {
                "ammo" -> RecoveredAttackReason.INSUFFICIENT_AMMO
                "flux" -> RecoveredAttackReason.INSUFFICIENT_BUDGET
                "target" -> RecoveredAttackReason.TARGET_INVALID
                else -> RecoveredAttackReason.RETREAT
            }, result.recoveredAttack.reason)
            assertFalse(result.recoveredAttack.canAttack)
            assertFalse(result.recovery.execution.awaitingAttack)
        }
    }

    @Test
    fun `相位锚原始冷却只按阶段RoF一次换算且与散耗并行`() = withSettings {
        val (ship, target, weapon) = coolingPair()
        `when`(ship.variant.hasHullMod("phase_anchor")).thenReturn(true)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        val cloak = cloak()
        `when`(cloak.chargeUpDur).thenReturn(0.5f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        val recovery = GravityPhaseRecovery()
        val idle = recovery.evaluate(mock(), ship, cloak, target, danger).recovery
        assertEquals(4f, idle.naturalDuration, 0.01f)
        assertTrue(idle.phaseCandidateDuration in 0.95f..1.04f)
        ship.mutableStats.energyRoFMult.modifyMult("phase_anchor_modifier", 2f)
        ship.mutableStats.fluxDissipation.modifyMult("phase_anchor_modifier", 2f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        val active = recovery.evaluate(mock(), ship, cloak, target, danger).recovery
        assertTrue(active.phaseCandidateDuration in 0.85f..0.91f)
        assertEquals(3.5f + 0.5f * kotlin.math.ln(3f) / 2f, active.naturalDuration, 0.01f)
        assertEquals(2f, ship.mutableStats.energyRoFMult.modifiedValue)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        `when`(cloak.effectLevel).thenReturn(0.5f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(2f)
        val out = recovery.evaluate(mock(), ship, cloak, target, danger).recovery
        assertEquals(3.75f + 0.25f * kotlin.math.ln(2f), out.naturalDuration, 0.01f)
    }

    @Test
    fun `原版最小间隔限制和系统冷却不被相位锚重复加速`() = withSettings {
        val (ship, target, weapon) = coolingPair()
        `when`(ship.variant.hasHullMod("phase_anchor")).thenReturn(true)
        `when`((weapon.spec as ProjectileWeaponSpecAPI).refireDelay).thenReturn(0.01f)
        `when`(weapon.cooldownRemaining).thenReturn(1f)
        val cloak = cloak()
        val withoutSystem = GravityPhaseRecovery().evaluate(mock(), ship, cloak, target, danger)
        assertTrue(withoutSystem.recovery.phaseCandidateDuration >= 1f / 3f - 0.001f)
        val system = mock(ShipSystemAPI::class.java)
        `when`(ship.system).thenReturn(system)
        `when`(system.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        `when`(system.cooldownRemaining).thenReturn(3f)
        `when`(ship.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)).thenReturn(true)
        val withSystem = GravityPhaseRecovery().evaluate(mock(), ship, cloak, target, danger)
        assertTrue(withSystem.recovery.phaseCandidateDuration >= 1f - 0.001f)
        assertEquals(3f, withSystem.recovery.naturalDuration)
    }

    @Test
    fun `基础射速和IN剩余时间只各换算一次`() {
        val (ship, target, _) = coolingPair()
        `when`(ship.variant.hasHullMod("phase_anchor")).thenReturn(true)
        ship.mutableStats.energyRoFMult.modifyMult("base", 1.5f)
        ship.mutableStats.energyRoFMult.modifyMult("phase_anchor_modifier", 2f)
        ship.mutableStats.fluxDissipation.modifyMult("phase_anchor_modifier", 2f)
        val cloak = cloak()
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IN)
        `when`(cloak.effectLevel).thenReturn(0.5f)
        `when`(cloak.chargeUpDur).thenReturn(1f)
        `when`(cloak.chargeDownDur).thenReturn(0.5f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(2f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak, target, danger).recovery
        assertEquals((4f - 0.25f * 1.5f) / 1.5f + 0.25f * kotlin.math.ln(2f), result.naturalDuration, 0.01f)
        assertTrue(result.phaseCandidateDuration in 0.67f..0.69f)
        assertEquals(3f, ship.mutableStats.energyRoFMult.modifiedValue)
    }

    @Test
    fun `运行时重装间隔改变公开冷却进度换算`() {
        val (ship, target, weapon) = coolingPair()
        `when`(weapon.cooldownRemaining).thenReturn(1f)
        `when`(weapon.refireDelay).thenReturn(4f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), target, danger).recovery
        assertEquals(2f, result.naturalDuration, 0.01f)
        assertTrue(result.phaseCandidateDuration in 0.66f..0.71f)
    }

    @Test
    fun `爆发光束冷却按RoF推进而非重复除倍率`() {
        val (ship, target, _) = coolingPair()
        val beam = beam(ship, burst = true, rate = 100f)
        `when`(beam.location).thenReturn(Vector2f())
        `when`(beam.range).thenReturn(1000f)
        `when`(beam.isFiring).thenReturn(false)
        `when`(beam.chargeLevel).thenReturn(0f)
        `when`(beam.cooldownRemaining).thenReturn(4f)
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        `when`(ship.variant.hasHullMod("phase_anchor")).thenReturn(true)
        ship.mutableStats.energyRoFMult.modifyMult("base", 1.5f)
        val result = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), target, danger).recovery
        assertEquals(4f / 1.5f, result.naturalDuration, 0.01f)
        assertTrue(result.phaseCandidateDuration in 0.44f..0.48f)
    }

    @Test
    fun `主动排散与冷却并行但前两秒暂停武器重装`() {
        val (ship, target, weapon) = coolingPair()
        `when`(ship.fluxTracker.currFlux).thenReturn(1200f)
        val longVent = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), target, danger).recovery
        assertEquals(4f, longVent.naturalDuration)
        assertEquals(6f, longVent.ventDuration)
        `when`(ship.fluxTracker.currFlux).thenReturn(200f)
        val shortVent = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), target, danger).recovery
        assertEquals(4.5f, shortVent.ventDuration)
        val system = mock(ShipSystemAPI::class.java)
        `when`(ship.system).thenReturn(system)
        `when`(system.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        `when`(system.cooldownRemaining).thenReturn(4f)
        `when`(ship.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)).thenReturn(true)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        val systemOnly = GravityPhaseRecovery().evaluate(mock(), ship, cloak(), target, danger).recovery
        assertEquals(4f, systemOnly.ventDuration)
    }

    @Test
    fun `交接中的临时禁用和目标相位不授权攻击也不丢原身份`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(weapon))
        `when`(weapon.isDisabled).thenReturn(true)
        val disabled = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackState.WAITING, disabled.recoveredAttack.state)
        assertEquals(RecoveredAttackReason.WEAPON_DISABLED, disabled.recoveredAttack.reason)
        assertFalse(disabled.recoveredAttack.canAttack)
        `when`(weapon.isDisabled).thenReturn(false)
        `when`(target.isPhased).thenReturn(true)
        val phased = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(RecoveredAttackReason.TARGET_PHASED, phased.recoveredAttack.reason)
        assertFalse(phased.recoveredAttack.canAttack)
        kotlin.test.assertSame(target, recovery.committedTarget(ship))
        recovery.cancel(cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.RETREAT)
        assertEquals(RecoveredAttackState.CANCELLED, recovery.recoveredAttack.state)
        assertEquals(RecoveredAttackReason.RETREAT, recovery.recoveredAttack.reason)
        assertFalse(recovery.execution.awaitingAttack)
        kotlin.test.assertNull(recovery.committedTarget(ship))
    }

    @Test
    fun `光束仅充能或命中其他目标不能兑现恢复攻击`() {
        val (ship, target, _) = coolingPair()
        val weapon = beam(ship, burst = true, rate = 100f)
        `when`(weapon.location).thenReturn(Vector2f())
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.isFiring).thenReturn(false)
        `when`(weapon.chargeLevel).thenReturn(0f)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(weapon))
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        assertEquals(RecoveredAttackState.WAITING, recovery.evaluate(mock(), ship, cloak, target, danger).recoveredAttack.state)
        val beam = mock(com.fs.starfarer.api.combat.BeamAPI::class.java, RETURNS_DEEP_STUBS)
        val otherTarget = this.target()
        `when`(beam.damageTarget).thenReturn(otherTarget)
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(weapon.beams).thenReturn(listOf(beam))
        assertEquals(RecoveredAttackState.WAITING, recovery.evaluate(mock(), ship, cloak, target, danger).recoveredAttack.state)
        `when`(beam.damageTarget).thenReturn(target)
        assertEquals(RecoveredAttackState.STARTED, recovery.evaluate(mock(), ship, cloak, target, danger).recoveredAttack.state)
    }

    @Test
    fun `交接超时后原武器有效输出解除同目标恢复禁令`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(weapon))
        recovery.advance(ship, cloak, 3f)
        assertEquals(RecoveredAttackState.EXPIRED, recovery.evaluate(mock(), ship, cloak, target, danger).recoveredAttack.state)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.isInBurst).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(1f)
        `when`(weapon.burstFireTimeRemaining).thenReturn(0.8f)
        assertEquals(RecoveredAttackState.NONE, recovery.evaluate(mock(), ship, cloak, target, danger).recoveredAttack.state)
        `when`(weapon.isInBurst).thenReturn(false)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        val next = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, next.recovery.mode)
        assertEquals("ACCEPTED", next.recovery.phaseCandidateReason)
    }

    @Test
    fun `执行中禁用武器不能凭零预算伪装完成`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        recovery.evaluate(mock(), ship, cloak, target, danger)
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        recovery.advance(ship, cloak, 0.2f)
        `when`(weapon.cooldownRemaining).thenReturn(0f)
        `when`(weapon.isDisabled).thenReturn(true)
        val result = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals("CANCELLED", result.recovery.execution.state.name)
        assertEquals("WEAPON_INVALID", result.recovery.execution.reason.name)
        assertFalse(result.recovery.execution.awaitingAttack)
    }

    @Test
    fun `承诺复用刚评估的武器实例而非重新按射界挑选`() {
        val (ship, target, weapon) = coolingPair()
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        val evaluated = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, evaluated.recovery.mode)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(1000f)
        recovery.commit(ship, cloak, target, evaluated.recovery.mode)
        assertEquals(4f, recovery.execution.cooldownRemaining)
        assertTrue(recovery.execution.objects.contains("0:cooling=4.0"))
    }

    @Test
    fun `交接预算保留所有无关活动武器的剩余费用`() {
        val (ship, target, weapon) = coolingPair()
        val unrelated = projectile(ship, "unrelated", 1000f, interruptible = true)
        `when`(unrelated.range).thenReturn(100f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon, unrelated))
        val cloak = cloak()
        val recovery = GravityPhaseRecovery()
        completeRecovery(recovery, ship, cloak, target, listOf(weapon))
        `when`(ship.fluxTracker.currFlux).thenReturn(7000f)
        `when`(unrelated.isFiring).thenReturn(true)
        `when`(unrelated.isInBurst).thenReturn(true)
        `when`(unrelated.chargeLevel).thenReturn(1f)
        `when`(unrelated.burstFireTimeRemaining).thenReturn(0.6f)
        val result = recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals(3000f, result.activeBurstCost, 0.01f)
        assertEquals(3500f, result.recoveredAttack.attackCost, 0.01f)
        assertFalse(result.recoveredAttack.budgetSufficient)
        assertFalse(result.recoveredAttack.canAttack)
        assertEquals(RecoveredAttackReason.INSUFFICIENT_BUDGET, result.recoveredAttack.reason)
        assertEquals(RecoveredAttackState.CANCELLED, result.recoveredAttack.state)
    }

    private fun completeRecovery(recovery: GravityPhaseRecovery, ship: ShipAPI, cloak: ShipSystemAPI,
                                 target: ShipAPI, weapons: List<WeaponAPI>) {
        recovery.evaluate(mock(), ship, cloak, target, danger)
        recovery.commit(ship, cloak, target, PhaseRecoverMode.PHASE_COOLDOWN)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.ACTIVE)
        `when`(cloak.effectLevel).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3f)
        recovery.advance(ship, cloak, 0.2f)
        weapons.forEach { `when`(it.cooldownRemaining).thenReturn(0f) }
        recovery.evaluate(mock(), ship, cloak, target, danger)
        assertEquals("OBJECTIVES_MET", recovery.execution.reason.name)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.OUT)
        recovery.advance(ship, cloak, 0.2f)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.COOLDOWN)
        `when`(cloak.effectLevel).thenReturn(0f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        recovery.advance(ship, cloak, 0.2f)
        assertTrue(recovery.execution.awaitingAttack)
    }

    private fun target(): ShipAPI = ship(0f, 0f).also {
        `when`(it.id).thenReturn("enemy")
        `when`(it.owner).thenReturn(1)
        `when`(it.isAlive).thenReturn(true)
        `when`(it.location).thenReturn(Vector2f(500f, 0f))
    }

    private fun withSettings(block: () -> Unit) {
        val previous = com.fs.starfarer.api.Global.getSettings()
        val settings = mock(com.fs.starfarer.api.SettingsAPI::class.java)
        `when`(settings.getFloat("minRefireDelay")).thenReturn(0.05f)
        `when`(settings.getFloat("minVentTimeForWeaponReload")).thenReturn(2f)
        com.fs.starfarer.api.Global.setSettings(settings)
        try {
            block()
        } finally {
            com.fs.starfarer.api.Global.setSettings(previous)
        }
    }

    private fun coolingPair(): Triple<ShipAPI, ShipAPI, WeaponAPI> {
        val ship = ship(1000f, 100f)
        val target = ship(0f, 0f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        `when`(ship.id).thenReturn("player")
        `when`(target.id).thenReturn("enemy")
        `when`(ship.owner).thenReturn(0)
        `when`(target.owner).thenReturn(1)
        `when`(target.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f())
        `when`(target.location).thenReturn(Vector2f(500f, 0f))
        `when`(ship.shipTarget).thenReturn(target)
        val weapon = projectile(ship, "cooling", 100f)
        `when`(weapon.location).thenReturn(Vector2f())
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.cooldownRemaining).thenReturn(4f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        return Triple(ship, target, weapon)
    }

    private fun projectile(ship: ShipAPI, id: String, shotCost: Float, interruptible: Boolean = false, delay: Float = 0.2f): WeaponAPI {
        val weapon = mock(WeaponAPI::class.java)
        val spec = mock(ProjectileWeaponSpecAPI::class.java)
        val derived = mock(WeaponAPI.DerivedWeaponStatsAPI::class.java)
        val ammo = mock(com.fs.starfarer.api.combat.AmmoTrackerAPI::class.java)
        `when`(weapon.ammoTracker).thenReturn(ammo)
        `when`(ammo.ammoPerSecond).thenReturn(0f)
        `when`(ammo.reloadSize).thenReturn(1f)
        `when`(ammo.maxAmmo).thenReturn(10)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.id).thenReturn(id)
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.originalSpec).thenReturn(spec)
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammo).thenReturn(10)
        `when`(weapon.cooldown).thenReturn(2f)
        `when`(weapon.location).thenReturn(Vector2f())
        `when`(weapon.range).thenReturn(1000f)
        `when`(spec.refireDelay).thenReturn(2f)
        `when`(weapon.refireDelay).thenAnswer { spec.refireDelay }
        `when`(spec.burstSize).thenReturn(5)
        `when`(spec.burstDelay).thenReturn(delay)
        `when`(spec.energyPerShot).thenReturn(shotCost)
        `when`(spec.isInterruptibleBurst).thenReturn(interruptible)
        `when`(spec.turretFireOffsets).thenReturn(listOf(Vector2f()))
        `when`(spec.derivedStats).thenReturn(derived)
        `when`(derived.damagePerShot).thenReturn(100f)
        `when`(derived.fluxPerDam).thenReturn(shotCost / 100f)
        return weapon
    }

    private fun beam(ship: ShipAPI, burst: Boolean, rate: Float): WeaponAPI {
        val weapon = mock(WeaponAPI::class.java)
        val spec = mock(BeamWeaponSpecAPI::class.java)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.id).thenReturn("beam")
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.isBeam).thenReturn(true)
        `when`(weapon.isBurstBeam).thenReturn(burst)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(1f)
        `when`(spec.chargeupTime).thenReturn(1f)
        `when`(spec.burstDuration).thenReturn(if (burst) 2f else 0f)
        `when`(spec.fluxPerSecond).thenReturn(rate)
        return weapon
    }

    private fun bestMode(recovery: cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery): PhaseRecoverMode =
        recovery.mode

    private fun ship(currFlux: Float, hardFlux: Float): ShipAPI {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val flux = mock(FluxTrackerAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(flux.currFlux).thenReturn(currFlux)
        `when`(flux.hardFlux).thenReturn(hardFlux)
        `when`(flux.maxFlux).thenReturn(10000f)
        `when`(ship.allWeapons).thenReturn(emptyList())
        `when`(ship.mutableStats.fluxDissipation).thenReturn(MutableStat(200f))
        `when`(ship.mutableStats.energyRoFMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.ballisticRoFMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.missileRoFMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.energyAmmoRegenMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.ballisticAmmoRegenMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.missileAmmoRegenMult).thenReturn(MutableStat(1f))
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.ventRateMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.energyWeaponFluxCostMod).thenReturn(StatBonus())
        `when`(ship.mutableStats.beamWeaponFluxCostMult).thenReturn(MutableStat(1f))
        return ship
    }

    private fun cloak(): ShipSystemAPI {
        val cloak = mock(ShipSystemAPI::class.java)
        `when`(cloak.state).thenReturn(ShipSystemAPI.SystemState.IDLE)
        `when`(cloak.fluxPerUse).thenReturn(100f)
        `when`(cloak.fluxPerSecond).thenReturn(50f)
        return cloak
    }
}
