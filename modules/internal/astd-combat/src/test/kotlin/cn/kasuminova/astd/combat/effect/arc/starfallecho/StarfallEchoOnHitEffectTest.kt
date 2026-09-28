package cn.kasuminova.astd.combat.effect.arc.starfallecho

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 坠星残响 AOE 模块舰选举（[StarfallEchoOnHitEffect.planStationElection]）的真实逻辑验证：
 * 同一空间站（模块 parentStation 组 + 主舰体）只结算一名距爆心最近的代表；
 * 成员中心距超半径剔除（粗筛把碰撞半径计入判定，巨模块会虚扩判定圈）；
 * 非模块舰目标与导弹原样直通。
 */
class StarfallEchoOnHitEffectTest {

    private val effect = StarfallEchoOnHitEffect()
    private val hitPoint = Vector2f(0f, 0f)
    private val radius = 750f

    private fun ship(at: Vector2f, parent: ShipAPI? = null, withModules: Boolean = false): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.location).thenReturn(at)
        `when`(ship.parentStation).thenReturn(parent)
        `when`(ship.isShipWithModules).thenReturn(withModules)
        return ship
    }

    @Test
    fun `普通舰与导弹直通 regular 不参与选举`() {
        val normal = ship(Vector2f(100f, 0f))
        val missile = mock(MissileAPI::class.java)

        val plan = effect.planStationElection(listOf(normal, missile), hitPoint, radius)

        assertEquals(listOf(normal, missile), plan.regular)
        assertTrue(plan.stationRepresentatives.isEmpty())
    }

    @Test
    fun `同站多模块与主舰体同组竞争 只留距爆心最近者`() {
        val root = ship(Vector2f(600f, 0f), withModules = true)
        val near = ship(Vector2f(200f, 0f), parent = root)
        val far = ship(Vector2f(400f, 0f), parent = root)

        val plan = effect.planStationElection(listOf(far, root, near), hitPoint, radius)

        assertTrue(plan.regular.isEmpty())
        assertEquals(listOf(near), plan.stationRepresentatives, "同站只结算距爆心最近的成员")
    }

    @Test
    fun `主舰体距爆心最近时主舰体当选`() {
        val root = ship(Vector2f(50f, 0f), withModules = true)
        val module = ship(Vector2f(300f, 0f), parent = root)

        val plan = effect.planStationElection(listOf(module, root), hitPoint, radius)

        assertEquals(listOf(root), plan.stationRepresentatives)
    }

    @Test
    fun `模块中心距超半径剔除 碰撞半径粗筛不作数`() {
        val root = ship(Vector2f(1200f, 0f), withModules = true)
        // 粗筛口径下 1200 ≤ 750 + 模块碰撞半径会被误收，中心距复判必须剔除
        val outModule = ship(Vector2f(1200f, 0f), parent = root)
        val inModule = ship(Vector2f(700f, 0f), parent = root)

        val plan = effect.planStationElection(listOf(outModule, inModule, root), hitPoint, radius)

        assertEquals(listOf(inModule), plan.stationRepresentatives)
    }

    @Test
    fun `两座不同空间站各自选举一名代表`() {
        val rootA = ship(Vector2f(100f, 0f), withModules = true)
        val moduleA = ship(Vector2f(200f, 0f), parent = rootA)
        val rootB = ship(Vector2f(-100f, 0f), withModules = true)
        val moduleB = ship(Vector2f(-300f, 0f), parent = rootB)

        val plan = effect.planStationElection(listOf(moduleA, rootA, moduleB, rootB), hitPoint, radius)

        assertEquals(2, plan.stationRepresentatives.size)
        assertTrue(plan.stationRepresentatives.containsAll(listOf(rootA, rootB)))
    }

    @Test
    fun `空目标面返回空计划`() {
        val plan = effect.planStationElection(emptyList<CombatEntityAPI>(), hitPoint, radius)

        assertTrue(plan.regular.isEmpty())
        assertTrue(plan.stationRepresentatives.isEmpty())
    }
}
