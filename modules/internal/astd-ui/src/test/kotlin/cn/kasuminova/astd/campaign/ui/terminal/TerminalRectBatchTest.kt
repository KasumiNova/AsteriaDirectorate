package cn.kasuminova.astd.campaign.ui.terminal

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TerminalGl.RectBatch] 合批器：累积阶段不触碰 GL（纯数据），
 * 验证矩形顶点按 GL_QUADS 顶点序累积、size 口径与 flush 前的批次数。
 */
class TerminalRectBatchTest {

    @Test
    fun `矩形按逆时针四点累积为 GL_QUADS 顶点序`() {
        val batch = TerminalGl.RectBatch(Color.WHITE, 0.5f)
        assertEquals(0, batch.size)

        batch.rect(10f, 20f, 30f, 40f)
        assertEquals(1, batch.size)
        assertEquals(
            listOf(
                10f, 20f, // 左下
                40f, 20f, // 右下
                40f, 60f, // 右上
                10f, 60f, // 左上
            ),
            batch.coords,
        )

        batch.rect(0f, 0f, 1f, 1f)
        assertEquals(2, batch.size)
        assertEquals(16, batch.coords.size)
    }
}
