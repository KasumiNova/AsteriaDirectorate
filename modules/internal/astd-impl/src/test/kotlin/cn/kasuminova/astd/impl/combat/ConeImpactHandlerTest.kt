package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 §2.4 单元测试清单：
 * 1. 几何：锥内/锥外/恰在半角边界/恰在锥长边界；顶点重叠纳入。
 * 2. 大目标擦边：碰撞半径放宽修正生效。
 * 3. 归属与类型过滤矩阵（敌/我/中立 × 舰/机/弹）。
 * 4. 粗筛结果与全表扫结果一致性（随机布点 1000 次对照）。
 * 5. direction 非单位矢量 WARN + 归一化；零长度方向 WARN + 不结算。
 * 另覆盖：伤害落点取目标朝爆点表面点、applyDamage 参数透传、filter 终判豁免、0 值防线。
 */
class ConeImpactHandlerTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    // ---- 桩 ----

    private class DamageCall(
        val target: CombatEntityAPI,
        val point: Vector2f,
        val damage: Float,
        val damageType: DamageType,
        val emp: Float,
        val source: Any?,
        val showFloaty: Boolean,
    )

    /** 记录全部 applyDamage 调用的引擎桩（mock 直通，answer 落表）。 */
    private class EngineStub {
        val calls = mutableListOf<DamageCall>()
        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            `when`(
                engine.applyDamage(
                    org.mockito.ArgumentMatchers.any(CombatEntityAPI::class.java),
                    org.mockito.ArgumentMatchers.any(Vector2f::class.java),
                    org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.any(DamageType::class.java),
                    org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                ),
            ).thenAnswer { inv ->
                calls += DamageCall(
                    target = inv.getArgument(0),
                    point = inv.getArgument(1),
                    damage = inv.getArgument(2),
                    damageType = inv.getArgument(3),
                    emp = inv.getArgument(4),
                    // applyDamage 形参序：entity/point/amount/type/emp/bypassShields/dealsSoftFlux/source/showFloaty。
                    source = inv.getArgument(7),
                    showFloaty = inv.getArgument(8),
                )
                null
            }
        }
    }

    private fun stubEntity(loc: Vector2f, radius: Float, owner: Int): CombatEntityAPI {
        val entity = mock(CombatEntityAPI::class.java)
        `when`(entity.location).thenReturn(loc)
        `when`(entity.collisionRadius).thenReturn(radius)
        `when`(entity.owner).thenReturn(owner)
        return entity
    }

    private fun stubShip(loc: Vector2f, radius: Float, owner: Int, fighter: Boolean = false, hulk: Boolean = false): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.location).thenReturn(loc)
        `when`(ship.collisionRadius).thenReturn(radius)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isFighter).thenReturn(fighter)
        `when`(ship.isHulk).thenReturn(hulk)
        return ship
    }

    private fun stubMissile(loc: Vector2f, radius: Float, owner: Int): MissileAPI {
        val missile = mock(MissileAPI::class.java)
        `when`(missile.location).thenReturn(loc)
        `when`(missile.collisionRadius).thenReturn(radius)
        `when`(missile.owner).thenReturn(owner)
        return missile
    }

    /** 以 [origin] 为顶点、[axisDeg] 为中轴，在偏角 [offsetDeg]、距离 [dist] 处取目标点。 */
    private fun polar(origin: Vector2f, axisDeg: Float, offsetDeg: Float, dist: Float): Vector2f {
        val rad = Math.toRadians((axisDeg + offsetDeg).toDouble())
        return Vector2f((origin.x + cos(rad) * dist).toFloat(), (origin.y + sin(rad) * dist).toFloat())
    }

    private fun specOf(
        origin: Vector2f = Vector2f(0f, 0f),
        direction: Vector2f = Vector2f(1f, 0f),
        halfAngleDeg: Float = 40f,
        range: Float = 600f,
        damage: Float = 500f,
        empDamage: Float = 0f,
        owner: Int = 0,
        filter: ConeTargetFilter = ConeTargetFilter { true },
        hitShips: Boolean = true,
        hitFighters: Boolean = true,
        hitMissiles: Boolean = true,
    ) = ConeImpactSpec(
        origin = origin,
        direction = direction,
        halfAngleDeg = halfAngleDeg,
        range = range,
        damage = damage,
        damageType = DamageType.FRAGMENTATION,
        empDamage = empDamage,
        source = null,
        owner = owner,
        filter = filter,
        hitShips = hitShips,
        hitFighters = hitFighters,
        hitMissiles = hitMissiles,
    )

    // ---- §2.4-1 几何边界（纯函数直驱）----

    @Test
    fun `geometry - inside cone on axis is accepted`() {
        assertTrue(
            ConeImpactHandler.isInsideCone(
                Vector2f(0f, 0f), 1f, 0f, 40f, 600f,
                Vector2f(300f, 0f), 10f,
            ),
        )
    }

    @Test
    fun `geometry - outside half angle is rejected`() {
        val loc = polar(Vector2f(0f, 0f), 0f, 50f, 300f)
        assertFalse(ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, loc, 1f))
    }

    @Test
    fun `geometry - exactly on half angle boundary is accepted, just beyond is rejected`() {
        val onBoundary = polar(Vector2f(0f, 0f), 0f, 40f, 300f)
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, onBoundary, 1f),
            "恰在半角边界应纳入",
        )
        val beyond = polar(Vector2f(0f, 0f), 0f, 40.5f, 300f)
        assertFalse(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, beyond, 1f),
            "越过半角 0.5° 应剔除",
        )
    }

    @Test
    fun `geometry - exactly on range boundary is accepted, just beyond is rejected`() {
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, Vector2f(600f, 0f), 1f),
            "恰在锥长边界应纳入",
        )
        assertFalse(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, Vector2f(602f, 0f), 1f),
            "越过锥长应剔除",
        )
    }

    @Test
    fun `geometry - vertex overlap is accepted without angle or range judgement`() {
        // 目标中心与顶点完全重叠：方向任意、即使范围极小也纳入（0 值防线，不除零）。
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(100f, 100f), 0f, 1f, 10f, 50f, Vector2f(100f, 100f), 30f),
        )
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(100f, 100f), 1f, 0f, 10f, 50f, Vector2f(100.3f, 99.8f), 5f),
        )
    }

    // ---- §2.4-2 大目标擦边：碰撞半径放宽 ----

    @Test
    fun `geometry - large target grazing edge is accepted by radius allowance`() {
        // 中心偏轴 50°（超出 40° 半角 10°），dist=100、radius=20 → 放宽 atan(20/100)≈11.3°，应纳入。
        val loc = polar(Vector2f(0f, 0f), 0f, 50f, 100f)
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, loc, 20f),
            "大目标擦边应由半径放宽纳入",
        )
    }

    @Test
    fun `geometry - large target beyond range by center is accepted when surface within range`() {
        // 中心距 620 > range 600，但 radius=30 → 表面距 590 ≤ 600，与 LazyLib 粗筛「表面进入半径」语义一致。
        assertTrue(
            ConeImpactHandler.isInsideCone(Vector2f(0f, 0f), 1f, 0f, 40f, 600f, Vector2f(620f, 0f), 30f),
        )
    }

    // ---- §2.4-3 归属与类型过滤矩阵 ----

    @Test
    fun `filter matrix - enemy ship fighter missile accepted, friendly excluded, hulk excluded, neutral included`() {
        val origin = Vector2f(0f, 0f)
        val near = { x: Float -> Vector2f(x, 0f) }
        val enemyShip = stubShip(near(100f), 10f, owner = 1)
        val enemyFighter = stubShip(near(150f), 5f, owner = 1, fighter = true)
        val enemyMissile = stubMissile(near(200f), 3f, owner = 1)
        val friendlyShip = stubShip(near(250f), 10f, owner = 0)
        val friendlyMissile = stubMissile(near(300f), 3f, owner = 0)
        val neutralShip = stubShip(near(350f), 10f, owner = 100)
        val hulkShip = stubShip(near(400f), 10f, owner = 1, hulk = true)
        val plainProjectile = stubEntity(near(450f), 2f, owner = 1)
        val all = listOf(enemyShip, enemyFighter, enemyMissile, friendlyShip, friendlyMissile, neutralShip, hulkShip, plainProjectile)

        val stub = EngineStub()
        val hits = ConeImpactHandler.resolve(stub.engine, specOf(origin = origin, owner = 0)) { _, _ -> all }

        assertEquals(setOf(enemyShip, enemyFighter, enemyMissile, neutralShip), hits.toSet(), "命中集: $hits")
        assertEquals(4, stub.calls.size, "每个命中目标恰好结算一次")
    }

    @Test
    fun `filter matrix - type switches gate ship fighter missile independently`() {
        val ship = stubShip(Vector2f(100f, 0f), 10f, owner = 1)
        val fighter = stubShip(Vector2f(150f, 0f), 5f, owner = 1, fighter = true)
        val missile = stubMissile(Vector2f(200f, 0f), 3f, owner = 1)
        val all = listOf(ship, fighter, missile)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(
            stub.engine,
            specOf(hitShips = false, hitFighters = true, hitMissiles = false),
        ) { _, _ -> all }

        assertEquals(listOf(fighter), hits)
    }

    @Test
    fun `filter - terminal filter exempts the directly hit target (starlance case)`() {
        val direct = stubShip(Vector2f(100f, 0f), 10f, owner = 1)
        val bystander = stubShip(Vector2f(200f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(
            stub.engine,
            specOf(filter = ConeTargetFilter { it !== direct }),
        ) { _, _ -> listOf(direct, bystander) }

        assertEquals(listOf(bystander), hits, "命中本体应由 filter 豁免")
        assertEquals(1, stub.calls.size)
    }

    // ---- §2.4-4 粗筛与全表扫一致性（1000 次随机布点对照）----

    @Test
    fun `coarse query result matches full scan over 1000 random layouts`() {
        val rng = Random(20260729L)
        repeat(1000) { iteration ->
            val origin = Vector2f(rng.nextFloat() * 2000f - 1000f, rng.nextFloat() * 2000f - 1000f)
            val axisDeg = rng.nextFloat() * 360f
            val axisRad = Math.toRadians(axisDeg.toDouble())
            val direction = Vector2f(cos(axisRad).toFloat(), sin(axisRad).toFloat())
            val halfAngle = 5f + rng.nextFloat() * 85f
            val range = 100f + rng.nextFloat() * 900f
            val owner = rng.nextInt(3)

            val entities = ArrayList<CombatEntityAPI>()
            repeat(30) {
                val loc = Vector2f(rng.nextFloat() * 4000f - 2000f, rng.nextFloat() * 4000f - 2000f)
                val radius = 1f + rng.nextFloat() * 60f
                val entityOwner = rng.nextInt(4)
                entities += when (rng.nextInt(4)) {
                    0 -> stubShip(loc, radius, entityOwner, fighter = true)
                    1 -> stubShip(loc, radius, entityOwner)
                    2 -> stubMissile(loc, radius, entityOwner)
                    else -> stubEntity(loc, radius, entityOwner)
                }
            }

            val spec = specOf(origin = origin, direction = direction, halfAngleDeg = halfAngle, range = range, owner = owner)

            // 全表扫：候选 = 全部实体。
            val stubFull = EngineStub()
            val fullScan = ConeImpactHandler.resolve(stubFull.engine, spec) { _, _ -> entities }
            // 粗筛语义复刻（LazyLib getEntitiesWithinRange 的网格判定：dist ≤ 半径 + 目标碰撞半径，
            // 对齐结算器含边界的 RANGE_EPS 容忍）。
            val stubCoarse = EngineStub()
            val coarse = ConeImpactHandler.resolve(stubCoarse.engine, spec) { p, r ->
                entities.filter { e ->
                    val dx = e.location.x - p.x
                    val dy = e.location.y - p.y
                    sqrt(dx * dx + dy * dy) <= r + e.collisionRadius + ConeImpactHandler.RANGE_EPS
                }
            }

            assertEquals(
                fullScan.toSet(),
                coarse.toSet(),
                "第 $iteration 次布点：粗筛与全表扫命中集不一致（halfAngle=$halfAngle range=$range）",
            )
        }
    }

    // ---- §2.4-5 direction 非单位矢量 ----

    @Test
    fun `non unit direction warns and normalizes to identical result`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val all = listOf(target)

        val stubUnit = EngineStub()
        val unitHits = ConeImpactHandler.resolve(stubUnit.engine, specOf(direction = Vector2f(1f, 0f))) { _, _ -> all }
        val stubScaled = EngineStub()
        val scaledHits = ConeImpactHandler.resolve(stubScaled.engine, specOf(direction = Vector2f(7f, 0f))) { _, _ -> all }

        assertEquals(unitHits, scaledHits, "非单位矢量归一化后命中集应与单位矢量一致")
        assertTrue(capture.messages().any { it.contains("非单位矢量") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `zero length direction warns and settles nothing`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(stub.engine, specOf(direction = Vector2f(0f, 0f))) { _, _ -> listOf(target) }

        assertTrue(hits.isEmpty(), "零长度方向不得产出命中")
        assertTrue(stub.calls.isEmpty(), "零长度方向不得结算伤害")
        assertTrue(capture.messages().any { it.contains("零长度") }, "必须记 WARN: ${capture.messages()}")
    }

    // ---- 结算细节 ----

    @Test
    fun `applyDamage passes damage type emp source and floaty through, point on surface facing blast`() {
        val source = stubShip(Vector2f(-500f, 0f), 30f, owner = 0)
        val target = stubShip(Vector2f(300f, 0f), 20f, owner = 1)
        val stub = EngineStub()
        val spec = specOf(damage = 750f, empDamage = 120f).copy(source = source)

        val hits = ConeImpactHandler.resolve(stub.engine, spec) { _, _ -> listOf(target) }

        assertEquals(listOf(target), hits)
        assertEquals(1, stub.calls.size)
        val call = stub.calls.single()
        assertEquals(target, call.target)
        assertEquals(750f, call.damage)
        assertEquals(DamageType.FRAGMENTATION, call.damageType)
        assertEquals(120f, call.emp)
        assertEquals(source, call.source)
        assertTrue(call.showFloaty, "伤害浮字必须开启（锥状冲击波及的玩家可见反馈）")
        // 落点 = 顶点沿目标方向推进 dist - radius = 300 - 20 = 280。
        assertEquals(280f, call.point.x, 1e-3f)
        assertEquals(0f, call.point.y, 1e-3f)
    }

    @Test
    fun `zero damage and zero emp warns and skips applyDamage but still returns hits`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(stub.engine, specOf(damage = 0f, empDamage = 0f)) { _, _ -> listOf(target) }

        assertEquals(listOf(target), hits, "命中清单仍返回（供 VFX 使用）")
        assertTrue(stub.calls.isEmpty(), "无结算量不得调用 applyDamage")
        assertTrue(capture.messages().any { it.contains("无结算量") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `negative damage clamps to zero with warn`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        ConeImpactHandler.resolve(stub.engine, specOf(damage = -50f, empDamage = 30f)) { _, _ -> listOf(target) }

        assertEquals(1, stub.calls.size)
        assertEquals(0f, stub.calls.single().damage, "负伤害应 clamp 到 0")
        assertEquals(30f, stub.calls.single().emp)
        assertTrue(capture.messages().any { it.contains("damage 非法") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `non positive range warns and settles nothing`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(stub.engine, specOf(range = 0f)) { _, _ -> listOf(target) }

        assertTrue(hits.isEmpty())
        assertTrue(stub.calls.isEmpty())
        assertTrue(capture.messages().any { it.contains("range 非正") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `half angle out of domain clamps with warn`() {
        val capture = WarnCapture(ConeImpactHandler::class.java).also { captures += it }
        val target = stubShip(Vector2f(300f, 0f), 10f, owner = 1)
        val stub = EngineStub()

        val hits = ConeImpactHandler.resolve(stub.engine, specOf(halfAngleDeg = 270f)) { _, _ -> listOf(target) }

        assertEquals(listOf(target), hits, "270° clamp 到 180° 后轴上目标仍应命中")
        assertTrue(capture.messages().any { it.contains("halfAngleDeg 越界") }, "必须记 WARN: ${capture.messages()}")
    }
}
