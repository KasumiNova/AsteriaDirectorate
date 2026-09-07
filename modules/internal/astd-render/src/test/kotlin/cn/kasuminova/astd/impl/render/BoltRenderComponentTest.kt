package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Box 螺栓几何纯函数（[boltStretch]/[boltFrame]/[hitGlowScale]）的完整逻辑验证。
 *
 * 锚点：原版 ProjectileRenderer 前伸段 max(width/2, length×0.2)、TrailExtender distanceRatio 出生伸入、
 * 命中光晕伤害缩放 sqrt(damage/250) 钳 [0.8, 2.5]。
 */
class BoltRenderComponentTest {

    @Test
    fun `前伸段对齐原版口径`() {
        assertEquals(27.6f, boltStretch(138f, 34f), 1e-3f, "aod7：length×0.2 主导")
        assertEquals(25f, boltStretch(40f, 50f), 1e-3f, "短粗弹：width/2 主导")
    }

    @Test
    fun `全速飞行时缩放拉满且中心在覆盖区间中点`() {
        // length=100 width=20 → stretch=20，基准全长 120；tail 在 head 正后 100（满距离比）
        val frame = boltFrame(
            head = Vector2f(100f, 0f),
            tail = Vector2f(0f, 0f),
            facingDeg = 0f,
            specLength = 100f,
            specWidth = 20f,
        )
        assertEquals(1f, frame.scaleX, 1e-3f, "覆盖长 100+20 = 基准全长 120 → scaleX=1")
        assertEquals(60f, frame.center.x, 1e-3f, "front=(120,0) 与 tail=(0,0) 的中点")
        assertEquals(0f, frame.center.y, 1e-3f)
    }

    @Test
    fun `出生瞬间 tail 在炮口时螺栓从一点开始拉长`() {
        // tail=head（出生）：span=stretch=20 → scaleX=20/120≈0.167
        val frame = boltFrame(
            head = Vector2f(50f, 0f),
            tail = Vector2f(50f, 0f),
            facingDeg = 0f,
            specLength = 100f,
            specWidth = 20f,
        )
        assertEquals(20f / 120f, frame.scaleX, 1e-3f)
        assertEquals(60f, frame.center.x, 1e-3f, "front=(70,0) 与 tail=(50,0) 的中点")
    }

    @Test
    fun `任意朝向伸入几何一致（45 度）`() {
        val d = (100f / sqrt(2f))
        val frame = boltFrame(
            head = Vector2f(d, d),
            tail = Vector2f(0f, 0f),
            facingDeg = 45f,
            specLength = 100f,
            specWidth = 20f,
        )
        assertEquals(1f, frame.scaleX, 1e-3f)
        val s = 20f / sqrt(2f)
        assertEquals((d + s) / 2f, frame.center.x, 1e-3f)
        assertEquals((d + s) / 2f, frame.center.y, 1e-3f)
    }

    @Test
    fun `tail 缺失时按 head 处理不炸`() {
        val frame = boltFrame(Vector2f(0f, 0f), null, 90f, 100f, 20f)
        assertEquals(20f / 120f, frame.scaleX, 1e-3f)
        assertEquals(10f, frame.center.y, 1e-3f, "front=(0,20) 与 head=(0,0) 的中点")
    }

    @Test
    fun `命中光晕伤害缩放锚点`() {
        assertEquals(0.8f, hitGlowScale(0f), 1e-3f)
        assertEquals(0.8f, hitGlowScale(100f), 1e-3f, "sqrt(0.4)≈0.63 被下限钳住")
        assertEquals(1f, hitGlowScale(250f), 1e-3f)
        assertEquals(2f, hitGlowScale(1000f), 1e-3f)
        assertEquals(2.5f, hitGlowScale(10000f), 1e-3f, "上限钳 2.5")
    }
}
