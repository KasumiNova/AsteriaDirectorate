package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.impl.render.ASTDTrailLayerSpec
import cn.kasuminova.astd.impl.render.BloomMeshComponent
import cn.kasuminova.astd.impl.render.TexTrailComponent
import cn.kasuminova.astd.impl.render.TexTrailSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ProjectileVfxTreeAssembler] 组装自检：蓝图（[ProjectileVfxTreeSpec]）→ RenderEntity 场景树的
 * 组件类型 / 节点 id / renderOrder 排序（弹头 300 垫底、贴图拖尾 360+layer 其上）。
 */
class ProjectileVfxTreeAssemblerTest {

    private fun spec(head: Boolean): ProjectileVfxTreeSpec = ProjectileVfxTreeSpec(
        id = "asm_test",
        trailLayer = ASTDTrailLayerSpec(
            startWidth = 12f,
            length = 200f,
            startColor = ASTDColor(1f, 1f, 1f, 1f),
            startEmissive = ASTDColor(1f, 1f, 1f, 1f),
            endColor = ASTDColor(1f, 1f, 1f, 1f),
        ),
        head = if (head) {
            ASTDProjectileVfxHeadLayerSpec(
                length = 120f,
                width = 24f,
                shoulderRatio = 0.5f,
                rearRatio = 0.95f,
                shellColorStart = ASTDColor(0f, 0f, 0f, 0.08f),
                shellColorMid = ASTDColor(0.72f, 0.94f, 1f, 0.46f),
                shellColorEnd = ASTDColor(1f, 1f, 1f, 0.98f),
                blur = 0.35f,
                alphaScale = 1f,
            )
        } else {
            null
        },
        headSizeScale = 1.5f,
        texTrails = listOf(
            "twin" to texTrailSpec(layer = 1),
            "zappy" to texTrailSpec(layer = 2),
        ),
        boxFlares = emptyList(),
        anchorArcs = emptyList(),
    )

    private fun texTrailSpec(layer: Int) = TexTrailSpec(
        width = 12f,
        texturePath = "graphics/fx/astd_trails_twin.png",
        layer = layer,
        headColor = ASTDColor(1f, 1f, 1f, 0.92f),
        tailColor = ASTDColor(0.04f, 0.11f, 0.22f, 0.06f),
    )

    @Test
    fun `弹头层组装为 BloomMeshComponent 且子节点按 renderOrder 升序`() {
        val tree = ProjectileVfxTreeAssembler.assemble(spec(head = true))

        assertEquals(
            listOf("asm_test_head", "asm_test_textrail_twin", "asm_test_textrail_zappy"),
            tree.children.map { it.id },
        )
        assertIs<BloomMeshComponent>(tree.children.first { it.id == "asm_test_head" })
        assertIs<TexTrailComponent>(tree.children.first { it.id == "asm_test_textrail_twin" })
        val orders = tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序（弹头 300 < 拖尾 360+layer）")
    }

    @Test
    fun `无弹头层时仅拖尾节点`() {
        val tree = ProjectileVfxTreeAssembler.assemble(spec(head = false))

        assertEquals(listOf("asm_test_textrail_twin", "asm_test_textrail_zappy"), tree.children.map { it.id })
        assertTrue(tree.children.none { it.id == "asm_test_head" })
    }
}
