package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.TrailDriftRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手写 DSL spec 的蓝图自检：验证 [ProjectileVfxSpecs] 的构建函数产出的 [ProjectileVfxTreeSpec] 蓝图拓扑与驱动策略。
 *
 * 简单 spec = Box 螺栓弹头（默认开启，染 boltColor 近白单色系）+ 四条 Static Trail 贴图拖尾（twin 外带 / smooth 核心 / zappy 装饰 ×2），
 * 全部参数由文件底部常量与公式纯函数派生——本测试含公式数值锚点与全 spec 的接线守护。
 * 蓝图 → RenderEntity 场景树的组装（组件类型/节点 id/renderOrder）由 astd-render 的 ProjectileVfxTreeAssemblerTest 守护。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 蓝图：Box 螺栓 + twin 与 zappy 两条 Static Trail 拖尾`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))

        val bolt = assertNotNull(vfx.tree.bolt, "aod7 弹头为 Box 螺栓（默认开启）")
        assertEquals(0xE4 / 255f, bolt.color.red, 1e-3f)
        assertEquals(0xF2 / 255f, bolt.color.green, 1e-3f)
        assertEquals(1f, bolt.color.blue, 1e-3f)
        assertEquals(0xC8 / 255f, bolt.color.alpha, 1e-3f)

        assertEquals(listOf("twin", "zappy"), vfx.tree.staticTrails.map { it.first })
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        val zappy = vfx.tree.staticTrails.first { it.first == "zappy" }.second

        assertEquals(TEX_TWIN, twin.texturePath)
        assertEquals(1, twin.layer)
        assertEquals(30f, twin.width)
        assertEquals(420f, twin.bandLength)
        assertNull(twin.recede, "recede 不声明 = 自动取弹体长度 ×0.75（tracker 运行期解析）")

        assertEquals(TEX_ZAPPY, zappy.texturePath)
        assertEquals(2, zappy.layer)
        assertEquals(24f, zappy.width)
        assertEquals(420f, zappy.bandLength)
        assertNull(zappy.angularOutRange, "aod7 zappy 不做尾端自旋（新旧段拼接扭曲跳变主要来自 angular 随机自旋）")
        assertNull(zappy.velocityOutRange, "angular 需配合非零 velocity 才生效，zappy 两者均不声明")
        assertEquals(0.5f, zappy.glowPower)
        assertNull(zappy.angularInRange)
        assertNull(zappy.velocityInRange)
        assertNull(twin.angularOutRange, "twin 外带不加自旋")
    }

    @Test
    fun `aod7 带长随武器射程折算`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot", weaponRangeSu = 1200f))
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(900f, spec.bandLength, "round5(1200×0.75)=900")
        }
        // 平铺/滚动随带长同比例缩放（保持图案密度）
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        assertEquals(300f, twin.tileLength, "round5(900/3)=300")
        assertEquals(110f, twin.scrollSpeed, "round5(900×0.12)=round5(108)=110")
    }

    @Test
    fun `aod7 策略：仅淡出与 headLead 缺省`() {
        val p = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot")).policy
        assertEquals(0.15f, p.hitFadeOutSeconds)
        assertEquals(0.15f, p.expireFadeOutSeconds)
        assertEquals(0.15f, p.removedFadeOutSeconds)
        assertNull(p.headLeadWorld, "headLead 不声明 = 缺省 0，锚点压在弹体前端（螺栓头部）")
    }

    @Test
    fun `简单 spec 蓝图拓扑：Box 螺栓 + 四层 Static Trail 拖尾`() {
        // spc3：Box 螺栓染 boltColor 近白单色系 + twin 外带 + smooth 核心 + zappy 装饰×2 按声明序叠层。
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        val bolt = assertNotNull(plain.tree.bolt)
        assertEquals(229 / 255f, bolt.color.red, 1e-3f, "boltColor(violet)=mix(主色, 白, 0.7)=0.898，hex 舍入 229")
        assertEquals(211 / 255f, bolt.color.green, 1e-3f, "0.826，hex 舍入 211")
        assertEquals(1f, bolt.color.blue, 1e-3f)
        assertEquals(199 / 255f, bolt.color.alpha, 1e-3f, "原版 coreColor 近白口径 alpha 0.78，hex 舍入 199")

        assertEquals(listOf("twin", "core", "zappy_0", "zappy_1"), plain.tree.staticTrails.map { it.first })

        // bandWidth(6, 2.2)=round05(max(2.1, 6.93))=7 ×2 = 14；核心 ×0.5=7；装饰 ×0.6=8.5
        val twin = plain.tree.staticTrails.first { it.first == "twin" }.second
        val core = plain.tree.staticTrails.first { it.first == "core" }.second
        val zappy = plain.tree.staticTrails.first { it.first == "zappy_0" }.second
        assertEquals(14f, twin.width)
        assertEquals(7f, core.width)
        assertEquals(8.5f, zappy.width)
        assertEquals(-45f..45f, zappy.angularOutRange, "简单 spec 的 zappy 装饰层同样带默认尾端自旋")
        assertEquals(TrailDriftRange(-12f, -12f, 12f, 12f), zappy.velocityOutRange)
        assertEquals(0.8f, core.glowPower, "全层统一 trailGlow 默认 0.8")
        assertEquals(0.8f, twin.glowPower, "外带同样吃 trailGlow")
        assertEquals(listOf(1, 2, 3, 3), plain.tree.staticTrails.map { it.second.layer })
        plain.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(135f, spec.bandLength)
            assertNull(spec.recede, "recede 不声明 = 自动取弹体长度 ×0.2")
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
    fun `boxFlare 闪烁默认关闭 调用任意 flicker 方法即启用`() {
        val vfx = projectileVfx("flare_flick_test") {
            boxFlare("still") { size(10f, 10f) }
            boxFlare("blink") { size(10f, 10f); flicker(0.8f) }
        }
        val still = vfx.tree.boxFlares.first { it.first == "still" }.second
        val blink = vfx.tree.boxFlares.first { it.first == "blink" }.second
        assertFalse(still.flick, "不调用 flicker 即不闪烁")
        assertTrue(blink.flick, "调用 flicker 即启用闪烁")
        assertEquals(0.8f, blink.flickerRate, 1e-6f)
    }

    @Test
    fun `贯星之矛：四层拖尾之外追加水平光斑 弹头光斑与锚点电弧`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_piercing_lance_shot"))
        assertEquals(listOf("twin", "core", "zappy_0", "zappy_1"), vfx.tree.staticTrails.map { it.first })
        assertEquals(listOf("core", "light"), vfx.tree.boxFlares.map { it.first })
        assertEquals(listOf("arc"), vfx.tree.anchorArcs.map { it.first })
        assertNotNull(vfx.onFire, "贯星之矛带发射点扭曲钩子")

        // 亮度 +25%：主色 alpha 0.95 × 0.78 × 1.25 ≈ 0.9263，hex 量化（×255 取整 236）后 0.9255
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        assertEquals(236 / 255f, twin.headColor.alpha, 1e-3f)
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(0f, spec.recede, "贯星之矛 recede 显式 0：带体亮头直抵弹头")
        }
    }

    @Test
    fun `贯星之矛带长随射程折算 85`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_piercing_lance_shot", weaponRangeSu = 1000f))
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(850f, spec.bandLength, "round5(1000×0.85)=850")
        }
    }

    @Test
    fun `电荷针刺族：固定短拖尾 180 无 zappy 层 宽度 25`() {
        for (id in listOf("astd_charge_needle_shot", "astd_heavy_charge_needle_shot")) {
            val vfx = assertNotNull(ProjectileVfxSpecs.build(id), "$id 应已接入")
            assertEquals(listOf("twin", "core"), vfx.tree.staticTrails.map { it.first }, "$id 移除 zappy 装饰层")
            vfx.tree.staticTrails.forEach { (_, spec) ->
                assertEquals(180f, spec.bandLength, "$id 固定短拖尾 180")
                assertNull(spec.angularOutRange)
                assertNull(spec.velocityOutRange)
            }
            // 宽度 −50%：bandWidth(6, 2.2)=round05(max(2.2, 6.6))=6.5 ×2 ×0.5 ≈ 7（实读 7.0）
            val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
            assertEquals(7.0f, twin.width, "$id 拖尾宽度 ×0.5")
        }
    }

    @Test
    fun `辉星 MRM：带长射程 50 recede 0 弹头 SMOOTH 光斑`() {
        for (id in listOf("astd_stellar_mrm_launcher_shot", "astd_stellar_mrm_pod_shot")) {
            val vfx = assertNotNull(ProjectileVfxSpecs.build(id, weaponRangeSu = 2500f), "$id 应已接入")
            vfx.tree.staticTrails.forEach { (_, spec) ->
                assertEquals(1250f, spec.bandLength, "$id round5(2500×0.5)=1250")
                assertEquals(0f, spec.recede, "$id recede 显式 0")
            }
            val light = vfx.tree.boxFlares.firstOrNull { it.first == "light" }?.second
            assertNotNull(light, "$id 弹头 SMOOTH 光斑")
            assertEquals(30f, light.width)
            assertEquals(30f, light.height)
        }
    }

    @Test
    fun `电驱加速炮：黄色弹体 带长射程 25 拖尾宽度 50`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_electric_drive_accelerator_shot", weaponRangeSu = 800f))
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(200f, spec.bandLength, "round5(800×0.25)=200")
        }
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        assertEquals(7f, twin.width, "bandWidth(9, 2.2)=7 ×2 ×0.5=7")
        // 黄色主色：头部色 mix(黄, 白, 0.45) 应偏暖（红>蓝）
        assertTrue(twin.headColor.red > twin.headColor.blue, "电驱加速炮弹体/拖尾应为黄色系")
        val bolt = assertNotNull(vfx.tree.bolt)
        assertTrue(bolt.color.red > bolt.color.blue, "螺栓弹头染色偏黄")
    }

    @Test
    fun `平铺 滚动公式锚点`() {
        assertEquals(55f, mainTile(135f))
        assertEquals(100f, mainTile(240f))
        assertEquals(25f, mainScroll(135f))
        assertEquals(40f, mainScroll(240f))
        assertEquals(70f, arcTile(135f))
        assertEquals(30f, arcScroll(135f))
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
