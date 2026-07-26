package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.BloomMeshComponent
import cn.kasuminova.astd.impl.render.MeshComponent
import cn.kasuminova.astd.impl.render.TexTrailComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 手写 DSL spec 的装配自检：验证 [ProjectileVfxSpecs] 的构建函数产出的场景树拓扑与驱动策略。
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
    fun `无贴图拖尾的 spec 弹头保持直绘网格组件`() {
        // mnl_omega_grid 无 texTrail：弹头不走 bloom 管线，保持 BodyRenderManager 直绘
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_mnl_omega_grid"))
        val head = vfx.tree.children.first { it.id == "astd_mnl_omega_grid_head" }
        assertTrue(head is MeshComponent)
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
    fun `spc3 仅辉光与弹体两节点且策略对齐旧 preset`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_spc3_shot"))
        assertEquals(setOf("astd_spc3_shot_glow", "astd_spc3_shot_body"), vfx.tree.children.map { it.id }.toSet())

        val p = vfx.policy
        assertEquals(135f, p.distanceWindow)
        assertEquals(135f, p.primaryTrailLength)
        assertEquals(6f, p.primaryTrailStartWidth)
        assertEquals(0.18f, p.removedFadeOutSeconds)
        assertEquals(0.1f, p.hitFadeOutSeconds)
        assertEquals(0.22f, p.expireFadeOutSeconds)
        assertEquals(1280f, p.layoutReferenceWidth)
    }

    @Test
    fun `带 ribbon 与 head 的简单 spec 装配对应条件节点`() {
        // mnl_omega_grid：glowScale=3.0、ribbon+head。应有 glow/body/ribbon/head，无 mist/sideWisp。
        val both = assertNotNull(ProjectileVfxSpecs.build("astd_mnl_omega_grid"))
        assertEquals(
            listOf("astd_mnl_omega_grid_glow", "astd_mnl_omega_grid_body", "astd_mnl_omega_grid_head", "astd_mnl_omega_grid_ribbon"),
            both.tree.children.map { it.id },
        )

        // slt3_pulse：仅 ribbon，无 head。应有 glow/body/ribbon。
        val ribbonOnly = assertNotNull(ProjectileVfxSpecs.build("astd_slt3_pulse"))
        assertEquals(
            setOf("astd_slt3_pulse_glow", "astd_slt3_pulse_body", "astd_slt3_pulse_ribbon"),
            ribbonOnly.tree.children.map { it.id }.toSet(),
        )

        // drv9_slug：无 ribbon/head。仅 glow/body。
        val plain = assertNotNull(ProjectileVfxSpecs.build("astd_drv9_slug"))
        assertEquals(setOf("astd_drv9_slug_glow", "astd_drv9_slug_body"), plain.tree.children.map { it.id }.toSet())
    }

    @Test
    fun `stellar_jet_bolt 已迁移并构建 glow+body 树；未知 spec 返回 null`() {
        // stellar jet bolt 由 StellarJetEmitterEveryFrameEffect 每帧 spawn，现已改走新管线（发射器直连 driver.track）。
        // 通用构建器（glowScale 有值、无 ribbon/head）→ 树只含 glow + body，与 drv9 同形。
        assertTrue(ProjectileVfxSpecs.has("astd_stellar_jet_bolt"))
        val bolt = assertNotNull(ProjectileVfxSpecs.build("astd_stellar_jet_bolt"))
        assertEquals(
            setOf("astd_stellar_jet_bolt_glow", "astd_stellar_jet_bolt_body"),
            bolt.tree.children.map { it.id }.toSet(),
        )
        assertEquals(240f, bolt.policy.primaryTrailLength, 1e-3f)

        assertEquals(null, ProjectileVfxSpecs.build("astd_does_not_exist"))
        // 抽查若干已迁移。
        assertTrue(ProjectileVfxSpecs.has("astd_aod7_shot"))
        assertTrue(ProjectileVfxSpecs.has("astd_gsp12_rift"))
        assertTrue(ProjectileVfxSpecs.has("astd_sgl8_swarm"))
    }
}
