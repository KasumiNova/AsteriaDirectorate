package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomMeshComponent
import cn.kasuminova.astd.impl.render.TexTrailComponent
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 手写 DSL spec 的装配自检：验证 [ProjectileVfxSpecs] 的构建函数产出的场景树拓扑与驱动策略。
 *
 * 三层混合改版后：简单 spec = trail 风格声明（驱动锚点）+ twin 外带 / smooth 核心 / zappy 装饰三条贴图拖尾
 * （弹头改由原版弹体渲染承担，代码弹头仅 aod7 hero 保留），全部参数由文件底部常量与公式纯函数派生——
 * 本测试含公式数值锚点与全 10 个简单 spec 的接线守护。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 由网格弹头与两条贴图拖尾组成`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))
        val childIds = vfx.tree.children.map { it.id }

        // 贴图拖尾即拖尾主体：twin(layer1 垫底) + zappy(layer2)；弹头为代码网格 head{}；trail{} 仅风格声明。
        // 3 个组件节点按 renderOrder 升序：head(300)/twin(361)/zappy(362)。
        // aod7 豁免三层混合改版：无 twist、宽度不翻倍、head{} 保留。
        assertEquals(
            listOf(
                "astd_aod7_shot_head",
                "astd_aod7_shot_textrail_twin",
                "astd_aod7_shot_textrail_zappy",
            ),
            childIds,
        )
        val orders = vfx.tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序")
        val twin = vfx.tree.children.first { it.id == "astd_aod7_shot_textrail_twin" } as TexTrailComponent
        val zappy = vfx.tree.children.first { it.id == "astd_aod7_shot_textrail_zappy" } as TexTrailComponent
        assertEquals(0f, twin.spec.twistMaxAngleDeg, "aod7 豁免随机扭转")
        assertEquals(0f, zappy.spec.twistMaxAngleDeg, "aod7 豁免随机扭转")
        // 有贴图拖尾时弹头并入 bloom 管线（BloomMeshComponent），与拖尾能量同源消除接缝色差
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_head" } is BloomMeshComponent)
    }

    @Test
    fun `aod7 策略逐字段对齐旧 preset 锚点取 trail 长宽`() {
        val p = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot")).policy
        assertEquals(2f, p.minDistancePerNode)
        assertEquals(96, p.maxHistoryNodes)
        assertEquals(420f, p.distanceWindow)
        assertEquals(60f, p.historyFps)
        assertEquals(1.25f, p.durationSeconds)
        assertEquals(0.6f, p.dissolveStartRatio)
        assertEquals(1846f, p.layoutReferenceWidth)
        assertEquals(0.15f, p.hitFadeOutSeconds)
        assertEquals(0.15f, p.expireFadeOutSeconds)
        assertEquals(0.15f, p.removedFadeOutSeconds)
        assertEquals(420f, p.primaryTrailLength)
        assertEquals(96f, p.primaryTrailStartWidth)  // trail{} 恢复为锚点来源；viewportTailCap 由 layoutRef 主导
    }

    @Test
    fun `简单 spec 拓扑：三层贴图拖尾 无代码弹头`() {
        // spc3：twin 外带(361) + smooth 核心(362) + zappy 装饰(363) 升序；弹头由原版弹体渲染承担（无 head 节点）；
        // 策略锚点取 trail{} 长宽（数值不动，viewportTailCap 零回归）
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        assertEquals(
            listOf(
                "astd_spc3_shot_textrail_twin",
                "astd_spc3_shot_textrail_core",
                "astd_spc3_shot_textrail_zappy",
            ),
            plain.tree.children.map { it.id },
        )
        assertTrue(plain.tree.children.none { it.id == "astd_spc3_shot_head" }, "简单 spec 不再有代码弹头")
        assertEquals(135f, plain.policy.primaryTrailLength)
        assertEquals(6f, plain.policy.primaryTrailStartWidth)
        assertEquals(0.18f, plain.policy.removedFadeOutSeconds)
        assertEquals(0.1f, plain.policy.hitFadeOutSeconds)
        assertEquals(0.22f, plain.policy.expireFadeOutSeconds)
        assertEquals(1280f, plain.policy.layoutReferenceWidth)
    }

    @Test
    fun `未知 spec 返回 null；已接入 spec 均可构建`() {
        assertEquals(null, ProjectileVfxSpecs.build("astd_does_not_exist"))
        // 抽查若干已迁移。
        assertTrue(ProjectileVfxSpecs.has("astd_aod7_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_spc3_shot"))
    }

    // —— 公式数值锚点（公式实现被手滑改动时在此暴露）——

    @Test
    fun `宽度公式锚点：0 35 倍旧宽与 3 15 倍体型档取大 按 0 5 取整`() {
        assertEquals(7.0f, bandWidth(6f, 2.2f))     // spc3：3.15×2.2=6.93 主导
        assertEquals(8.0f, bandWidth(12f, 2.6f))    // 8.19→8.0
        assertEquals(11.5f, bandWidth(20f, 3.6f))   // 11.34→11.5
        assertEquals(7.0f, bandWidth(16f, 2.2f))    // 0.35×16=5.6 不主导
        assertEquals(5.5f, arcWidth(7.0f))          // 装饰带 0.8×外带
        // 三层混合常量（美术裁定）：宽度翻倍 / 核心半宽 / alpha 0.45-0.6-0.45（初版 0.6-0.8-0.6 过曝 ×0.75）/ twist ±90°/±30°
        assertEquals(2f, BAND_WIDTH_MULT)
        assertEquals(0.5f, CORE_WIDTH_RATIO)
        assertEquals(0.45f, ALPHA_OUTER)
        assertEquals(0.6f, ALPHA_CORE)
        assertEquals(0.45f, ALPHA_DECOR)
        assertEquals(90f, TWIST_OUTER_DEG)
        assertEquals(30f, TWIST_INNER_DEG)
    }

    @Test
    fun `平铺 滚动 节点 退距公式锚点`() {
        assertEquals(55f, mainTile(135f))
        assertEquals(100f, mainTile(240f))
        assertEquals(25f, mainScroll(135f))
        assertEquals(40f, mainScroll(240f))
        assertEquals(70f, arcTile(135f))
        assertEquals(30f, arcScroll(135f))
        assertEquals(16, trailNodes(135f))          // 下限 16
        assertEquals(24, trailNodes(420f))          // 上限 24
        assertEquals(21, trailNodes(340f))
        assertEquals(20f, headRecede(250f))
        assertEquals(25f, headRecede(310f))
    }

    @Test
    fun `颜色公式锚点：头部近白高亮 中段主色 尾部压暗`() {
        val blue = ASTDColor(0.2f, 0.55f, 1f, 0.92f)

        val head = bandHeadColor(blue)
        assertEquals(0.2f + 0.8f * 0.45f, head.red, 1e-3f)   // mix(主色, 白, 0.45)——保持主色饱和，防与弹头光晕色差
        assertEquals(0.92f * 0.78f, head.alpha, 1e-3f)
        val headDim = bandHeadColor(blue, 0.45f)             // 外带/装饰层 alpha×0.45
        assertEquals(0.92f * 0.78f * 0.45f, headDim.alpha, 1e-3f)

        val mid = bandMidColor(blue)
        assertEquals(0.2f, mid.red, 1e-3f)
        assertEquals(0.92f * 0.55f, mid.alpha, 1e-3f)

        val tail = bandTailColor(blue)
        assertEquals(0.2f * 0.16f, tail.red, 1e-3f)
        assertEquals(0.07f, tail.alpha, 1e-3f)
    }

    // —— 全 10 个简单 spec 接线守护：登记表参数与工厂公式接线漂移时在此暴露（aod7 为 hero 不在此表）——

    private data class Row(
        val id: String,
        val color: ASTDColor,
        val width: Float,
        val length: Float,
        val glowScale: Float,
    )

    private val violet = ASTDColor(0.66f, 0.42f, 1f, 0.9f)
    private val arcColdBlue = ASTDColor(0.55f, 0.78f, 1f, 0.9f)

    private val rows = listOf(
        Row("astd_spc3_shot", violet, 6f, 135f, 2.2f),
        Row("astd_charge_needle_shot", arcColdBlue, 6f, 135f, 2.2f),
        Row("astd_heavy_charge_needle_shot", arcColdBlue, 9f, 165f, 2.2f),
        Row("astd_electric_drive_accelerator_shot", ASTDColor(1f, 1f, 1f, 0.9f), 9f, 500f, 2.2f),
        Row("astd_qiongjue_phase_railgun_shot", ASTDColor(0.92f, 0.95f, 1f, 1f), 12f, 300f, 2.2f),
        Row("astd_positron_shockwave_shot", ASTDColor(0.62f, 0.82f, 1f, 0.85f), 5f, 90f, 2.2f),
        Row("astd_heavy_ion_pulse_shot", arcColdBlue, 12f, 220f, 2.2f),
        Row("astd_stellar_mrm_launcher_shot", violet, 10f, 420f, 2.2f),
        Row("astd_stellar_mrm_pod_shot", violet, 10f, 420f, 2.2f),
        Row("astd_piercing_lance_shot", ASTDColor(0.55f, 0.78f, 1f, 0.95f), 36f, 260f, 4.0f),
    )

    @Test
    fun `全简单 spec 的三层贴图拖尾参数等于公式计算值`() {
        assertEquals(10, rows.size, "简单 spec 行数与登记表一致（aod7 为 hero 不在此表）")
        for (row in rows) {
            val vfx = assertNotNull(ProjectileVfxSpecs.build(row.id), row.id)
            val children = vfx.tree.children.associateBy { it.id }
            assertEquals(
                listOf("${row.id}_textrail_twin", "${row.id}_textrail_core", "${row.id}_textrail_zappy"),
                vfx.tree.children.map { it.id },
                row.id,
            )

            val bandW = bandWidth(row.width, row.glowScale) * BAND_WIDTH_MULT
            val nodeCount = trailNodes(row.length)
            val recede = headRecede(row.length)

            // twin 外带：全宽、alpha 0.45、±90° 扭转
            val twin = children.getValue("${row.id}_textrail_twin") as TexTrailComponent
            assertEquals(TEX_TWIN, twin.spec.texturePath, row.id)
            assertEquals(1, twin.spec.layer, row.id)
            assertEquals(bandW, twin.spec.width, 1e-3f, row.id)
            assertEquals(mainTile(row.length), twin.spec.tileLength, 1e-3f, row.id)
            assertEquals(mainScroll(row.length), twin.spec.scrollSpeed, 1e-3f, row.id)
            assertEquals(nodeCount, twin.spec.nodeCount, row.id)
            assertEquals(recede, twin.spec.recede, 1e-3f, row.id)
            assertEquals(TWIST_OUTER_DEG, twin.spec.twistMaxAngleDeg, row.id)
            assertColorEquals(bandHeadColor(row.color, ALPHA_OUTER), twin.spec.headColor, row.id)
            assertColorEquals(bandMidColor(row.color, ALPHA_OUTER), twin.spec.midColor, row.id)
            assertColorEquals(bandTailColor(row.color, ALPHA_OUTER), twin.spec.tailColor, row.id)

            // smooth 核心：宽度 −50%、alpha 0.6、±30° 扭转
            val core = children.getValue("${row.id}_textrail_core") as TexTrailComponent
            assertEquals(TEX_SMOOTH, core.spec.texturePath, row.id)
            assertEquals(2, core.spec.layer, row.id)
            assertEquals(round05(bandW * CORE_WIDTH_RATIO), core.spec.width, 1e-3f, row.id)
            assertEquals(mainTile(row.length), core.spec.tileLength, 1e-3f, row.id)
            assertEquals(mainScroll(row.length), core.spec.scrollSpeed, 1e-3f, row.id)
            assertEquals(nodeCount, core.spec.nodeCount, row.id)
            assertEquals(recede, core.spec.recede, 1e-3f, row.id)
            assertEquals(TWIST_INNER_DEG, core.spec.twistMaxAngleDeg, row.id)
            assertColorEquals(bandHeadColor(row.color, ALPHA_CORE), core.spec.headColor, row.id)
            assertColorEquals(bandMidColor(row.color, ALPHA_CORE), core.spec.midColor, row.id)
            assertColorEquals(bandTailColor(row.color, ALPHA_CORE), core.spec.tailColor, row.id)

            // zappy 装饰：0.8×外带、更快平铺/滚动、alpha 0.45、±30° 扭转
            val zappy = children.getValue("${row.id}_textrail_zappy") as TexTrailComponent
            assertEquals(TEX_ZAPPY, zappy.spec.texturePath, row.id)
            assertEquals(3, zappy.spec.layer, row.id)
            assertEquals(arcWidth(bandW), zappy.spec.width, 1e-3f, row.id)
            assertEquals(arcTile(row.length), zappy.spec.tileLength, 1e-3f, row.id)
            assertEquals(arcScroll(row.length), zappy.spec.scrollSpeed, 1e-3f, row.id)
            assertEquals(nodeCount, zappy.spec.nodeCount, row.id)
            assertEquals(recede, zappy.spec.recede, 1e-3f, row.id)
            assertEquals(TWIST_INNER_DEG, zappy.spec.twistMaxAngleDeg, row.id)
            assertColorEquals(bandHeadColor(row.color, ALPHA_DECOR), zappy.spec.headColor, row.id)
            assertColorEquals(bandMidColor(row.color, ALPHA_DECOR), zappy.spec.midColor, row.id)
            assertColorEquals(bandTailColor(row.color, ALPHA_DECOR), zappy.spec.tailColor, row.id)
        }
    }

    private fun assertColorEquals(expected: ASTDColor, actual: ASTDColor?, context: String) {
        assertNotNull(actual, context)
        // DSL colors() 走 0xRRGGBBAA 十六进制入参，通道量化到 1/255——期望值先按同一取整量化再比较
        val q = quantize(expected)
        assertEquals(q.red, actual.red, 1e-3f, context)
        assertEquals(q.green, actual.green, 1e-3f, context)
        assertEquals(q.blue, actual.blue, 1e-3f, context)
        assertEquals(q.alpha, actual.alpha, 1e-3f, context)
    }

    private fun quantize(c: ASTDColor): ASTDColor {
        fun ch(v: Float): Float = (v.coerceIn(0f, 1f) * 255f).roundToInt() / 255f
        return ASTDColor(ch(c.red), ch(c.green), ch(c.blue), ch(c.alpha))
    }
}
