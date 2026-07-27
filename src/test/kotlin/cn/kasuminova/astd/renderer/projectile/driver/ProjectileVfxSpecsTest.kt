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
 * P2 观感翻译后：简单 spec = trail 风格声明 + texTrail 贴图拖尾主体（+ 可选 arc 副带 / bloom 弹头），
 * 全部参数由文件底部公式纯函数派生——本测试含公式数值锚点与全 23 个 spec 的接线守护。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 由网格弹头与两条贴图拖尾组成 有 trail 声明但不落 BoxUtil 兜底节点`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))
        val childIds = vfx.tree.children.map { it.id }

        // 贴图拖尾即拖尾主体：twin(layer1 垫底) + zappy(layer2)；弹头为代码网格 head{}；trail{} 仅风格声明，
        // 有 texTrail 时不生成 BoxUtil 直线拖尾兜底节点（无 astd_aod7_shot_trail）。
        // 3 个组件节点按 renderOrder 升序：head(300)/twin(361)/zappy(362)。
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
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_textrail_twin" } is TexTrailComponent)
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_textrail_zappy" } is TexTrailComponent)
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
    fun `简单 spec 拓扑：bloom 弹头与主带恒在，arc 副带按开关装配`() {
        // spc3：无 ribbon → bloom 弹头 + 主带（renderOrder 升序：300 < 361）；策略锚点取 trail{} 长宽（数值不动，viewportTailCap 零回归）
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        assertEquals(listOf("astd_spc3_shot_head", "astd_spc3_shot_textrail_main"), plain.tree.children.map { it.id })
        assertTrue(plain.tree.children.first { it.id == "astd_spc3_shot_head" } is BloomMeshComponent)
        assertEquals(135f, plain.policy.primaryTrailLength)
        assertEquals(6f, plain.policy.primaryTrailStartWidth)
        assertEquals(0.18f, plain.policy.removedFadeOutSeconds)
        assertEquals(0.1f, plain.policy.hitFadeOutSeconds)
        assertEquals(0.22f, plain.policy.expireFadeOutSeconds)
        assertEquals(1280f, plain.policy.layoutReferenceWidth)

        // slt3：ribbon → bloom 弹头 + 主带 + arc 副带（300 < 361 < 362）
        val ribbonOnly = assertNotNull(ProjectileVfxSpecs.build("astd_slt3_pulse"))
        assertEquals(
            listOf("astd_slt3_pulse_head", "astd_slt3_pulse_textrail_main", "astd_slt3_pulse_textrail_arc"),
            ribbonOnly.tree.children.map { it.id },
        )
    }

    @Test
    fun `stellar_jet_bolt 构建贴图拖尾树；未知 spec 返回 null`() {
        // stellar jet bolt 由 StellarJetEmitterEveryFrameEffect 每帧 spawn，走新管线（发射器直连 driver.track）。
        assertTrue(ProjectileVfxSpecs.has("astd_stellar_jet_bolt"))
        val bolt = assertNotNull(ProjectileVfxSpecs.build("astd_stellar_jet_bolt"))
        assertEquals(240f, bolt.policy.primaryTrailLength, 1e-3f)
        assertEquals(
            listOf("astd_stellar_jet_bolt_head", "astd_stellar_jet_bolt_textrail_main"),
            bolt.tree.children.map { it.id },
        )

        assertEquals(null, ProjectileVfxSpecs.build("astd_does_not_exist"))
        // 抽查若干已迁移。
        assertTrue(ProjectileVfxSpecs.has("astd_aod7_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_gsp12_rift"))
        assertTrue(ProjectileVfxSpecs.has("astd_sgl8_swarm"))
    }

    // —— 公式数值锚点（公式实现被手滑改动时在此暴露）——

    @Test
    fun `宽度公式锚点：0 35 倍旧宽与 3 15 倍体型档取大 按 0 5 取整`() {
        assertEquals(7.0f, bandWidth(6f, 2.2f))     // spc3：3.15×2.2=6.93 主导
        assertEquals(8.0f, bandWidth(12f, 2.6f))    // drv11：8.19→8.0
        assertEquals(11.5f, bandWidth(20f, 3.6f))   // sgl8：11.34→11.5
        assertEquals(7.0f, bandWidth(16f, 2.2f))    // rct6：0.35×16=5.6 不主导
        assertEquals(5.5f, arcWidth(7.0f))          // 副带 0.8×主带
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
        assertEquals(82.5f, headLength(250f))
        assertEquals(12.5f, headWidth(7.0f))
    }

    @Test
    fun `颜色公式锚点：头部近白高亮 中段主色 尾部压暗`() {
        val blue = ASTDColor(0.2f, 0.55f, 1f, 0.92f)

        val head = bandHeadColor(blue)
        assertEquals(0.2f + 0.8f * 0.45f, head.red, 1e-3f)   // mix(主色, 白, 0.45)——保持主色饱和，防与弹头光晕色差
        assertEquals(0.92f * 0.78f, head.alpha, 1e-3f)
        val headDim = bandHeadColor(blue, 0.8f)              // 双带主带垫底 alpha×0.8
        assertEquals(0.92f * 0.78f * 0.8f, headDim.alpha, 1e-3f)

        val mid = bandMidColor(blue)
        assertEquals(0.2f, mid.red, 1e-3f)
        assertEquals(0.92f * 0.55f, mid.alpha, 1e-3f)

        val tail = bandTailColor(blue)
        assertEquals(0.2f * 0.16f, tail.red, 1e-3f)
        assertEquals(0.07f, tail.alpha, 1e-3f)

        assertEquals(0.2f * 0.25f, shellStartColor(blue).red, 1e-3f)
        assertEquals(0.08f, shellStartColor(blue).alpha, 1e-3f)
        assertEquals(0.2f + 0.8f * 0.55f, shellMidColor(blue).red, 1e-3f)
        assertEquals(0.93f, shellMidColor(blue).alpha, 1e-3f)
        assertEquals(0.2f + 0.8f * 0.85f, shellEndColor(blue).red, 1e-3f)
        assertEquals(0.98f, shellEndColor(blue).alpha, 1e-3f)
    }

    // —— 全 23 个简单 spec 接线守护：登记表参数与工厂公式接线漂移时在此暴露 ——

    private data class Row(
        val id: String,
        val color: ASTDColor,
        val texture: String,
        val arcTexture: String,
        val width: Float,
        val length: Float,
        val glowScale: Float,
        val ribbon: Boolean,
    )

    private val violet = ASTDColor(0.66f, 0.42f, 1f, 0.9f)
    private val amber = ASTDColor(1f, 0.62f, 0.18f, 0.95f)
    private val omega = ASTDColor(0.72f, 0.35f, 1f, 0.96f)
    private val blue = ASTDColor(0.2f, 0.55f, 1f, 0.92f)
    private val teal = ASTDColor(0.22f, 1f, 0.78f, 0.9f)
    private val rose = ASTDColor(1f, 0.34f, 0.42f, 0.94f)
    private val singularity = ASTDColor(0.78f, 0.92f, 1f, 0.96f)
    private val stellar = ASTDColor(1f, 0.92f, 0.74f, 0.92f)

    private val rows = listOf(
        Row("astd_spc3_shot", violet, TEX_SMOOTH, TEX_ZAPPY, 6f, 135f, 2.2f, false),
        Row("astd_stellar_jet_bolt", stellar, TEX_CONTRAIL, TEX_ZAPPY, 10f, 240f, 2.4f, false),
        Row("astd_drv9_slug", amber, TEX_TWIN, TEX_ZAPPY, 10f, 190f, 2.2f, false),
        Row("astd_drv11_slug", amber, TEX_TWIN, TEX_ZAPPY, 12f, 230f, 2.6f, false),
        Row("astd_drv_omega_slug", omega, TEX_LIGHTNING, TEX_LIGHTNING, 14f, 260f, 3.0f, false),
        Row("astd_slt3_pulse", blue, TEX_ZAPPY, TEX_ZAPPY, 8f, 170f, 2.2f, true),
        Row("astd_slt4_burst", blue, TEX_ZAPPY, TEX_ZAPPY, 9f, 190f, 2.2f, true),
        Row("astd_slt_omega_stream", omega, TEX_LIGHTNING, TEX_LIGHTNING, 8f, 240f, 2.2f, true),
        Row("astd_vpd6_pulse", teal, TEX_CLEAN, TEX_ZAPPY, 8f, 180f, 2.2f, false),
        Row("astd_vpd_omega_arc", omega, TEX_LIGHTNING, TEX_LIGHTNING, 9f, 220f, 2.2f, true),
        Row("astd_rct6_torp", rose, TEX_CONTRAIL, TEX_ZAPPY, 16f, 280f, 2.2f, false),
        Row("astd_tsm2_missile", singularity, TEX_CIRCLE, TEX_ZAPPY, 18f, 310f, 3.4f, false),
        Row("astd_tsm_omega_missile", omega, TEX_LIGHTNING, TEX_LIGHTNING, 18f, 330f, 3.3f, false),
        Row("astd_gsp12_rift", singularity, TEX_CIRCLE, TEX_ZAPPY, 18f, 280f, 3.1f, true),
        Row("astd_jmb2_beam", teal, TEX_CLEAN, TEX_ZAPPY, 12f, 260f, 2.5f, false),
        Row("astd_jmb9_beam", blue, TEX_ZAPPY, TEX_ZAPPY, 13f, 280f, 2.6f, false),
        Row("astd_jmb_omega_beam", omega, TEX_LIGHTNING, TEX_LIGHTNING, 15f, 330f, 3.0f, false),
        Row("astd_sgl8_swarm", singularity, TEX_CIRCLE, TEX_ZAPPY, 20f, 340f, 3.6f, false),
        Row("astd_fdp4_charge", amber, TEX_TWIN, TEX_ZAPPY, 14f, 250f, 2.6f, false),
        Row("astd_ftb_omega_beam", omega, TEX_LIGHTNING, TEX_LIGHTNING, 16f, 350f, 3.2f, false),
        Row("astd_mnl2_mine", teal, TEX_CLEAN, TEX_ZAPPY, 13f, 210f, 2.4f, false),
        Row("astd_mnl3_mine", blue, TEX_ZAPPY, TEX_ZAPPY, 14f, 230f, 2.5f, false),
        Row("astd_mnl_omega_grid", omega, TEX_LIGHTNING, TEX_LIGHTNING, 15f, 260f, 3.0f, true),
    )

    @Test
    fun `全简单 spec 的贴图拖尾参数等于公式计算值`() {
        assertEquals(23, rows.size, "简单 spec 行数与登记表一致（aod7 为 hero 不在此表）")
        for (row in rows) {
            val vfx = assertNotNull(ProjectileVfxSpecs.build(row.id), row.id)
            val main = vfx.tree.children.first { it.id == "${row.id}_textrail_main" } as TexTrailComponent
            val bandW = bandWidth(row.width, row.glowScale)
            val mainAlpha = if (row.ribbon) 0.8f else 1f
            val expectedRecede = headRecede(row.length)
            assertEquals(row.texture, main.spec.texturePath, row.id)
            assertEquals(1, main.spec.layer, row.id)
            assertEquals(bandW, main.spec.width, 1e-3f, row.id)
            assertEquals(mainTile(row.length), main.spec.tileLength, 1e-3f, row.id)
            assertEquals(mainScroll(row.length), main.spec.scrollSpeed, 1e-3f, row.id)
            assertEquals(trailNodes(row.length), main.spec.nodeCount, row.id)
            assertEquals(expectedRecede, main.spec.recede, 1e-3f, row.id)
            assertColorEquals(bandHeadColor(row.color, mainAlpha), main.spec.headColor, row.id)
            assertColorEquals(bandMidColor(row.color, mainAlpha), main.spec.midColor, row.id)
            assertColorEquals(bandTailColor(row.color, mainAlpha), main.spec.tailColor, row.id)

            val arc = vfx.tree.children.firstOrNull { it.id == "${row.id}_textrail_arc" }
            if (row.ribbon) {
                arc as TexTrailComponent
                assertEquals(row.arcTexture, arc.spec.texturePath, row.id)
                assertEquals(2, arc.spec.layer, row.id)
                assertEquals(arcWidth(bandW), arc.spec.width, 1e-3f, row.id)
                assertEquals(arcTile(row.length), arc.spec.tileLength, 1e-3f, row.id)
                assertEquals(arcScroll(row.length), arc.spec.scrollSpeed, 1e-3f, row.id)
                assertEquals(expectedRecede, arc.spec.recede, 1e-3f, row.id)
            } else {
                assertEquals(null, arc, row.id)
            }

            // 弹头恒在（目检结论：无弹头的射弹头部观感过平），且恒走 bloom 管线
            assertTrue(vfx.tree.children.first { it.id == "${row.id}_head" } is BloomMeshComponent, row.id)
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
