package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArcTrailComponentTest {

    private val spec = ArcTrailSpec(
        width = 7f,
        texturePath = "graphics/fx/astd_trails_zappy.png",
        headColor = ASTDColor(1f, 1f, 1f, 1f),
        tailColor = ASTDColor(0f, 0f, 1f, 0.4f),
        nodeCount = 11,
        tileLength = 180f,
        jagAmplitude = 14f,
        jagWavelength = 200f,
        jagFlickerHz = 9f,
        alphaFlicker = 0.2f,
    )

    @Test
    fun `nodes span from anchor to follow end with pinned endpoints`() {
        val start = Vector2f(0f, 0f)
        val end = Vector2f(500f, 0f)
        val nodes = arcTrailNodes(start, end, spec, elapsed = 1.0f, intensity = 1f)

        assertEquals(11, nodes.size)
        // 两端钉死：锚点与跟随端不脱开
        assertEquals(0f, nodes.first().position.x, 1e-4f)
        assertEquals(0f, nodes.first().position.y, 1e-4f)
        assertEquals(500f, nodes.last().position.x, 1e-4f)
        assertEquals(0f, nodes.last().position.y, 1e-4f)
        // 中段折点在法向（±y）放开，振幅不超限
        val maxOffset = nodes.maxOf { abs(it.position.y) }
        assertTrue(maxOffset > 0.1f, "中段应有可见折点偏移，实际 $maxOffset")
        assertTrue(maxOffset <= 14f + 1e-3f, "折点偏移不应超振幅上限，实际 $maxOffset")
        // 全部节点宽度 = spec 宽度
        for (node in nodes) assertEquals(7f, node.width, 1e-4f)
    }

    @Test
    fun `jag pattern is stable within a time bucket and rerolled across buckets`() {
        val start = Vector2f(0f, 0f)
        val end = Vector2f(400f, 100f)
        val a = arcTrailNodes(start, end, spec, elapsed = 1.000f, intensity = 1f)
        val b = arcTrailNodes(start, end, spec, elapsed = 1.050f, intensity = 1f) // 同桶（9Hz 桶宽≈0.111s）
        val c = arcTrailNodes(start, end, spec, elapsed = 1.300f, intensity = 1f) // 跨桶

        for (i in a.indices) {
            assertEquals(a[i].position.x, b[i].position.x, 1e-4f, "同桶跨帧须逐点一致（不闪）")
            assertEquals(a[i].position.y, b[i].position.y, 1e-4f)
        }
        val moved = a.indices.any { i ->
            abs(a[i].position.y - c[i].position.y) > 0.5f || abs(a[i].position.x - c[i].position.x) > 0.5f
        }
        assertTrue(moved, "跨时间桶折点图案应重掷（抖动动画）")
    }

    @Test
    fun `colors gradient tail to head and intensity scales alpha`() {
        val nodes = arcTrailNodes(Vector2f(0f, 0f), Vector2f(300f, 0f), spec, elapsed = 2f, intensity = 0.5f)

        // 尾端（锚点侧）取 tailColor 蓝、头端取 headColor 白；整体 alpha × intensity × 闪烁系数
        assertEquals(0f, nodes.first().color.red, 1e-4f)
        assertEquals(1f, nodes.last().color.red, 1e-4f)
        val flicker = arcAlphaFlicker(kotlin.math.floor(2f * 9f), 0.2f)
        assertEquals(0.4f * 0.5f * flicker, nodes.first().color.alpha, 1e-4f)
        assertTrue(nodes.last().color.alpha > nodes.first().color.alpha, "头端应亮于尾端")
    }

    @Test
    fun `node angles follow the displaced polyline and stay normalized`() {
        val nodes = arcTrailNodes(Vector2f(0f, 0f), Vector2f(0f, 600f), spec, elapsed = 3.3f, intensity = 1f)
        for (node in nodes) {
            assertTrue(node.angle >= 0f && node.angle < 360f, "角度须在 [0,360)，实际 ${node.angle}")
        }
        // 竖直向上的连线：节点角应在 90° 附近（折点偏移引起的偏差有界）
        for (node in nodes.drop(1).dropLast(1)) {
            val dev = abs(node.angle - 90f)
            assertTrue(dev < 45f, "竖直连线的节点角不应偏离 90° 太多，实际 ${node.angle}")
        }
    }

    @Test
    fun `alpha flicker stays within declared range and reacts to bucket`() {
        assertEquals(1f, arcAlphaFlicker(0f, 0f), "幅度 0 即关闭")
        for (bucket in 0..50) {
            val v = arcAlphaFlicker(bucket.toFloat(), 0.25f)
            assertTrue(v in (1f - 0.25f - 1e-4f)..1f, "闪烁系数越界: $v")
        }
        val values = (0..8).map { arcAlphaFlicker(it.toFloat(), 0.25f) }.toSet()
        assertNotEquals(1, values.size, "不同时间桶应给出不同闪烁系数")
    }

    @Test
    fun `zero length arc degrades without producing NaN`() {
        val p = Vector2f(77f, 88f)
        val nodes = arcTrailNodes(p, Vector2f(p), spec, elapsed = 1f, intensity = 1f)
        assertEquals(11, nodes.size)
        for (node in nodes) {
            assertTrue(!node.position.x.isNaN() && !node.position.y.isNaN(), "零长电弧不得产出 NaN")
            assertTrue(!node.angle.isNaN())
        }
        // 全部节点钉在锚点附近（端点钉死，连线长≈0）
        for (node in nodes) {
            val d = sqrt((node.position.x - 77f).let { it * it } + (node.position.y - 88f).let { it * it })
            assertTrue(d < 1f, "零长电弧节点应钉在锚点，实际偏移 $d")
        }
    }
}
