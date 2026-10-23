package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderLayer
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectileVfxDriverTest {

    private val policy = ProjectileVfxDriverPolicy(
        hitFadeOutSeconds = 0.2f,
        expireFadeOutSeconds = 0.3f,
        removedFadeOutSeconds = 0.1f,
    )

    private fun driver(tree: RenderEntity): ProjectileVfxDriverImpl {
        val host = object : RenderHost {
            override val hostId = "test"
        }
        return ProjectileVfxDriverImpl(host, tree, policy)
    }

    @Test
    fun `每帧产出的 FrameState 跟随宿主位置与位移朝向`() {
        val rec = RecordingNode()
        val d = driver(rec)

        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(50f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, 0f, 0f, 0.1f, alive = true)

        val frame = assertNotNull(rec.lastFrame)
        assertEquals(100f, frame.origin.x, 0.01f)
        assertEquals(0f, frame.origin.y, 0.01f)
        assertEquals(0f, frame.facing, 0.5f)
        assertEquals(1f, frame.intensity, 1e-4f)
        assertEquals(RenderPhase.Active, frame.phase)
        assertTrue(frame.active)
        assertTrue(rec.attachEngineWasNull, "headless 下 engine 为 null")
    }

    @Test
    fun `树锚点沿位移朝向前移 headLead`() {
        val rec = RecordingNode()
        val host = object : RenderHost {
            override val hostId = "test-lead"
        }
        val d = ProjectileVfxDriverImpl(host, rec, policy.copy(headLeadWorld = 30f))

        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, 0f, 0f, 0.1f, alive = true)
        val fx = assertNotNull(rec.lastFrame)
        assertEquals(130f, fx.origin.x, 0.01f, "origin 应为弹体前端 +headLead（沿 +x）")
        assertEquals(0f, fx.origin.y, 0.01f)

        d.advanceForTests(100f, 100f, 90f, 0.1f, alive = true)
        val fy = assertNotNull(rec.lastFrame)
        assertEquals(100f, fy.origin.x, 0.5f)
        assertEquals(130f, fy.origin.y, 0.5f, "朝向 90° 时 origin 应沿 +y 前移")
    }

    @Test
    fun `headLead 缺省时锚点即弹体前端`() {
        val rec = RecordingNode()
        val d = driver(rec)

        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, 0f, 0f, 0.1f, alive = true)

        val frame = assertNotNull(rec.lastFrame)
        assertEquals(100f, frame.origin.x, 0.01f, "headLead=0 时 origin 应即弹体前端")
    }

    @Test
    fun `向下位移时 facing 归一化到 0-360 而非负角`() {
        val rec = RecordingNode()
        val d = driver(rec)

        // BoxUtil setStateVanilla 对负角渲染异常，facing 必须归一化到 [0,360)
        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, -100f, 0f, 0.1f, alive = true)

        val frame = assertNotNull(rec.lastFrame)
        assertTrue(frame.facing >= 0f && frame.facing < 360f, "facing 必须在 [0,360)，实际 ${frame.facing}")
        assertEquals(315f, frame.facing, 0.5f)
    }

    @Test
    fun `存活期间保持 Active 且不溶解`() {
        val rec = RecordingNode()
        val d = driver(rec)

        var x = 0f
        repeat(100) {
            d.advanceForTests(x, 0f, 0f, 0.1f, alive = true)
            x += 20f
        }

        val frame = assertNotNull(rec.lastFrame)
        assertEquals(ProjectileVfxDriverState.Active, d.state, "存活期间应保持 Active")
        assertEquals(0f, frame.dissolve, 1e-4f, "弹体侧不做时间驱动溶解")
        assertEquals(1f, frame.intensity, 1e-4f)
    }

    @Test
    fun `淡出期弹体仍在场则跟随移动，移除后几何冻结并按淡出时长释放`() {
        val rec = RecordingNode()
        val d = driver(rec)
        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(50f, 0f, 0f, 0.1f, alive = true)
        assertEquals(ProjectileVfxDriverState.Active, d.state)

        // 原版 fadeTime 窗口内 alive=false 的弹体仍在场滑行
        d.advanceForTests(80f, 0f, 0f, 0.05f, alive = false)
        assertEquals(ProjectileVfxDriverState.Fading, d.state)
        val f1 = assertNotNull(rec.lastFrame)
        assertEquals(RenderPhase.FadingOut, f1.phase)
        assertEquals(FadeReason.Removed, f1.fadeReason)
        assertEquals(80f, f1.origin.x, 0.01f, "淡出期弹体仍在场应跟随到 80，而非钉死在 50")

        d.advanceRemovedForTests(0.02f)
        val frozen = assertNotNull(rec.lastFrame)
        assertEquals(80f, frozen.origin.x, 0.01f, "移除后应冻结在最后一帧位置")
        assertEquals(RenderPhase.FadingOut, frozen.phase)

        assertEquals(ProjectileVfxDriverState.Fading, d.state, "淡出窗口未尽，应仍在 Fading")
        d.advanceRemovedForTests(0.05f)
        assertEquals(ProjectileVfxDriverState.Removed, d.state, "fadeElapsed ≥ removedFadeOutSeconds 后应释放")
        assertTrue(rec.detached, "释放应递归 onDetach 到树")
    }

    private class RecordingNode : RenderEntity {
        override val id: String = "rec"
        override val layer: RenderLayer = CombatEngineLayers.ABOVE_PARTICLES
        override val children: List<RenderEntity> = emptyList()

        var lastFrame: FrameState? = null
        var attachEngineWasNull: Boolean = false
        var detached: Boolean = false

        override fun addChild(child: RenderEntity) {}
        override fun removeChild(id: String) {}
        override fun onAttach(ctx: RenderContext): Boolean {
            attachEngineWasNull = ctx.engine == null
            return ctx.engine != null
        }

        override fun advance(ctx: RenderContext, amount: Float) {
            lastFrame = ctx.frame
        }

        override fun render(ctx: RenderContext) {}
        override fun beginFadeOut(reason: FadeReason, seconds: Float) {}
        override fun onDetach() {
            detached = true
        }
    }
}
