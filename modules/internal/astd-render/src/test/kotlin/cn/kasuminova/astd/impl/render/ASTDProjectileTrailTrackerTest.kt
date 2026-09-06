package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 弹体 Static Trail 跟踪器的几何纯函数守护：[trailAnchor] 锚点前移。
 * tracker 本体依赖 DamagingProjectileAPI 与 BoxUtil 回调，不在单测覆盖——由实机烟测目检。
 */
class ASTDProjectileTrailTrackerTest {

    @Test
    fun `锚点沿朝向提前 forwardOffset`() {
        // 朝东（0 弧度）：提前 10 → +x 10
        val east = trailAnchor(Vector2f(100f, 200f), 0.0, forwardOffset = 10f)
        assertEquals(110f, east.x, 1e-4f)
        assertEquals(200f, east.y, 1e-4f)
        // 朝北（π/2）：提前 10 → +y 10
        val north = trailAnchor(Vector2f(100f, 200f), PI / 2, forwardOffset = 10f)
        assertEquals(100f, north.x, 1e-4f)
        assertEquals(210f, north.y, 1e-4f)
    }
}
