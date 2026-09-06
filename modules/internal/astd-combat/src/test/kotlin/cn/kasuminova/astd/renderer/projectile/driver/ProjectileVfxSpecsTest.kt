package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.TrailDriftRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手写 DSL spec 的蓝图自检：验证 [ProjectileVfxSpecs] 的构建函数产出的 [ProjectileVfxTreeSpec] 蓝图拓扑与驱动策略。
 *
 * Static Trail 迁移（2026-09）后：简单 spec = 三条 Static Trail 贴图拖尾（twin 外带 / smooth 核心 / zappy 装饰），
 * 弹头全部由原版弹体渲染承担（aod7 亦不例外），全部参数由文件底部常量与公式纯函数派生——
 * 本测试含公式数值锚点与全 spec 的接线守护。
 * 蓝图 → RenderEntity 场景树的组装（组件类型/节点 id/renderOrder）由 astd-render 的 ProjectileVfxTreeAssemblerTest 守护。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 蓝图：twin 与 zappy 两条 Static Trail 拖尾`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))

        assertEquals(listOf("twin", "zappy"), vfx.tree.staticTrails.map { it.first })
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        val zappy = vfx.tree.staticTrails.first { it.first == "zappy" }.second

        assertEquals(TEX_TWIN, twin.texturePath)
        assertEquals(1, twin.layer)
        assertEquals(30f, twin.width)
        assertEquals(420f, twin.bandLength)
        assertEquals(35f, twin.recede, "headRecede(420)=round5(33.6)=35：recede ≤ headLead+length/2−speed/30=42，保证 30Hz cadence 最坏滞留下拖尾头仍藏在螺栓底下")

        assertEquals(TEX_ZAPPY, zappy.texturePath)
        assertEquals(2, zappy.layer)
        assertEquals(24f, zappy.width)
        assertEquals(420f, zappy.bandLength)
        assertEquals(-45f..45f, zappy.angularOutRange, "zappy 装饰层默认尾端自旋")
        assertEquals(TrailDriftRange(-16f, -16f, 16f, 16f), zappy.velocityOutRange, "angular 需配合非零 velocity 才生效")
        assertEquals(0.5f, zappy.glowPower)
        assertNull(zappy.angularInRange)
        assertNull(zappy.velocityInRange)
        assertNull(twin.angularOutRange, "twin 外带不加自旋")
    }

    @Test
    fun `aod7 策略：仅淡出与 headLead 自动`() {
        val p = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot")).policy
        assertEquals(0.15f, p.hitFadeOutSeconds)
        assertEquals(0.15f, p.expireFadeOutSeconds)
        assertEquals(0.15f, p.removedFadeOutSeconds)
        assertNull(p.headLeadWorld, "headLead 不声明 = 自动取弹体 spec.length/2")
    }

    @Test
    fun `简单 spec 蓝图拓扑：三层 Static Trail 拖尾`() {
        // spc3：twin 外带 + smooth 核心 + zappy 装饰按声明序叠层；弹头由原版弹体渲染承担。
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        assertEquals(listOf("twin", "core", "zappy"), plain.tree.staticTrails.map { it.first })

        // bandWidth(6, 2.2)=round05(max(2.1, 6.93))=7 ×2 = 14；核心 ×0.5=7；装饰 ×0.6=8.5
        val twin = plain.tree.staticTrails.first { it.first == "twin" }.second
        val core = plain.tree.staticTrails.first { it.first == "core" }.second
        val zappy = plain.tree.staticTrails.first { it.first == "zappy" }.second
        assertEquals(14f, twin.width)
        assertEquals(7f, core.width)
        assertEquals(8.5f, zappy.width)
        assertEquals(-45f..45f, zappy.angularOutRange, "简单 spec 的 zappy 装饰层同样带默认尾端自旋")
        assertEquals(TrailDriftRange(-16f, -16f, 16f, 16f), zappy.velocityOutRange)
        assertEquals(0.45f, core.glowPower, "仅核心层给适度 bloom")
        assertEquals(0f, twin.glowPower)
        assertEquals(listOf(1, 2, 3), plain.tree.staticTrails.map { it.second.layer })
        plain.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(135f, spec.bandLength)
            assertEquals(10f, spec.recede, "headRecede(135)=round5(10.8)=10")
        }

        assertEquals(0.18f, plain.policy.removedFadeOutSeconds)
        assertEquals(0.1f, plain.policy.hitFadeOutSeconds)
        assertEquals(0.22f, plain.policy.expireFadeOutSeconds)
        assertNull(plain.policy.headLeadWorld)
    }

    @Test
    fun `未知 spec 返回 null；已接入 spec 均可构建`() {
        assertEquals(null, ProjectileVfxSpecs.build("astd_does_not_exist"))
        // 抽查若干已接入。
        assertTrue(ProjectileVfxSpecs.has("astd_aod7_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_spc3_shot"))
    }

    @Test
    fun `贯星之矛：三层拖尾之外追加光斑与锚点电弧`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_piercing_lance_shot"))
        assertEquals(listOf("twin", "core", "zappy"), vfx.tree.staticTrails.map { it.first })
        assertEquals(listOf("core"), vfx.tree.boxFlares.map { it.first })
        assertEquals(listOf("arc"), vfx.tree.anchorArcs.map { it.first })
        assertNotNull(vfx.onFire, "贯星之矛带发射点扭曲钩子")
    }

    @Test
    fun `平铺 滚动 退距公式锚点`() {
        assertEquals(55f, mainTile(135f))
        assertEquals(100f, mainTile(240f))
        assertEquals(25f, mainScroll(135f))
        assertEquals(40f, mainScroll(240f))
        assertEquals(70f, arcTile(135f))
        assertEquals(30f, arcScroll(135f))
        assertEquals(20f, headRecede(250f))
        assertEquals(25f, headRecede(310f))
    }

    @Test
    fun `颜色公式锚点：头部近白高亮 尾部压暗`() {
        val blue = ASTDColor(0.2f, 0.55f, 1f, 0.92f)

        val head = bandHeadColor(blue)
        assertEquals(0.2f + 0.8f * 0.45f, head.red, 1e-3f)   // mix(主色, 白, 0.45)——保持主色饱和，防与弹头光晕色差
        assertEquals(0.92f * 0.78f, head.alpha, 1e-3f)
        val headDim = bandHeadColor(blue, 0.45f)             // 外带/装饰层 alpha×0.45
        assertEquals(0.92f * 0.78f * 0.45f, headDim.alpha, 1e-3f)

        val tail = bandTailColor(blue)
        assertEquals(0.2f * 0.16f, tail.red, 1e-3f)
        assertEquals(0.07f, tail.alpha, 1e-3f)
    }

}
