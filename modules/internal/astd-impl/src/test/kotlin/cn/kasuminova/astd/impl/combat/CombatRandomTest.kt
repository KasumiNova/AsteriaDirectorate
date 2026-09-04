package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.impl.buff.WarnCapture
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 规格 §4.1 / §6 风险表：确定性派生语义（对齐 Misc.getRandom，其类初始化依赖游戏内 Settings
 * 无法在裸单测触达，CombatRandom 逐位复现其算法）与 0 值防线。
 */
class CombatRandomTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    @Test
    fun `derivation matches the verified Misc-getRandom algorithm bit for bit`() {
        // jar 反编译核实的参照算法：new Random(seed) 丢弃 numCalls 个 nextLong，再以 new Random(r.nextLong()) 出序列。
        fun reference(seed: Long, numCalls: Int): Random {
            val r = Random(seed)
            repeat(numCalls) { r.nextLong() }
            return Random(r.nextLong())
        }

        for ((seed, callIndex) in listOf(42L to 0, 42L to 7, -9L to 3, Long.MAX_VALUE to 11)) {
            assertEquals(
                reference(seed, callIndex).nextFloat(),
                CombatRandom.deriveRandom(seed, callIndex).nextFloat(),
                "seed=$seed, callIndex=$callIndex 派生序列必须与参照算法一致",
            )
            assertEquals(
                reference(seed, callIndex).nextLong(),
                CombatRandom.deriveRandom(seed, callIndex).nextLong(),
            )
        }
    }

    @Test
    fun `same seed and call index always yields the same value`() {
        val range = 0.8f..1.5f
        repeat(5) {
            assertEquals(
                CombatRandom.nextFloatIn(12345L, 3, range),
                CombatRandom.nextFloatIn(12345L, 3, range),
            )
        }
    }

    @Test
    fun `different call indices produce a varying sequence within range`() {
        val range = 0.8f..1.5f
        val values = (0 until 10).map { CombatRandom.nextFloatIn(12345L, it, range) }

        assertTrue(values.toSet().size > 1, "不同 callIndex 不得恒同值: $values")
        values.forEach { v ->
            assertTrue(v in range, "取值必须落在 range 内: $v")
        }
    }

    @Test
    fun `different seeds produce different sequences`() {
        val range = 0f..1f
        val a = (0 until 5).map { CombatRandom.nextFloatIn(111L, it, range) }
        val b = (0 until 5).map { CombatRandom.nextFloatIn(222L, it, range) }
        assertNotEquals(a, b)
    }

    @Test
    fun `zero seed stays deterministic instead of falling back to shared random`() {
        // Misc.getRandom(0, *) 返回全局共享随机（非确定性）；CombatRandom 必须归一化。
        val range = 0.5f..2f
        assertEquals(
            CombatRandom.nextFloatIn(0L, 4, range),
            CombatRandom.nextFloatIn(0L, 4, range),
        )
    }

    @Test
    fun `negative call index warns and clamps to zero`() {
        val capture = WarnCapture(CombatRandom::class.java).also { captures += it }
        val range = 0.8f..1.5f

        val clamped = CombatRandom.nextFloatIn(99L, -3, range)
        assertEquals(CombatRandom.nextFloatIn(99L, 0, range), clamped)
        assertTrue(capture.messages().any { it.contains("callIndex") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `inverted range warns and swaps`() {
        val capture = WarnCapture(CombatRandom::class.java).also { captures += it }

        val value = CombatRandom.nextFloatIn(77L, 2, 1.5f..0.8f)
        assertTrue(value in 0.8f..1.5f, "交换后取值必须落在正序区间内: $value")
        assertEquals(CombatRandom.nextFloatIn(77L, 2, 0.8f..1.5f), value)
        assertTrue(capture.messages().any { it.contains("起止倒置") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `degenerate range returns the single point`() {
        assertEquals(1.25f, CombatRandom.nextFloatIn(55L, 9, 1.25f..1.25f))
    }

    @Test
    fun `seedOf is stable per ship and slot and isolates same-type weapons`() {
        assertEquals(CombatRandom.seedOf("ship-1", "WS0001"), CombatRandom.seedOf("ship-1", "WS0001"))
        assertNotEquals(CombatRandom.seedOf("ship-1", "WS0001"), CombatRandom.seedOf("ship-1", "WS0002"))
        assertNotEquals(CombatRandom.seedOf("ship-1", "WS0001"), CombatRandom.seedOf("ship-2", "WS0001"))
    }
}
