package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Box 螺栓几何纯函数（[boltFrame]/[hitGlowScale]）的完整逻辑验证。
 *
 * 锚点：贴图跨 [tailEnd → 弹体位置]（原版 body 带体区间）、X 向缩放 = 覆盖长/spec.length
 * （TrailExtender distanceRatio 出生伸入同语义）、命中光晕伤害缩放 sqrt(damage/250) 钳 [0.8, 2.5]。
 */
class BoltRenderComponentTest {

    @Test
    fun `全速飞行时缩放拉满且中心在头尾中点`() {
        // tail 在 head 正后 100（满距离比）→ span=100=specLength → scaleX=1
        val frame = boltFrame(
            head = Vector2f(100f, 0f),
            tail = Vector2f(0f, 0f),
            facingDeg = 0f,
            specLength = 100f,
        )
        assertEquals(1f, frame.scaleX, 1e-3f)
        assertEquals(50f, frame.center.x, 1e-3f)
        assertEquals(0f, frame.center.y, 1e-3f)
    }

    @Test
    fun `出生瞬间 tail 在炮口时缩放下限钳住`() {
        // tail=head（出生）：span=0 → scaleX 钳到下限 0.02
        val frame = boltFrame(
            head = Vector2f(50f, 0f),
            tail = Vector2f(50f, 0f),
            facingDeg = 0f,
            specLength = 100f,
        )
        assertEquals(0.02f, frame.scaleX, 1e-3f)
        assertEquals(50f, frame.center.x, 1e-3f)
    }

    @Test
    fun `伸入半程时缩放随覆盖长比例`() {
        // tail 在 head 正后 40 → scaleX=0.4
        val frame = boltFrame(
            head = Vector2f(100f, 0f),
            tail = Vector2f(60f, 0f),
            facingDeg = 0f,
            specLength = 100f,
        )
        assertEquals(0.4f, frame.scaleX, 1e-3f)
        assertEquals(80f, frame.center.x, 1e-3f)
    }

    @Test
    fun `任意朝向伸入几何一致（45 度）`() {
        val d = (100f / sqrt(2f))
        val frame = boltFrame(
            head = Vector2f(d, d),
            tail = Vector2f(0f, 0f),
            facingDeg = 45f,
            specLength = 100f,
        )
        assertEquals(1f, frame.scaleX, 1e-3f)
        assertEquals(d / 2f, frame.center.x, 1e-3f)
        assertEquals(d / 2f, frame.center.y, 1e-3f)
    }

    @Test
    fun `tail 缺失时按 head 处理不炸`() {
        val frame = boltFrame(Vector2f(0f, 0f), null, 90f, 100f)
        assertEquals(0.02f, frame.scaleX, 1e-3f)
        assertEquals(0f, frame.center.x, 1e-3f)
        assertEquals(0f, frame.center.y, 1e-3f)
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
