package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.CurveCoreComponent
import cn.kasuminova.astd.impl.render.MeshComponent
import cn.kasuminova.astd.impl.render.MistComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 手写 DSL spec 的装配自检：验证 [ProjectileVfxSpecs] 的构建函数产出的场景树拓扑与驱动策略。
 */
class ProjectileVfxSpecsTest {

    @Test
    fun `aod7 组件齐活且曲线弹芯取代 glow body 时排除 BoxUtil 直线拖尾`() {
        val vfx = assertNotNull(ProjectileVfxSpecs.build("astd_aod7_shot"))
        val childIds = vfx.tree.children.map { it.id }

        // 有 curveCore → 无 BoxUtil 直线拖尾节点；P0 起 glow/body 网格合并为单条曲线弹芯。
        // 5 个组件节点按 renderOrder 升序：mist(50)/core(100)/sideWisp(240)/head(300)/ribbon(360)。
        assertEquals(
            listOf(
                "astd_aod7_shot_mist",
                "astd_aod7_shot_core",
                "astd_aod7_shot_side_wisp",
                "astd_aod7_shot_head",
                "astd_aod7_shot_ribbon",
            ),
            childIds,
        )
        val orders = vfx.tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序")
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_mist" } is MistComponent)
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_core" } is CurveCoreComponent)
        assertTrue(vfx.tree.children.first { it.id == "astd_aod7_shot_head" } is MeshComponent)
    }

    @Test
    fun `aod7 策略逐字段对齐旧 preset(尺寸放大不改 length cap 故 startWidth 例外)`() {
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
        assertEquals(96f, p.primaryTrailStartWidth)  // 目检上调（40→96 使 body widthBase≈2×）；viewportTailCap 由 layoutRef 主导，length 不变
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
