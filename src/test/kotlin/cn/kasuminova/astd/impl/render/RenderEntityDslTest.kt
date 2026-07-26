package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderLayer
import com.fs.starfarer.api.combat.CombatEngineLayers
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P0 自检：直接调 DSL 构建树，验证结构挂载、setter 生效、按层定序、运行时增删的 onDetach 配对。
 * 不做源码 contain 测试，不依赖引擎。
 */
class RenderEntityDslTest {

    private fun sample(): RenderEntity = renderEntity("business") {
        head("head") { color(Color.RED); width(24f); length(138f) }
        trail("primary") { color(Color.BLUE); width(40f); length(360f) }
        trail("secondary") { length(500f) }
        child { renderEntity("subeffect") {} }
    }

    @Test
    fun `DSL 挂载全部组件——含此前漏挂父节点的 trail`() {
        val tree = sample()
        assertEquals(
            listOf("head", "primary", "secondary", "subeffect"),
            tree.children.map { it.id },
        )
    }

    @Test
    fun `setter 真实写入参数，省略即默认`() {
        val tree = sample()

        val head = tree.children.first { it.id == "head" } as RenderEntityImpl.HeadComponent
        assertEquals(Color.RED, head.color)
        assertEquals(24f, head.width)
        assertEquals(138f, head.length)

        val primary = tree.children.first { it.id == "primary" } as RenderEntityImpl.TrailComponent
        assertEquals(Color.BLUE, primary.color)
        assertEquals(40f, primary.width)
        assertEquals(360f, primary.length)

        val secondary = tree.children.first { it.id == "secondary" } as RenderEntityImpl.TrailComponent
        assertEquals(500f, secondary.length)
        assertEquals(Color.WHITE, secondary.color) // 未设置 → 默认
        assertEquals(12f, secondary.width)          // 未设置 → 默认
    }

    @Test
    fun `children 按 layer 升序，同层保持插入序`() {
        val root = RenderEntityImpl("root")
        // 乱序挂入：高层先、低层后；两个同层节点验证层内插入序
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
        // 同为 ABOVE_PARTICLES，仅 renderOrder 不同；两个 0 值节点验证同序内的插入序稳定。
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
        assertEquals(1, first.detachCount)  // 被顶替 → onDetach
        assertEquals(0, second.detachCount)
    }

    /** 最小 RenderEntity，用于无条件计数 onDetach 调用（不经 RenderEntityImpl 的附着态守卫）。 */
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
        override fun onDetach() { detachCount++ }
    }
}
