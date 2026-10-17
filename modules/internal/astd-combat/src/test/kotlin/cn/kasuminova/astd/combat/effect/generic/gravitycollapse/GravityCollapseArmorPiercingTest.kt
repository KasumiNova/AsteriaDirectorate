package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 穿甲反解（[GravityCollapseArmorPiercing]）的回归测试。
 *
 * 验证方式：不锚定常量，而是用「原版装甲公式正向模拟」做往返验证——
 * 反解出的输入伤害 X 经引擎公式结算后，船体/装甲吞吐必须等于目标口径
 * （打击强度 = 单次伤害 × 穿甲倍率 × 类型倍率）。
 */
class GravityCollapseArmorPiercingTest {

    /** 原版装甲公式的正向模拟：返回引擎结算后的船体伤害。 */
    private fun simulateEngineHullDamage(input: Float, armor: Float, maxReduction: Float, typeMult: Float = 2f): Float {
        val h = input * typeMult
        val floor = (1f - maxReduction).coerceIn(0f, 1f)
        val mult = (h / (h + armor)).coerceIn(floor, 1f)
        return input * mult
    }

    /** 目标口径：以 单次伤害 × 穿甲倍率 × 类型倍率 为打击强度时，原版公式结算出的船体伤害。 */
    private fun targetHullDamage(damage: Float, pierceMult: Float, armor: Float, maxReduction: Float, typeMult: Float = 2f): Float {
        val mult = GravityCollapseArmorPiercing.armorThroughputMultiplier(damage * pierceMult * typeMult, armor, maxReduction)
        return damage * mult
    }

    private fun assertRoundTrip(damage: Float, pierceMult: Float, armor: Float, maxReduction: Float) {
        val x = GravityCollapseArmorPiercing.solvePiercedInputDamage(damage, pierceMult, armor, maxReduction)
        val expected = targetHullDamage(damage, pierceMult, armor, maxReduction)
        val actual = simulateEngineHullDamage(x, armor, maxReduction)
        assertEquals(expected, actual, expected * 1e-3f + 1e-3f, "往返不一致：D=$damage N=$pierceMult A=$armor maxRed=$maxReduction")
        // 穿甲倍率 ≥1 时，反解输入伤害不应低于原单次伤害（否则等于反向减伤）。
        if (pierceMult >= 1f) {
            assertTrue(x >= damage - 1e-3f, "反解输入 $x 不应小于原伤害 $damage")
        }
    }

    @Test
    fun `未触顶分支往返（常规装甲与高穿甲倍率）`() {
        assertRoundTrip(damage = 100f, pierceMult = 5f, armor = 500f, maxReduction = 0.85f)
        assertRoundTrip(damage = 250f, pierceMult = 4f, armor = 800f, maxReduction = 0.85f)
    }

    @Test
    fun `触顶分支往返（低伤害对高装甲，减伤率达到上限）`() {
        assertRoundTrip(damage = 10f, pierceMult = 2f, armor = 2000f, maxReduction = 0.85f)
        assertRoundTrip(damage = 40f, pierceMult = 3f, armor = 3000f, maxReduction = 0.9f)
    }

    @Test
    fun `边界——穿甲后打击强度远大于装甲时接近全额伤害`() {
        val x = GravityCollapseArmorPiercing.solvePiercedInputDamage(100f, 5f, 1f, 0.85f)
        val actual = simulateEngineHullDamage(x, 1f, 0.85f)
        assertEquals(100f * (1000f / 1001f), actual, 0.5f)
    }

    @Test
    fun `边界——装甲为零无需反解（原样返回）`() {
        assertEquals(100f, GravityCollapseArmorPiercing.solvePiercedInputDamage(100f, 5f, 0f, 0.85f))
    }

    @Test
    fun `边界——零伤害输入返回零`() {
        assertEquals(0f, GravityCollapseArmorPiercing.solvePiercedInputDamage(0f, 5f, 500f, 0.85f))
    }

    @Test
    fun `装甲吞吐倍率与原版公式一致（h 除以 h+A 并按下限托底）`() {
        // 强打击：h/(h+A) 高于下限，取公式值
        assertEquals(0.5f, GravityCollapseArmorPiercing.armorThroughputMultiplier(500f, 500f, 0.85f), 1e-4f)
        // 弱打击：被最大减伤率下限托底
        assertEquals(0.15f, GravityCollapseArmorPiercing.armorThroughputMultiplier(10f, 2000f, 0.85f), 1e-4f)
        // 无装甲：全额
        assertEquals(1f, GravityCollapseArmorPiercing.armorThroughputMultiplier(100f, 0f, 0.85f), 1e-4f)
        // 零打击强度：零
        assertEquals(0f, GravityCollapseArmorPiercing.armorThroughputMultiplier(0f, 500f, 0.85f), 1e-4f)
    }

    @Test
    fun `有效装甲取 当前格装甲与最低装甲的较大值 乘有效倍率`() {
        assertEquals(600f, GravityCollapseArmorPiercing.effectiveArmor(500f, 100f, 1.2f), 1e-4f)
        assertEquals(120f, GravityCollapseArmorPiercing.effectiveArmor(10f, 100f, 1.2f), 1e-4f)
        assertEquals(0f, GravityCollapseArmorPiercing.effectiveArmor(0f, 0f, 1.2f), 1e-4f)
    }
}
