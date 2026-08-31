package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.AnchorArcComponent
import cn.kasuminova.astd.impl.render.BloomMeshComponent
import cn.kasuminova.astd.impl.render.BoxFlareComponent
import cn.kasuminova.astd.impl.render.BoxFlareStyle
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

}
