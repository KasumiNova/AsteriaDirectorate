package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.apache.log4j.Level
import org.lazywizard.lazylib.MathUtils
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
 * 桩引擎完整驱动 [CuifengTorpedoStrikeImpl.strike]：直击自适应增伤（辐能 + 舰体，
 * 玩家 owner=0 恒取 v2）、护盾命中硬辐推进（紫色浮字）、全额面板 AOE（存活直击目标豁免）、
 * 模块舰减半口径与面板异常防线。
 *
 * VFX 步骤在桩引擎下安全空转：粒子 API 走 Mockito 默认桩，十字辉星在无 GL 环境
 * 由 try/catch 降级跳过（不影响结算断言）。
 */
class CuifengTorpedoStrikeImplTest {
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
        val bypass: Boolean,
    )

    private data class FloatyRecord(val text: String, val color: Color, val attachedTo: CombatEntityAPI?)

    /** 记录型桩引擎：customData 真实 map、inPlay 动态集合、applyDamage/浮字全记录。 */
    private class StubEngineWorld {
        val customData = HashMap<String, Any?>()
        val inPlay = mutableSetOf<CombatEntityAPI>()
        val damages = mutableListOf<DamageRecord>()
        val floaties = mutableListOf<FloatyRecord>()

        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            `when`(engine.customData).thenReturn(customData)
            `when`(engine.isEntityInPlay(any())).thenAnswer { inv -> inv.getArgument<CombatEntityAPI>(0) in inPlay }
            doAnswer { inv ->
                damages += DamageRecord(
                    target = inv.getArgument(0),
                    point = inv.getArgument(1),
                    amount = inv.getArgument(2),
                    type = inv.getArgument(3),
                    bypass = inv.getArgument(5),
                )
                null
            }.`when`(engine).applyDamage(
                any(CombatEntityAPI::class.java), nullable(Vector2f::class.java), anyFloat(),
                any(DamageType::class.java), anyFloat(), anyBoolean(), anyBoolean(),
                nullable(Any::class.java), anyBoolean(),
            )
            doAnswer { inv ->
                floaties += FloatyRecord(
                    text = "+${inv.getArgument<Float>(1).toInt()}",
                    color = inv.getArgument(2),
                    attachedTo = inv.getArgument(3),
                )
                null
            }.`when`(engine).addFloatingDamageText(
                nullable(Vector2f::class.java), anyFloat(), nullable(Color::class.java),
                nullable(CombatEntityAPI::class.java), nullable(CombatEntityAPI::class.java),
            )
        }
    }

    private fun stubProjectile(panel: Float = 1500f, owner: Int = 0): DamagingProjectileAPI {
        val p = mock(DamagingProjectileAPI::class.java)
        `when`(p.damageAmount).thenReturn(panel)
        `when`(p.owner).thenReturn(owner)
        `when`(p.source).thenReturn(null)
        `when`(p.projectileSpecId).thenReturn("astd_cuifeng_torpedo_shot")
        `when`(p.location).thenReturn(Vector2f(500f, 0f))
        return p
    }

    /** 辐能追踪器桩：maxFlux/currFlux 固定返回值，increaseFlux 全记录。 */
    private class StubFlux(val increases: MutableList<Pair<Float, Boolean>> = mutableListOf()) {
        val tracker: FluxTrackerAPI = mock(FluxTrackerAPI::class.java)

        fun config(curr: Float, max: Float): StubFlux {
            `when`(tracker.currFlux).thenReturn(curr)
            `when`(tracker.maxFlux).thenReturn(max)
            doAnswer { inv ->
                increases += (inv.getArgument<Float>(0) to inv.getArgument<Boolean>(1))
                null
            }.`when`(tracker).increaseFlux(anyFloat(), anyBoolean())
            return this
        }
    }

    private fun stubMember(dp: Float): FleetMemberAPI {
        val m = mock(FleetMemberAPI::class.java)
        `when`(m.unmodifiedDeploymentPointsCost).thenReturn(dp)
        return m
    }

    private fun stubShip(
        x: Float,
        owner: Int = 1,
        hullSize: ShipAPI.HullSize = ShipAPI.HullSize.CRUISER,
        dp: Float = 20f,
        flux: StubFlux = StubFlux().config(0f, 10000f),
        isFighter: Boolean = false,
        parentStation: ShipAPI? = null,
        shield: ShieldAPI? = null,
    ): ShipAPI {
        val s = mock(ShipAPI::class.java)
        val member = stubMember(dp)
        `when`(s.owner).thenReturn(owner)
        `when`(s.location).thenReturn(Vector2f(x, 0f))
        `when`(s.isFighter).thenReturn(isFighter)
        `when`(s.isHulk).thenReturn(false)
        `when`(s.isPhased).thenReturn(false)
        `when`(s.hullSize).thenReturn(hullSize)
        `when`(s.fleetMember).thenReturn(member)
        `when`(s.fluxTracker).thenReturn(flux.tracker)
        `when`(s.parentStation).thenReturn(parentStation)
        `when`(s.shield).thenReturn(shield)
        return s
    }

    private fun stubMissile(x: Float, owner: Int = 1, expired: Boolean = false): MissileAPI {
        val m = mock(MissileAPI::class.java)
        `when`(m.owner).thenReturn(owner)
        `when`(m.location).thenReturn(Vector2f(x, 0f))
        `when`(m.isExpired).thenReturn(expired)
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
        CuifengTorpedoStrikeImpl.strike(world.engine, projectile, target, point, shieldHit) { _, _ -> victims }
    }

    @Test
    fun `巡洋舰直击：辐能自适应加舰体自适应合并一笔 ENERGY，无盾 bypass 落舰心`() {
        val world = StubEngineWorld()
        // 辐能 65%（系数 0.5）、部署点 20 = 巡洋基准（差值 0）；玩家 owner=0 恒 v2：x=0.5、档位 40%
        val target = stubShip(500f, flux = StubFlux().config(6500f, 10000f)).also { world.inPlay += it }
        strike(world, stubProjectile(), target, shieldHit = false, victims = emptyList())

        assertEquals(1, world.damages.size, "自适应增伤合并为一笔（辐能 375 + 舰体 600 = 975）")
        val bonus = world.damages[0]
        assertEquals(target, bonus.target)
        assertEquals(975f, bonus.amount, 1e-3f)
        assertEquals(DamageType.ENERGY, bonus.type)
        assertTrue(bonus.bypass, "目标无盾 → bypassShields=true（七星/辉星判例口径）")
        assertEquals(500f, bonus.point?.x, "无盾落点 = 舰心")
        assertEquals(1, world.customData[CuifengTorpedoStrikeImpl.TELE_ADAPTIVE_HITS])
        assertEquals(
            975f,
            (world.customData[CuifengTorpedoStrikeImpl.TELE_LAST_ADAPTIVE_BONUS + CuifengTorpedoStrikeImpl.TELE_OWNER_PLAYER] as Float),
            1e-3f,
        )
        assertTrue(world.floaties.isEmpty(), "非护盾命中不触发硬辐浮字")
    }

    @Test
    fun `护盾命中战机：硬辐推进最大辐能 4% 并紫色浮字，无自适应增伤`() {
        val world = StubEngineWorld()
        // 战机跳过舰体自适应；辐能 0 → 辐能自适应 0 → 步骤 1 整笔不发生
        val flux = StubFlux().config(0f, 10000f)
        val fighter = stubShip(500f, flux = flux, isFighter = true).also { world.inPlay += it }
        strike(world, stubProjectile(), fighter, shieldHit = true, victims = emptyList())

        assertTrue(world.damages.isEmpty(), "辐能 0 且战机无舰体自适应 → 直击增伤不结算")
        assertEquals(1, world.customData[CuifengTorpedoStrikeImpl.TELE_HARD_FLUX_PUSHES])
        assertEquals(
            400f,
            (world.customData[CuifengTorpedoStrikeImpl.TELE_LAST_HARD_FLUX + CuifengTorpedoStrikeImpl.TELE_OWNER_PLAYER] as Float),
            1e-3f,
            "硬辐推进 = 最大辐能 10000 × v2 4%",
        )
        assertEquals(1, world.floaties.size, "硬辐推进紫色浮字")
        assertEquals("+400", world.floaties[0].text)
        assertEquals(Color(200, 130, 255), world.floaties[0].color, "设计案「显示为紫色值」")
        assertEquals(fighter, world.floaties[0].attachedTo)
        assertEquals(listOf(400f to true), flux.increases, "强制硬辐 increaseFlux(amount, true)")
    }

    @Test
    fun `AOE：存活直击目标豁免、同阵营与残骸与过期导弹跳过、波及敌舰全额面板`() {
        val world = StubEngineWorld()
        val projectile = stubProjectile()
        val target = stubMissile(500f).also { world.inPlay += it }
        val bystander = stubShip(560f).also { world.inPlay += it }
        val friendly = stubShip(570f, owner = 0).also { world.inPlay += it }
        val expiredMissile = stubMissile(580f, expired = true).also { world.inPlay += it }
        val hulk = stubShip(590f).also {
            `when`(it.isHulk).thenReturn(true)
            world.inPlay += it
        }
        val victims = listOf<CombatEntityAPI>(projectile, target, bystander, friendly, expiredMissile, hulk)

        strike(world, projectile, target, shieldHit = false, victims = victims)

        assertEquals(1, world.damages.size, "仅波及敌舰一艘（直击导弹存活豁免、弹体自身/友军/过期弹/残骸跳过）")
        val aoe = world.damages[0]
        assertEquals(bystander, aoe.target)
        assertEquals(1500f, aoe.amount, 1e-3f, "AOE 全额面板（不缩放）")
        assertEquals(DamageType.ENERGY, aoe.type)
        assertTrue(aoe.bypass, "波及舰无盾 → bypassShields=true")
        assertEquals(560f, aoe.point?.x, "无盾落点 = 波及舰舰心")
        assertEquals(1, world.customData[CuifengTorpedoStrikeImpl.TELE_AOE_HITS])
        assertEquals(1, world.customData[CuifengTorpedoStrikeImpl.TELE_AOE_SHIP_HITS])
    }

    @Test
    fun `AOE：直击目标本帧已死不再豁免，残骸外照常结算`() {
        val world = StubEngineWorld()
        val target = stubMissile(500f) // 不在 inPlay：本帧已被直击面板击毁
        val bystander = stubShip(560f).also { world.inPlay += it }

        strike(world, stubProjectile(), target, shieldHit = false, victims = listOf(target, bystander))

        assertEquals(2, world.damages.size, "已死直击目标不再豁免（残骸不参与结算的口径按 inPlay 判定）")
        assertEquals(target, world.damages[0].target)
        assertEquals(bystander, world.damages[1].target)
    }

    @Test
    fun `模块舰：舰体自适应取主舰体部署点并减半`() {
        val world = StubEngineWorld()
        // 主舰体巡洋、部署点 30（超基准 10 点）：1500 × (0.4 + 10×0.04) = 1200，模块减半 → 600
        val parent = stubShip(480f, hullSize = ShipAPI.HullSize.CRUISER, dp = 30f)
        val module = stubShip(500f, hullSize = ShipAPI.HullSize.FRIGATE, parentStation = parent)
            .also { world.inPlay += it }

        strike(world, stubProjectile(), module, shieldHit = false, victims = emptyList())

        assertEquals(1, world.damages.size)
        val bonus = world.damages[0]
        assertEquals(module, bonus.target)
        assertEquals(600f, bonus.amount, 1e-2f, "模块舰：主舰体口径舰体自适应 1200 × 0.5")
    }

    @Test
    fun `盾覆盖时增伤尊重护盾：bypass 关闭且落点在盾面`() {
        val world = StubEngineWorld()
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.isOn).thenReturn(true)
        `when`(shield.isWithinArc(any())).thenReturn(true)
        `when`(shield.location).thenReturn(Vector2f(500f, 0f))
        `when`(shield.radius).thenReturn(100f)
        val target = stubShip(500f, shield = shield).also { world.inPlay += it }
        val hitPoint = Vector2f(600f, 0f)

        strike(world, stubProjectile(), target, shieldHit = true, victims = emptyList(), point = hitPoint)

        assertEquals(1, world.damages.size)
        val bonus = world.damages[0]
        assertTrue(!bonus.bypass, "盾覆盖 → bypassShields=false（尊重护盾）")
        val expected = MathUtils.getPointOnCircumference(Vector2f(500f, 0f), 100f, 0f)
        assertEquals(expected.x, bonus.point?.x ?: Float.NaN, 1e-3f, "落点 = 盾面（七星/辉星判例口径）")
        assertEquals(expected.y, bonus.point?.y ?: Float.NaN, 1e-3f)
    }

    @Test
    fun `面板值异常防线：WARN 且附加机制全部跳过`() {
        val capture = WarnCapture(CuifengTorpedoStrikeImpl::class.java)
        warnCapture = capture
        val world = StubEngineWorld()
        val target = stubShip(500f).also { world.inPlay += it }

        strike(world, stubProjectile(panel = 0f), target, shieldHit = true, victims = listOf(target))
        strike(world, stubProjectile(panel = Float.NaN), target, shieldHit = true, victims = listOf(target))

        assertTrue(world.damages.isEmpty(), "panel≤0/NaN 时直击/AOE 全部跳过")
        assertTrue(world.floaties.isEmpty(), "面板异常时硬辐推进不触发")
        assertTrue(world.customData.isEmpty(), "面板异常时无任何遥测计数")
        val warns = capture.events.filter { it.level == Level.WARN && it.renderedMessage.contains("面板值异常") }
        assertEquals(2, warns.size, "两次异常各 WARN 一次（不静默）")
    }
}
