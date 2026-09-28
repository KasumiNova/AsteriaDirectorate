package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Box 螺栓几何与尺寸裁定纯函数（[boltFrame]/[hitGlowScale]/[resolveBoltDimensions]）的完整逻辑验证。
 *
 * 锚点：贴图跨 [tailEnd → 弹体位置]（原版 body 带体区间）、X 向缩放 = 覆盖长/spec.length
 * （TrailExtender distanceRatio 出生伸入同语义）、命中光晕伤害缩放 sqrt(damage/250) 钳 [0.8, 2.5]、
 * 尺寸裁定 override 优先/spec 缺失时 override 齐全仍可渲染（脚本 spawn 弹体 projectileSpec 为 null 判例）。
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
    fun `导弹合成尾点：沿朝向反推全长 喂 boltFrame 后拉满且中心退半程`() {
        // 0° 朝向：尾 = 头 − (specLength, 0)
        val tail = missileBoltTail(Vector2f(100f, 20f), 0f, 28f)
        assertEquals(72f, tail.x, 1e-3f)
        assertEquals(20f, tail.y, 1e-3f)
        val frame = boltFrame(Vector2f(100f, 20f), tail, 0f, 28f)
        assertEquals(1f, frame.scaleX, 1e-3f, "合成全长尾点 → 无出生伸入")
        assertEquals(86f, frame.center.x, 1e-3f, "中心 = 头退半程")
        assertEquals(20f, frame.center.y, 1e-3f)
    }

    @Test
    fun `导弹合成尾点任意朝向几何一致（90 度）`() {
        val tail = missileBoltTail(Vector2f(50f, 50f), 90f, 28f)
        assertEquals(50f, tail.x, 1e-3f)
        assertEquals(22f, tail.y, 1e-3f)
        val frame = boltFrame(Vector2f(50f, 50f), tail, 90f, 28f)
        assertEquals(1f, frame.scaleX, 1e-3f)
        assertEquals(36f, frame.center.y, 1e-3f)
    }

    @Test
    fun `尺寸裁定 spec 缺失但 override 齐全仍可渲染`() {
        // 脚本 spawnProjectile 产出的 MissileAPI 其 projectileSpec 为 null（实机判例）：
        // 显式接管路径（length/width override 齐全）不依赖 spec
        val dims = resolveBoltDimensions(
            lengthOverride = 28f, widthOverride = 8f,
            specLength = null, specWidth = null, specHitGlowRadius = null,
        )
        assertNotNull(dims, "override 齐全时无 spec 也必须裁定成功")
        assertEquals(28f, dims.length, 1e-4f)
        assertEquals(8f, dims.width, 1e-4f)
        assertEquals(8f, dims.hitGlowRadius, 1e-4f, "无 spec 时命中光晕半径退化为螺栓全宽")
    }

    @Test
    fun `尺寸裁定 override 优先 缺 override 且无 spec 不可裁定`() {
        val overridden = resolveBoltDimensions(28f, 8f, 100f, 40f, 50f)
        assertNotNull(overridden)
        assertEquals(28f, overridden.length, 1e-4f, "override 优先于 spec 值")
        assertEquals(8f, overridden.width, 1e-4f)
        assertEquals(50f, overridden.hitGlowRadius, 1e-4f, "有 spec 时光晕半径取 spec 值")

        val fromSpec = resolveBoltDimensions(null, null, 100f, 40f, 50f)
        assertNotNull(fromSpec)
        assertEquals(100f, fromSpec.length, 1e-4f)
        assertEquals(40f, fromSpec.width, 1e-4f)

        assertNull(resolveBoltDimensions(28f, null, null, null, null), "缺 width override 且无 spec → 不可裁定")
        assertNull(resolveBoltDimensions(null, null, null, null, null))
        assertNull(resolveBoltDimensions(0f, 8f, null, null, null), "尺寸 ≤0 非法")
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
