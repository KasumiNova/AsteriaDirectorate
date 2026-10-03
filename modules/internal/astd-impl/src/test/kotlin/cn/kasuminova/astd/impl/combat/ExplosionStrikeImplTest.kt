package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.apache.log4j.Level
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ExplosionStrikeImpl] 统一结算入口的真实逻辑验证：
 * - 舰体压点数学（clampIntoHull 纯函数直驱 + resolveDamagePoint 经 ShipAPI 桩全路径）；
 * - 线性衰减数学（[LinearExplosionFalloffImpl.damageFor] 直驱）；
 * - strike 全流程：内置过滤矩阵、victimFilter 终判、落点/bypass 口径、返回受害清单；
 * - 0 值防线（radius 非正、damage/emp 非法、护盾数据异常）全部 WARN 不静默。
 */
class ExplosionStrikeImplTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    // ---- 桩 ----

    private data class DamageRecord(
        val target: CombatEntityAPI,
        val point: Vector2f?,
        val amount: Float,
        val type: DamageType,
        val emp: Float,
        val bypass: Boolean,
        val softFlux: Boolean,
        val playSound: Boolean,
    )

    /** 记录型桩引擎：applyDamage 九参全记录。 */
    private class StubEngineWorld {
        val damages = mutableListOf<DamageRecord>()

        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            doAnswer { inv ->
                damages += DamageRecord(
                    target = inv.getArgument(0),
                    point = inv.getArgument(1),
                    amount = inv.getArgument(2),
                    type = inv.getArgument(3),
                    emp = inv.getArgument(4),
                    bypass = inv.getArgument(5),
                    softFlux = inv.getArgument(6),
                    playSound = inv.getArgument(8),
                )
                null
            }.`when`(engine).applyDamage(
                any(CombatEntityAPI::class.java), nullable(Vector2f::class.java), anyFloat(),
                any(DamageType::class.java), anyFloat(), anyBoolean(), anyBoolean(),
                nullable(Any::class.java), anyBoolean(),
            )
        }
    }

    private fun stubShip(
        at: Vector2f,
        radius: Float,
        owner: Int = 1,
        hulk: Boolean = false,
        phased: Boolean = false,
        shield: ShieldAPI? = null,
    ): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.location).thenReturn(at)
        `when`(s.collisionRadius).thenReturn(radius)
        `when`(s.owner).thenReturn(owner)
        `when`(s.isHulk).thenReturn(hulk)
        `when`(s.isPhased).thenReturn(phased)
        `when`(s.shield).thenReturn(shield)
        return s
    }

    private fun stubMissile(at: Vector2f, radius: Float, owner: Int = 1, expired: Boolean = false): MissileAPI {
        val m = mock(MissileAPI::class.java)
        `when`(m.location).thenReturn(at)
        `when`(m.collisionRadius).thenReturn(radius)
        `when`(m.owner).thenReturn(owner)
        `when`(m.isExpired).thenReturn(expired)
        return m
    }

    private fun stubShield(at: Vector2f, radius: Float, on: Boolean = true, withinArc: Boolean = true): ShieldAPI {
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.isOn).thenReturn(on)
        `when`(shield.isWithinArc(any())).thenReturn(withinArc)
        `when`(shield.location).thenReturn(at)
        `when`(shield.radius).thenReturn(radius)
        return shield
    }

    // ---- 舰体压点数学（纯函数直驱）----

    @Test
    fun `压点 - 爆心在 0_9 倍碰撞半径内原样返回`() {
        val center = Vector2f(1000f, 1000f)
        val inside = ExplosionStrikeImpl.clampIntoHull(center, 100f, Vector2f(1089f, 1000f))
        assertEquals(1089f, inside.x, 1e-3f, "距离 89 ≤ 0.9×100：原样取爆心")
        assertEquals(1000f, inside.y, 1e-3f)
    }

    @Test
    fun `压点 - 爆心超出 0_9 倍碰撞半径截断到命中侧`() {
        val center = Vector2f(1000f, 1000f)
        val clamped = ExplosionStrikeImpl.clampIntoHull(center, 100f, Vector2f(1200f, 1000f))
        assertEquals(1090f, clamped.x, 1e-3f, "距离 200 > 90：沿舰心→爆心方向截断到 90")
        assertEquals(1000f, clamped.y, 1e-3f)

        val diagonal = ExplosionStrikeImpl.clampIntoHull(Vector2f(0f, 0f), 100f, Vector2f(60f, 80f))
        assertEquals(54f, diagonal.x, 1e-3f, "斜向距离 100 > 90：按比例 0.9 截断")
        assertEquals(72f, diagonal.y, 1e-3f)
    }

    @Test
    fun `压点 - 爆心与舰心重合不除零原样返回`() {
        val same = ExplosionStrikeImpl.clampIntoHull(Vector2f(500f, 500f), 100f, Vector2f(500f, 500f))
        assertEquals(500f, same.x, 1e-3f)
        assertEquals(500f, same.y, 1e-3f)
    }

    // ---- 统一落点解析（ShipAPI 桩全路径）----

    @Test
    fun `落点 - 盾覆盖爆心取盾面点且 bypass 口径为覆盖`() {
        val shield = stubShield(Vector2f(1000f, 1000f), 120f)
        val ship = stubShip(Vector2f(1000f, 1000f), 100f, shield = shield)

        val point = ExplosionStrikeImpl.resolveDamagePoint(ship, Vector2f(1150f, 1000f))

        assertEquals(1120f, point.x, 1e-3f, "盾面点 = 盾心沿爆心方向外推盾半径")
        assertEquals(1000f, point.y, 1e-3f)
        assertTrue(ExplosionStrikeImpl.shieldCovers(ship, Vector2f(1150f, 1000f)), "盾覆盖判定成立")
    }

    @Test
    fun `落点 - 盾关闭或不在弧内压回舰体`() {
        val offShield = stubShield(Vector2f(1000f, 1000f), 120f, on = false)
        val shipOff = stubShip(Vector2f(1000f, 1000f), 100f, shield = offShield)
        val pointOff = ExplosionStrikeImpl.resolveDamagePoint(shipOff, Vector2f(1300f, 1000f))
        assertEquals(1090f, pointOff.x, 1e-3f, "盾关闭 → 命中侧压点，不再退回舰心")

        val outArcShield = stubShield(Vector2f(1000f, 1000f), 120f, withinArc = false)
        val shipOutArc = stubShip(Vector2f(1000f, 1000f), 100f, shield = outArcShield)
        val pointOutArc = ExplosionStrikeImpl.resolveDamagePoint(shipOutArc, Vector2f(1300f, 1000f))
        assertEquals(1090f, pointOutArc.x, 1e-3f, "爆心不在盾弧内 → 命中侧压点")
    }

    @Test
    fun `落点 - 护盾数据异常 WARN 并退回压点`() {
        val capture = WarnCapture(ExplosionStrikeImpl::class.java).also { captures += it }
        val brokenShield = stubShield(Vector2f(1000f, 1000f), 0f)
        val ship = stubShip(Vector2f(1000f, 1000f), 100f, shield = brokenShield)

        val point = ExplosionStrikeImpl.resolveDamagePoint(ship, Vector2f(1300f, 1000f))

        assertEquals(1090f, point.x, 1e-3f, "盾半径非正 → 退回舰体压点（不返回舰心）")
        assertTrue(capture.messages().any { it.contains("护盾数据异常") }, "必须记 WARN: ${capture.messages()}")
    }

    // ---- 线性衰减数学（直驱）----

    @Test
    fun `线性衰减 - 核心区内全额到半径线性降到最低`() {
        val falloff = LinearExplosionFalloffImpl(coreRadius = 50f, minDamage = 100f)
        assertEquals(1000f, falloff.damageFor(1000f, 0f, 200f), 1e-3f, "爆心表面全额")
        assertEquals(1000f, falloff.damageFor(1000f, 50f, 200f), 1e-3f, "核心区边缘全额")
        assertEquals(550f, falloff.damageFor(1000f, 125f, 200f), 1e-3f, "中点线性一半")
        assertEquals(100f, falloff.damageFor(1000f, 200f, 200f), 1e-3f, "半径边缘最低")
        assertEquals(100f, falloff.damageFor(1000f, 300f, 200f), 1e-3f, "半径外 clamp 不反推")
    }

    @Test
    fun `线性衰减 - 核心区不小于半径时不除零直接最低`() {
        val falloff = LinearExplosionFalloffImpl(coreRadius = 200f, minDamage = 100f)
        assertEquals(100f, falloff.damageFor(1000f, 300f, 200f), 1e-3f, "span ≤ 0 → 超出核心直接 minDamage")
    }

    @Test
    fun `全额衰减 - 任意表面距离恒面板`() {
        assertEquals(1000f, FullExplosionFalloffImpl.damageFor(1000f, 0f, 200f), 1e-3f)
        assertEquals(1000f, FullExplosionFalloffImpl.damageFor(1000f, 199f, 200f), 1e-3f)
    }

    // ---- strike 全流程 ----

    @Test
    fun `结算 - 敌舰命中侧压点加bypass 导弹取爆心点 友军残骸相位过期弹剔除`() {
        val world = StubEngineWorld()
        val center = Vector2f(0f, 0f)
        val enemyShip = stubShip(Vector2f(60f, 0f), 60f)
        val enemyMissile = stubMissile(Vector2f(30f, 30f), 4f)
        val friendly = stubShip(Vector2f(50f, 0f), 60f, owner = 0)
        val hulk = stubShip(Vector2f(50f, 0f), 60f, hulk = true)
        val phased = stubShip(Vector2f(50f, 0f), 60f, phased = true)
        val expiredMissile = stubMissile(Vector2f(40f, 0f), 4f, expired = true)
        val asteroid = mock(CombatEntityAPI::class.java).also {
            `when`(it.location).thenReturn(Vector2f(20f, 0f))
            `when`(it.owner).thenReturn(1)
        }
        val candidates = listOf<CombatEntityAPI>(enemyShip, enemyMissile, friendly, hulk, phased, expiredMissile, asteroid)

        val victims = ExplosionStrikeImpl.strike(
            world.engine, center, 150f, 800f, DamageType.ENERGY,
            emp = 0f, null, 0,
            FullExplosionFalloffImpl,
            playSound = true,
        ) { _, _ -> candidates }

        assertEquals(listOf<CombatEntityAPI>(enemyShip, enemyMissile), victims, "受害清单 = 实际结算目标")
        assertEquals(2, world.damages.size)

        val shipHit = world.damages[0]
        assertEquals(enemyShip, shipHit.target)
        assertEquals(800f, shipHit.amount, 1e-3f, "全额模式无距离衰减")
        assertEquals(DamageType.ENERGY, shipHit.type)
        assertTrue(shipHit.bypass, "无盾舰船 → bypassShields=true")
        assertTrue(!shipHit.softFlux, "dealsSoftFlux 恒 false")
        assertTrue(shipHit.playSound, "playSound 透传")
        assertEquals(6f, shipHit.point?.x ?: Float.NaN, 1e-3f, "落点 = 命中侧压点（距舰心 60 截断到 0.9×60=54 的靠爆心侧），不再是舰心")
        assertEquals(0f, shipHit.point?.y ?: Float.NaN, 1e-3f)

        val missileHit = world.damages[1]
        assertEquals(enemyMissile, missileHit.target)
        assertTrue(!missileHit.bypass, "导弹无盾 → bypassShields=false")
        assertEquals(0f, missileHit.point?.x ?: Float.NaN, 1e-3f, "导弹落点 = 爆心点")
    }

    @Test
    fun `结算 - victimFilter 终判豁免直击目标`() {
        val world = StubEngineWorld()
        val direct = stubShip(Vector2f(10f, 0f), 60f)
        val bystander = stubShip(Vector2f(60f, 0f), 60f)

        val victims = ExplosionStrikeImpl.strike(
            world.engine, Vector2f(0f, 0f), 150f, 800f, DamageType.ENERGY,
            emp = 0f, null, 0,
            FullExplosionFalloffImpl,
            victimFilter = { it !== direct },
        ) { _, _ -> listOf(direct, bystander) }

        assertEquals(listOf<CombatEntityAPI>(bystander), victims, "直击目标由 victimFilter 豁免")
        assertEquals(1, world.damages.size)
    }

    @Test
    fun `结算 - 盾覆盖目标走盾面点且 bypass 关闭`() {
        val world = StubEngineWorld()
        val shield = stubShield(Vector2f(100f, 0f), 120f)
        val covered = stubShip(Vector2f(100f, 0f), 100f, shield = shield)

        val victims = ExplosionStrikeImpl.strike(
            world.engine, Vector2f(0f, 0f), 300f, 800f, DamageType.ENERGY,
            emp = 0f, null, 0,
            FullExplosionFalloffImpl,
        ) { _, _ -> listOf(covered) }

        assertEquals(1, victims.size)
        val hit = world.damages.single()
        assertTrue(!hit.bypass, "盾覆盖爆心 → bypassShields=false（尊重护盾）")
        assertEquals(-20f, hit.point?.x ?: Float.NaN, 1e-3f, "落点 = 盾面点（盾心沿爆心方向外推盾半径）")
        assertEquals(0f, hit.point?.y ?: Float.NaN, 1e-3f)
    }

    @Test
    fun `结算 - EMP 量透传`() {
        val world = StubEngineWorld()
        val target = stubShip(Vector2f(50f, 0f), 60f)

        ExplosionStrikeImpl.strike(
            world.engine, Vector2f(0f, 0f), 150f, 800f, DamageType.ENERGY,
            emp = 240f, null, 0,
            FullExplosionFalloffImpl,
        ) { _, _ -> listOf(target) }

        assertEquals(240f, world.damages.single().emp, 1e-3f)
    }

    // ---- 0 值防线 ----

    @Test
    fun `防线 - radius 非正 WARN 且不结算`() {
        val capture = WarnCapture(ExplosionStrikeImpl::class.java).also { captures += it }
        val world = StubEngineWorld()

        val victims = ExplosionStrikeImpl.strike(
            world.engine, Vector2f(0f, 0f), 0f, 800f, DamageType.ENERGY,
            emp = 0f, null, 0,
            FullExplosionFalloffImpl,
        ) { _, _ -> listOf(stubShip(Vector2f(10f, 0f), 60f)) }

        assertTrue(victims.isEmpty())
        assertTrue(world.damages.isEmpty())
        assertTrue(capture.messages().any { it.contains("radius 非正") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `防线 - damage 非法 clamp 到 0 且与 emp 同为 0 时 WARN 不结算`() {
        val capture = WarnCapture(ExplosionStrikeImpl::class.java).also { captures += it }
        val world = StubEngineWorld()
        val target = stubShip(Vector2f(50f, 0f), 60f)

        val victims = ExplosionStrikeImpl.strike(
            world.engine, Vector2f(0f, 0f), 150f, Float.NaN, DamageType.ENERGY,
            emp = 0f, null, 0,
            FullExplosionFalloffImpl,
        ) { _, _ -> listOf(target) }

        assertTrue(victims.isEmpty(), "damage NaN clamp 到 0 后与 emp 同为 0：无结算量")
        assertTrue(world.damages.isEmpty())
        val warns = capture.events.filter { it.level == Level.WARN }.map { it.renderedMessage }
        assertTrue(warns.any { it.contains("damage 非法") }, "非法 damage 必须 WARN: $warns")
        assertTrue(warns.any { it.contains("无结算量") }, "无结算量必须 WARN: $warns")
    }
}
