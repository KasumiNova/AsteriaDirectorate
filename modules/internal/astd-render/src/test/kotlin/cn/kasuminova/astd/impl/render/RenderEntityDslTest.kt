package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderLayer
import com.fs.starfarer.api.combat.CombatEngineLayers
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderEntityDslTest {

    private fun sample(): RenderEntity = renderEntity("business") {
        addChild(RenderEntityImpl("head"))
        addChild(RenderEntityImpl("primary"))
        addChild(RenderEntityImpl("secondary"))
        child { renderEntity("subeffect") {} }
    }

    @Test
    fun `DSL 挂载全部子节点`() {
        val tree = sample()
        assertEquals(
            listOf("head", "primary", "secondary", "subeffect"),
            tree.children.map { it.id },
        )
    }

    @Test
    fun `children 按 layer 升序，同层保持插入序`() {
        val root = RenderEntityImpl("root")
        root.addChild(RenderEntityImpl("top", CombatEngineLayers.JUST_BELOW_WIDGETS))
        root.addChild(RenderEntityImpl("mid-a", CombatEngineLayers.ABOVE_SHIPS_LAYER))
        root.addChild(RenderEntityImpl("bottom", CombatEngineLayers.BELOW_SHIPS_LAYER))
        root.addChild(RenderEntityImpl("mid-b", CombatEngineLayers.ABOVE_SHIPS_LAYER))

        assertEquals(
            listOf("bottom", "mid-a", "mid-b", "top"),
            root.children.map { it.id },
        )
    }

    @Test
    fun `同层内按 renderOrder 升序，相同 renderOrder 保持插入序`() {
        val root = RenderEntityImpl("root")
        root.addChild(RenderEntityImpl("body", CombatEngineLayers.ABOVE_PARTICLES, 200))
        root.addChild(RenderEntityImpl("glow", CombatEngineLayers.ABOVE_PARTICLES, 100))
        root.addChild(RenderEntityImpl("trail-a", CombatEngineLayers.ABOVE_PARTICLES, 0))
        root.addChild(RenderEntityImpl("trail-b", CombatEngineLayers.ABOVE_PARTICLES, 0))

        assertEquals(
            listOf("trail-a", "trail-b", "glow", "body"),
            root.children.map { it.id },
        )
    }

    @Test
    fun `removeChild 从子集移除并触发子节点 onDetach（防句柄泄漏）`() {
        val root = RenderEntityImpl("root")
        val child = DetachCountingEntity("x")
        root.addChild(child)
        assertEquals(listOf("x"), root.children.map { it.id })

        root.removeChild("x")
        assertEquals(emptyList(), root.children.map { it.id })
        assertEquals(1, child.detachCount)
    }

    @Test
    fun `addChild 同 id 覆盖时释放被顶替者`() {
        val root = RenderEntityImpl("root")
        val first = DetachCountingEntity("dup")
        val second = DetachCountingEntity("dup")
        root.addChild(first)
        root.addChild(second)

        assertEquals(1, root.children.size)
        assertEquals(1, first.detachCount)
        assertEquals(0, second.detachCount)
    }

    private class DetachCountingEntity(override val id: String) : RenderEntity {
        override val layer: RenderLayer = CombatEngineLayers.ABOVE_PARTICLES
        override val children: List<RenderEntity> = emptyList()
        var detachCount = 0
        override fun addChild(child: RenderEntity) {}
        override fun removeChild(id: String) {}
        override fun onAttach(ctx: RenderContext): Boolean = true
        override fun advance(ctx: RenderContext, amount: Float) {}
        override fun render(ctx: RenderContext) {}
        override fun beginFadeOut(reason: FadeReason, seconds: Float) {}
        override fun onDetach() {
            detachCount++
        }
    }
}
