package cn.kasuminova.astd.impl.render

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class TexTrailRendererTest {

    @Test
    fun `packRgba packs pixels row-major in RGBA byte order`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        // 四个角各写一个可区分的颜色（ARGB 入参）
        image.setRGB(0, 0, 0x11223344)
        image.setRGB(1, 0, 0x55667788.toInt())
        image.setRGB(0, 1, 0x99AABBCC.toInt())
        image.setRGB(1, 1, 0xFF0000FF.toInt())

        val buffer = packRgba(image)

        assertEquals(16, buffer.remaining())
        val expected = listOf(
            // (0,0)：R=0x22 G=0x33 B=0x44 A=0x11
            0x22, 0x33, 0x44, 0x11,
            // (1,0)：R=0x66 G=0x77 B=0x88 A=0x55
            0x66, 0x77, 0x88, 0x55,
            // (0,1)：R=0xAA G=0xBB B=0xCC A=0x99
            0xAA, 0xBB, 0xCC, 0x99,
            // (1,1)：R=0x00 G=0x00 B=0xFF A=0xFF
            0x00, 0x00, 0xFF, 0xFF,
        )
        expected.forEachIndexed { index, value ->
            assertEquals(value.toByte(), buffer.get(index), "byte[$index]")
        }
    }
}
