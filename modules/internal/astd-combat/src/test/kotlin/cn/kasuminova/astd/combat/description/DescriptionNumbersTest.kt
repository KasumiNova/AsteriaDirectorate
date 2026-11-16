package cn.kasuminova.astd.combat.description

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DescriptionNumbersTest {
    @Test
    fun `小数采用两位四舍五入并移除尾零和负零`() {
        val cases = mapOf(1.234f to "1.23", 1.235f to "1.24", -1.235f to "-1.24", -0.004f to "0", 12.30f to "12.3", 1500f to "1500")
        cases.forEach { (value, expected) -> assertEquals(expected, DescriptionNumbers.number(value)) }
    }

    @Test
    fun `比例与百分比点使用不同换算`() {
        for (points in listOf(1f, 7.5f, 20f, 125f, -15f)) {
            assertEquals(DescriptionNumbers.percentPoints(points), DescriptionNumbers.percent(points / 100f))
        }
        assertEquals("+12.5%", DescriptionNumbers.signedPercent(0.125f))
        assertEquals("-12.5%", DescriptionNumbers.signedPercent(-0.125f))
        assertEquals("0%", DescriptionNumbers.signedPercent(-0.000001f))
    }

    @Test
    fun `非有限输入拒绝参与数字格式化`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { DescriptionNumbers.number(value) }
        }
    }
}
