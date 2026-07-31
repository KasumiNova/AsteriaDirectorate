package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexAbsorbImpl.Companion.pullAccel
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.spi.LoggingEvent
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** log4j 1.x 事件捕获器（级别可配；WarnCapture 仅 WARN，本组还要断言 INFO，故就地一份）。 */
private class LogCapture(loggerClass: Class<*>, level: Level) {
    private val logger: Logger = Logger.getLogger(loggerClass)
    private val previousLevel: Level? = logger.level
    val events = mutableListOf<LoggingEvent>()

    private val appender = object : AppenderSkeleton() {
        override fun append(event: LoggingEvent) {
            events += event
        }

        override fun close() {}
        override fun requiresLayout(): Boolean = false
    }

    init {
        logger.level = level
        logger.addAppender(appender)
    }

    fun messages(): List<String> = events.map { it.renderedMessage }

    fun detach() {
        logger.removeAppender(appender)
        logger.level = previousLevel
    }
}

/**
 * 规格 04 §4.1 用例 1：三条三锚点在 k_s=1/2/5 精确命中 + 玩家固定 v2（轨一映射链路与 owner==0 旁路）。
 */
class AnnihilationVortexTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    @Test
    fun `三锚点在 k_s 1 2 5 精确命中`() {
        val cases = mapOf(
            1f to Triple(150f, 0.5f, 3200f),
            2f to Triple(187.5f, 1.0f, 8800f),
            5f to Triple(300f, 2.5f, 16000f),
        )
        for ((scale, expected) in cases) {
            DifficultyTuningImpl.installScaleForTests(scale)
            assertEquals(expected.first, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.RADIUS, 1), 1e-4f, "k_s=$scale 半径")
            assertEquals(expected.second, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.AOE_MULT, 1), 1e-6f, "k_s=$scale AOE 倍率")
            assertEquals(expected.third, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.ABSORB_LIMIT, 1), 1e-2f, "k_s=$scale 吸收阈值")
        }
    }

    @Test
    fun `玩家来源固定 v2 与 k_s 无关`() {
        for (scale in listOf(1f, 5f)) {
            DifficultyTuningImpl.installScaleForTests(scale)
            assertEquals(187.5f, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.RADIUS, 0), 1e-4f)
            assertEquals(1.0f, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.AOE_MULT, 0), 1e-6f)
            assertEquals(8800f, AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.ABSORB_LIMIT, 0), 1e-2f)
        }
    }

    @Test
    fun `坍缩与吸收半径派生公式`() {
        // 吸收半径 = max(30, radius×0.25)。
        assertEquals(46.875f, AnnihilationVortexDifficulty.absorbRadiusFor(187.5f), 1e-3f)
        assertEquals(75f, AnnihilationVortexDifficulty.absorbRadiusFor(300f), 1e-3f)
        assertEquals(30f, AnnihilationVortexDifficulty.absorbRadiusFor(10f), "半径过小吃下限")
        assertEquals(30f, AnnihilationVortexDifficulty.absorbRadiusFor(0f), "0 半径吃下限不除零")
        // 坍缩半径 = 涡旋半径 × 150%。
        assertEquals(281.25f, AnnihilationVortexDifficulty.collapseRadiusFor(187.5f), 1e-3f)
    }
}

/**
 * 规格 04 §4.1 用例 2/3/7/8：池记账（类型转换比/软上限分段/保底）、0 值防线（INFO/WARN 捕获）、
 * SELF_MANAGED 生命周期（宿主失效 host.remove 恰好一次 + 两条回收路径的日志分流）。
 */
class AnnihilationVortexPoolTest {

    private data class PoolFixture(val pool: AnnihilationVortexPoolImpl, val host: BuffHost, val weapon: com.fs.starfarer.api.combat.WeaponAPI)

    private fun newPool(threshold: Float = 1e6f, shipAlive: Boolean = true): PoolFixture {
        val host = mock(BuffHost::class.java)
        val weapon = stubWeapon("WS 003", "astd_annihilation_vortex")
        val ship = stubShip()
        `when`(ship.isAlive).thenReturn(shipAlive)
        `when`(weapon.ship).thenReturn(ship)
        return PoolFixture(AnnihilationVortexPoolImpl(threshold, host, weapon), host, weapon)
    }

    @Test
    fun `类型转换比 ENERGY 1 HE 0_5 KINETIC 0_5 FRAG 0_25`() {
        val (pool, _) = newPool()
        assertEquals(100f, pool.addAbsorbed(DamageType.ENERGY, 100f), 1e-4f)
        assertEquals(50f, pool.addAbsorbed(DamageType.HIGH_EXPLOSIVE, 100f), 1e-4f)
        assertEquals(50f, pool.addAbsorbed(DamageType.KINETIC, 100f), 1e-4f)
        assertEquals(25f, pool.addAbsorbed(DamageType.FRAGMENTATION, 100f), 1e-4f)
        assertEquals(225f, pool.convertedTotal, 1e-3f)
        assertEquals(4, pool.absorbedCount)
    }

    @Test
    fun `软上限分段折算 阈值内全额超出部分四分之一`() {
        val (pool, _) = newPool(threshold = 1000f)
        assertEquals(800f, pool.addAbsorbed(DamageType.ENERGY, 800f), 1e-4f, "阈值内全额")
        // room=200，inRoom=200，excess=200×0.25=50。
        assertEquals(250f, pool.addAbsorbed(DamageType.ENERGY, 400f), 1e-4f, "超出段部分折算")
        assertEquals(1050f, pool.convertedTotal, 1e-3f)
        // 池满后 room=0，全部走超额折算。
        assertEquals(25f, pool.addAbsorbed(DamageType.ENERGY, 100f), 1e-4f)
        assertEquals(1075f, pool.convertedTotal, 1e-3f)
    }

    @Test
    fun `坍缩保底 空池按 500 乘 AOE 倍率`() {
        assertEquals(500f, AnnihilationVortexDifficulty.collapseDamage(0f, 1.0f), 1e-4f, "空池 v2 保底 500")
        assertEquals(1250f, AnnihilationVortexDifficulty.collapseDamage(0f, 2.5f), 1e-3f, "空池 v5 保底 500×2.5")
        assertEquals(500f, AnnihilationVortexDifficulty.collapseDamage(300f, 1.0f), 1e-4f, "低于保底抬到 500")
        assertEquals(800f, AnnihilationVortexDifficulty.collapseDamage(800f, 1.0f), 1e-4f, "高于保底按池值")
    }

    @Test
    fun `0 值防线 threshold 非正 WARN 且 clamp 到 1`() {
        val capture = LogCapture(AnnihilationVortexPoolImpl::class.java, Level.WARN)
        try {
            val (pool, _) = newPool(threshold = 0f)
            assertEquals(1f, pool.threshold, 1e-6f)
            assertTrue(capture.messages().any { "threshold 非正" in it }, "threshold=0 必须 WARN，实际: ${capture.messages()}")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `0 值防线 0 伤害弹体 INFO 节流且照常计数入池 0`() {
        val capture = LogCapture(AnnihilationVortexPoolImpl::class.java, Level.INFO)
        try {
            val (pool, _) = newPool()
            assertEquals(0f, pool.addAbsorbed(DamageType.ENERGY, 0f), 1e-6f)
            pool.addAbsorbed(DamageType.ENERGY, 0f)
            val infos = capture.messages().filter { "0 面板伤害弹体" in it }
            assertEquals(1, infos.size, "同弹种 INFO 只记一条，实际: $infos")
            assertEquals(2, pool.absorbedCount, "0 伤害弹体照常计数")
            assertEquals(0f, pool.convertedTotal, 1e-6f, "0 伤害弹体入池 0")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `0 值防线 未登记伤害类型 WARN 一次且按 1_0 计入`() {
        val capture = LogCapture(AnnihilationVortexPoolImpl::class.java, Level.WARN)
        try {
            val (pool, _) = newPool()
            assertEquals(100f, pool.addAbsorbed(DamageType.OTHER, 100f), 1e-4f, "未登记类型按 1.0 计入")
            pool.addAbsorbed(DamageType.OTHER, 100f)
            val warns = capture.messages().filter { "未登记伤害类型" in it }
            assertEquals(1, warns.size, "同类型 WARN 节流一条，实际: $warns")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `SELF_MANAGED 宿主失效 advance 经 host_remove 恰好一次`() {
        val f = newPool(shipAlive = false)
        assertEquals(BuffLifetime.SELF_MANAGED, f.pool.lifetime)
        assertEquals(AnnihilationVortexBeamEffect.POOL_BUFF_ID, f.pool.id)

        f.pool.advance(0.1f)
        f.pool.advance(0.1f)
        verify(f.host, times(1)).remove(f.pool, f.weapon)
    }

    @Test
    fun `宿主有效时 advance 不回收`() {
        val f = newPool(shipAlive = true)
        f.pool.advance(0.1f)
        verify(f.host, never()).remove(f.pool, f.weapon)
    }

    @Test
    fun `回收日志分流 未消费记 INFO 已消费静默`() {
        val capture = LogCapture(AnnihilationVortexPoolImpl::class.java, Level.INFO)
        try {
            val (discarded, _) = newPool()
            discarded.addAbsorbed(DamageType.ENERGY, 100f)
            discarded.onRemove()
            assertTrue(capture.messages().any { "吞噬池随宿主失效丢弃" in it && "100.0" in it }, "未消费丢弃必须 INFO，实际: ${capture.messages()}")

            val (consumed, _) = newPool()
            consumed.addAbsorbed(DamageType.ENERGY, 100f)
            consumed.markConsumed()
            consumed.onRemove()
            assertEquals(1, capture.messages().count { "吞噬池随宿主失效丢弃" in it }, "已消费移除不得再记丢弃 INFO")
        } finally {
            capture.detach()
        }
    }
}

/**
 * 规格 04 §4.1 用例 5/6：牵引纯函数边界 + 注入粗筛的完整吸收/牵引结算驱动（infra2 同款注入处置）。
 */
class AnnihilationVortexAbsorbTest {

    private fun projectile(owner: Int, at: Vector2f, velocity: Vector2f = Vector2f(0f, 0f), damage: Float = 100f, type: DamageType = DamageType.ENERGY, collision: Float = 4f): DamagingProjectileAPI {
        val p = mock(DamagingProjectileAPI::class.java)
        `when`(p.owner).thenReturn(owner)
        `when`(p.location).thenReturn(at)
        `when`(p.velocity).thenReturn(velocity)
        `when`(p.collisionRadius).thenReturn(collision)
        `when`(p.damageType).thenReturn(type)
        `when`(p.baseDamageAmount).thenReturn(damage)
        return p
    }

    private fun absorbOf(candidates: List<CombatEntityAPI>): AnnihilationVortexAbsorbImpl =
        AnnihilationVortexAbsorbImpl { _, _, _ -> candidates }

    @Test
    fun `牵引加速度纯函数 边缘 0 中心最大 超出不反推`() {
        val r = 187.5f
        assertEquals(0f, pullAccel(r, r), 1e-4f, "涡旋边缘加速度为 0")
        assertEquals(AnnihilationVortexDifficulty.PULL_ACCEL_MAX, pullAccel(0f, r), 1e-3f, "圆心处最大")
        assertEquals(AnnihilationVortexDifficulty.PULL_ACCEL_MAX * 0.5f, pullAccel(r / 2f, r), 1e-3f, "中点线性一半")
        assertEquals(0f, pullAccel(r * 1.5f, r), 1e-4f, "超出半径 clamp 为 0 不反推")
    }

    @Test
    fun `吸收半径内弹体移除并入账 回调抛出位置`() {
        val engine = mock(CombatEngineAPI::class.java)
        val inside = projectile(owner = 1, at = Vector2f(30f, 0f), damage = 120f, type = DamageType.HIGH_EXPLOSIVE)
        val fx = mutableListOf<Vector2f>()

        val outcome = absorbOf(listOf(inside)).advance(
            engine = engine, center = Vector2f(0f, 0f), radius = 187.5f,
            absorbRadius = AnnihilationVortexDifficulty.absorbRadiusFor(187.5f),
            sourceOwner = 0, amount = 0.016f, onAbsorbedFx = { fx += it },
        )

        verify(engine, times(1)).removeEntity(inside)
        assertEquals(1, outcome.absorbed.size)
        val shot = outcome.absorbed.first()
        assertEquals(DamageType.HIGH_EXPLOSIVE, shot.type)
        assertEquals(120f, shot.baseDamage, 1e-4f)
        assertEquals(30f, shot.location.x, 1e-4f)
        assertEquals(1, fx.size, "吸收 flare 回调逐发抛出")
        assertEquals(0, outcome.pulledCount)
    }

    @Test
    fun `吸收半径外弹体被向心牵引 速度改写且计数`() {
        val engine = mock(CombatEngineAPI::class.java)
        // 中心原点，弹体在 +x 150su（半径 187.5 内、吸收半径 46.875 外），初速 +x 100。
        val velocity = Vector2f(100f, 0f)
        val outside = projectile(owner = 1, at = Vector2f(150f, 0f), velocity = velocity)

        val outcome = absorbOf(listOf(outside)).advance(
            engine = engine, center = Vector2f(0f, 0f), radius = 187.5f,
            absorbRadius = AnnihilationVortexDifficulty.absorbRadiusFor(187.5f),
            sourceOwner = 0, amount = 0.1f, onAbsorbedFx = {},
        )

        assertEquals(1, outcome.pulledCount)
        assertTrue(outcome.absorbed.isEmpty())
        assertTrue(velocity.x < 100f, "牵引必须削减背离中心的速度分量，实际 vx=${velocity.x}")
        verify(engine, never()).removeEntity(any())
    }

    @Test
    fun `同归属与非弹体实体跳过`() {
        val engine = mock(CombatEngineAPI::class.java)
        val friendly = projectile(owner = 0, at = Vector2f(10f, 0f))
        val asteroid = mock(CombatEntityAPI::class.java)
        `when`(asteroid.owner).thenReturn(1)
        `when`(asteroid.location).thenReturn(Vector2f(10f, 0f))

        val outcome = absorbOf(listOf(friendly, asteroid)).advance(
            engine = engine, center = Vector2f(0f, 0f), radius = 187.5f,
            absorbRadius = 46.875f, sourceOwner = 0, amount = 0.1f, onAbsorbedFx = {},
        )

        assertEquals(0, outcome.absorbed.size)
        assertEquals(0, outcome.pulledCount)
        verify(engine, never()).removeEntity(any())
    }

    @Test
    fun `0 值防线 半径非正 WARN 一次且 clamp 到最小值`() {
        val capture = LogCapture(AnnihilationVortexAbsorbImpl::class.java, Level.WARN)
        try {
            val engine = mock(CombatEngineAPI::class.java)
            val absorb = absorbOf(emptyList())
            absorb.advance(engine, Vector2f(0f, 0f), 0f, 30f, 0, 0.1f, {})
            absorb.advance(engine, Vector2f(0f, 0f), -5f, 30f, 0, 0.1f, {})
            val warns = capture.messages().filter { "半径输入非正" in it }
            assertEquals(1, warns.size, "半径 WARN 每实例一条，实际: $warns")
        } finally {
            capture.detach()
        }
    }
}

/**
 * 规格 04 §4.1 坍缩结算：敌我过滤 + hulk 剔除 + applyDamage 九参通道 + 命中计数 + 0 值防线。
 */
class AnnihilationVortexCollapseTest {

    private fun ship(owner: Int, hulk: Boolean = false, at: Vector2f = Vector2f(50f, 0f)): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.owner).thenReturn(owner)
        `when`(s.isHulk).thenReturn(hulk)
        `when`(s.location).thenReturn(at)
        return s
    }

    private fun collapseOf(candidates: List<CombatEntityAPI>): AnnihilationVortexCollapseImpl =
        AnnihilationVortexCollapseImpl { _, _, _ -> candidates }

    @Test
    fun `敌方舰船战机导弹命中 友方 hulk 非目标剔除`() {
        val engine = mock(CombatEngineAPI::class.java)
        val source = ship(owner = 0)
        val enemyShip = ship(owner = 1)
        val friendly = ship(owner = 0, at = Vector2f(60f, 0f))
        val enemyHulk = ship(owner = 1, hulk = true)
        val enemyMissile = mock(MissileAPI::class.java)
        `when`(enemyMissile.owner).thenReturn(1)
        `when`(enemyMissile.location).thenReturn(Vector2f(70f, 0f))
        val asteroid = mock(CombatEntityAPI::class.java)
        `when`(asteroid.owner).thenReturn(1)
        `when`(asteroid.location).thenReturn(Vector2f(80f, 0f))

        val hits = collapseOf(listOf(source, enemyShip, friendly, enemyHulk, enemyMissile, asteroid)).resolve(
            engine = engine, center = Vector2f(0f, 0f), radius = 281.25f, damage = 500f, source = source,
        )

        assertEquals(2, hits, "仅敌方活舰 + 敌方导弹命中")
        verify(engine, times(1)).applyDamage(
            eq(enemyShip), any(Vector2f::class.java), eq(500f), eq(DamageType.ENERGY),
            eq(0f), eq(false), eq(false), eq(source), eq(true),
        )
        verify(engine, times(1)).applyDamage(
            eq(enemyMissile), any(Vector2f::class.java), eq(500f), eq(DamageType.ENERGY),
            eq(0f), eq(false), eq(false), eq(source), eq(true),
        )
        verify(engine, never()).applyDamage(eq(friendly), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean())
        verify(engine, never()).applyDamage(eq(enemyHulk), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean())
        verify(engine, never()).applyDamage(eq(asteroid), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean())
    }

    @Test
    fun `0 值防线 来源缺失 WARN 一次且不结算`() {
        val capture = LogCapture(AnnihilationVortexCollapseImpl::class.java, Level.WARN)
        try {
            val engine = mock(CombatEngineAPI::class.java)
            val collapse = collapseOf(listOf(ship(owner = 1)))
            assertEquals(0, collapse.resolve(engine, Vector2f(0f, 0f), 281.25f, 500f, null))
            assertEquals(0, collapse.resolve(engine, Vector2f(0f, 0f), 281.25f, 500f, null))
            val warns = capture.messages().filter { "来源舰缺失" in it }
            assertEquals(1, warns.size, "来源缺失 WARN 每实例一条，实际: $warns")
            verify(engine, never()).applyDamage(any(), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean())
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `0 值防线 伤害非正 WARN 且不结算`() {
        val capture = LogCapture(AnnihilationVortexCollapseImpl::class.java, Level.WARN)
        try {
            val engine = mock(CombatEngineAPI::class.java)
            val collapse = collapseOf(listOf(ship(owner = 1)))
            assertEquals(0, collapse.resolve(engine, Vector2f(0f, 0f), 281.25f, 0f, ship(owner = 0)))
            assertTrue(capture.messages().any { "坍缩伤害非正" in it }, "伤害非正必须 WARN，实际: ${capture.messages()}")
            verify(engine, never()).applyDamage(any(), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean())
        } finally {
            capture.detach()
        }
    }
}
