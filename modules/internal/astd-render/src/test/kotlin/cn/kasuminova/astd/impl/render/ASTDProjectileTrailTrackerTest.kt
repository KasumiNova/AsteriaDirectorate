package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 弹体 Static Trail 跟踪器的几何纯函数守护：[trailAnchor] 前移/横向偏移与 [wobbleOffset] 蛇行扰动。
 * tracker 本体依赖 DamagingProjectileAPI 与 BoxUtil 回调，不在单测覆盖——由实机烟测目检。
 */
class ASTDProjectileTrailTrackerTest {

    private fun spec(
        wobbleAmplitude: Float = 0f,
        wobbleWavelength: Float = 90f,
        wobbleScroll: Float = 0f,
        wobblePhase: Float = 0f,
    ) = StaticTrailSpec(
        texturePath = "graphics/fx/astd_trails_twin.png",
        width = 12f,
        headColor = ASTDColor(1f, 1f, 1f, 1f),
        tailColor = ASTDColor(0f, 0f, 0f, 0f),
        bandLength = 180f,
        wobbleAmplitude = wobbleAmplitude,
        wobbleWavelength = wobbleWavelength,
        wobbleScroll = wobbleScroll,
        wobblePhase = wobblePhase,
    )

    @Test
    fun `锚点沿朝向提前 forwardOffset`() {
        // 朝东（0 弧度）：提前 10 → +x 10
        val east = trailAnchor(Vector2f(100f, 200f), 0.0, forwardOffset = 10f, lateralOffset = 0f)
        assertEquals(110f, east.x, 1e-4f)
        assertEquals(200f, east.y, 1e-4f)
        // 朝北（π/2）：提前 10 → +y 10
        val north = trailAnchor(Vector2f(100f, 200f), PI / 2, forwardOffset = 10f, lateralOffset = 0f)
        assertEquals(100f, north.x, 1e-4f)
        assertEquals(210f, north.y, 1e-4f)
    }

    @Test
    fun `锚点横向偏移沿法向`() {
        // 朝东，横向 +5 → 法向（-sin, +cos）= +y 5
        val p = trailAnchor(Vector2f(100f, 200f), 0.0, forwardOffset = 10f, lateralOffset = 5f)
        assertEquals(110f, p.x, 1e-4f)
        assertEquals(205f, p.y, 1e-4f)
    }

    @Test
    fun `wobble 零振幅恒为零`() {
        val s = spec(wobbleAmplitude = 0f, wobbleScroll = 30f, wobblePhase = 0.8f)
        assertEquals(0f, wobbleOffset(s, 0f))
        assertEquals(0f, wobbleOffset(s, 1.23f))
    }

    @Test
    fun `wobble 相位与爬行频率`() {
        // 静止（scroll=0）：偏移 = 振幅 × sin(相位)
        val still = spec(wobbleAmplitude = 5f, wobbleScroll = 0f, wobblePhase = (PI / 2).toFloat())
        assertEquals(5f, wobbleOffset(still, 0f), 1e-4f)
        assertEquals(5f, wobbleOffset(still, 3.7f), 1e-4f)

        // 爬行频率 = scroll/波长：scroll=30、波长=110 → 一周期 110/30 秒；
        // 零相位下 t=0 为 0，四分之三周期后到波谷 -振幅
        val crawl = spec(wobbleAmplitude = 5f, wobbleWavelength = 110f, wobbleScroll = 30f, wobblePhase = 0f)
        assertEquals(0f, wobbleOffset(crawl, 0f), 1e-4f)
        assertEquals(-5f, wobbleOffset(crawl, 110f / 30f * 0.75f), 1e-3f)
    }
}
