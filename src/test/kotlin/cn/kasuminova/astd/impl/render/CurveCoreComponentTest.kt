package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class CurveCoreComponentTest {

    private val spec = CurveCoreSpec(
        width = 100f,
        tailWidthScale = 0.1f,
        headColor = ASTDColor(1f, 1f, 1f, 1f),
        tailColor = ASTDColor(0f, 0f, 1f, 0.2f),
        nodeCount = 5,
    )

    @Test
    fun `empty history falls back to straight beam with head at origin`() {
        val nodes = curveCoreNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, spec, 1f)

        assertEquals(5, nodes.size)
        assertEquals(0f, nodes.first().position.x, 0.0001f)
        assertEquals(0f, nodes.first().position.y, 0.0001f)
        assertEquals(-200f, nodes.last().position.x, 0.0001f)
        assertEquals(0f, nodes.last().position.y, 0.0001f)
    }

    @Test
    fun `width tapers from head to tail and color lerps`() {
        val nodes = curveCoreNodes(emptyList(), Vector2f(), 0f, 200f, spec, 1f)

        assertEquals(100f, nodes.first().width, 0.0001f)
        assertEquals(10f, nodes.last().width, 0.0001f)
        assertEquals(1f, nodes.first().color.alpha, 0.0001f)
        assertEquals(0.2f, nodes.last().color.alpha, 0.0001f)
        assertEquals(1f, nodes.first().color.red, 0.0001f)
        assertEquals(0f, nodes.last().color.red, 0.0001f)
        assertEquals(1f, nodes.last().color.blue, 0.0001f)
        // 中点 t=0.5：宽度/颜色各取一半
        assertEquals(55f, nodes[2].width, 0.0001f)
        assertEquals(0.6f, nodes[2].color.alpha, 0.0001f)
    }

    @Test
    fun `intensity scales alpha of every node`() {
        val nodes = curveCoreNodes(emptyList(), Vector2f(), 0f, 200f, spec, 0.5f)

        assertEquals(0.5f, nodes.first().color.alpha, 0.0001f)
        assertEquals(0.1f, nodes.last().color.alpha, 0.0001f)
    }

    @Test
    fun `curved history bends nodes in local frame`() {
        // facing=0（+x 前向），弹体在 (100,0)；历史点显示它从 (0,0) 经 (50,30) 飞来 → 局部系应向 -y 侧弯
        val history = listOf(
            ASTDProjectileHistoryNode(Vector2f(0f, 0f), 0f, 0f),
            ASTDProjectileHistoryNode(Vector2f(50f, 30f), 0f, 0.1f),
            ASTDProjectileHistoryNode(Vector2f(100f, 0f), 0f, 0.2f),
        )

        val nodes = curveCoreNodes(history, Vector2f(100f, 0f), 0f, 200f, spec, 1f)

        // 头节点锚在原点
        assertEquals(0f, nodes.first().position.x, 0.0001f)
        assertEquals(0f, nodes.first().position.y, 0.0001f)
        // 中间节点须出现侧向弯曲（采样点未必正中 30 的拐点，但须明显偏离直梁）
        val maxAbsY = nodes.dropLast(1).maxOf { kotlin.math.abs(it.position.y) }
        assert(maxAbsY in 20f..31f) { "expected lateral bend from curved history, maxAbsY=$maxAbsY" }
        // 历史弧长(~116.6)短于请求长度 200：尾端沿末段方向延长，y 继续向负侧伸出
        assert(nodes.last().position.y < -35f) { "tail should extend along final heading: ${nodes.last().position}" }
        // 尾端不超出请求长度
        for (node in nodes) {
            val distance = sqrt(node.position.x * node.position.x + node.position.y * node.position.y)
            assert(distance <= 200f + 0.5f) { "node beyond requested length: $distance" }
        }
    }

    @Test
    fun `facing rotates world history into local frame`() {
        // facing=90°（+y 前向），弹体在 (0,100)；历史沿 +y 飞来 → 局部系应是一条 -x 直梁
        val history = listOf(
            ASTDProjectileHistoryNode(Vector2f(0f, 0f), 90f, 0f),
            ASTDProjectileHistoryNode(Vector2f(0f, 50f), 90f, 0.1f),
            ASTDProjectileHistoryNode(Vector2f(0f, 100f), 90f, 0.2f),
        )

        val nodes = curveCoreNodes(history, Vector2f(0f, 100f), 90f, 200f, spec, 1f)

        for (node in nodes) {
            assertEquals(0f, node.position.y, 0.001f)
        }
        assertEquals(-100f, nodes[2].position.x, 0.5f)
    }
}
