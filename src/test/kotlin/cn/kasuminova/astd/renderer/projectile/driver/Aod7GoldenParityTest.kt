package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderLayer
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalog
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntime
import com.fs.starfarer.api.combat.CombatEngineLayers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * aod7 迁移保真对比：把同一帧序列分别喂给旧 [ASTDProjectileVfxRuntime]（读旧 preset）与新 [ProjectileVfxDriverImpl]
 * （读手写 DSL 的 [ProjectileVfxSpecs] 策略），逐帧逐字段比对二者产出的飞行几何输入（旧 RenderContext vs 新 FrameState）。
 *
 * 判据：几何输入（origin/facing/length/dissolve/history…）逐字段相等 ⇒ 证明 DSL 策略忠实转写了旧 preset 的飞行行为。
 * 注意：网格线宽（trail/head 尺寸）是作者面数值，aod7 已按目检上调、与旧 preset 有意不同，不属本测试比对范围；
 * startWidth 40→56 不改 viewportTailCap（由 layoutReferenceWidth 主导），故 length 仍逐帧相等。
 */
class Aod7GoldenParityTest {

    @AfterTest
    fun tearDown() {
        ASTDProjectileVfxPresetCatalog.resetForTests()
    }

    @Test
    fun `新驱动的 FrameState 与旧 runtime 的 RenderContext 逐帧逐字段一致`() {
        val preset = assertNotNull(ASTDProjectileVfxPresetCatalog.preset("aod7_shot"), "aod7_shot preset 应存在")

        val old = ASTDProjectileVfxRuntime.forTests(preset)
        val rec = CapturingNode()
        val driver = ProjectileVfxDriverImpl(
            host = object : RenderHost { override val hostId = "golden" },
            tree = rec,
            policy = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot")).policy,
        )

        // 向右下运动（含负 atan2 → 归一化到 [0,360)），并给一个初始 projectileFacing。
        var x = 120f
        var y = 340f
        val step = 34f
        val facing = 315f
        val amount = 1f / 60f

        // 16 帧≈0.27s，刻意停留在溶解起点（duration*dissolveStart≈0.75s）之前：新驱动存活期不再按时间老化溶解，
        // 越过溶解起点后会与旧 runtime 分道（新恒 dissolve=0，旧开始溶解），故此比对只覆盖飞行前段的几何转写。
        repeat(16) { frame ->
            old.advanceForTests(x, y, facing, amount, projectileAlive = true)
            driver.advanceForTests(x, y, facing, amount, alive = true)

            // 第 1 帧两侧都回退到 projectileFacing、且历史仅 1 点，几何尚未成形，跳过严格比对。
            if (frame >= 1) {
                val ctx = assertNotNull(old.lastContextForTests(), "旧管线第 $frame 帧应有 context")
                val fs = assertNotNull(rec.lastFrame, "新驱动第 $frame 帧应有 FrameState")
                assertFrameMatchesContext(frame, ctx, fs)
            }

            x += step
            y -= step
        }
    }

    private fun assertFrameMatchesContext(
        frame: Int,
        ctx: cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext,
        fs: FrameState,
    ) {
        val eps = 1e-3f
        assertEquals(ctx.location.x, fs.origin.x, eps, "帧 $frame origin.x")
        assertEquals(ctx.location.y, fs.origin.y, eps, "帧 $frame origin.y")
        assertEquals(ctx.renderFacing, fs.facing, eps, "帧 $frame facing")
        assertEquals(ctx.elapsed, fs.elapsed, eps, "帧 $frame elapsed")
        assertEquals(ctx.logicElapsed, fs.logicElapsed, eps, "帧 $frame logicElapsed")
        assertEquals(ctx.flightProgress, fs.flightProgress, eps, "帧 $frame flightProgress")
        assertEquals(ctx.dissolve, fs.dissolve, eps, "帧 $frame dissolve")
        assertEquals(ctx.visibleLength, fs.length, eps, "帧 $frame visibleLength")
        assertEquals(ctx.beamAlpha, fs.intensity, eps, "帧 $frame beamAlpha")
        assertEquals(ctx.worldUnitsPerPixel, fs.worldUnitsPerPixel, eps, "帧 $frame worldUnitsPerPixel")

        assertEquals(ctx.historyNodes.size, fs.historyNodes.size, "帧 $frame historyNodes 数量")
        ctx.historyNodes.forEachIndexed { i, oldNode ->
            val newNode = fs.historyNodes[i]
            assertEquals(oldNode.location.x, newNode.location.x, eps, "帧 $frame history[$i].x")
            assertEquals(oldNode.location.y, newNode.location.y, eps, "帧 $frame history[$i].y")
            assertEquals(oldNode.facing, newNode.facing, eps, "帧 $frame history[$i].facing")
            assertEquals(oldNode.elapsed, newNode.elapsed, eps, "帧 $frame history[$i].elapsed")
        }
        assertTrue(fs.historyNodes.isNotEmpty(), "帧 $frame historyNodes 不应为空")
    }

    /** 捕获每帧 FrameState 的最小节点；不依赖引擎。 */
    private class CapturingNode : RenderEntity {
        override val id: String = "capture"
        override val layer: RenderLayer = CombatEngineLayers.ABOVE_PARTICLES
        override val children: List<RenderEntity> = emptyList()
        var lastFrame: FrameState? = null
        override fun addChild(child: RenderEntity) {}
        override fun removeChild(id: String) {}
        override fun onAttach(ctx: RenderContext): Boolean = ctx.engine != null
        override fun advance(ctx: RenderContext, amount: Float) { lastFrame = ctx.frame }
        override fun render(ctx: RenderContext) {}
        override fun beginFadeOut(reason: FadeReason, seconds: Float) {}
        override fun onDetach() {}
    }
}
