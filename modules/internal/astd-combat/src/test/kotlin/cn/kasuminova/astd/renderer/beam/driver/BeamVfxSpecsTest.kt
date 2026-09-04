package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.impl.render.AnnihilationVortexVortexComponent
import cn.kasuminova.astd.impl.render.BeamCoreComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 光束 spec 的装配自检：验证 [BeamVfxSpecs] 构建的场景树拓扑（束体/detail/impact 三节点齐活、按序）。
 * 节点内部束体/螺旋/回流的观感一致性由目检兜底；本测试只盯 spec → 树装配。
 */
class BeamVfxSpecsTest {

    @Test
    fun `psi_omega 装配束体+螺旋+虹吸三节点且按 renderOrder 升序`() {
        assertTrue(BeamVfxSpecs.has("astd_psi_omega"))
        val tree = assertNotNull(BeamVfxSpecs.build("astd_psi_omega"))
        assertEquals(
            setOf("astd_psi_omega_core", "astd_psi_omega_helix", "astd_psi_omega_siphon"),
            tree.children.map { it.id }.toSet(),
        )
        // 三节点同层，按 renderOrder 升序：core(100) < helix(200) < siphon(300)。
        val orders = tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序")
        assertTrue(tree.children.first { it.id == "astd_psi_omega_core" } is BeamCoreComponent)

        assertNull(BeamVfxSpecs.build("astd_does_not_exist"))
    }

    @Test
    fun `gravityCollapse 装配束体+环+炮口+nebula+微束+螺旋六节点并按绘制层序返回`() {
        val tree = BeamVfxSpecs.gravityCollapse(scale = 1f, beamWidthMul = 1f, startupBurst = true)
        // 六节点齐活、ID 无碰撞（LinkedHashMap 去重，count==6 即证无重复 ID）。
        assertEquals(
            setOf(
                "astd_gravity_collapse_core",
                "astd_gravity_collapse_rings",
                "astd_gravity_collapse_muzzle",
                "astd_gravity_collapse_ambient",
                "astd_gravity_collapse_micro",
                "astd_gravity_collapse_helix",
            ),
            tree.children.map { it.id }.toSet(),
        )
        assertEquals(6, tree.children.size, "六节点不得因 ID 碰撞被去重")

        // 束体走公共 4 件套节点。
        assertTrue(tree.children.first { it.id == "astd_gravity_collapse_core" } is BeamCoreComponent)

        // 子节点跨两层（粒子层/舰船之上层），children 须按 (层序, renderOrder) 复合升序返回，锁定绘制层次。
        val keys = tree.children.map { it.layer.ordinal to it.renderOrder }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys, "子节点须按 (层, renderOrder) 升序")
    }

    @Test
    fun `annihilation_vortex 装配束体+涡旋两节点且按 renderOrder 升序`() {
        assertTrue(BeamVfxSpecs.has("astd_annihilation_vortex"))
        val tree = assertNotNull(BeamVfxSpecs.build("astd_annihilation_vortex"))
        assertEquals(
            setOf("astd_annihilation_vortex_core", "astd_annihilation_vortex_vortex"),
            tree.children.map { it.id }.toSet(),
        )
        // 两节点按 renderOrder 升序：core(100) < vortex(200)。
        val orders = tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序")
        assertTrue(tree.children.first { it.id == "astd_annihilation_vortex_core" } is BeamCoreComponent)
        // 涡旋节点默认半径为玩家 v2 档 187.5（运行时由 BeamEffect 按难度档位覆写）。
        val vortex = tree.children.first { it.id == "astd_annihilation_vortex_vortex" }
        assertTrue(vortex is AnnihilationVortexVortexComponent)
        assertEquals(187.5f, (vortex as AnnihilationVortexVortexComponent).vortexRadius, 1e-4f)
    }
}
