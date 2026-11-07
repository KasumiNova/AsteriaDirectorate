package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BeamVisualClipperTest {

    private val origin = Vector2f(0f, 0f)

    private fun stubShip(
        x: Float,
        y: Float,
        radius: Float,
        owner: Int = 1,
        phased: Boolean = false,
        collisionClass: CollisionClass = CollisionClass.SHIP,
        fighter: Boolean = false,
        shield: ShieldAPI? = null,
    ): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.location).thenReturn(Vector2f(x, y))
        `when`(ship.collisionRadius).thenReturn(radius)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isPhased).thenReturn(phased)
        `when`(ship.collisionClass).thenReturn(collisionClass)
        `when`(ship.isFighter).thenReturn(fighter)
        `when`(ship.exactBounds).thenReturn(null)
        `when`(ship.shield).thenReturn(shield)
        return ship
    }

    private fun stubShield(x: Float, y: Float, radius: Float, coversHit: Boolean): ShieldAPI {
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.type).thenReturn(ShieldAPI.ShieldType.OMNI)
        `when`(shield.isOn).thenReturn(true)
        `when`(shield.location).thenReturn(Vector2f(x, y))
        `when`(shield.radius).thenReturn(radius)
        `when`(shield.isWithinArc(any())).thenReturn(coversHit)
        return shield
    }

    private fun clipLength(ships: List<ShipAPI>, source: ShipAPI? = null, phasedShipsBlock: Boolean = false): Float =
        BeamVisualClipper.clip(ships, source, origin, 0f, 500f, phasedShipsBlock).length

    @Test
    fun `无遮挡时端点为发射点沿朝向的满射程`() {
        val line = BeamVisualClipper.clip(emptyList(), null, Vector2f(10f, 20f), 90f, 300f, phasedShipsBlock = false)
        assertEquals(10f, line.to.x, 1e-3f)
        assertEquals(320f, line.to.y, 1e-3f)
        assertEquals(90f, line.facing, 1e-3f)
        assertEquals(300f, line.length, 1e-3f)
    }

    @Test
    fun `舰体挡在射线上时截断到入射点，多艘取最近`() {
        val far = stubShip(300f, 0f, 30f)
        val near = stubShip(100f, 0f, 20f)
        assertEquals(80f, clipLength(listOf(far, near)), 1e-3f)
    }

    @Test
    fun `发射舰、碰撞类别 NONE 与己方战机不遮挡`() {
        val source = stubShip(0f, 0f, 50f, owner = 0)
        val drone = stubShip(100f, 0f, 20f, collisionClass = CollisionClass.NONE)
        val friendlyFighter = stubShip(150f, 0f, 10f, owner = 0, fighter = true)
        assertEquals(500f, clipLength(listOf(source, drone, friendlyFighter), source = source), 1e-3f)
    }

    @Test
    fun `敌方战机遮挡`() {
        val source = stubShip(0f, 0f, 50f, owner = 0)
        val enemyFighter = stubShip(150f, 0f, 10f, owner = 1, fighter = true)
        assertEquals(140f, clipLength(listOf(source, enemyFighter), source = source), 1e-3f)
    }

    @Test
    fun `战机光束穿透友军舰船但不穿透敌舰`() {
        val source = stubShip(0f, 0f, 50f, owner = 0)
        val friendly = stubShip(150f, 0f, 20f, owner = 0)
        val enemy = stubShip(300f, 0f, 20f, owner = 1)
        val transparent = BeamVisualClipper.clip(
            listOf(source, friendly, enemy), source, origin, 0f, 500f,
            phasedShipsBlock = false, friendlyTransparent = true,
        ).length
        assertEquals(280f, transparent, 1e-3f)
        val opaque = BeamVisualClipper.clip(
            listOf(source, friendly, enemy), source, origin, 0f, 500f,
            phasedShipsBlock = false, friendlyTransparent = false,
        ).length
        assertEquals(130f, opaque, 1e-3f)
    }

    @Test
    fun `相位舰遮挡由策略决定`() {
        val phased = stubShip(200f, 0f, 25f, phased = true, collisionClass = CollisionClass.NONE)
        assertEquals(500f, clipLength(listOf(phased), phasedShipsBlock = false), 1e-3f)
        assertEquals(175f, clipLength(listOf(phased), phasedShipsBlock = true), 1e-3f)
    }

    @Test
    fun `护盾覆盖入射点时截断到护盾圆，未覆盖时落到舰体`() {
        val covered = stubShip(200f, 0f, 30f, shield = stubShield(200f, 0f, 60f, coversHit = true))
        assertEquals(140f, clipLength(listOf(covered)), 1e-3f)

        val uncovered = stubShip(200f, 0f, 30f, shield = stubShield(200f, 0f, 60f, coversHit = false))
        assertEquals(170f, clipLength(listOf(uncovered)), 1e-3f)
    }

    @Test
    fun `射线圆求交——正向、偏离、越界、后方、起点在圆内`() {
        assertEquals(40f, BeamVisualClipper.rayCircleEnterDistance(0f, 0f, 1f, 0f, 100f, 50f, 0f, 10f)!!, 1e-3f)
        assertNull(BeamVisualClipper.rayCircleEnterDistance(0f, 0f, 1f, 0f, 100f, 50f, 50f, 10f))
        assertNull(BeamVisualClipper.rayCircleEnterDistance(0f, 0f, 1f, 0f, 100f, 200f, 0f, 10f))
        assertNull(BeamVisualClipper.rayCircleEnterDistance(0f, 0f, 1f, 0f, 100f, -50f, 0f, 10f))
        assertEquals(0f, BeamVisualClipper.rayCircleEnterDistance(0f, 0f, 1f, 0f, 100f, 5f, 0f, 10f)!!, 1e-3f)
    }

    @Test
    fun `射线圆求交——斜向命中按几何关系计算`() {
        val inv = (1.0 / Math.sqrt(2.0)).toFloat()
        val len = (100.0 * Math.sqrt(2.0)).toFloat()
        val t = BeamVisualClipper.rayCircleEnterDistance(0f, 0f, inv, inv, len, 50f, 50f, (10.0 * Math.sqrt(2.0)).toFloat())
        assertEquals((40.0 * Math.sqrt(2.0)).toFloat(), t!!, 1e-2f)
    }

    private fun stubMissile(x: Float, y: Float, radius: Float, owner: Int): MissileAPI {
        val missile = mock(MissileAPI::class.java)
        `when`(missile.location).thenReturn(Vector2f(x, y))
        `when`(missile.collisionRadius).thenReturn(radius)
        `when`(missile.owner).thenReturn(owner)
        `when`(missile.collisionClass).thenReturn(CollisionClass.MISSILE_NO_FF)
        `when`(missile.isFizzling).thenReturn(false)
        `when`(missile.didDamage()).thenReturn(false)
        return missile
    }

    private fun resolveWithMissiles(
        missiles: List<MissileAPI>,
        sourceOwner: Int?,
        missilesBlock: Boolean = true,
    ): Float {
        val engine = mock(CombatEngineAPI::class.java)
        val source = if (sourceOwner != null) stubShip(0f, 0f, 10f, owner = sourceOwner) else null
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.getFirePoint(0)).thenReturn(Vector2f(0f, 0f))
        `when`(weapon.currAngle).thenReturn(0f)
        `when`(weapon.range).thenReturn(500f)
        `when`(weapon.ship).thenReturn(source)
        `when`(engine.ships).thenReturn(emptyList())
        `when`(engine.missiles).thenReturn(missiles)
        `when`(engine.asteroids).thenReturn(emptyList())
        return BeamVisualClipper.resolve(
            engine, weapon,
            phasedShipsBlock = false,
            beamCollisionClass = CollisionClass.RAY,
            missilesBlock = missilesBlock,
        ).length
    }

    @Test
    fun `敌方导弹遮挡束体，友军导弹任何情况下不遮挡`() {
        val enemy = stubMissile(200f, 0f, 10f, owner = 1)
        val friendly = stubMissile(100f, 0f, 10f, owner = 0)
        assertEquals(190f, resolveWithMissiles(listOf(enemy, friendly), sourceOwner = 0), 1e-3f)
        assertEquals(500f, resolveWithMissiles(listOf(friendly), sourceOwner = 0), 1e-3f)
    }

    @Test
    fun `missilesBlock 为 false 时敌方导弹不遮挡`() {
        val enemy = stubMissile(200f, 0f, 10f, owner = 1)
        assertEquals(500f, resolveWithMissiles(listOf(enemy), sourceOwner = 0, missilesBlock = false), 1e-3f)
    }
}
