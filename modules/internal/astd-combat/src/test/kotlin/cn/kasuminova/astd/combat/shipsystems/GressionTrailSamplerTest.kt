package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GressionTrailSamplerTest {
    @Test
    fun `小位移跨帧累计并保留余量`() {
        val sampler = GressionChargeVfx.TrailSampler(100f, 0.125f)
        val emitted = mutableListOf<Float>()
        repeat(3) { sampler.advance(30f, 0.25f, emitted::add) }
        assertTrue(emitted.isEmpty())
        sampler.advance(30f, 0.25f, emitted::add)
        assertEquals(1, emitted.size)
        assertEquals(1f / 3f, emitted.single(), 0.00001f)
        emitted.clear()
        sampler.advance(80f, 0.25f, emitted::add)
        assertEquals(listOf(1f), emitted)
    }

    @Test
    fun `高速移动同时受最短时间间隔约束`() {
        val sampler = GressionChargeVfx.TrailSampler(10f, 0.125f)
        val emitted = mutableListOf<Float>()
        sampler.advance(100f, 0.0625f, emitted::add)
        assertTrue(emitted.isEmpty())
        sampler.advance(100f, 0.0625f, emitted::add)
        assertEquals(listOf(1f), emitted)
    }

    @Test
    fun `同一段航迹拆分帧后采样位置一致`() {
        fun sample(parts: Int): List<Float> {
            val sampler = GressionChargeVfx.TrailSampler(100f, 0.125f)
            val points = mutableListOf<Float>()
            repeat(parts) { frame ->
                sampler.advance(1000f / parts, 1f / parts) { fraction ->
                    points += (frame + fraction) * 1000f / parts
                }
            }
            return points
        }
        val oneFrame = sample(1)
        for (parts in listOf(8, 32, 128)) {
            val split = sample(parts)
            assertEquals(oneFrame.size, split.size)
            oneFrame.zip(split).forEach { (a, b) -> assertEquals(a, b, 0.0001f) }
        }
        assertEquals(listOf(125f, 250f, 375f, 500f, 625f, 750f, 875f, 1000f), oneFrame)
    }

    @Test
    fun `静止只累计时间不生成或补发残影`() {
        val sampler = GressionChargeVfx.TrailSampler(100f, 0.125f)
        val emitted = mutableListOf<Float>()
        sampler.advance(0f, 20f, emitted::add)
        assertTrue(emitted.isEmpty())
        sampler.advance(50f, 0.0625f, emitted::add)
        assertTrue(emitted.isEmpty())
        sampler.advance(100f, 0.125f, emitted::add)
        assertEquals(listOf(0.5f), emitted)
    }

    @Test
    fun `零时间帧不累计位移`() {
        val sampler = GressionChargeVfx.TrailSampler(100f, 0.125f)
        val emitted = mutableListOf<Float>()
        sampler.advance(1000f, 0f, emitted::add)
        sampler.advance(50f, 0.25f, emitted::add)
        assertTrue(emitted.isEmpty())
    }
}
