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
 * 坠星残翼脚本碰撞结算的真实逻辑验证（Mockito 桩 + 真 BuffHost + 真 MutableStat）：
 * - 船体穿透单点结算：拍到期一次 applyDamage，落点 = 射弹当前位置，伤害 = 20% 面板、
 *   EMP = 20% EMP 面板，bypassShields=true（装甲格分摊由原版装甲池承担，不逐格结算）；
 * - 主弹振频适应全局闩锁：重复结算只附加一次 1 层；
 * - 护盾接触行为：主弹恒穿盾（到期拍 20%+EMP、不移除弹体、拍外不结算）、
 *   子射弹撞盾全额面板 + 0.5 层 + 阻挡消散。
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
    fun `船体穿透单点结算 落点射弹当前位置 20% 面板与 EMP bypassShields`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val projLocation = Vector2f(120f, -45f)
        val proj = projectileOf(damage = 1000f, emp = 500f, at = projLocation)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = false)

        effect.settleHullPierce(engine, proj, state, ship)

        assertEquals(1, calls.size, "一拍对一个目标只结算一次（不逐格遍历装甲）")
        val call = calls.single()
        assertTrue(call.entity === ship, "结算实体必须是目标舰")
        assertEquals(200f, call.damage, 1e-4f, "单拍 20% 面板（1000×0.2）")
        assertEquals(100f, call.emp, 1e-4f, "单拍附带 20% EMP 面板（500×0.2）")
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
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = false)

        effect.settleHullPierce(engine, proj, state, ship)
        effect.settleHullPierce(engine, proj, state, ship)

        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(1f, buff?.stacks ?: -1f, 1e-4f, "同一枚主弹多次穿透结算只附加一次 1 层")
    }

    @Test
    fun `主弹护盾接触 恒穿盾 到期拍结算 20% 面板与 EMP 不移除弹体`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.radius).thenReturn(100f)
        `when`(ship.shield).thenReturn(shield)
        val proj = projectileOf(damage = 1000f, emp = 500f)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = false)

        // 拍外接触：不结算
        val blockedOutsideTick = effect.resolveShieldContact(engine, proj, state, ship, Vector2f(50f, 0f), tickDue = false)
        assertTrue(!blockedOutsideTick && calls.isEmpty(), "拍率限外的主弹盾接触不得结算")
        // 到期拍：穿盾结算
        val blocked = effect.resolveShieldContact(engine, proj, state, ship, Vector2f(50f, 0f), tickDue = true)

        assertTrue(!blocked, "主弹恒穿盾，不得阻挡移除")
        verify(engine, never()).removeEntity(proj)
        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals(200f, call.damage, 1e-4f, "穿盾拍 20% 面板")
        assertEquals(100f, call.emp, 1e-4f, "穿盾拍附带 20% EMP 面板")
        assertTrue(!call.bypassShield, "盾结算 bypassShield=false")
        assertEquals(100f, call.point.x, 1e-3f, "盾面落点 = 舰心沿命中方向外推盾半径")
        assertEquals(0f, call.point.y, 1e-3f)
        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(1f, buff?.stacks ?: -1f, 1e-4f, "首个接触目标附加 1 层振频适应")
    }

    @Test
    fun `子射弹船体接触 全额面板结算后阻挡消散 不附加振频适应`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val proj = projectileOf(damage = 200f, emp = 0f)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = true)
        val contact = Vector2f(30f, -10f)

        effect.resolveMoteHullBlock(engine, proj, ship, contact)

        verify(engine).removeEntity(proj)
        assertEquals(1, calls.size, "撞船体只结算一次（穿透权只归主弹）")
        val call = calls.single()
        assertEquals(200f, call.damage, 1e-4f, "子射弹撞船体结算全额面板（不吃穿透拍率限）")
        assertEquals(0f, call.emp, 1e-4f)
        assertTrue(!call.bypassShield, "撞盾路径同款裁定：bypassShield=false")
        assertEquals(30f, call.point.x, 1e-4f, "落点 = 首个船体接触点")
        assertEquals(-10f, call.point.y, 1e-4f)
        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertTrue(buff == null, "振频适应只在撞盾路径附加，撞船体不附加")
    }

    @Test
    fun `子射弹护盾接触 全额面板加半层并阻挡消散`() {
        val (engine, calls) = recordingEngine()
        val ship = stubTargetShip()
        val shield = mock(ShieldAPI::class.java)
        `when`(shield.radius).thenReturn(100f)
        `when`(ship.shield).thenReturn(shield)
        val proj = projectileOf(damage = 200f, emp = 0f)
        val state = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = true)

        val blocked = effect.resolveShieldContact(engine, proj, state, ship, Vector2f(50f, 0f), tickDue = false)

        assertTrue(blocked, "子射弹撞盾 = 阻挡消散")
        verify(engine).removeEntity(proj)
        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals(200f, call.damage, 1e-4f, "子射弹撞盾结算全额面板（不吃穿透拍率限）")
        assertEquals(0f, call.emp, 1e-4f)
        val buff = ship.getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
        assertEquals(0.5f, buff?.stacks ?: -1f, 1e-4f, "子射弹撞盾附加 0.5 层振频适应")
    }

    @Test
    fun `子射弹撞非舰船目标 全额面板结算后消散 主弹维持单次穿越穿透结算`() {
        val (engine, calls) = recordingEngine()
        val missile = mock(com.fs.starfarer.api.combat.MissileAPI::class.java)
        `when`(missile.location).thenReturn(Vector2f(50f, 0f))
        `when`(missile.collisionRadius).thenReturn(10f)

        // 子射弹：撞导弹 = 全额面板 + 0 EMP + 消散（穿透权只归主弹）
        val mote = projectileOf(damage = 200f, emp = 0f)
        val moteState = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = true)
        val moteHit = effect.pierceSimpleTarget(
            engine, mote, moteState, missile,
            Vector2f(40f, 0f), Vector2f(60f, 0f), 50f, 0f, 20f, 5f, HashSet(),
        )
        assertTrue(moteHit)
        verify(engine).removeEntity(mote)
        assertEquals(1, calls.size)
        assertEquals(200f, calls.single().damage, 1e-4f)
        assertEquals(0f, calls.single().emp, 1e-4f)
        assertTrue(!calls.single().bypassShield)

        // 主弹：同目标维持 20% 面板 + EMP 穿透结算，不消散；重复接触被单次穿越闩锁拦截
        calls.clear()
        val main = projectileOf(damage = 1000f, emp = 500f)
        val mainState = StarfallWingOnFireEffect.ProjectileState(stubWeapon("WS 001", "astd_starfall_wing"), isMote = false)
        val mainHit = effect.pierceSimpleTarget(
            engine, main, mainState, missile,
            Vector2f(40f, 0f), Vector2f(60f, 0f), 50f, 0f, 20f, 5f, HashSet(),
        )
        assertTrue(mainHit)
        verify(engine, never()).removeEntity(main)
        assertEquals(200f, calls.single().damage, 1e-4f)
        assertEquals(100f, calls.single().emp, 1e-4f)
        calls.clear()
        val again = effect.pierceSimpleTarget(
            engine, main, mainState, missile,
            Vector2f(40f, 0f), Vector2f(60f, 0f), 50f, 0f, 20f, 5f, HashSet(),
        )
        assertTrue(!again, "同一穿越中重复接触不再结算")
        assertEquals(0, calls.size)
    }
}
