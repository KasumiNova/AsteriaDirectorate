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

/**
 * 通用弹体 spec（[ProjectileVfxSpecs.simpleProjectileVfx]）迁移保真对比：抽取 plain/ribbon/head 三个代表，
 * 逐帧逐字段比对"旧 generic preset 经旧 runtime" 与 "手写 DSL 策略经新驱动" 的飞行几何输入。
 * 证明通用构建器把旧 `preset()` 工厂的策略公式（window=length、startWidth=width、固定采样/淡出/生命周期）转写无误。
 */
class GenericSpecParityTest {

    @AfterTest
    fun tearDown() {
        ASTDProjectileVfxPresetCatalog.resetForTests()
    }

    @Test
    fun `plain 弹体（drv9）DSL 策略与旧 preset 飞行几何逐帧一致`() =
        assertParity(projectileSpecId = "astd_drv9_slug", presetId = "drv9_slug")

    @Test
    fun `ribbon 弹体（slt3）DSL 策略与旧 preset 飞行几何逐帧一致`() =
        assertParity(projectileSpecId = "astd_slt3_pulse", presetId = "slt3_pulse")

    @Test
    fun `head 弹体（rct6）DSL 策略与旧 preset 飞行几何逐帧一致`() =
        assertParity(projectileSpecId = "astd_rct6_torp", presetId = "rct6")

    private fun assertParity(projectileSpecId: String, presetId: String) {
        val preset = assertNotNull(ASTDProjectileVfxPresetCatalog.preset(presetId), "$presetId preset 应存在")
        val old = ASTDProjectileVfxRuntime.forTests(preset)
        val rec = CapturingNode()
        val driver = ProjectileVfxDriverImpl(
            host = object : RenderHost { override val hostId = "parity" },
            tree = rec,
            policy = assertNotNull(ProjectileVfxSpecs.build(projectileSpecId)).policy,
        )

        var x = 120f
        var y = 340f
        val step = 34f
        val facing = 315f
        val amount = 1f / 60f
        val eps = 1e-3f

        // 16 帧≈0.27s，刻意停留在溶解起点（duration*dissolveStart≈0.75s）之前：新驱动存活期不再按时间老化溶解，
        // 越过溶解起点后会与旧 runtime 分道（新恒 dissolve=0，旧开始溶解），故此比对只覆盖飞行前段的几何转写。
        repeat(16) { frame ->
            old.advanceForTests(x, y, facing, amount, projectileAlive = true)
            driver.advanceForTests(x, y, facing, amount, alive = true)
            if (frame >= 1) {
                val ctx = assertNotNull(old.lastContextForTests(), "$projectileSpecId 帧 $frame 旧 context")
                val fs = assertNotNull(rec.lastFrame, "$projectileSpecId 帧 $frame 新 FrameState")
                assertEquals(ctx.location.x, fs.origin.x, eps, "$projectileSpecId 帧 $frame origin.x")
                assertEquals(ctx.location.y, fs.origin.y, eps, "$projectileSpecId 帧 $frame origin.y")
                assertEquals(ctx.renderFacing, fs.facing, eps, "$projectileSpecId 帧 $frame facing")
                assertEquals(ctx.flightProgress, fs.flightProgress, eps, "$projectileSpecId 帧 $frame flightProgress")
                assertEquals(ctx.dissolve, fs.dissolve, eps, "$projectileSpecId 帧 $frame dissolve")
                assertEquals(ctx.visibleLength, fs.length, eps, "$projectileSpecId 帧 $frame visibleLength")
                assertEquals(ctx.beamAlpha, fs.intensity, eps, "$projectileSpecId 帧 $frame beamAlpha")
                assertEquals(ctx.historyNodes.size, fs.historyNodes.size, "$projectileSpecId 帧 $frame history 数量")
            }
            x += step
            y -= step
        }
    }

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
