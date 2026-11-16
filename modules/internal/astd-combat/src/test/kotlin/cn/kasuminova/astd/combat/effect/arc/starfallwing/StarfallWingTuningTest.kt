package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallWingTuningTest {
    @Test
    fun `随机威力在区间内插值并按实际充能支出叠加`() {
        for ((minimum, maximum) in listOf(0.3f to 1.7f, 0.8f to 2.2f)) {
            for (conversion in listOf(0f, 0.06f, 0.17f)) {
                for (spent in listOf(0f, 3.3f, 10f)) {
                    val low = StarfallWingTuning.power(0f, spent, minimum, maximum, conversion)
                    val high = StarfallWingTuning.power(1f, spent, minimum, maximum, conversion)
                    assertEquals(minimum + spent * conversion, low, 1e-6f)
                    assertEquals(maximum - minimum, high - low, 1e-6f)
                    assertEquals((low + high) / 2f,
                        StarfallWingTuning.power(0.5f, spent, minimum, maximum, conversion), 1e-6f)
                }
            }
        }
    }

    @Test
    fun `尺寸衰减阈值前跟随威力且阈值处连续`() {
        for (power in listOf(0.4f, 0.8f, 1f, 1.7f, 2f)) {
            assertEquals(power, StarfallWingTuning.sizeFactor(power))
        }
        assertEquals(StarfallWingTuning.sizeFactor(2f), StarfallWingTuning.sizeFactor(2.00001f), 2e-5f)
    }

    @Test
    fun `超阈值后每段等长威力增量的尺寸收益减半且持续增大`() {
        var previous = StarfallWingTuning.sizeFactor(2f)
        var previousGain = 0f
        for (step in 1..8) {
            val current = StarfallWingTuning.sizeFactor(2f + step * 0.2f)
            val gain = current - previous
            assertTrue(gain > 0f)
            if (step > 1) assertEquals(previousGain * 0.5f, gain, 1e-6f)
            previous = current
            previousGain = gain
        }
    }

    @Test
    fun `弹速只承受一半威力增减且保持中性点`() {
        for (power in listOf(0.5f, 0.8f, 1f, 1.4f, 2.5f)) {
            val speedDelta = StarfallWingTuning.speedFactor(power) - 1f
            assertEquals((power - 1f) * 0.5f, speedDelta, 1e-6f)
        }
    }

    @Test
    fun `转向跨越零度时采用最短角度并限制角速度`() {
        assertEquals(0f, StarfallWingTuning.turnToward(350f, 10f, 10f), 1e-6f)
        assertEquals(0f, StarfallWingTuning.turnToward(10f, 350f, 10f), 1e-6f)
        assertEquals(10f, StarfallWingTuning.turnToward(350f, 10f, 40f), 1e-6f)
        assertEquals(350f, StarfallWingTuning.turnToward(10f, -10f, 40f), 1e-6f)
        assertEquals(55f, StarfallWingTuning.turnToward(45f, 170f, 10f), 1e-6f)
    }

    @Test
    fun `同一目标航向的转向结果不依赖帧划分`() {
        val full = StarfallWingTuning.turnToward(350f, 120f, 45f)
        var split = 350f
        repeat(10) { split = StarfallWingTuning.turnToward(split, 120f, 4.5f) }
        assertEquals(full, split, 1e-4f)
    }

    @Test
    fun `航程弧线只向一侧偏转并在终点回正`() {
        val positivePhase = kotlin.math.PI.toFloat() / 2f
        val negativePhase = -positivePhase
        assertEquals(0f, StarfallWingTuning.arcOffset(0f, positivePhase), 1e-4f)
        assertEquals(0f, StarfallWingTuning.arcOffset(1f, positivePhase), 1e-4f)
        assertEquals(0f, StarfallWingTuning.arcOffset(1.2f, positivePhase), 1e-4f)
        for (i in 1..44) {
            val progress = i / 100f
            val positive = StarfallWingTuning.arcOffset(progress, positivePhase)
            assertTrue(positive > 0f)
            assertEquals(-positive, StarfallWingTuning.arcOffset(progress, negativePhase), 1e-4f)
            assertEquals(positive, StarfallWingTuning.arcOffset(0.45f - progress, positivePhase), 1e-4f)
        }
        assertEquals(0f, StarfallWingTuning.arcOffset(0.5f, positivePhase))
    }

    @Test
    fun `接近目标或缺少绕行余量时取消偏移`() {
        val phase = kotlin.math.PI.toFloat() / 2f
        assertEquals(0f, StarfallWingTuning.approachOffset(0.2f, phase, 900f, 900f))
        assertEquals(0f, StarfallWingTuning.approachOffset(0.2f, phase, 500f, 400f))
        assertEquals(0f, StarfallWingTuning.approachOffset(0.2f, phase, 100f, 700f))
        val narrow = StarfallWingTuning.approachOffset(0.2f, phase, 850f, 900f)
        val wide = StarfallWingTuning.approachOffset(0.2f, phase, 400f, 900f)
        assertTrue(narrow > 0f && narrow < wide)
    }

    @Test
    fun `不同弹速和帧步长的弧线能够到达射程内小目标`() {
        val range = 1000f
        for (distance in listOf(250f, 500f, 850f, range)) {
            for (speed in listOf(770f, 1100f, 1925f, 2585f)) {
                for (dt in listOf(1f / 30f, 1f / 60f, 1f / 144f)) {
                    for (side in listOf(-1f, 1f)) {
                        var x = 0f
                        var y = 0f
                        var facing = 0f
                        var travelled = 0f
                        var hit = false
                        while (travelled < range + speed * 0.5f && !hit) {
                            val dx = distance - x
                            val dy = -y
                            val targetDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                            val heading = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            val offset = StarfallWingTuning.approachOffset(
                                travelled / distance, side * kotlin.math.PI.toFloat() / 2f, (targetDistance - 10f).coerceAtLeast(0f), range - travelled + minOf(range * 0.08f, speed * 0.2f), speed,
                            )
                            if (travelled < range) {
                                facing = StarfallWingTuning.turnToward(facing, heading + offset, StarfallWingTuning.turnRate(speed) * dt)
                            }
                            val step = speed * dt
                            val radians = Math.toRadians(facing.toDouble())
                            val sx = (kotlin.math.cos(radians) * step).toFloat()
                            val sy = (kotlin.math.sin(radians) * step).toFloat()
                            val t = ((dx * sx + dy * sy) / (step * step)).coerceIn(0f, 1f)
                            val missX = dx - sx * t
                            val missY = dy - sy * t
                            hit = missX * missX + missY * missY <= 10f * 10f
                            x += sx
                            y += sy
                            travelled += step
                        }
                        assertTrue(hit, "distance=$distance speed=$speed dt=$dt side=$side")
                    }
                }
            }
        }
    }

    @Test
    fun `随机相位不会让弧线幅度退化为零`() {
        for (i in 0..360) {
            val phase = Math.toRadians(i.toDouble()).toFloat()
            assertTrue(kotlin.math.abs(StarfallWingTuning.arcOffset(0.2f, phase)) > 0f)
        }
    }

    @Test
    fun `命中和消散竞争同一弹体的唯一爆炸结算`() {
        val first = StarfallWingShot(StarfallWingTuning.ShuttleType.BLUE, 1f, 1200f, 900f, 0f)
        val other = StarfallWingShot(StarfallWingTuning.ShuttleType.RED, 1f, 1200f, 900f, 0f)
        assertTrue(first.claimExplosion())
        assertFalse(first.claimExplosion())
        assertTrue(other.claimExplosion())
    }
}
