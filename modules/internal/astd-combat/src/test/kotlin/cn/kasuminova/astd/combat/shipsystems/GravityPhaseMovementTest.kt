package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class GravityPhaseMovementTest {
    @Test
    fun `路径使用当前硬辐能对应的完整相位航速`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(104f)
        `when`(ship.phaseCloak.effectLevel).thenReturn(1f)
        `when`(ship.hardFluxLevel).thenReturn(0.2f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_flux_level_for_min_speed_mod")
            .computeEffective(0.5f)).thenReturn(0.5f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.hitpoints).thenReturn(1200f)
        `when`(ship.maxHitpoints).thenReturn(5000f)
        `when`(ship.location).thenReturn(Vector2f())
        `when`(target.location).thenReturn(Vector2f(1000f, 0f))
        `when`(target.velocity).thenReturn(Vector2f())
        `when`(target.id).thenReturn("target")
        `when`(target.allWeapons).thenReturn(emptyList())

        val request = GravityPhaseMovement.request(mock(CombatEngineAPI::class.java), ship, target, 700f)

        assertEquals(1200f, request.maxDamage)
        assertEquals(104f, request.speed, 0.0001f)
    }

    @Test
    fun `恢复中断后的相位路线按当前高硬辐能估算可执行航速`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(70.067215f)
        `when`(ship.phaseCloak.effectLevel).thenReturn(0.81366354f)
        `when`(ship.hardFluxLevel).thenReturn(0.30962807f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_flux_level_for_min_speed_mod")
            .computeEffective(0.5f)).thenReturn(0.5f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.location).thenReturn(Vector2f())
        `when`(ship.collisionRadius).thenReturn(97f)
        `when`(target.location).thenReturn(Vector2f(1000f, 0f))
        `when`(target.velocity).thenReturn(Vector2f())
        `when`(target.id).thenReturn("target")
        `when`(target.allWeapons).thenReturn(emptyList())
        `when`(engine.customData).thenReturn(mutableMapOf())

        val request = GravityPhaseMovement.request(engine, ship, target, 612f)

        assertEquals(76.06279f, request.speed, 0.0001f)
        assertEquals(600f, request.lateralDistance)
    }

    @Test
    fun `非相位路线按预计激活后硬辐能估算下潜航速`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(130f)
        `when`(ship.phaseCloak.effectLevel).thenReturn(0f)
        `when`(ship.phaseCloak.fluxPerUse).thenReturn(800f)
        `when`(ship.hardFluxLevel).thenReturn(0.07f)
        `when`(ship.maxFlux).thenReturn(10000f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_flux_level_for_min_speed_mod")
            .computeEffective(0.5f)).thenReturn(0.5f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.location).thenReturn(Vector2f())
        `when`(target.location).thenReturn(Vector2f(1000f, 0f))
        `when`(target.velocity).thenReturn(Vector2f())
        `when`(target.id).thenReturn("target")
        `when`(target.allWeapons).thenReturn(emptyList())
        `when`(engine.customData).thenReturn(mutableMapOf())

        val request = GravityPhaseMovement.request(engine, ship, target, 612f)

        assertEquals(103.869995f, request.speed, 0.0001f)

        `when`(ship.phaseCloak.fluxPerUse).thenReturn(0f)
        `when`(ship.hardFluxLevel).thenReturn(0.30962807f)
        val recovering = GravityPhaseMovement.request(engine, ship, target, 612f)
        assertEquals(76.06279f, recovering.speed, 0.0001f)

        `when`(ship.hardFluxLevel).thenReturn(0f)
        val continuing = GravityPhaseMovement.request(engine, ship, target, 612f, continuation = true)
        assertEquals(77.74f, continuing.speed, 0.0001f)
    }

    @Test
    fun `充入过渡按完整相位航速规划而不是沿用瞬时最低航速`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(ship.mutableStats.maxSpeed.modifiedValue).thenReturn(4.5622387f)
        `when`(ship.phaseCloak.effectLevel).thenReturn(0.038080566f)
        `when`(ship.hardFluxLevel).thenReturn(0.2076458f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_flux_level_for_min_speed_mod")
            .computeEffective(0.5f)).thenReturn(0.5f)
        `when`(ship.mutableStats.dynamic.getMod("phase_cloak_speed")).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        `when`(ship.location).thenReturn(Vector2f())
        `when`(target.location).thenReturn(Vector2f(1000f, 0f))
        `when`(target.velocity).thenReturn(Vector2f())
        `when`(target.id).thenReturn("target")
        `when`(target.allWeapons).thenReturn(emptyList())
        `when`(engine.customData).thenReturn(mutableMapOf())

        val request = GravityPhaseMovement.request(engine, ship, target, 612f)

        assertEquals(87.39577f, request.speed, 0.0001f)
    }

    @Test
    fun `横移加速度使用原版舰级系数`() {
        val expected = mapOf(
            ShipAPI.HullSize.FRIGATE to 100f,
            ShipAPI.HullSize.DESTROYER to 75f,
            ShipAPI.HullSize.CRUISER to 50f,
            ShipAPI.HullSize.CAPITAL_SHIP to 25f,
        )
        for ((hullSize, acceleration) in expected) {
            val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
            `when`(ship.location).thenReturn(Vector2f())
            `when`(ship.velocity).thenReturn(Vector2f())
            `when`(ship.hullSpec.hullSize).thenReturn(hullSize)
            `when`(ship.mutableStats.acceleration.modifiedValue).thenReturn(100f)

            assertEquals(acceleration, GravityPhaseMovement.motion(ship, false).strafeAcceleration)
        }
    }

    @Test
    fun `绕后路径按原版相位承伤倍率评估而非普通全额伤害`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val enemy = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val engine = mock(CombatEngineAPI::class.java)
        val weapon = mock(com.fs.starfarer.api.combat.WeaponAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.location).thenReturn(Vector2f(100f, 0f))
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.collisionRadius).thenReturn(30f)
        `when`(ship.hitpoints).thenReturn(5000f)
        `when`(ship.armorGrid.armorRating).thenReturn(100f)
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.beamDamageTakenMult.modifiedValue).thenReturn(1f)
        `when`(ship.mutableStats.maxArmorDamageReduction.modifiedValue).thenReturn(0.85f)
        `when`(ship.mutableStats.minArmorFraction.modifiedValue).thenReturn(0.05f)
        `when`(enemy.owner).thenReturn(1)
        `when`(enemy.isAlive).thenReturn(true)
        `when`(enemy.location).thenReturn(Vector2f())
        `when`(enemy.velocity).thenReturn(Vector2f())
        `when`(enemy.allWeapons).thenReturn(listOf(weapon))
        `when`(enemy.mutableStats.hitStrengthBonus.computeEffective(100f)).thenReturn(100f)
        `when`(target.owner).thenReturn(1)
        `when`(target.location).thenReturn(Vector2f(5000f, 0f))
        `when`(target.velocity).thenReturn(Vector2f())
        `when`(target.allWeapons).thenReturn(emptyList())
        `when`(weapon.type).thenReturn(com.fs.starfarer.api.combat.WeaponAPI.WeaponType.ENERGY)
        `when`(weapon.usesAmmo()).thenReturn(false)
        `when`(weapon.range).thenReturn(1000f)
        `when`(weapon.location).thenReturn(Vector2f())
        `when`(weapon.distanceFromArc(org.mockito.ArgumentMatchers.any())).thenReturn(0f)
        `when`(weapon.derivedStats.dps).thenReturn(100f)
        `when`(weapon.derivedStats.damagePerShot).thenReturn(100f)
        `when`(weapon.damageType).thenReturn(com.fs.starfarer.api.combat.DamageType.ENERGY)
        `when`(engine.ships).thenReturn(listOf(ship, enemy, target))
        `when`(engine.asteroids).thenReturn(emptyList())
        `when`(engine.mapWidth).thenReturn(18000f)
        `when`(engine.mapHeight).thenReturn(12000f)
        val point = cn.kasuminova.astd.combat.shipsystems.base.PhasePosition(100f, 0f)

        val normal = GravityPhaseMovement.space(engine, ship, target).damagePerSecond(point, 0f)
        val phased = GravityPhaseMovement.space(engine, ship, target, phasedRoute = true).damagePerSecond(point, 0f)

        assertEquals(0.25f, phased / normal, 0.0001f)
    }

    @Test
    fun `相位路线使用预期时流而非充入瞬时值且非相位路线使用战场时间`() {
        val ship = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.isPhased).thenReturn(true)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.location).thenReturn(Vector2f())
        `when`(ship.velocity).thenReturn(Vector2f())
        `when`(ship.armorGrid.grid).thenReturn(emptyArray())
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1.2f)
        `when`(ship.mutableStats.dynamic.getValue("phase_time_mult")).thenReturn(1f)
        `when`(target.isAlive).thenReturn(true)
        `when`(target.owner).thenReturn(1)
        `when`(engine.ships).thenReturn(listOf(ship, target))
        `when`(engine.asteroids).thenReturn(emptyList())
        `when`(engine.mapWidth).thenReturn(18000f)
        `when`(engine.mapHeight).thenReturn(12000f)

        val phased = GravityPhaseMovement.space(engine, ship, target, phasedRoute = true)
        val unphased = GravityPhaseMovement.space(engine, ship, target, phasedRoute = false)

        assertEquals(1f / 3.6f, phased.battleTimePerLocalSecond, 0.0001f)
        assertEquals(1f / 1.2f, unphased.battleTimePerLocalSecond, 0.0001f)
        assertEquals(false, phased.collides)
        assertEquals(true, unphased.collides)

        `when`(ship.phaseCloak.effectLevel).thenReturn(1f)
        `when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(3.6f)
        val execution = GravityPhaseMovement.space(
            engine, ship, target, phasedRoute = false, useCurrentClock = true,
        )
        assertEquals(1f / 3.6f, execution.battleTimePerLocalSecond, 0.0001f)
        assertEquals(true, execution.collides)
    }

    @Test
    fun `目标运动预测使用目标自身时流换算后的战场速度`() {
        val target = mock(ShipAPI::class.java, RETURNS_DEEP_STUBS)
        `when`(target.id).thenReturn("target")
        `when`(target.location).thenReturn(Vector2f())
        `when`(target.velocity).thenReturn(Vector2f(100f, -50f))
        `when`(target.angularVelocity).thenReturn(20f)
        `when`(target.isAlive).thenReturn(true)
        `when`(target.mutableStats.timeMult.modifiedValue).thenReturn(2f)

        val sampled = GravityPhaseMovement.target(target)

        assertEquals(200f, sampled.velocity.x)
        assertEquals(-100f, sampled.velocity.y)
        assertEquals(40f, sampled.angularVelocity)
    }
}
