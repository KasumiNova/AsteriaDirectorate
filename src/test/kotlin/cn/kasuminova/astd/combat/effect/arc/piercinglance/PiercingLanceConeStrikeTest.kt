package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 规格 09 §4.1 用例 1~10：贯星之矛锥状冲击组装（[PiercingLanceConeStrike.buildConeSpec] 纯函数）
 * 与难度登记（[PiercingLanceDifficulty.valueFor]）的真实逻辑验证。
 *
 * 锥形几何边界（锥内/锥外/半角/锥长/大目标擦边/归属矩阵）由基建 ConeImpactHandler 测试覆盖，此处不重复。
 * 难度档经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 PositronShockwaveDifficultyTest 先例）。
 */
class PiercingLanceConeStrikeTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
        warnCapture?.detach()
        warnCapture = null
    }

    // ---- 测试桩 ----

    private fun stubShip(owner: Int, location: Vector2f = Vector2f(0f, 0f)): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.location).thenReturn(location)
        return ship
    }

    /**
     * 造一发弹体 mock：[velocity] 弹体速度（null 表示引擎未给出）；[ship] 来源船（null = 无武器来源）；
     * [owner] 弹体归属方（source 缺失时 spec.owner 的回退值）。
     */
    private fun stubProjectile(
        velocity: Vector2f?,
        ship: ShipAPI?,
        owner: Int = 0,
    ): DamagingProjectileAPI {
        val projectile = mock(DamagingProjectileAPI::class.java)
        `when`(projectile.velocity).thenReturn(velocity)
        if (ship != null) {
            val weapon = mock(WeaponAPI::class.java)
            `when`(weapon.ship).thenReturn(ship)
            `when`(projectile.weapon).thenReturn(weapon)
        }
        `when`(projectile.owner).thenReturn(owner)
        return projectile
    }

    // ---- 用例 ----

    @Test
    fun `用例1 buildConeSpec 半角与锥长按难度档正确换算`() {
        // v2 档：玩家来源（owner == 0）固定 v2，与难度档无关
        DifficultyTuningImpl.installScaleForTests(5f)
        val playerSpec = PiercingLanceConeStrike.buildConeSpec(
            stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 0)),
            directTarget = null,
            hitPoint = Vector2f(100f, 100f),
        )
        assertNotNull(playerSpec)
        assertEquals(25f, playerSpec.halfAngleDeg, 1e-3f, "v2 锥半角应为 50/2")
        assertEquals(375f, playerSpec.range, 1e-3f, "v2 锥长应为 375")

        // v5 档：敌方来源 + k_s = 5 → 破晓锚点
        DifficultyTuningImpl.installScaleForTests(5f)
        val enemySpec = PiercingLanceConeStrike.buildConeSpec(
            stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 1)),
            directTarget = null,
            hitPoint = Vector2f(100f, 100f),
        )
        assertNotNull(enemySpec)
        assertEquals(40f, enemySpec.halfAngleDeg, 1e-3f, "v5 锥半角应为 80/2")
        assertEquals(600f, enemySpec.range, 1e-3f, "v5 锥长应为 600")
    }

    @Test
    fun `用例2 buildConeSpec 破片与EMP同锚`() {
        // v2：2500 × 1.25 = 3125
        val v2 = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 0)),
                directTarget = null,
                hitPoint = Vector2f(0f, 0f),
            ),
        )
        assertEquals(3125f, v2.damage, 1e-3f)
        assertEquals(DamageType.FRAGMENTATION, v2.damageType)
        assertEquals(3125f, v2.empDamage, 1e-3f)

        // v5：2500 × 2.00 = 5000
        DifficultyTuningImpl.installScaleForTests(5f)
        val v5 = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 1)),
                directTarget = null,
                hitPoint = Vector2f(0f, 0f),
            ),
        )
        assertEquals(5000f, v5.damage, 1e-3f)
        assertEquals(5000f, v5.empDamage, 1e-3f)

        // v1：2500 × 1.00 = 2500
        DifficultyTuningImpl.installScaleForTests(1f)
        val v1 = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 1)),
                directTarget = null,
                hitPoint = Vector2f(0f, 0f),
            ),
        )
        assertEquals(2500f, v1.damage, 1e-3f)
        assertEquals(2500f, v1.empDamage, 1e-3f)
    }

    @Test
    fun `用例3 valueFor 玩家固定 v2`() {
        val player = stubShip(owner = 0)
        for (scale in listOf(1f, 5f)) {
            DifficultyTuningImpl.installScaleForTests(scale)
            assertEquals(50f, PiercingLanceDifficulty.valueFor(player, PiercingLanceDifficulty.CONE_ARC), 1e-3f, "k_s=$scale 玩家锥角恒 v2")
            assertEquals(375f, PiercingLanceDifficulty.valueFor(player, PiercingLanceDifficulty.CONE_RANGE), 1e-3f, "k_s=$scale 玩家锥长恒 v2")
            assertEquals(1.25f, PiercingLanceDifficulty.valueFor(player, PiercingLanceDifficulty.CONE_DAMAGE), 1e-3f, "k_s=$scale 玩家倍率恒 v2")
        }
    }

    @Test
    fun `用例4 valueFor 敌版走难度插值`() {
        val enemy = stubShip(owner = 1)

        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(80f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_ARC), 1e-3f)
        assertEquals(600f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_RANGE), 1e-3f)
        assertEquals(2.00f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_DAMAGE), 1e-3f)

        DifficultyTuningImpl.installScaleForTests(1f)
        assertEquals(40f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_ARC), 1e-3f)
        assertEquals(300f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_RANGE), 1e-3f)
        assertEquals(1.00f, PiercingLanceDifficulty.valueFor(enemy, PiercingLanceDifficulty.CONE_DAMAGE), 1e-3f)
    }

    @Test
    fun `用例5 buildConeSpec 命中本体豁免`() {
        val directTarget = stubShip(owner = 1)
        val spec = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3000f, 0f), stubShip(owner = 0)),
                directTarget = directTarget,
                hitPoint = Vector2f(0f, 0f),
            ),
        )
        assertFalse(spec.filter.accept(directTarget), "命中本体必须被 filter 豁免")
        assertTrue(spec.filter.accept(stubShip(owner = 1)), "其他目标应通过 filter")
    }

    @Test
    fun `用例6 buildConeSpec 命中矢量为弹体速度方向`() {
        val hitPoint = Vector2f(500f, -200f)
        val spec = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3f, 4f), stubShip(owner = 0)),
                directTarget = null,
                hitPoint = hitPoint,
            ),
        )
        assertEquals(0.6f, spec.direction.x, 1e-4f)
        assertEquals(0.8f, spec.direction.y, 1e-4f)
        assertEquals(1f, spec.direction.length(), 1e-4f, "命中矢量必须为单位矢量")
        assertSame(hitPoint, spec.origin, "锥顶点必须即命中点")
    }

    @Test
    fun `用例7 buildConeSpec 速度零向量回退 source 到 target 方向`() {
        val source = stubShip(owner = 0, location = Vector2f(100f, 100f))
        val target = stubShip(owner = 1, location = Vector2f(130f, 140f))
        val spec = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(0f, 0f), source),
                directTarget = target,
                hitPoint = Vector2f(130f, 140f),
            ),
        )
        assertEquals(0.6f, spec.direction.x, 1e-4f, "回退方向应为 source→target 归一化 (30,40)/50")
        assertEquals(0.8f, spec.direction.y, 1e-4f)
    }

    @Test
    fun `用例8 buildConeSpec 矢量不可得记 WARN 并放弃`() {
        val capture = WarnCapture(PiercingLanceConeStrike::class.java)
        warnCapture = capture

        // 速度零向量 + 无来源船（weapon 为 null）→ 两条路径均不可得
        val spec = PiercingLanceConeStrike.buildConeSpec(
            stubProjectile(Vector2f(0f, 0f), ship = null),
            directTarget = null,
            hitPoint = Vector2f(0f, 0f),
        )
        assertNull(spec, "矢量不可得必须放弃本次锥面结算")
        assertEquals(1, capture.events.size, "必须记 WARN 恰好一条（不静默吞机制）")
        assertTrue(capture.messages().first().contains("贯星之矛命中矢量不可得"), "WARN 内容应说明放弃原因")
    }

    @Test
    fun `用例9 buildConeSpec 来源为 null 时 owner 回退弹体归属`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val spec = assertNotNull(
            PiercingLanceConeStrike.buildConeSpec(
                stubProjectile(Vector2f(3000f, 0f), ship = null, owner = 1),
                directTarget = null,
                hitPoint = Vector2f(0f, 0f),
            ),
        )
        assertEquals(1, spec.owner, "source 缺失时 owner 必须回退弹体归属")
        assertNull(spec.source)
        assertEquals(600f, spec.range, 1e-3f, "无主弹体按敌版口径取值：k_s=5 锥长应为 600")
    }

    @Test
    fun `用例10 vfxSpec 登记可构建`() {
        assertTrue(ProjectileVfxSpecs.has("astd_piercing_lance_shot"), "贯星之矛弹体 VFX 未登记")
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_piercing_lance_shot"), "贯星之矛弹体 VFX 构建返回 null")
        assertEquals("astd_piercing_lance_shot", vfx.tree.id, "构建产物的 spec id 必须与登记键一致")
    }
}
