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
 * 简单 spec = Box 螺栓弹头（默认开启，外缘染主色）+ 三条 Static Trail 贴图拖尾（twin 外带 / smooth 核心 / zappy 装饰），
 * 全部参数由文件底部常量与公式纯函数派生——本测试含公式数值锚点与全 spec 的接线守护。
 * 蓝图 → RenderEntity 场景树的组装（组件类型/节点 id/renderOrder）由 astd-render 的 ProjectileVfxTreeAssemblerTest 守护。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 蓝图：Box 螺栓 + twin 与 zappy 两条 Static Trail 拖尾`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))

        val bolt = assertNotNull(vfx.tree.bolt, "aod7 弹头为 Box 螺栓（默认开启）")
        assertEquals(0xCF / 255f, bolt.fringeColor.red, 1e-3f)
        assertEquals(0xE8 / 255f, bolt.fringeColor.green, 1e-3f)
        assertEquals(1f, bolt.fringeColor.blue, 1e-3f)
        assertEquals(1f, bolt.fringeColor.alpha, 1e-3f)

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
    fun `简单 spec 蓝图拓扑：Box 螺栓 + 三层 Static Trail 拖尾`() {
        // spc3：Box 螺栓外缘染主色（alpha 拉满）+ twin 外带 + smooth 核心 + zappy 装饰按声明序叠层。
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        val bolt = assertNotNull(plain.tree.bolt)
        assertEquals(168 / 255f, bolt.fringeColor.red, 1e-3f, "violet 主色（hex 0.66×255→168）")
        assertEquals(107 / 255f, bolt.fringeColor.green, 1e-3f, "0.42×255→107")
        assertEquals(1f, bolt.fringeColor.alpha, 1e-3f, "螺栓外缘 alpha 拉满（亮度衰减由组件逐帧乘算）")
        assertEquals(1f, bolt.coreColor.red, 1e-3f, "核心层默认近白")

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
    fun `bolt DSL：off 关闭螺栓层；仅螺栓也可成树`() {
        val off = projectileVfx("bolt_off_test") {
            bolt { off() }
            staticTrail("twin", TEX_TWIN) { }
        }
        assertNull(off.tree.bolt)

        val boltOnly = projectileVfx("bolt_only_test") { }
        assertNotNull(boltOnly.tree.bolt, "bolt 默认开启，空块即仅螺栓弹头")
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
