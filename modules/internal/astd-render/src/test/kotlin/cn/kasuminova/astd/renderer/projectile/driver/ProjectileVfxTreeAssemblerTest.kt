package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BoltRenderComponent
import cn.kasuminova.astd.impl.render.BoltSpec
import cn.kasuminova.astd.impl.render.BoxFlareComponent
import cn.kasuminova.astd.impl.render.BoxFlareSpec
import cn.kasuminova.astd.impl.render.SpriteBodyRenderComponent
import cn.kasuminova.astd.impl.render.SpriteBodySpec
import cn.kasuminova.astd.impl.render.StaticTrailComponent
import cn.kasuminova.astd.impl.render.StaticTrailSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [ProjectileVfxTreeAssembler] 组装自检：蓝图（[ProjectileVfxTreeSpec]）→ RenderEntity 场景树的
 * 组件类型 / 节点 id / renderOrder 排序（螺栓 200 < 拖尾 360+layer < 光斑 368）。
 */
class ProjectileVfxTreeAssemblerTest {

    private fun treeSpec(): ProjectileVfxTreeSpec = ProjectileVfxTreeSpec(
        id = "asm_test",
        staticTrails = listOf(
            "twin" to trailSpec(layer = 1),
            "zappy" to trailSpec(layer = 2),
        ),
        bolt = BoltSpec(color = ASTDColor(0.6f, 0.85f, 1f, 1f)),
        spriteBody = null,
        boxFlares = listOf(
            "flare" to BoxFlareSpec(
                width = 120f,
                height = 14f,
                coreColor = ASTDColor(1f, 1f, 1f, 1f),
                fringeColor = ASTDColor(0.6f, 0.85f, 1f, 1f),
                glowPower = 1f,
                discRatio = 4f,
                flickerRate = 1.2f,
                noisePower = 0.1f,
                style = cn.kasuminova.astd.impl.render.BoxFlareStyle.SMOOTH_DISC,
                facingOffsetDeg = 0f,
                fixedFacingDeg = null,
                offsetX = 0f,
            )
        ),
        anchorArcs = emptyList(),
    )

    private fun trailSpec(layer: Int) = StaticTrailSpec(
        texturePath = "graphics/fx/astd_trails_twin.png",
        layer = layer,
        width = 12f,
        headColor = ASTDColor(1f, 1f, 1f, 0.92f),
        tailColor = ASTDColor(0.04f, 0.11f, 0.22f, 0.06f),
        bandLength = 200f,
    )

    @Test
    fun `拖尾层组装为 StaticTrailComponent 且子节点按 renderOrder 升序`() {
        val tree = ProjectileVfxTreeAssembler.assemble(treeSpec())

        assertEquals(
            listOf("asm_test_bolt", "asm_test_trail_twin", "asm_test_trail_zappy", "asm_test_boxflare_flare"),
            tree.children.map { it.id },
        )
        assertIs<BoltRenderComponent>(tree.children.first { it.id == "asm_test_bolt" })
        assertIs<StaticTrailComponent>(tree.children.first { it.id == "asm_test_trail_twin" })
        assertIs<BoxFlareComponent>(tree.children.first { it.id == "asm_test_boxflare_flare" })
        val orders = tree.children.map { it.renderOrder }
        assertEquals(orders.sorted(), orders, "子节点须按 renderOrder 升序（螺栓 200 < 拖尾 360+layer < 光斑 368）")
    }

    @Test
    fun `bolt 为 null 时不组装螺栓组件`() {
        val spec = treeSpec().let {
            ProjectileVfxTreeSpec(
                id = it.id,
                staticTrails = it.staticTrails,
                bolt = null,
                spriteBody = null,
                boxFlares = it.boxFlares,
                anchorArcs = it.anchorArcs,
            )
        }
        val tree = ProjectileVfxTreeAssembler.assemble(spec)
        assertEquals(
            listOf("asm_test_trail_twin", "asm_test_trail_zappy", "asm_test_boxflare_flare"),
            tree.children.map { it.id },
        )
    }

    @Test
    fun `spriteBody 声明时组装 SpriteBodyRenderComponent 且绘制序压在螺栓之下`() {
        val base = treeSpec()
        val spec = ProjectileVfxTreeSpec(
            id = base.id,
            staticTrails = base.staticTrails,
            bolt = base.bolt,
            spriteBody = SpriteBodySpec("graphics/fx/astd_ice_shard.png", width = 20f, height = 20f),
            boxFlares = base.boxFlares,
            anchorArcs = base.anchorArcs,
        )
        val tree = ProjectileVfxTreeAssembler.assemble(spec)
        val body = tree.children.first { it.id == "asm_test_spritebody" }
        assertIs<SpriteBodyRenderComponent>(body)
        assertEquals(190, body.renderOrder)
        assertEquals("graphics/fx/astd_ice_shard.png", (body as SpriteBodyRenderComponent).spec.texturePath)
    }

    @Test
    fun `StaticTrailComponent 持有树 id 与层名供 StaticTrailData 池键`() {
        val tree = ProjectileVfxTreeAssembler.assemble(treeSpec())
        val twin = tree.children.first { it.id == "asm_test_trail_twin" } as StaticTrailComponent
        assertEquals(1, twin.spec.layer)
        assertEquals("graphics/fx/astd_trails_twin.png", twin.spec.texturePath)
    }
}
