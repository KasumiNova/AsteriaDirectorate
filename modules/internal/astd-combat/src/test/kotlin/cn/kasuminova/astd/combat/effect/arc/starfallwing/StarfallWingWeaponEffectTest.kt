package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.impl.buff.BuffInstall
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShieldAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 坠星残翼脚本碰撞结算与子射弹 onHit 钩子的真实逻辑验证（Mockito 桩 + 真 BuffHost + 真 MutableStat）：
 * - 船体穿透单点结算：拍到期一次 applyDamage，落点 = 射弹当前位置，伤害/EMP = 面板×穿透比例
 *   （bypassShields=true；装甲格分摊由原版装甲池承担，不逐格结算）；
 * - 主弹振频适应全局闩锁：重复结算只附加一次 1 层；
 * - 护盾接触行为：主弹恒穿盾（到期拍结算面板×穿透比例 + EMP、不移除弹体、拍外不结算）；
 * - 子射弹原版碰撞的 onHit 钩子：撞盾（shieldHit=true）附加 0.5 层振频适应，
 *   撞船体（shieldHit=false）不附加；伤害本身由原版结算，脚本不触碰。
 */
class StarfallWingWeaponEffectTest {

    private val effect = StarfallWingWeaponEffect()

    @BeforeTest
    fun installBuffBackend() {
        // buffHost() 扩展走 api 侧 BuffBackends 桥：测试环境装真后端（引擎为 null 时跳过心跳登记）。
        BuffInstall.install()
    }

    /** 一次 applyDamage 调用的捕获记录。 */
    private data class DamageCall(
        val entity: CombatEntityAPI,
        val point: Vector2f,
        val damage: Float,
        val type: DamageType,
        val emp: Float,
        val bypassShield: Boolean,
    )

    private fun recordingEngine(): Pair<CombatEngineAPI, MutableList<DamageCall>> {
        val calls = mutableListOf<DamageCall>()
        val engine = mock(CombatEngineAPI::class.java)
        doAnswer { inv ->
            calls += DamageCall(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                inv.getArgument(3), inv.getArgument(4), inv.getArgument(5),
            )
            null
        }.`when`(engine).applyDamage(
            any(), any(), anyFloat(), any(), anyFloat(), anyBoolean(), anyBoolean(), any(), anyBoolean(),
        )
        doAnswer { mock(EmpArcEntityAPI::class.java) }.`when`(engine).spawnEmpArcVisual(
            any(), any(), any(), any(), anyFloat(), any(), any(),
        )
        return engine to calls
    }

    private fun projectileOf(damage: Float, emp: Float, at: Vector2f = Vector2f(120f, -45f)): DamagingProjectileAPI {
        val proj = mock(DamagingProjectileAPI::class.java)
        `when`(proj.damageAmount).thenReturn(damage)
        `when`(proj.empAmount).thenReturn(emp)
        `when`(proj.location).thenReturn(at)
        return proj
    }

    /** 带护盾承伤 stat 的 stub 船：facing 0、舰心原点、承伤比 base 0.7。 */
    private fun stubTargetShip(): com.fs.starfarer.api.combat.ShipAPI {
        val ship = stubShip()
        `when`(ship.location).thenReturn(Vector2f(0f, 0f))
        `when`(ship.facing).thenReturn(0f)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.shieldDamageTakenMult).thenReturn(MutableStat(0.7f))
        `when`(ship.mutableStats).thenReturn(stats)
        return ship
    }

    @Test
    fun `船体穿透单点结算 落点射弹当前位置 穿透比例面板与 EMP bypassShields`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val projLocation = Vector2f(120f, -45f)
        val proj = projectileOf(damage = 1000f, emp = 500f, at = projLocation)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"))

        effect.settleHullPierce(engine, proj, state, ship)

        assertEquals(1, calls.size, "一拍对一个目标只结算一次（不逐格遍历装甲）")
        val call = calls.single()
        assertTrue(call.entity === ship, "结算实体必须是目标舰")
        // 期望从 tuning 纯函数派生（比例数值是调参面，不硬编码）
        assertEquals(StarfallWingTuning.pierceTickDamage(1000f), call.damage, 1e-4f, "单拍伤害 = 面板×穿透比例")
        assertEquals(StarfallWingTuning.pierceTickEmp(500f), call.emp, 1e-4f, "单拍 EMP = EMP 面板×穿透比例")
        assertEquals(DamageType.ENERGY, call.type)
        assertTrue(call.bypassShield, "穿船体结算 bypassShield=true（只跳过引擎护盾弧判定，装甲池结算照走）")
        assertEquals(projLocation.x, call.point.x, 1e-4f, "落点 = 射弹当前位置")
        assertEquals(projLocation.y, call.point.y, 1e-4f)
    }

    @Test
    fun `主弹振频适应全局闩锁 重复穿透结算只附加一次 1 层`() {
        val (engine, _) = recordingEngine()
        val ship = stubTargetShip()
        val proj = projectileOf(damage = 1000f, emp = 500f)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"))

        effect.settleHullPierce(engine, proj, state, ship)
        effect.settleHullPierce(engine, proj, state, ship)

        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(1f, buff?.stacks ?: -1f, 1e-4f, "同一枚主弹多次穿透结算只附加一次 1 层")
    }

    @Test
    fun `主弹护盾接触 恒穿盾 到期拍结算穿透比例面板与 EMP 不移除弹体`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.radius).thenReturn(100f)
        `when`(ship.shield).thenReturn(shield)
        val proj = projectileOf(damage = 1000f, emp = 500f)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"))

        // 拍外接触：不结算
        val settledOutsideTick = effect.resolveShieldContact(engine, proj, state, ship, Vector2f(50f, 0f), tickDue = false)
        assertTrue(!settledOutsideTick && calls.isEmpty(), "拍率限外的主弹盾接触不得结算")
        // 到期拍：穿盾结算
        val settled = effect.resolveShieldContact(engine, proj, state, ship, Vector2f(50f, 0f), tickDue = true)

        assertTrue(settled, "到期拍必须结算")
        verify(engine, never()).removeEntity(proj)
        assertEquals(1, calls.size)
        val call = calls.single()
        // 期望从 tuning 纯函数派生（比例数值是调参面，不硬编码）
        assertEquals(StarfallWingTuning.pierceTickDamage(1000f), call.damage, 1e-4f, "穿盾拍 = 面板×穿透比例")
        assertEquals(StarfallWingTuning.pierceTickEmp(500f), call.emp, 1e-4f, "穿盾拍附带 EMP 面板×穿透比例")
        assertTrue(!call.bypassShield, "盾结算 bypassShield=false")
        assertEquals(100f, call.point.x, 1e-3f, "盾面落点 = 舰心沿命中方向外推盾半径")
        assertEquals(0f, call.point.y, 1e-3f)
        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(1f, buff?.stacks ?: -1f, 1e-4f, "首个接触目标附加 1 层振频适应")
    }

    @Test
    fun `子射弹原版碰撞 onHit 撞盾附加半层振频适应 撞船体不附加`() {
        val (engine, _) = recordingEngine()
        val ship = stubTargetShip()
        val onHit = StarfallWingMoteOnHitEffect()
        val proj = projectileOf(damage = 200f, emp = 0f)
        val impact = mock(com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI::class.java)

        // 撞船体：不附加（伤害由原版碰撞结算，脚本不触碰）
        onHit.onHit(proj, ship, Vector2f(30f, -10f), false, impact, engine)
        assertTrue(
            ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) == null,
            "撞船体不附加振频适应",
        )

        // 撞盾：附加 0.5 层
        onHit.onHit(proj, ship, Vector2f(50f, 0f), true, impact, engine)
        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(0.5f, buff?.stacks ?: -1f, 1e-4f, "子射弹撞盾附加 0.5 层振频适应")
        // 重复撞盾可继续叠加（无弹体级闩锁，每枚子射弹独立一次原版碰撞）
        onHit.onHit(proj, ship, Vector2f(50f, 0f), true, impact, engine)
        assertEquals(1f, buff?.stacks ?: -1f, 1e-4f, "两次撞盾累计 1 层")
    }

    @Test
    fun `主弹撞非舰船目标 维持单次穿越穿透结算`() {
        val (engine, calls) = recordingEngine()
        val missile = mock(com.fs.starfarer.api.combat.MissileAPI::class.java)
        `when`(missile.location).thenReturn(Vector2f(50f, 0f))
        `when`(missile.collisionRadius).thenReturn(10f)

        // 主弹：穿透比例面板 + EMP 穿透结算，不消散；重复接触被单次穿越闩锁拦截
        val main = projectileOf(damage = 1000f, emp = 500f)
        val mainState = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"))
        val mainHit = effect.pierceSimpleTarget(
            engine, main, mainState, missile,
            Vector2f(40f, 0f), Vector2f(60f, 0f), 50f, 0f, 20f, 5f, HashSet(),
        )
        assertTrue(mainHit)
        verify(engine, never()).removeEntity(main)
        // 期望从 tuning 纯函数派生（比例数值是调参面，不硬编码）
        assertEquals(StarfallWingTuning.pierceTickDamage(1000f), calls.single().damage, 1e-4f)
        assertEquals(StarfallWingTuning.pierceTickEmp(500f), calls.single().emp, 1e-4f)
        calls.clear()
        val again = effect.pierceSimpleTarget(
            engine, main, mainState, missile,
            Vector2f(40f, 0f), Vector2f(60f, 0f), 50f, 0f, 20f, 5f, HashSet(),
        )
        assertTrue(!again, "同一穿越中重复接触不再结算")
        assertEquals(0, calls.size)
    }
}
