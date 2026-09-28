package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.buff.stubShip
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 裂隙折跃点位伤害拍（[RiftShiftSystemStats.settlePointsTick]）的真实逻辑验证
 * （Mockito 桩 + applyDamage 捕获）：
 * - 接触判定 = 目标心到伤害点位距离 ≤ 接触范围；界内敌舰单点结算一次、bypassShields=true、
 *   落点 = 最近伤害点位（压回碰撞圈内）；界外目标、施放舰自身与同 owner 友军不结算；
 * - hulk 残骸参与结算；护盾覆盖接触方向时改走盾面落点且 bypassShields=false；
 * - 敌导弹与中立陨石同属目标面（按 owner 过滤），过期/消退弹体跳过；
 * - 目标处于两点交叠区时一拍只按最近点位结算一次。
 */
class RiftShiftSystemStatsTest {

    private val stats = RiftShiftSystemStats()

    /** 一次 applyDamage 调用的捕获记录。 */
    private data class DamageCall(
        val entity: CombatEntityAPI,
        val point: Vector2f,
        val damage: Float,
        val type: DamageType,
        val bypassShield: Boolean,
    )

    private fun recordingEngine(
        ships: List<ShipAPI> = emptyList(),
        missiles: List<MissileAPI> = emptyList(),
        asteroids: List<CombatEntityAPI> = emptyList(),
    ): Pair<CombatEngineAPI, MutableList<DamageCall>> {
        val calls = mutableListOf<DamageCall>()
        val engine = mock(CombatEngineAPI::class.java)
        doAnswer { inv ->
            calls += DamageCall(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                inv.getArgument(3), inv.getArgument(5),
            )
            null
        }.`when`(engine).applyDamage(
            any(), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean(),
        )
        `when`(engine.ships).thenReturn(ships)
        `when`(engine.missiles).thenReturn(missiles)
        `when`(engine.asteroids).thenReturn(asteroids)
        return engine to calls
    }

    /** 伤害点位序列：(0,0)→(800,0) 路径按间距布点（x=100..800）；源舰 owner 0。 */
    private val points = RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(800f, 0f))
    private val source = stubShip()

    /** 敌舰桩（owner 1）：舰心 [at]、碰撞半径 60、存活、无盾（null → 走船体落点分支）。 */
    private fun enemyShip(at: Vector2f, alive: Boolean = true, hulk: Boolean = false, shield: ShieldAPI? = null): ShipAPI {
        val ship = stubShip(hulk = hulk)
        `when`(ship.location).thenReturn(at)
        `when`(ship.owner).thenReturn(1)
        `when`(ship.isAlive).thenReturn(alive)
        `when`(ship.collisionRadius).thenReturn(60f)
        `when`(ship.shield).thenReturn(shield)
        return ship
    }

    @Test
    fun `界内敌舰单点结算 落点最近伤害点位 bypassShields`() {
        val ship = enemyShip(Vector2f(400f, 50f))
        val (engine, calls) = recordingEngine(ships = listOf(ship))

        stats.settlePointsTick(engine, source, points, 400f)

        assertEquals(1, calls.size, "一拍对一个接触目标只结算一次")
        val call = calls.single()
        assertTrue(call.entity === ship, "结算实体必须是目标舰")
        assertEquals(400f, call.damage, 1e-4f)
        assertEquals(DamageType.ENERGY, call.type)
        assertTrue(call.bypassShield, "船体落点结算 bypassShields=true")
        // 最近点位 (400,0)：到舰心距离 50 ≤ 0.9×碰撞半径 54，落点无需压回
        assertEquals(400f, call.point.x, 1e-4f, "落点 = 最近伤害点位")
        assertEquals(0f, call.point.y, 1e-4f)
    }

    @Test
    fun `两点交叠区目标一拍只结算一次且取最近点位`() {
        // 舰心 (140,0)：距点位 (100,0) 40su、距点位 (200,0) 60su，均在接触范围内
        val ship = enemyShip(Vector2f(140f, 0f))
        val (engine, calls) = recordingEngine(ships = listOf(ship))

        stats.settlePointsTick(engine, source, points, 200f)

        assertEquals(1, calls.size, "邻近点位半径交叠不重复结算")
        assertEquals(100f, calls.single().point.x, 1e-4f, "结算按最近点位")
        assertEquals(0f, calls.single().point.y, 1e-4f)
    }

    @Test
    fun `落点压回碰撞圈内 接触距离超出碰撞半径时沿命中方向缩回`() {
        // 舰心 (400, 95)：在接触范围内，但到最近点位 (400,0) 距离 95 > 0.9×60=54
        val ship = enemyShip(Vector2f(400f, 95f))
        val (engine, calls) = recordingEngine(ships = listOf(ship))

        stats.settlePointsTick(engine, source, points, 400f)

        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals(400f, call.point.x, 1e-3f, "压回沿命中方向（垂向），横坐标不变")
        assertEquals(95f - 54f, call.point.y, 1e-3f, "落点压回 0.9×碰撞半径处")
    }

    @Test
    fun `施放舰自身不结算 裂隙掠过不伤自舰`() {
        // 施放舰折跃途中舰位落在路径上（舰心即点位位置）
        `when`(source.location).thenReturn(Vector2f(400f, 0f))
        `when`(source.collisionRadius).thenReturn(60f)
        val (engine, calls) = recordingEngine(ships = listOf(source))

        stats.settlePointsTick(engine, source, points, 400f)

        assertTrue(calls.isEmpty(), "施放舰自身恒排除（舰位压在点位上也不结算）")
    }

    @Test
    fun `界外目标与同 owner 友军不结算`() {
        val outOfReach = enemyShip(Vector2f(400f, 1000f))
        val ally = stubShip()
        `when`(ally.location).thenReturn(Vector2f(400f, 10f))
        `when`(ally.owner).thenReturn(0)
        `when`(ally.isAlive).thenReturn(true)
        val (engine, calls) = recordingEngine(ships = listOf(outOfReach, ally))

        stats.settlePointsTick(engine, source, points, 400f)

        assertTrue(calls.isEmpty(), "界外目标与友军均不结算")
    }

    @Test
    fun `hulk 残骸参与结算 相位外判定不豁免`() {
        val hulk = enemyShip(Vector2f(400f, 30f), alive = false, hulk = true)
        val (engine, calls) = recordingEngine(ships = listOf(hulk))

        stats.settlePointsTick(engine, source, points, 400f)

        assertEquals(1, calls.size, "hulk 残骸属于裂隙目标面")
        assertTrue(calls.single().entity === hulk)
    }

    @Test
    fun `护盾覆盖接触方向时走盾面落点且不 bypass`() {
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.isOn).thenReturn(true)
        `when`(shield.radius).thenReturn(120f)
        `when`(shield.isWithinArc(any())).thenReturn(true)
        val ship = enemyShip(Vector2f(400f, 50f), shield = shield)
        val (engine, calls) = recordingEngine(ships = listOf(ship))

        stats.settlePointsTick(engine, source, points, 200f)

        assertEquals(1, calls.size)
        val call = calls.single()
        assertFalse(call.bypassShield, "盾覆盖结算 bypassShields=false")
        assertEquals(400f, call.point.x, 1e-3f, "盾面落点：舰心沿命中方向外推盾半径")
        assertEquals(50f - 120f, call.point.y, 1e-3f)
    }

    @Test
    fun `敌导弹与中立陨石同属目标面 友军导弹与消退弹体不结算`() {
        val enemyMissile = mock(MissileAPI::class.java)
        `when`(enemyMissile.location).thenReturn(Vector2f(200f, 20f))
        `when`(enemyMissile.owner).thenReturn(1)
        val allyMissile = mock(MissileAPI::class.java)
        `when`(allyMissile.location).thenReturn(Vector2f(200f, 20f))
        `when`(allyMissile.owner).thenReturn(0)
        val fadingMissile = mock(MissileAPI::class.java)
        `when`(fadingMissile.location).thenReturn(Vector2f(200f, 20f))
        `when`(fadingMissile.owner).thenReturn(1)
        `when`(fadingMissile.isFading).thenReturn(true)
        val asteroid = mock(CombatEntityAPI::class.java)
        `when`(asteroid.location).thenReturn(Vector2f(600f, -40f))
        `when`(asteroid.owner).thenReturn(100)
        val (engine, calls) = recordingEngine(
            missiles = listOf(enemyMissile, allyMissile, fadingMissile),
            asteroids = listOf(asteroid),
        )

        stats.settlePointsTick(engine, source, points, 400f)

        assertEquals(2, calls.size, "敌导弹 + 中立陨石各结算一次，友军导弹与消退弹体跳过")
        assertTrue(calls.any { it.entity === enemyMissile }, "敌导弹参与结算")
        assertTrue(calls.any { it.entity === asteroid }, "中立陨石参与结算")
        assertTrue(calls.none { it.entity === allyMissile }, "友军导弹不结算")
        assertTrue(calls.none { it.entity === fadingMissile }, "消退中的弹体不结算")
        calls.forEach { assertFalse(it.bypassShield, "非舰船目标不 bypass（无护盾弧判定需求）") }
    }

    @Test
    fun `空点位序列一拍零结算`() {
        val ship = enemyShip(Vector2f(400f, 0f))
        val (engine, calls) = recordingEngine(ships = listOf(ship))

        stats.settlePointsTick(engine, source, emptyList(), 400f)

        assertTrue(calls.isEmpty())
    }
}
