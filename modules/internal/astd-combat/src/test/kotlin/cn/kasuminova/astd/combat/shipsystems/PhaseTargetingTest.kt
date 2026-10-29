package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSupport
import cn.kasuminova.astd.combat.shipsystems.base.PhaseTargeting
import cn.kasuminova.astd.combat.shipsystems.base.WeaponTracker
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FighterWingAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.loading.WingRole
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseTargetingTest {
    @Test
    fun `射界检查同时要求射程和目标未被相位遮蔽`() {
        val target = shipAt(900f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        assertTrue(PhaseTargeting.weaponCanAttack(weapon, target))

        `when`(target.isPhased).thenReturn(true)
        assertFalse(PhaseTargeting.weaponCanAttack(weapon, target))
    }

    @Test
    fun `强制禁用武器和射界角度偏差都不能触发攻击`() {
        val target = shipAt(500f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.isForceDisabled).thenReturn(true)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        assertFalse(PhaseTargeting.weaponCanAttack(weapon, target))

        `when`(weapon.isForceDisabled).thenReturn(false)
        val outsideArc = target.collisionRadius * 0.5f
        `when`(weapon.distanceFromArc(target.location)).thenReturn(outsideArc)
        assertFalse(PhaseTargeting.weaponCanAttack(weapon, target))
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        assertTrue(PhaseTargeting.weaponCanAttack(weapon, target))
    }

    @Test
    fun `武器冷却期间只有短时就绪查询接受`() {
        val target = shipAt(500f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(weapon.cooldownRemaining).thenReturn(1.5f)
        assertFalse(PhaseTargeting.weaponCanAttack(weapon, target))
        assertTrue(PhaseTargeting.weaponCanAttack(weapon, target, soon = true))

        `when`(weapon.cooldownRemaining).thenReturn(4f)
        assertFalse(PhaseTargeting.weaponCanAttack(weapon, target, soon = true))
    }

    @Test
    fun `点防专用和机库不决定主武器攻击距离`() {
        val pd = weaponAt(0f, 0f, 1500f)
        `when`(pd.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        val bay = weaponAt(0f, 0f, 3000f)
        `when`(bay.type).thenReturn(WeaponAPI.WeaponType.LAUNCH_BAY)
        val gun = weaponAt(0f, 0f, 900f)
        val ship = shipAt(0f, 0f)
        `when`(ship.allWeapons).thenReturn(listOf(pd, bay, gun))
        assertEquals(900f, PhaseTargeting.effectiveRange(ship))
    }

    @Test
    fun `目标侧后按目标朝向计算而不是本舰朝向`() {
        val target = shipAt(1000f, 0f)
        `when`(target.facing).thenReturn(0f)
        val behind = shipAt(0f, 100f)
        val front = shipAt(1800f, 100f)
        assertTrue(PhaseTargeting.inRearArc(behind, target))
        assertFalse(PhaseTargeting.inRearArc(front, target))
    }

    @Test
    fun `持续光束只有存在真实光束时才算有效输出`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val beam = weaponAt(0f, 0f, 1000f)
        `when`(beam.isBeam).thenReturn(true)
        `when`(beam.isFiring).thenReturn(true)
        `when`(beam.distanceFromArc(target.location)).thenReturn(0f)
        `when`(ship.allWeapons).thenReturn(listOf(beam))
        assertFalse(effectiveOutput(ship, target))
    }

    @Test
    fun `爆发光束按真实光束而非isInBurst判定有效输出`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        val beam = mock(BeamAPI::class.java, org.mockito.Mockito.RETURNS_DEEP_STUBS)
        `when`(weapon.isBeam).thenReturn(true)
        `when`(weapon.isBurstBeam).thenReturn(true)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.isInBurst).thenReturn(false)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(weapon.beams).thenReturn(listOf(beam))
        `when`(beam.damageTarget).thenReturn(target)
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        assertTrue(effectiveOutput(ship, target))
    }

    @Test
    fun `最后一轮射弹不会因不足下一轮弹药或发射间隔而被隐藏`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammo).thenReturn(0)
        `when`(weapon.spec.burstSize).thenReturn(5)
        `when`(weapon.isInBurst).thenReturn(true)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.cooldownRemaining).thenReturn(0.2f)
        `when`(weapon.burstFireTimeRemaining).thenReturn(0f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.shipTarget).thenReturn(target)
        `when`(weapon.ship).thenReturn(ship)
        assertTrue(PhaseTargeting.usableAttackWeapon(weapon))
        assertTrue(PhaseTargeting.weaponCanAttack(weapon, target))
        assertTrue(effectiveOutput(ship, target))
        `when`(weapon.isInBurst).thenReturn(false)
        assertFalse(PhaseTargeting.usableAttackWeapon(weapon))
        assertFalse(effectiveOutput(ship, target))
    }

    @Test
    fun `已耗尽最后弹药的光束仍按真实命中判定输出`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        val beam = mock(BeamAPI::class.java, org.mockito.Mockito.RETURNS_DEEP_STUBS)
        `when`(weapon.usesAmmo()).thenReturn(true)
        `when`(weapon.ammo).thenReturn(0)
        `when`(weapon.isBeam).thenReturn(true)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.beams).thenReturn(listOf(beam))
        `when`(beam.damageTarget).thenReturn(target)
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        assertTrue(effectiveOutput(ship, target))
    }

    @Test
    fun `无弹药Pulse只把真实释放算作输出而不是自然收尾`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.isPulse).thenReturn(true)
        `when`(weapon.usesAmmo()).thenReturn(false)
        `when`((weapon.spec as ProjectileWeaponSpecAPI).chargeTime).thenReturn(1f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.shipTarget).thenReturn(target)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(1f)
        val finishingTracker = WeaponTracker()
        finishingTracker.sample(ship.allWeapons)
        `when`(weapon.chargeLevel).thenReturn(0.5f)
        val finishing = finishingTracker.sample(ship.allWeapons)
        assertFalse(finishing.single().releaseObserved)
        assertFalse(PhaseTargeting.effectiveOutput(target, finishing))

        val releaseTracker = WeaponTracker()
        releaseTracker.sample(ship.allWeapons)
        `when`(weapon.chargeLevel).thenReturn(1f)
        releaseTracker.sample(ship.allWeapons)
        `when`(weapon.chargeLevel).thenReturn(0f)
        `when`(weapon.isFiring).thenReturn(false)
        `when`(weapon.cooldownRemaining).thenReturn(0.2f)
        val released = releaseTracker.sample(ship.allWeapons)
        assertTrue(released.single().releaseObserved)
        assertTrue(PhaseTargeting.effectiveOutput(target, released))
    }

    @Test
    fun `无弹药单发通过充能历史识别释放`() {
        val target = shipAt(500f, 0f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.usesAmmo()).thenReturn(false)
        `when`((weapon.spec as ProjectileWeaponSpecAPI).chargeTime).thenReturn(1f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.shipTarget).thenReturn(target)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.8f)
        val tracker = WeaponTracker()
        tracker.sample(ship.allWeapons)
        `when`(weapon.chargeLevel).thenReturn(0f)
        `when`(weapon.isFiring).thenReturn(false)
        `when`(weapon.cooldownRemaining).thenReturn(0.2f)
        val released = tracker.sample(ship.allWeapons)

        assertTrue(released.single().releaseObserved)
        assertTrue(PhaseTargeting.effectiveOutput(target, released))
    }

    @Test
    fun `单发冷却开始保留释放目标且其他目标不算输出`() {
        val target = shipAt(500f, 0f)
        val other = shipAt(500f, 200f)
        val ship = shipAt(0f, 0f)
        val weapon = weaponAt(0f, 0f, 1000f)
        `when`(weapon.usesAmmo()).thenReturn(false)
        `when`((weapon.spec as ProjectileWeaponSpecAPI).chargeTime).thenReturn(1f)
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(weapon.distanceFromArc(other.location)).thenReturn(0f)
        `when`(ship.allWeapons).thenReturn(listOf(weapon))
        `when`(ship.shipTarget).thenReturn(target)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(weapon.chargeLevel).thenReturn(0.8f)
        val tracker = WeaponTracker()
        tracker.sample(ship.allWeapons)
        `when`(ship.shipTarget).thenReturn(other)
        `when`(weapon.chargeLevel).thenReturn(0.7f)
        `when`(weapon.isFiring).thenReturn(false)
        `when`(weapon.cooldownRemaining).thenReturn(2f)
        val released = tracker.sample(ship.allWeapons)

        assertTrue(released.single().releaseObserved)
        assertTrue(released.single().releaseTarget === other)
        assertFalse(PhaseTargeting.effectiveOutput(target, released))
        assertTrue(PhaseTargeting.effectiveOutput(other, released))
    }

    @Test
    fun `纯航母快照使用统一目标并独立保留联队真实射程`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f, "preferred")
        val current = enemyAt(500f, "current")
        val fighter = fighterAt(2900f, target)
        attachWing(carrier, 4000f, fighter)
        `when`(carrier.shipTarget).thenReturn(current)

        val situation = snapshot(carrier, target)
        assertEquals("preferred", situation.targetId)
        assertEquals(PhaseSupport(true, 4000f, true, true), situation.support)
        assertEquals(0f, situation.effectiveWeaponRange)
        assertFalse(situation.attack.hasWeapons)
        assertFalse(situation.attack.canFire)

        `when`(fighter.shipTarget).thenReturn(current)
        assertEquals(PhaseSupport(true, 4000f, true, false), snapshot(carrier, target).support)
    }

    @Test
    fun `联队范围采用实时range和舰体边界且越界不算交战`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(4080f)
        val fighter = fighterAt(4000f, target)
        val wing = attachWing(carrier, 4000f, fighter)
        assertEquals(PhaseSupport(true, 4000f, true, true), PhaseTargeting.sampleSupport(carrier, target))

        target.location.x = 4081f
        assertEquals(PhaseSupport(true, 4000f, false, false), PhaseTargeting.sampleSupport(carrier, target))
        `when`(wing.range).thenReturn(4500f)
        assertEquals(PhaseSupport(true, 4500f, true, true), PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `没有统一有效敌人时只提供可用联队距离`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(1000f)
        attachWing(carrier, 4000f, fighterAt(900f, target))
        val available = PhaseSupport(true, 4000f, false, false)
        assertEquals(available, PhaseTargeting.sampleSupport(carrier, null))
        val owner = carrier.owner
        `when`(target.owner).thenReturn(owner)
        assertEquals(available, PhaseTargeting.sampleSupport(carrier, target))
        `when`(target.owner).thenReturn(1)
        `when`(target.isAlive).thenReturn(false)
        assertEquals(available, PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `目标指派不冒充开火且要求fighter真实武器范围和射界`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val fighter = fighterAt(2900f, target)
        val weapon = fighter.allWeapons.single()
        attachWing(carrier, 4000f, fighter)
        val ready = PhaseSupport(true, 4000f, true, false)
        `when`(weapon.isFiring).thenReturn(false)
        assertEquals(ready, PhaseTargeting.sampleSupport(carrier, target))
        `when`(weapon.isFiring).thenReturn(true)
        weapon.location.x = 2000f
        assertEquals(ready, PhaseTargeting.sampleSupport(carrier, target))
        weapon.location.x = 2900f
        `when`(weapon.distanceFromArc(target.location)).thenReturn(100f)
        assertEquals(ready, PhaseTargeting.sampleSupport(carrier, target))
        `when`(weapon.distanceFromArc(target.location)).thenReturn(0f)
        `when`(target.isPhased).thenReturn(true)
        assertEquals(ready, PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `光束联队要求真实命中统一目标`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val fighter = fighterAt(2900f, target)
        val weapon = fighter.allWeapons.single()
        val beam = mock(BeamAPI::class.java, org.mockito.Mockito.RETURNS_DEEP_STUBS)
        attachWing(carrier, 4000f, fighter)
        `when`(weapon.isBeam).thenReturn(true)
        assertFalse(PhaseTargeting.sampleSupport(carrier, target).engaged)
        `when`(weapon.beams).thenReturn(listOf(beam))
        `when`(beam.brightness).thenReturn(1f)
        `when`(beam.damage.damage).thenReturn(100f)
        `when`(beam.damageTarget).thenReturn(carrier)
        assertFalse(PhaseTargeting.sampleSupport(carrier, target).engaged)
        `when`(beam.damageTarget).thenReturn(target)
        assertTrue(PhaseTargeting.sampleSupport(carrier, target).engaged)
    }

    @Test
    fun `全返航排除支援但部分返航保留存活作战成员`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val returning = fighterAt(2900f, target)
        val active = fighterAt(2900f, target)
        val wing = attachWing(carrier, 4000f, returning, active)
        `when`(wing.isReturning(returning)).thenReturn(true)
        assertEquals(PhaseSupport(true, 4000f, true, true), PhaseTargeting.sampleSupport(carrier, target))
        `when`(wing.isReturning(active)).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `召回和强制召回都清除可用支援`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        attachWing(carrier, 4000f, fighterAt(2900f, target))
        `when`(carrier.isPullBackFighters).thenReturn(true)
        assertEquals(PhaseSupport(), snapshot(carrier, target).support)
        `when`(carrier.isPullBackFighters).thenReturn(false)
        `when`(carrier.forceCarrierPullBackTime).thenReturn(1f)
        assertEquals(PhaseSupport(), snapshot(carrier, target).support)
        `when`(carrier.forceCarrierTargetTime).thenReturn(1f)
        assertTrue(snapshot(carrier, target).support.available)
    }

    @Test
    fun `无机库空机库空联队和全灭都不能产生支援`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        assertEquals(PhaseSupport(), snapshot(carrier, target).support)
        `when`(carrier.numFighterBays).thenReturn(3)
        assertEquals(PhaseSupport(), snapshot(carrier, target).support)
        val wing = attachWing(carrier, 4000f)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        val fighter = fighterAt(2900f, target)
        `when`(wing.wingMembers).thenReturn(listOf(fighter))
        `when`(wing.isDestroyed).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(wing.isDestroyed).thenReturn(false)
        `when`(fighter.isAlive).thenReturn(false)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(fighter.isAlive).thenReturn(true)
        `when`(fighter.isHulk).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `装饰点防非战斗支援角色与外舰联队不算攻击支援`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val fighter = fighterAt(2900f, target)
        val weapon = fighter.allWeapons.single()
        val wing = attachWing(carrier, 4000f, fighter)
        `when`(weapon.isDecorative).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(weapon.isDecorative).thenReturn(false)
        `when`(weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(false)
        `when`(fighter.isNonCombat(false)).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(fighter.isNonCombat(false)).thenReturn(false)
        `when`(wing.role).thenReturn(WingRole.SUPPORT)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(wing.role).thenReturn(WingRole.FIGHTER)
        `when`(wing.sourceShip).thenReturn(target)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `不可作战fighter和无效range不贡献有效距离`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val fighter = fighterAt(2900f, target)
        val wing = attachWing(carrier, 4000f, fighter)
        `when`(fighter.isLanding).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(fighter.isLanding).thenReturn(false)
        `when`(fighter.isFinishedLanding).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(fighter.isFinishedLanding).thenReturn(false)
        `when`(fighter.isPhased).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        `when`(fighter.isPhased).thenReturn(false)
        for (range in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            `when`(wing.range).thenReturn(range)
            assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
        }
    }

    @Test
    fun `多联队只取可用距离且短程越界联队不能借长程联队冒充交战`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        val short = attachWing(carrier, 1000f, fighterAt(2900f, target))
        val longFighter = fighterAt(2900f, target)
        `when`(longFighter.allWeapons.single().isFiring).thenReturn(false)
        val long = attachWing(carrier, 4000f, longFighter)
        val destroyed = attachWing(carrier, 8000f, fighterAt(2900f, target))
        `when`(destroyed.isDestroyed).thenReturn(true)
        assertEquals(PhaseSupport(true, 4000f, true, false), PhaseTargeting.sampleSupport(carrier, target))
        `when`(long.isReturning(longFighter)).thenReturn(true)
        assertEquals(PhaseSupport(true, 1000f, false, false), PhaseTargeting.sampleSupport(carrier, target))
        `when`(short.isDestroyed).thenReturn(true)
        assertEquals(PhaseSupport(), PhaseTargeting.sampleSupport(carrier, target))
    }

    @Test
    fun `战斗航母保留真实直射距离和近距离主武器机会`() {
        val carrier = shipAt(0f, 0f)
        val target = enemyAt(3000f)
        attachWing(carrier, 4000f, fighterAt(2900f, target))
        val gun = weaponAt(0f, 0f, 900f)
        val bay = weaponAt(0f, 0f, 9000f)
        val pd = weaponAt(0f, 0f, 8000f)
        `when`(bay.type).thenReturn(WeaponAPI.WeaponType.LAUNCH_BAY)
        `when`(pd.hasAIHint(WeaponAPI.AIHints.PD_ONLY)).thenReturn(true)
        `when`(carrier.allWeapons).thenReturn(listOf(gun, bay, pd))
        val distant = snapshot(carrier, target)
        assertEquals(900f, distant.effectiveWeaponRange)
        assertTrue(distant.attack.hasWeapons)
        assertFalse(distant.attack.inRange)
        assertFalse(distant.attack.canFire)
        assertEquals(PhaseSupport(true, 4000f, true, true), distant.support)

        target.location.x = 500f
        val close = snapshot(carrier, target)
        assertEquals(900f, close.effectiveWeaponRange)
        assertTrue(close.attack.inRange)
        assertTrue(close.attack.canFire)
        assertTrue(close.support.available)
        `when`(carrier.isPullBackFighters).thenReturn(true)
        val recalled = snapshot(carrier, target)
        assertEquals(PhaseSupport(), recalled.support)
        assertEquals(900f, recalled.effectiveWeaponRange)
        assertTrue(recalled.attack.canFire)
    }

    private fun enemyAt(x: Float, id: String = "enemy"): ShipAPI = shipAt(x, 0f).also {
        `when`(it.isAlive).thenReturn(true)
        `when`(it.owner).thenReturn(1)
        `when`(it.id).thenReturn(id)
    }

    private fun fighterAt(x: Float, target: ShipAPI): ShipAPI {
        val fighter = shipAt(x, 0f)
        val weapon = weaponAt(x, 0f, 500f)
        `when`(weapon.isFiring).thenReturn(true)
        `when`(fighter.isFighter).thenReturn(true)
        `when`(fighter.isAlive).thenReturn(true)
        `when`(fighter.shipTarget).thenReturn(target)
        `when`(fighter.allWeapons).thenReturn(listOf(weapon))
        return fighter
    }

    private fun attachWing(carrier: ShipAPI, range: Float, vararg fighters: ShipAPI): FighterWingAPI {
        val wing = mock(FighterWingAPI::class.java)
        `when`(wing.sourceShip).thenReturn(carrier)
        `when`(wing.range).thenReturn(range)
        `when`(wing.role).thenReturn(WingRole.FIGHTER)
        `when`(wing.wingMembers).thenReturn(fighters.toList())
        val wings = carrier.allWings + wing
        `when`(carrier.allWings).thenReturn(wings)
        `when`(carrier.numFighterBays).thenReturn(wings.size)
        return wing
    }

    private fun snapshot(ship: ShipAPI, target: ShipAPI): PhaseSituation {
        val engine = mock(CombatEngineAPI::class.java)
        val cloak = mock(ShipSystemAPI::class.java)
        val flux = mock(FluxTrackerAPI::class.java)
        val flags = mock(ShipwideAIFlags::class.java)
        `when`(engine.ships).thenReturn(listOf(ship, target))
        `when`(ship.fluxTracker).thenReturn(flux)
        `when`(ship.aiFlags).thenReturn(flags)
        return GravityPhaseSnapshot().sample(
            engine = engine,
            ship = ship,
            cloak = cloak,
            preferredTarget = target,
            phased = false,
            phaseActiveTime = 0f,
            timeSinceUnphase = 1f,
            route = PhaseRouteStatus(false, false, false, null),
            danger = PhaseDanger(false, false, false, 0f, 0f),
            recovery = PhaseRecovery(false),
            remainingBurstCost = 0f,
            effectiveOutput = false,
        )
    }

    private fun effectiveOutput(ship: ShipAPI, target: ShipAPI): Boolean =
        PhaseTargeting.effectiveOutput(target, WeaponTracker().sample(ship.allWeapons))

    private fun shipAt(x: Float, y: Float): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        val stats = mock(com.fs.starfarer.api.combat.MutableShipStatsAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.location).thenReturn(Vector2f(x, y))
        `when`(ship.collisionRadius).thenReturn(40f)
        `when`(stats.timeMult.modifiedValue).thenReturn(1f)
        return ship
    }

    private fun weaponAt(x: Float, y: Float, range: Float): WeaponAPI {
        val weapon = mock(WeaponAPI::class.java)
        val spec = mock(ProjectileWeaponSpecAPI::class.java)
        `when`(spec.burstSize).thenReturn(1)
        `when`(spec.aiHints).thenReturn(java.util.EnumSet.noneOf(WeaponAPI.AIHints::class.java))
        val stats = mock(WeaponAPI.DerivedWeaponStatsAPI::class.java)
        `when`(weapon.derivedStats).thenReturn(stats)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.id).thenReturn("test_weapon")
        `when`(weapon.location).thenReturn(Vector2f(x, y))
        `when`(weapon.range).thenReturn(range)
        `when`(weapon.type).thenReturn(WeaponAPI.WeaponType.ENERGY)
        return weapon
    }
}
