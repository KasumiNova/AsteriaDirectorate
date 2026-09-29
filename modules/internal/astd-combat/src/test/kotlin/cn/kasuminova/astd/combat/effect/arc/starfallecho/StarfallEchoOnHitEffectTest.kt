package cn.kasuminova.astd.combat.effect.arc.starfallecho

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 坠星残响 AOE 模块舰选举（[StarfallEchoOnHitEffect.planStationElection]）的真实逻辑验证：
 * 同一空间站（模块 parentStation 组 + 主舰体）只结算一名距爆心最近的代表；
 * 成员中心距超半径剔除（粗筛把碰撞半径计入判定，巨模块会虚扩判定圈）；
 * 非模块舰目标与导弹原样直通。
 * 附舰船遮挡判定（[StarfallEchoOnHitEffect.isOccluded] 线段-碰撞圆纯几何）与
 * 同站成员识别（[StarfallEchoOnHitEffect.isSameStationGroup]）用例。
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

    // ==== 舰船遮挡判定（isOccluded / segmentIntersectsCircle 纯几何） ====

    @Test
    fun `爆心正后方的目标被中间大船遮挡`() {
        // 大船（碰撞圆半径 300）横在爆心与目标舰心之间
        val blockers = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(400f, 0f), 300f))

        assertTrue(effect.isOccluded(hitPoint, Vector2f(700f, 0f), blockers), "线段穿过碰撞圆内部：完全遮挡免伤")
    }

    @Test
    fun `侧向目标视线不经过遮挡船 不豁免`() {
        val blockers = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(400f, 0f), 300f))

        assertFalse(effect.isOccluded(hitPoint, Vector2f(400f, 700f), blockers), "线段与碰撞圆相离：正常结算")
    }

    @Test
    fun `遮挡船在目标背后或爆心背后 不构成遮挡`() {
        val beyond = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(900f, 0f), 100f))
        val behind = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(-300f, 0f), 100f))

        assertFalse(effect.isOccluded(hitPoint, Vector2f(500f, 0f), beyond), "圆心投影落在线段端点之外（目标背后）")
        assertFalse(effect.isOccluded(hitPoint, Vector2f(500f, 0f), behind), "圆心投影落在线段端点之外（爆心背后）")
    }

    @Test
    fun `直击船自身遮挡正后方目标 近侧擦线目标不误判`() {
        // 爆心压在被直击船碰撞圆表面（半径 300，圆心 (300,0)，爆心原点）
        val directHit = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(300f, 0f), 300f))

        assertTrue(
            effect.isOccluded(hitPoint, Vector2f(700f, 0f), directHit),
            "目标在被直击船正后方：被船体挡住，豁免 AOE",
        )
        assertFalse(
            effect.isOccluded(hitPoint, Vector2f(-200f, 0f), directHit),
            "目标在爆心近侧（圆心到线段最短距离 = 整半径 > 收敛后半径）：不得误判遮挡",
        )
    }

    @Test
    fun `遮挡圆收敛系数生效 擦边接触不遮挡`() {
        // 圆心到线段距离 = 整半径（相切）：收敛到 0.9 倍后相离，不遮挡
        val tangent = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(400f, 300f), 300f))

        assertFalse(effect.isOccluded(hitPoint, Vector2f(800f, 0f), tangent), "线段与整半径圆相切：擦边不算遮挡")
    }

    @Test
    fun `爆心深入直击船圆内时该船不构成遮挡`() {
        // 宽扁舰体侧向船体命中：爆心深入直击船收敛圆内部（圆心 (200,0)，半径 300，爆心原点距圆心 200 < 270）
        val directHit = listOf(StarfallEchoOnHitEffect.BlockerCircle(Vector2f(200f, 0f), 300f))

        assertFalse(
            effect.isOccluded(hitPoint, Vector2f(700f, 0f), directHit),
            "爆心位于直击船收敛圆内部：该船不遮挡，避免全半径目标被静默免伤",
        )
        // 同场景下其他正常遮挡船仍然生效
        val withBlocker = directHit + StarfallEchoOnHitEffect.BlockerCircle(Vector2f(400f, 0f), 100f)
        assertTrue(
            effect.isOccluded(hitPoint, Vector2f(700f, 0f), withBlocker),
            "爆心外部的中间船仍正常遮挡",
        )
    }

    // ==== 同站成员识别（isSameStationGroup：同一座模块舰不互相遮挡） ====

    @Test
    fun `同站成员与主舰体互为同组 异舰不同组`() {
        val root = ship(Vector2f(0f, 0f), withModules = true)
        val module = ship(Vector2f(100f, 0f), parent = root)
        val other = ship(Vector2f(200f, 0f))

        assertTrue(effect.isSameStationGroup(module, root), "模块与主舰体同组")
        assertTrue(effect.isSameStationGroup(module, module), "自身同组（遮挡面剔除目标自身）")
        assertFalse(effect.isSameStationGroup(module, other), "异舰不同组")
        assertFalse(effect.isSameStationGroup(other, root), "普通舰与空间站不同组")
    }
}
