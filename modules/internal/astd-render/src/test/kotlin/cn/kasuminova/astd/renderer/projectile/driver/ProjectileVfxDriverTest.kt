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

/**
 * 垂直切片的 headless 自检：不依赖引擎，验证驱动每帧产出的 [FrameState] 字段映射正确、
 * 以及"宿主消失 → 淡出 → 释放"的生命周期。拖尾本体由 BoxUtil Static Trail 托管（不在本切片内），
 * 本驱动只剩树生命周期/锚点/渲染朝向。
 */
class ProjectileVfxDriverTest {

    private val policy = ProjectileVfxDriverPolicy(
        hitFadeOutSeconds = 0.2f,
        expireFadeOutSeconds = 0.3f,
        removedFadeOutSeconds = 0.1f,
    )

    private fun driver(tree: RenderEntity): ProjectileVfxDriverImpl {
        val host = object : RenderHost { override val hostId = "test" }
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
        assertEquals(100f, frame.origin.x, 0.01f)     // origin 跟随宿主位置（headLead=0，非弹体宿主）
        assertEquals(0f, frame.origin.y, 0.01f)
        assertEquals(0f, frame.facing, 0.5f)          // 沿 +x 移动 → 朝向约 0°
        assertEquals(1f, frame.intensity, 1e-4f)
        assertEquals(RenderPhase.Active, frame.phase)
        assertTrue(frame.active)
        assertTrue(rec.attachEngineWasNull, "headless 下 engine 为 null")
    }

    @Test
    fun `树锚点沿位移朝向前移 headLead`() {
        val rec = RecordingNode()
        val host = object : RenderHost { override val hostId = "test-lead" }
        val d = ProjectileVfxDriverImpl(host, rec, policy.copy(headLeadWorld = 30f))

        // 沿 +x 移动：origin 应领先弹体中心 30（对齐原版螺栓视觉头部）
        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, 0f, 0f, 0.1f, alive = true)
        val fx = assertNotNull(rec.lastFrame)
        assertEquals(130f, fx.origin.x, 0.01f, "origin 应为弹体中心 +headLead（沿 +x）")
        assertEquals(0f, fx.origin.y, 0.01f)

        // 改为沿 +y 移动：前移方向跟随位移朝向
        d.advanceForTests(100f, 100f, 90f, 0.1f, alive = true)
        val fy = assertNotNull(rec.lastFrame)
        assertEquals(100f, fy.origin.x, 0.5f)
        assertEquals(130f, fy.origin.y, 0.5f, "朝向 90° 时 origin 应沿 +y 前移")
    }

    @Test
    fun `headLead 为零时锚点退回弹体中心`() {
        val rec = RecordingNode()
        val d = driver(rec) // 测试 policy 未设 headLeadWorld 且非弹体宿主 → 0

        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, 0f, 0f, 0.1f, alive = true)

        val frame = assertNotNull(rec.lastFrame)
        assertEquals(100f, frame.origin.x, 0.01f, "headLead=0 时 origin 应即弹体中心")
    }

    @Test
    fun `向下位移时 facing 归一化到 0-360 而非负角`() {
        val rec = RecordingNode()
        val d = driver(rec)

        // 向右下移动：atan2(dy<0,dx>0) 裸值为负（约 -45°），必须归一化到约 315°，
        // 否则 BoxUtil setStateVanilla 对负角渲染异常导致歪斜。
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

        // 超射程：alive=false 但弹体仍在场滑行（原版 fadeTime 窗口）→ 进入淡出，跟随到新位置 80（而非冻结在 50）。
        d.advanceForTests(80f, 0f, 0f, 0.05f, alive = false)
        assertEquals(ProjectileVfxDriverState.Fading, d.state)
        val f1 = assertNotNull(rec.lastFrame)
        assertEquals(RenderPhase.FadingOut, f1.phase)
        assertEquals(FadeReason.Removed, f1.fadeReason)   // 无宿主 projectile → goneReason=Removed
        assertEquals(80f, f1.origin.x, 0.01f, "淡出期弹体仍在场应跟随到 80，而非钉死在 50")

        // 弹体彻底移除（无实时位置）→ 几何冻结在最后一帧（Static Trail 托管拖尾，不再有带头前飞补偿）。
        d.advanceRemovedForTests(0.02f)
        val frozen = assertNotNull(rec.lastFrame)
        assertEquals(80f, frozen.origin.x, 0.01f, "移除后应冻结在最后一帧位置")
        assertEquals(RenderPhase.FadingOut, frozen.phase)

        // dispose 截止 = removedFadeOutSeconds 0.1：fadeElapsed 累计 0.05+0.02=0.07 未到，再推进 0.05 → 0.12 ≥ 0.1 释放。
        assertEquals(ProjectileVfxDriverState.Fading, d.state, "淡出窗口未尽，应仍在 Fading")
        d.advanceRemovedForTests(0.05f)
        assertEquals(ProjectileVfxDriverState.Removed, d.state, "fadeElapsed ≥ removedFadeOutSeconds 后应释放")
        assertTrue(rec.detached, "释放应递归 onDetach 到树")
    }

    /** 记录型节点：直接实现 RenderEntity，捕获每帧收到的 FrameState 与生命周期调用。 */
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
        override fun advance(ctx: RenderContext, amount: Float) { lastFrame = ctx.frame }
        override fun render(ctx: RenderContext) {}
        override fun beginFadeOut(reason: FadeReason, seconds: Float) {}
        override fun onDetach() { detached = true }
    }
}
