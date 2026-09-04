package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [AnchorArcComponent] 行为测试：桩引擎记录 spawnEmpArcVisual 调用——
 * 锚点在 attach 时捕获并固定、首次 advance 生成唯一一道弧（弹体侧绑弹体中心零偏移 +
 * 逐帧重算）、后续不再生成、非 Active 相位不生成。
 */
class AnchorArcComponentTest {

    private data class ArcRecord(
        val from: Vector2f,
        val to: Vector2f,
        val toAnchor: CombatEntityAPI?,
        val thickness: Float,
        val fringe: Color,
        val core: Color,
        val arc: EmpArcEntityAPI,
    )

    private val spec = AnchorArcSpec(
        thickness = 10f,
        fringeColor = ASTDColor(0.47f, 0.75f, 1f, 0.75f),
        coreColor = ASTDColor(0.94f, 0.97f, 1f, 0.94f),
    )

    private val projectile: DamagingProjectileAPI = mock(DamagingProjectileAPI::class.java).also {
        doReturn(Vector2f(400f, 260f)).`when`(it).location
    }

    /** 弹体宿主桩：暴露 projectile 供 toAnchor 绑定。 */
    private inner class StubProjectileHost : ProjectileHost {
        override val hostId = "test"
        override val projectile: DamagingProjectileAPI = this@AnchorArcComponentTest.projectile
    }

    /** 记录型桩引擎：登记电弧调用并返回 mock 电弧实体。 */
    private class StubEngine {
        val arcs = mutableListOf<ArcRecord>()
        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            doAnswer { inv ->
                val arc = mock(EmpArcEntityAPI::class.java)
                arcs += ArcRecord(
                    from = inv.getArgument(0),
                    to = inv.getArgument(2),
                    toAnchor = inv.getArgument(3),
                    thickness = inv.getArgument(4),
                    fringe = inv.getArgument(5),
                    core = inv.getArgument(6),
                    arc = arc,
                )
                arc
            }.`when`(engine).spawnEmpArcVisual(
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                anyFloat(), nullable(Color::class.java), nullable(Color::class.java),
            )
        }
    }

    private fun context(
        engine: CombatEngineAPI?,
        origin: Vector2f,
        phase: RenderPhase = RenderPhase.Active,
        host: RenderHost = StubProjectileHost(),
    ) = RenderContextImpl(
        engine = engine,
        host = host,
        frame = FrameStateImpl(
            elapsed = 1f,
            logicElapsed = 1f,
            amountThisFrame = 0.05f,
            origin = origin,
            facing = 0f,
            length = 0f,
            endpoint = null,
            worldUnitsPerPixel = 1f,
            active = phase == RenderPhase.Active,
            intensity = 1f,
            phase = phase,
            flightProgress = 0f,
            dissolve = 0f,
            fadeReason = null,
        ),
    )

    @Test
    fun `attach 捕获发射点为固定锚点，首次 advance 生成电弧到弹体中心`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)

        assertTrue(component.onAttach(context(stub.engine, Vector2f(100f, 200f))))
        component.advance(context(stub.engine, Vector2f(418f, 260f)), 0.05f)

        assertEquals(1, stub.arcs.size)
        val arc = stub.arcs.single()
        // 锚点 = attach 时的 origin（发射点侧），不随后续帧移动
        assertEquals(100f, arc.from.x, 1e-4f)
        assertEquals(200f, arc.from.y, 1e-4f)
        // 跟随端 = 弹体中心（零偏移烘焙，免疫首帧 facing 不一致导致的末端反转）
        assertEquals(400f, arc.to.x, 1e-4f)
        assertEquals(260f, arc.to.y, 1e-4f)
        assertEquals(10f, arc.thickness, 1e-4f)
        assertEquals(Color(119, 191, 255, 191), arc.fringe)
        assertEquals(Color(239, 247, 255, 239), arc.core)
        // 弹体侧绑弹体为锚实体 + 开启逐帧重算：弧存活期内实时拉伸
        assertSame(projectile, arc.toAnchor)
        verify(arc.arc).setUpdateFromOffsetEveryFrame(true)
    }

    @Test
    fun `只生成一次：后续 advance 不再铺弧`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)
        component.onAttach(context(stub.engine, Vector2f(0f, 0f)))

        component.advance(context(stub.engine, Vector2f(50f, 0f)), 0.05f)
        component.advance(context(stub.engine, Vector2f(100f, 0f)), 0.05f)
        component.advance(context(stub.engine, Vector2f(150f, 0f)), 0.5f)
        assertEquals(1, stub.arcs.size, "生成一次后不得再铺弧")
    }

    @Test
    fun `非 Active 相位不生成`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)
        component.onAttach(context(stub.engine, Vector2f(0f, 0f)))
        component.advance(context(stub.engine, Vector2f(50f, 0f), RenderPhase.FadingOut), 0.5f)
        assertEquals(0, stub.arcs.size, "FadingOut 相位不得铺弧")
    }

    @Test
    fun `非弹体宿主时跟随端不绑锚实体，端点取当前 origin`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)
        val plainHost = object : RenderHost { override val hostId = "plain" }
        component.onAttach(context(stub.engine, Vector2f(0f, 0f), host = plainHost))
        component.advance(context(stub.engine, Vector2f(50f, 0f), host = plainHost), 0.05f)

        assertEquals(1, stub.arcs.size)
        val arc = stub.arcs.single()
        assertNull(arc.toAnchor, "非 ProjectileHost 不得绑锚实体")
        assertEquals(50f, arc.to.x, 1e-4f)
    }

    @Test
    fun `无引擎上下文 attach 失败`() {
        val component = AnchorArcComponent("arc", spec)
        assertFalse(component.onAttach(context(null, Vector2f(0f, 0f))))
    }
}
