package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BoxFlareStyle
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
    fun `坠星残响普通弹蓝图：Box 螺栓 + 四层拖尾 + 弹头光斑 + 开火锥状冲击`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_starfall_echo_shot"))

        val bolt = assertNotNull(vfx.tree.bolt, "坠星残响弹头为 Box 螺栓（默认开启）")
        // 主色 starfallBlue(0.55, 0.78, 1) mix 白 0.7 → 0.865/0.934/1.0，hex 舍入 221/238/255
        assertEquals(221 / 255f, bolt.color.red, 1e-3f)
        assertEquals(238 / 255f, bolt.color.green, 1e-3f)
        assertEquals(1f, bolt.color.blue, 1e-3f)
        assertEquals(0.78f, bolt.color.alpha, 1e-3f)

        assertEquals(listOf("twin", "core", "zappy_0", "zappy_1"), vfx.tree.staticTrails.map { it.first })
        assertEquals(listOf(1, 2, 3, 3), vfx.tree.staticTrails.map { it.second.layer })
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        val core = vfx.tree.staticTrails.first { it.first == "core" }.second
        val zappy = vfx.tree.staticTrails.first { it.first == "zappy_0" }.second
        // bandWidth(14, 2.2)=round05(max(4.9, 6.93))=7 ×2 ×0.75=10.5；核心 ×0.5=5.5；装饰 ×0.6=6.5
        assertEquals(10.5f, twin.width)
        assertEquals(5.5f, core.width)
        assertEquals(6.5f, zappy.width)
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(320f, spec.bandLength, "无射程入参时取固定带长 320")
            assertEquals(0.8f, spec.glowPower, "坠星残响 trailGlow 0.8 全层统一")
        }

        // 弹头 SMOOTH 光斑（boltFlare=40）
        val boltGlow = vfx.tree.boxFlares.firstOrNull { it.first == "boltGlow" }?.second
        assertNotNull(boltGlow, "普通弹弹头 SMOOTH 光斑")
        assertEquals(40f, boltGlow.width)
        assertEquals(40f, boltGlow.height)

        // 开火锥状冲击（muzzleBurst 登记一发 onFire 钩子）
        assertEquals(1, vfx.onFire.size, "普通弹带一个开火锥状冲击钩子")

        // 航迹持续发射器（原 .wpn EveryFrame 迁移进树）：蓝白碎片 + 半径 35 马赫环
        assertEquals(listOf("wake"), vfx.tree.shardWakes.map { it.first })
        val wake = vfx.tree.shardWakes.first().second
        assertEquals(0.01f, wake.interval, 1e-6f)
        assertEquals(3, wake.perTick)
        assertEquals(34f, wake.shardLength)
        assertEquals(listOf("ring"), vfx.tree.machRings.map { it.first })
        val ring = vfx.tree.machRings.first().second
        assertEquals(0.1f, ring.interval, 1e-6f)
        assertEquals(35f, ring.halfSize)
        assertEquals("graphics/fx/astd_generated_ring.png", ring.texturePath)
        assertTrue(ring.color.blue > ring.color.red, "普通弹马赫环应为蓝白色系")
    }

    @Test
    fun `坠星残响带长随射程折算 50`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_starfall_echo_shot", weaponRangeSu = 1000f))
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(500f, spec.bandLength, "round5(1000×0.5)=500")
        }
        // 平铺/滚动随带长同比例缩放（保持图案密度）
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        assertEquals(210f, twin.tileLength, "round5(500/2.4)=210")
        assertEquals(85f, twin.scrollSpeed, "round5(500/6)=round5(83.3)=85")
    }

    @Test
    fun `坠星残响第 5 发：红色拖尾 + 三枚同位光斑 顺向与横向光柱`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_starfall_echo_shot_final", weaponRangeSu = 1000f))

        // 红色主色（starfallFinalRed）：拖尾头部色 mix 白后仍红>蓝；螺栓弹头染红近白
        val twin = vfx.tree.staticTrails.first { it.first == "twin" }.second
        assertTrue(twin.headColor.red > twin.headColor.blue, "第 5 发拖尾应为红色系")
        assertEquals(0.9f, twin.glowPower, "第 5 发 trailGlow 0.9")
        val bolt = assertNotNull(vfx.tree.bolt)
        assertTrue(bolt.color.red > bolt.color.blue, "第 5 发螺栓弹头染色偏红")

        // 弹头 SMOOTH 光斑（boltFlare=60）+ 三枚同位光斑
        assertEquals(listOf("boltGlow", "light", "pillar", "pillar_cross"), vfx.tree.boxFlares.map { it.first })
        val boltGlow = vfx.tree.boxFlares.first { it.first == "boltGlow" }.second
        assertEquals(60f, boltGlow.width)
        val light = vfx.tree.boxFlares.first { it.first == "light" }.second
        assertEquals(BoxFlareStyle.SMOOTH, light.style)
        assertEquals(48f, light.width)
        assertEquals(48f, light.height)
        val pillar = vfx.tree.boxFlares.first { it.first == "pillar" }.second
        assertEquals(BoxFlareStyle.SHARP_DISC, pillar.style)
        assertEquals(220f, pillar.width)
        assertEquals(26f, pillar.height)
        assertEquals(0f, pillar.facingOffsetDeg, "顺向光柱不偏移朝向")
        val pillarCross = vfx.tree.boxFlares.first { it.first == "pillar_cross" }.second
        assertEquals(BoxFlareStyle.SHARP_DISC, pillarCross.style)
        assertEquals(160f, pillarCross.width)
        assertEquals(18f, pillarCross.height)
        assertEquals(90f, pillarCross.facingOffsetDeg, "横向光柱转 90°")

        // 共振红航迹发射器：碎片/环口径 ×2（shardLength 68、环半径 70），节拍与普通弹一致
        val wake = vfx.tree.shardWakes.first { it.first == "wake" }.second
        assertEquals(68f, wake.shardLength)
        assertTrue(wake.fringeColor.red > wake.fringeColor.blue, "第 5 发碎片应为共振红色系")
        val ring = vfx.tree.machRings.first { it.first == "ring" }.second
        assertEquals(70f, ring.halfSize)
        assertEquals(0.1f, ring.interval, 1e-6f)
        assertTrue(ring.color.red > ring.color.blue, "第 5 发马赫环应为共振红色系")
    }

    @Test
    fun `简单 spec 蓝图拓扑：Box 螺栓 + 四层 Static Trail 拖尾`() {
        // 坠星残翼主弹：Box 螺栓染 boltColor 近白单色系 + twin 外带 + smooth 核心 + zappy 装饰×2 按声明序叠层
        // + 三角碎片/马赫环航迹发射器。
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_starfall_wing_shot"))
        val bolt = assertNotNull(plain.tree.bolt)
        assertEquals(229 / 255f, bolt.color.red, 1e-3f, "boltColor(violet)=mix(主色, 白, 0.7)=0.898，hex 舍入 229")
        assertEquals(211 / 255f, bolt.color.green, 1e-3f, "0.826，hex 舍入 211")
        assertEquals(1f, bolt.color.blue, 1e-3f)
        assertEquals(199 / 255f, bolt.color.alpha, 1e-3f, "原版 coreColor 近白口径 alpha 0.78，hex 舍入 199")

        assertEquals(listOf("twin", "core", "zappy_0", "zappy_1"), plain.tree.staticTrails.map { it.first })

        // bandWidth(34, 2.2)=round05(max(11.9, 6.93))=12 ×2 = 24；核心 ×0.5=12；装饰 ×0.6=14.5
        val twin = plain.tree.staticTrails.first { it.first == "twin" }.second
        val core = plain.tree.staticTrails.first { it.first == "core" }.second
        val zappy = plain.tree.staticTrails.first { it.first == "zappy_0" }.second
        assertEquals(24f, twin.width)
        assertEquals(12f, core.width)
        assertEquals(14.5f, zappy.width)
        assertEquals(-45f..45f, zappy.angularOutRange, "简单 spec 的 zappy 装饰层同样带默认尾端自旋")
        assertEquals(TrailDriftRange(-12f, -6f, 12f, 6f), zappy.velocityOutRange)
        assertEquals(0.8f, core.glowPower, "坠星残翼 trailGlow 0.8 全层统一")
        assertEquals(0.8f, twin.glowPower, "外带同样吃 trailGlow")
        assertEquals(listOf(1, 2, 3, 3), plain.tree.staticTrails.map { it.second.layer })
        plain.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(450f, spec.bandLength)
            assertNull(spec.recede, "recede 不声明 = 自动取弹体长度 ×0.2")
        }

        // 航迹发射器：同色三角碎片（0.01s×2，向前慢速零散布）+ 马赫环（0.1s，半径 35）
        val wake = plain.tree.shardWakes.first { it.first == "wake" }.second
        assertEquals(0.01f, wake.interval, 1e-6f)
        assertEquals(2, wake.perTick)
        assertEquals(0f, wake.spreadDeg, "坠星残翼碎片统一向前飞行（设计案特效节）")
        assertEquals(34f, wake.shardLength)
        val ring = plain.tree.machRings.first { it.first == "ring" }.second
        assertEquals(0.1f, ring.interval, 1e-6f)
        assertEquals(35f, ring.halfSize)
        assertTrue(ring.color.blue > ring.color.red, "坠星残翼马赫环应为紫色系（蓝>红）")

        assertEquals(0.18f, plain.policy.removedFadeOutSeconds)
        assertEquals(0.1f, plain.policy.hitFadeOutSeconds)
        assertEquals(0.22f, plain.policy.expireFadeOutSeconds)
        assertNull(plain.policy.headLeadWorld)
    }

    @Test
    fun `未知 spec 返回 null；已接入 spec 均可构建`() {
        assertEquals(null, ProjectileVfxSpecs.build("astd_does_not_exist"))
        // 抽查若干已接入。
        assertTrue(ProjectileVfxSpecs.has("astd_starfall_echo_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_starfall_echo_shot_final"))
        assertTrue(ProjectileVfxSpecs.has("astd_starfall_wing_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_starfall_wing_mote"))
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
        // 炮口锥面冲击 + 发射点扭曲两个发射钩子（顺序：muzzleBurst 先于 extra 块登记）
        assertEquals(2, vfx.onFire.size, "贯星之矛带炮口冲击与发射点扭曲两个发射钩子")

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
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_electric_drive_accelerator_shot", weaponRangeSu = 750f))
        vfx.tree.staticTrails.forEach { (_, spec) ->
            assertEquals(190f, spec.bandLength, "round5(750×0.25)=190")
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

    @Test
    fun `坠星残翼子射弹：导弹螺栓显式接管 尺寸覆盖 28×8`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_starfall_wing_mote"))
        val bolt = assertNotNull(vfx.tree.bolt, "子射弹为 MissileAPI，bolt 默认自禁用，须显式 onMissile 接管")
        assertTrue(bolt.allowMissile, "子射弹 bolt 须显式接管导弹")
        assertEquals(28f, bolt.lengthOverride, "导弹 spec 无 length 键，显式给定全长 28")
        assertEquals(8f, bolt.widthOverride, "导弹 spec 无 width 键，显式给定全宽 8")
    }

}
