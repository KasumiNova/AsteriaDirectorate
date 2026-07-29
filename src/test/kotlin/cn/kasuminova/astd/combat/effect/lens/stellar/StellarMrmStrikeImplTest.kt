package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.apache.log4j.Level
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规格 08 §4.1 用例 3/13/14/15（+面板异常防线补测）：桩引擎完整驱动
 * [StellarMrmStrikeImpl.strike]——断言 applyDamage 次数/数值/类型/EMP 量、
 * 逐武器电弧数、撞线 removeEntity 与爆炸恒触发。
 *
 * 直接命中目标存活时与区域内目标同额吃 AOE（对齐七星「hit 爆炸整体缩放」裁定口径，
 * 规格 §2.2 过滤式 `it !== target 已死体` 按「目标已死才豁免」解读）。
 */
class StellarMrmStrikeImplTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        warnCapture?.detach()
        warnCapture = null
    }

    private data class DamageRecord(
        val target: CombatEntityAPI,
        val point: Vector2f?,
        val amount: Float,
        val type: DamageType,
        val emp: Float,
        val bypass: Boolean,
    )

    private data class ArcRecord(val from: Vector2f, val to: Vector2f)

    /** 记录型桩引擎：customData 真实 map、inPlay 动态集合、applyDamage/电弧/底闪/插件全记录。 */
    private class StubEngineWorld {
        val customData = HashMap<String, Any?>()
        val inPlay = mutableSetOf<CombatEntityAPI>()
        val removed = mutableListOf<CombatEntityAPI>()
        val damages = mutableListOf<DamageRecord>()
        val arcs = mutableListOf<ArcRecord>()
        var explosionCount = 0
        var pluginCount = 0

        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            `when`(engine.customData).thenReturn(customData)
            `when`(engine.isEntityInPlay(any())).thenAnswer { inv -> inv.getArgument<CombatEntityAPI>(0) in inPlay }
            doAnswer { inv ->
                val entity = inv.getArgument<CombatEntityAPI>(0)
                removed += entity
                inPlay.remove(entity)
                null
            }.`when`(engine).removeEntity(any())
            doAnswer { inv ->
                damages += DamageRecord(
                    target = inv.getArgument(0),
                    point = inv.getArgument(1),
                    amount = inv.getArgument(2),
                    type = inv.getArgument(3),
                    emp = inv.getArgument(4),
                    bypass = inv.getArgument(5),
                )
                null
            }.`when`(engine).applyDamage(
                any(CombatEntityAPI::class.java), nullable(Vector2f::class.java), anyFloat(),
                any(DamageType::class.java), anyFloat(), anyBoolean(), anyBoolean(),
                nullable(Any::class.java), anyBoolean(),
            )
            doAnswer { inv ->
                arcs += ArcRecord(from = inv.getArgument(0), to = inv.getArgument(2))
                null
            }.`when`(engine).spawnEmpArcVisual(
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                anyFloat(), nullable(Color::class.java), nullable(Color::class.java),
            )
            doAnswer { explosionCount++; null }.`when`(engine).spawnExplosion(
                nullable(Vector2f::class.java), nullable(Vector2f::class.java),
                nullable(Color::class.java), anyFloat(), anyFloat(),
            )
            doAnswer { pluginCount++; null }.`when`(engine).addPlugin(any())
        }
    }

    private fun stubProjectile(maxHitpoints: Float = 200f, panel: Float = 100f, owner: Int = 0): DamagingProjectileAPI {
        val p = mock(DamagingProjectileAPI::class.java)
        `when`(p.damageAmount).thenReturn(panel)
        `when`(p.maxHitpoints).thenReturn(maxHitpoints)
        `when`(p.owner).thenReturn(owner)
        `when`(p.source).thenReturn(null)
        `when`(p.projectileSpecId).thenReturn("astd_stellar_mrm_launcher_shot")
        `when`(p.location).thenReturn(Vector2f(0f, 0f))
        return p
    }

    /** 武器桩：currHealth 真实可变（setCurrHealth 写入可读回），供 EMP 逐武器扣减断言。 */
    private fun stubWeapon(x: Float, disabled: Boolean, maxHealth: Float = 300f): WeaponAPI {
        val w = mock(WeaponAPI::class.java)
        var health = maxHealth
        `when`(w.isDisabled).thenReturn(disabled)
        `when`(w.location).thenReturn(Vector2f(x, 0f))
        `when`(w.maxHealth).thenReturn(maxHealth)
        `when`(w.currHealth).thenAnswer { health }
        doAnswer { inv -> health = inv.getArgument(0); null }.`when`(w).setCurrHealth(anyFloat())
        return w
    }

    private fun stubFighter(x: Float, weapons: List<WeaponAPI>): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.owner).thenReturn(1)
        `when`(s.location).thenReturn(Vector2f(x, 0f))
        `when`(s.isFighter).thenReturn(true)
        `when`(s.isHulk).thenReturn(false)
        `when`(s.isPhased).thenReturn(false)
        `when`(s.isAlive).thenReturn(true)
        `when`(s.allWeapons).thenReturn(weapons)
        `when`(s.shield).thenReturn(null)
        return s
    }

    private fun stubShip(x: Float): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.owner).thenReturn(1)
        `when`(s.location).thenReturn(Vector2f(x, 0f))
        `when`(s.isFighter).thenReturn(false)
        `when`(s.isHulk).thenReturn(false)
        `when`(s.isPhased).thenReturn(false)
        `when`(s.isAlive).thenReturn(true)
        `when`(s.shield).thenReturn(null)
        return s
    }

    private fun stubMissile(x: Float, hitpoints: Float): MissileAPI {
        val m = mock(MissileAPI::class.java)
        `when`(m.owner).thenReturn(1)
        `when`(m.location).thenReturn(Vector2f(x, 0f))
        `when`(m.hitpoints).thenReturn(hitpoints)
        `when`(m.isExpired).thenReturn(false)
        return m
    }

    private fun strike(
        world: StubEngineWorld,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        shieldHit: Boolean,
        victims: List<CombatEntityAPI>,
        point: Vector2f = Vector2f(500f, 0f),
    ) {
        StellarMrmStrikeImpl.strike(
            world.engine, projectile, target, point, shieldHit,
        ) { _, _ -> victims }
    }

    @Test
    fun `用例3 maxHitpoints为0防线：阈值0恒不触发、恰好一次WARN`() {
        assertEquals(0f, StellarMrmStrikeMath.lineCrossThreshold(0f, 3f), "maxHitpoints=0 → 阈值 0")
        assertEquals(0f, StellarMrmStrikeMath.lineCrossThreshold(-1f, 3f), "maxHitpoints<0 → 阈值 0")

        val capture = WarnCapture(StellarMrmStrikeImpl::class.java)
        warnCapture = capture
        val world = StubEngineWorld()
        val target = stubMissile(x = 500f, hitpoints = 350f).also { world.inPlay += it }
        val projectile = stubProjectile(maxHitpoints = 0f)

        strike(world, projectile, target, shieldHit = false, victims = emptyList())
        strike(world, projectile, target, shieldHit = false, victims = emptyList())

        assertTrue(world.removed.isEmpty(), "阈值 0 → 撞线者死恒不触发")
        val warns = capture.events.filter { it.level == Level.WARN && it.renderedMessage.contains("撞线者死机制失效") }
        assertEquals(1, warns.size, "maxHitpoints=0 应每引擎恰好 WARN 一次（customData 去重）")
    }

    @Test
    fun `用例13 战机命中机体：增伤1次加EMP1次加电弧存活武器数加AOE同额`() {
        val world = StubEngineWorld()
        val w1 = stubWeapon(510f, disabled = false)
        val w2 = stubWeapon(490f, disabled = false)
        val w3 = stubWeapon(520f, disabled = true)
        val fighter = stubFighter(500f, listOf(w1, w2, w3)).also { world.inPlay += it }
        val bystander = stubShip(530f).also { world.inPlay += it }
        val projectile = stubProjectile()
        val hitPoint = Vector2f(505f, 8f)

        strike(world, projectile, fighter, shieldHit = false, victims = listOf(fighter, bystander), point = hitPoint)

        // 玩家 owner=0 恒 v2：增伤 100×1.0、AOE 100×1.0；EMP 100×4 走逐武器 setCurrHealth。
        assertEquals(3, world.damages.size, "增伤 + AOE(存活直击目标同额) + AOE(波及舰)；EMP 不占引擎伤害通路")
        val bonus = world.damages[0]
        assertEquals(fighter, bonus.target); assertEquals(100f, bonus.amount)
        assertEquals(DamageType.ENERGY, bonus.type); assertEquals(0f, bonus.emp)
        assertEquals(500f, bonus.point?.x, "增伤落点 = 舰心（七星判例口径）")
        val aoeDirect = world.damages[1]
        assertEquals(fighter, aoeDirect.target); assertEquals(100f, aoeDirect.amount)
        val aoeBystander = world.damages[2]
        assertEquals(bystander, aoeBystander.target); assertEquals(100f, aoeBystander.amount)
        assertTrue(world.damages.all { it.bypass }, "目标无盾 → bypassShields=true（七星实机判例口径）")

        // 全部武器 EMP：存活武器各扣 400（300 上限扣至 0 触发原版熄火），已瘫痪武器跳过。
        assertEquals(0f, w1.currHealth, "w1 存活武器 300-400 扣至 0（原版组件熄火阈值）")
        assertEquals(0f, w2.currHealth, "w2 存活武器 300-400 扣至 0")
        assertEquals(300f, w3.currHealth, "w3 已瘫痪武器跳过 EMP 扣减")

        assertEquals(2, world.arcs.size, "逐武器电弧 = 存活武器数（w3 瘫痪跳过）")
        assertTrue(world.arcs.any { it.to.x == 510f } && world.arcs.any { it.to.x == 490f }, "电弧锚到各存活武器槽位")

        assertEquals(1, world.explosionCount, "spawnExplosion 紫闪底一次")
        assertEquals(1, world.pluginCount, "十字辉星爆炸 VFX 插件一次")
        assertTrue(world.removed.isEmpty(), "命中战机不触发撞线移除")
    }

    @Test
    fun `用例14 命中低结构敌导弹：removeEntity被撞线移除且爆炸照常`() {
        val world = StubEngineWorld()
        val target = stubMissile(x = 500f, hitpoints = 350f).also { world.inPlay += it }
        val otherMissile = stubMissile(x = 520f, hitpoints = 900f).also { world.inPlay += it }
        val projectile = stubProjectile()

        strike(world, projectile, target, shieldHit = false, victims = listOf(target, otherMissile))

        assertEquals(listOf<CombatEntityAPI>(target), world.removed, "hp=350 < 阈值 600（v2）→ removeEntity 必定摧毁")
        assertEquals(1, world.damages.size, "被移除目标豁免 AOE，区域内另一枚敌导弹照常结算")
        assertEquals(otherMissile, world.damages[0].target)
        assertEquals(100f, world.damages[0].amount)
        assertEquals(DamageType.ENERGY, world.damages[0].type)
        assertEquals(1, world.explosionCount, "辉星爆炸恒执行")
        assertEquals(1, world.pluginCount, "十字 VFX 照常（同帧十字爆炸即撞线视觉）")
        assertTrue(world.arcs.isEmpty(), "导弹命中无逐武器电弧")
    }

    @Test
    fun `用例15 护盾命中战机：猎机本能不触发、爆炸触发`() {
        val world = StubEngineWorld()
        val fighter = stubFighter(500f, listOf(stubWeapon(510f, disabled = false))).also { world.inPlay += it }
        val projectile = stubProjectile()

        strike(world, projectile, fighter, shieldHit = true, victims = listOf(fighter))

        assertEquals(1, world.damages.size, "护盾命中无增伤/EMP，仅 AOE 一次")
        assertEquals(fighter, world.damages[0].target)
        assertEquals(100f, world.damages[0].amount)
        assertEquals(0f, world.damages[0].emp)
        assertTrue(world.arcs.isEmpty(), "护盾命中无逐武器电弧")
        assertEquals(1, world.explosionCount, "爆炸恒触发")
        assertEquals(1, world.pluginCount)
    }

    @Test
    fun `补测 面板值异常：WARN加整体跳过（规格2-4防线）`() {
        val capture = WarnCapture(StellarMrmStrikeImpl::class.java)
        warnCapture = capture
        val world = StubEngineWorld()
        val fighter = stubFighter(500f, emptyList()).also { world.inPlay += it }

        strike(world, stubProjectile(panel = 0f), fighter, shieldHit = false, victims = listOf(fighter))
        strike(world, stubProjectile(panel = Float.NaN), fighter, shieldHit = false, victims = listOf(fighter))

        assertTrue(world.damages.isEmpty(), "panel≤0/NaN 时附加机制全部跳过")
        assertTrue(world.removed.isEmpty())
        assertEquals(0, world.explosionCount)
        val warns = capture.events.filter { it.level == Level.WARN && it.renderedMessage.contains("面板值异常") }
        assertEquals(2, warns.size, "两次异常各 WARN 一次（不静默）")
    }
}
