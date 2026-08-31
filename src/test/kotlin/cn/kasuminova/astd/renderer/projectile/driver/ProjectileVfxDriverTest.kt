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
 * 以及"宿主消失 → 淡出 → 释放"的生命周期。真实 BoxUtil 渲染由游戏内目检验证（TrailComponent）。
 */
class ProjectileVfxDriverTest {

    private val policy = ProjectileVfxDriverPolicy(
        minDistancePerNode = 8f,
        maxHistoryNodes = 64,
        distanceWindow = 400f,
        historyFps = 60f,
        durationSeconds = 3f,
        dissolveStartRatio = 0.6f,
        layoutReferenceWidth = 1000f,
        hitFadeOutSeconds = 0.2f,
        expireFadeOutSeconds = 0.3f,
        removedFadeOutSeconds = 0.1f,
        primaryTrailLength = 400f,
        primaryTrailStartWidth = 40f,
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
        assertEquals(100f, frame.origin.x, 0.01f)     // origin 跟随宿主位置
        assertEquals(0f, frame.origin.y, 0.01f)
        assertEquals(0f, frame.facing, 0.5f)          // 沿 +x 移动 → 朝向约 0°
        assertTrue(frame.length > 0f, "可视拖尾长应为正")
        assertTrue(frame.intensity in 0f..1f)
        assertEquals(RenderPhase.Active, frame.phase)
        assertTrue(frame.active)
        assertTrue(frame.historyNodes.isNotEmpty(), "historyNodes 应随飞行累积，供网格类节点构建中线")
        assertTrue(rec.attachEngineWasNull, "headless 下 engine 为 null")
    }

    @Test
    fun `向下位移时 facing 归一化到 0-360 而非负角`() {
        val rec = RecordingNode()
        val d = driver(rec)

        // 向右下移动：atan2(dy<0,dx>0) 裸值为负（约 -45°），必须归一化到约 315°，
        // 否则 BoxUtil setStateVanilla 对负角渲染异常导致拖尾歪斜。
        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(100f, -100f, 0f, 0.1f, alive = true)

        val frame = assertNotNull(rec.lastFrame)
        assertTrue(frame.facing >= 0f && frame.facing < 360f, "facing 必须在 [0,360)，实际 ${frame.facing}")
        assertEquals(315f, frame.facing, 0.5f)
    }

    @Test
    fun `存活弹体飞行时长超过 durationSeconds 后拖尾不溶解`() {
        // 回归：制导导弹飞行时间远超 durationSeconds（此处 3s）。旧公式按 elapsed/duration 溶解，会让拖尾在途中提前淡尽
        //（beamAlpha→0、length 塌缩到约 8%）。现要求：只要弹体存活，dissolve 恒 0、beamAlpha 恒 1、长度维持在带长 cap。
        val rec = RecordingNode()
        val d = driver(rec)

        // 推进约 10s（>durationSeconds），每帧持续位移使行程远超带长 cap。
        var x = 0f
        repeat(100) {
            d.advanceForTests(x, 0f, 0f, 0.1f, alive = true)
            x += 20f
        }

        val frame = assertNotNull(rec.lastFrame)
        assertEquals(ProjectileVfxDriverState.Active, d.state, "存活期间应保持 Active")
        assertTrue(frame.elapsed > policy.durationSeconds, "本用例须已越过 durationSeconds")
        assertEquals(0f, frame.dissolve, 1e-4f, "存活期间不应溶解")
        assertEquals(1f, frame.intensity, 1e-4f, "存活期间 beamAlpha 应满值")
        // cap = viewportTailCap ×4 = max(1000*0.46, 40*4.8) × 4 = 460 × 4 = 1840（世界单位，分辨率无关）；
        // 行程 1980 > 1840 → 长度维持在 1840。
        assertEquals(1840f, frame.length, 1e-3f, "拖尾长应维持在带长 cap，而非随时间塌缩")
    }

    @Test
    fun `淡出期弹体仍在场则拖尾跟随移动，移除后带头前飞并加速释放`() {
        val rec = RecordingNode()
        val d = driver(rec)
        d.advanceForTests(0f, 0f, 0f, 0.1f, alive = true)
        d.advanceForTests(50f, 0f, 0f, 0.1f, alive = true)
        assertEquals(ProjectileVfxDriverState.Active, d.state)

        // 超射程：alive=false 但弹体仍在场滑行（原版 fadeTime 窗口）→ 进入淡出，且拖尾跟随到新位置 80（而非冻结在 50）。
        d.advanceForTests(80f, 0f, 0f, 0.05f, alive = false)
        assertEquals(ProjectileVfxDriverState.Fading, d.state)
        val f1 = assertNotNull(rec.lastFrame)
        assertEquals(RenderPhase.FadingOut, f1.phase)
        assertEquals(FadeReason.Removed, f1.fadeReason)   // 无宿主 projectile → goneReason=Removed
        assertEquals(80f, f1.origin.x, 0.01f, "淡出期弹体仍在场应跟随到 80，而非钉死在 50")

        // 弹体彻底移除（无实时位置）→ 几何冻结在最后一帧，但带头沿消亡前速度快照（(50-0)/0.1=500 su/s，
        // 快照于本帧 markGone 时、早于当帧 50→80 的速度更新）前飞弥合断头缝。
        d.advanceRemovedForTests(0.02f)
        val frozen = assertNotNull(rec.lastFrame)
        assertEquals(80f + 500f * 0.02f, frozen.origin.x, 0.01f, "移除后带头应前飞 10（80→90），而非钉死在 80")
        assertEquals(f1.trailLifetimeSeconds, frozen.trailLifetimeSeconds, 1e-6f, "冻结帧应保留逐节点寿命")
        assertTrue(frozen.trailTimeOffsetSeconds > 0f, "消亡后拖尾时间加速偏移应开始累加")

        // 前飞累计上限 = primaryTrailLength × 0.15 = 60：再推进 0.11s 累计 500×0.13=65 → 钳到 60，此后不再前移。
        d.advanceRemovedForTests(0.11f)
        val capped = assertNotNull(rec.lastFrame)
        assertEquals(80f + 60f, capped.origin.x, 0.01f, "前飞累计应钳在上限 60，不 overshoot")
        assertTrue(
            capped.trailTimeOffsetSeconds > frozen.trailTimeOffsetSeconds,
            "加速偏移应随淡出时间增长（尾先消头后消的时间压缩）",
        )

        // dispose 截止 = max(removedFadeOutSeconds 0.1, 拖尾死亡消散窗口 0.4)：fadeElapsed 累计 0.05+0.02+0.11=0.18 未到。
        assertEquals(ProjectileVfxDriverState.Fading, d.state, "死亡消散窗口未尽，应仍在 Fading")
        d.advanceRemovedForTests(0.23f)
        assertEquals(ProjectileVfxDriverState.Removed, d.state, "fadeElapsed 0.41 ≥ 0.4（死亡消散窗口）后应释放")
        assertTrue(rec.detached, "释放应递归 onDetach 到树")
    }

    @Test
    fun `visibleLength 为世界单位且 cap 与像素尺度无关`() {
        // 回归：旧实现带长 = traveledDistance/worldUnitsPerPixel（像素尺度），与世界坐标历史路径混比，
        // scale<1 时带尾越出出生点约 1/scale 倍。现带长与历史路径同域（世界单位），cap 也不再乘像素尺度
        //（viewportTailCap ×4 直接折为世界单位，分辨率/缩放无关）。
        val rec = RecordingNode()
        val d = driver(rec)

        var x = 0f
        repeat(40) {
            d.advanceForTests(x, 0f, 0f, 0.1f, alive = true, worldUnitsPerPixel = 0.5f)
            x += 20f
        }

        val frame = assertNotNull(rec.lastFrame)
        // cap = viewportTailCap(40, 1000) × 4 = 1840（世界单位）；行程 780 < 1840 → 带长 = 全行程，不做像素缩放
        assertEquals(780f, frame.length, 1e-3f, "带长应为世界单位行程（未达 cap），不做像素缩放")
        // 带长不得越出历史弧长（同域后直接可比；历史窗口按带长保留，必然 ≥ 带长）
        val nodes = frame.historyNodes
        var arc = 0f
        for (i in 1 until nodes.size) {
            val dx = nodes[i].location.x - nodes[i - 1].location.x
            val dy = nodes[i].location.y - nodes[i - 1].location.y
            arc += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        assertTrue(frame.length <= arc + 1f, "带长 ${frame.length} 不得越出历史弧长 $arc")
        // 逐节点寿命 = 预期带长 1840 / 实测速度 200 = 9.2s，写入 FrameState
        assertEquals(9.2f, frame.trailLifetimeSeconds, 0.02f)
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
