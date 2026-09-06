package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTimelinesTest {

    @Test
    fun `开机时间线扫描线与徽记分阶段推进`() {
        assertEquals(0f, BootTimeline.sweepProgress(BootTimeline.SWEEP_START - 0.001f), 1e-4f)
        assertTrue(BootTimeline.sweepProgress(BootTimeline.SWEEP_START + 0.01f) > 0f)
        assertEquals(1f, BootTimeline.sweepProgress(BootTimeline.SWEEP_START + BootTimeline.SWEEP_DURATION), 1e-4f)
        assertEquals(0f, BootTimeline.emblemAlpha(BootTimeline.EMBLEM_START - 0.001f), 1e-4f)
        assertEquals(1f, BootTimeline.emblemAlpha(BootTimeline.EMBLEM_START + BootTimeline.EMBLEM_DURATION), 1e-4f)
        assertFalse(BootTimeline.finished(BootTimeline.DURATION - 0.01f))
        assertTrue(BootTimeline.finished(BootTimeline.DURATION))
    }

    @Test
    fun `逐行打印依序推进并在行间停顿`() {
        val printer = LinePrinter(listOf("AB", "CD"))

        // "AB" 每 tick(12ms) 1 字符：两个 tick 打完；行间停顿 45ms 后第二行开始。
        var events = printer.advance(0.024f)
        assertEquals(listOf(LinePrinter.Event.LineStarted(0)), events)
        assertEquals("AB", printer.visibleText(0))
        assertEquals("", printer.visibleText(1))

        events = printer.advance(0.044f)
        assertTrue(events.isEmpty())
        assertEquals("", printer.visibleText(1))

        events = printer.advance(0.001f)
        assertEquals(listOf(LinePrinter.Event.LineStarted(1)), events)

        events = printer.advance(0.024f)
        assertEquals("CD", printer.visibleText(1))
        assertTrue(events.isEmpty())

        events = printer.advance(LinePrinter.LINE_GAP_MS / 1000f)
        assertEquals(listOf(LinePrinter.Event.AllDone), events)
        assertTrue(printer.done)
    }

    @Test
    fun `卡死行打印四成后停滞随后整行抹除且队列继续`() {
        // 10 字符 → 每 tick 1 字符，卡死阈值为 4 字符。
        val printer = LinePrinter(listOf("0123456789", "ok"), jammedLines = setOf(0))

        var events = printer.advance(0.048f)
        assertEquals("0123", printer.visibleText(0))
        assertTrue(events.filterIsInstance<LinePrinter.Event.JamLineErased>().isEmpty())

        // 停滞 300ms 期间文本不动。
        events = printer.advance(0.299f)
        assertEquals("0123", printer.visibleText(0))
        assertTrue(events.isEmpty())

        // 停滞结束后整行抹除，下一行随即开始。
        events = printer.advance(0.002f)
        assertEquals("", printer.visibleText(0))
        assertEquals(
            listOf(LinePrinter.Event.JamLineErased(0), LinePrinter.Event.LineStarted(1)),
            events,
        )

        events = printer.advance(0.024f)
        assertEquals("ok", printer.visibleText(1))
        events = printer.advance(LinePrinter.LINE_GAP_MS / 1000f)
        assertTrue(events.any { it is LinePrinter.Event.AllDone })
        assertTrue(printer.done)
    }

    @Test
    fun `空队列立即完成且未开始行不可见`() {
        val printer = LinePrinter(emptyList())
        assertTrue(printer.done)
        assertTrue(printer.advance(1f).isEmpty())

        val single = LinePrinter(listOf("x"))
        assertEquals("", single.visibleText(0))
        single.advance(LinePrinter.TICK_MS / 1000f)
        assertEquals("x", single.visibleText(0))
        single.advance(1f)
        assertTrue(single.done)
    }

    @Test
    fun `卡死行下标越界拒绝构造`() {
        val e = assertFailsWith<IllegalArgumentException> {
            LinePrinter(listOf("x"), jammedLines = setOf(1))
        }
        assertTrue(e.message!!.contains("卡死行下标越界"))
    }

    @Test
    fun `盖章时间线缩放震屏墨渍与落定`() {
        assertEquals(2.6f, StampTimeline.scale(0f), 1e-3f)
        assertEquals(1f, StampTimeline.scale(StampTimeline.SLAM_DURATION), 1e-3f)
        assertEquals(0.92f, StampTimeline.stampAlpha(0.1f), 1e-3f)

        assertFalse(StampTimeline.shaking(StampTimeline.SHAKE_START - 0.001f))
        val still = StampTimeline.shakeOffset(StampTimeline.SHAKE_START - 0.001f)
        assertEquals(0f, still.first, 1e-4f)
        assertEquals(0f, still.second, 1e-4f)
        val shaking = StampTimeline.shakeOffset(StampTimeline.SHAKE_START + 0.05f)
        assertTrue(shaking.first * shaking.first + shaking.second * shaking.second > 0.01f)
        val recovered = StampTimeline.shakeOffset(StampTimeline.SHAKE_START + StampTimeline.SHAKE_DURATION + 0.01f)
        assertEquals(0f, recovered.first, 1e-4f)
        assertEquals(0f, recovered.second, 1e-4f)

        assertEquals(0f, StampTimeline.splashAlpha(StampTimeline.SHAKE_START - 0.001f))
        assertTrue(StampTimeline.splashAlpha(StampTimeline.SHAKE_START + 0.1f) > 0f)
        assertEquals(1f, StampTimeline.splashScale(StampTimeline.SHAKE_START), 1e-3f)
        assertEquals(3.2f, StampTimeline.splashScale(StampTimeline.SHAKE_START + StampTimeline.SPLASH_DURATION), 1e-3f)
        assertEquals(0.25f, StampTimeline.splashAlpha(StampTimeline.SETTLE), 1e-3f)

        assertFalse(StampTimeline.settled(StampTimeline.SETTLE - 0.01f))
        assertTrue(StampTimeline.settled(StampTimeline.SETTLE))
    }

    @Test
    fun `回执金额数字滚动单调到位`() {
        assertEquals(0, ReceiptRoll.amountAt(1000, 0f))
        assertEquals(1000, ReceiptRoll.amountAt(1000, ReceiptRoll.DURATION))
        val mid = ReceiptRoll.amountAt(1000, ReceiptRoll.DURATION * 0.5f)
        assertTrue(mid in 1..999)
        assertTrue(ReceiptRoll.amountAt(1000, 0.3f) <= ReceiptRoll.amountAt(1000, 0.6f))
        assertFalse(ReceiptRoll.done(ReceiptRoll.DURATION - 0.01f))
        assertTrue(ReceiptRoll.done(ReceiptRoll.DURATION))
    }

    @Test
    fun `glitch 抖动范围与撕裂带窗口`() {
        assertTrue(GlitchTimeline.active(0f))
        assertFalse(GlitchTimeline.active(GlitchTimeline.DURATION + 0.01f))

        repeat(20) { i ->
            val t = 0.01f + i * 0.02f
            val jitter = GlitchTimeline.jitterOffset(t)
            assertTrue(jitter.first >= -GlitchTimeline.JITTER_AMPLITUDE - 0.01f)
            assertTrue(jitter.first <= GlitchTimeline.JITTER_AMPLITUDE + 0.01f)
        }
        val frozen = GlitchTimeline.jitterOffset(GlitchTimeline.DURATION + 0.01f)
        assertEquals(0f, frozen.first, 1e-4f)
        assertEquals(0f, frozen.second, 1e-4f)
        assertEquals(0f, GlitchTimeline.tearOffset(GlitchTimeline.DURATION + 0.01f, band = 3), 1e-4f)

        // 窗口内至少存在一条撕裂带产生非零错位。
        val tears = (0 until 8).map { GlitchTimeline.tearOffset(0.1f, band = it) }
        assertTrue(tears.any { it != 0f })
        assertTrue(tears.all { it >= -GlitchTimeline.TEAR_AMPLITUDE && it <= GlitchTimeline.TEAR_AMPLITUDE })
    }

    @Test
    fun `glitch 噪点行按白 teal 红循环分桶且白桶略亮`() {
        assertEquals(GlitchTimeline.NoiseTone.WHITE, GlitchTimeline.noiseTone(0))
        assertEquals(GlitchTimeline.NoiseTone.TEAL, GlitchTimeline.noiseTone(1))
        assertEquals(GlitchTimeline.NoiseTone.RED, GlitchTimeline.noiseTone(2))
        assertEquals(GlitchTimeline.NoiseTone.WHITE, GlitchTimeline.noiseTone(3))

        // 同桶行颜色与透明度一致（渲染侧据此合批，每桶一次 GL 状态提交）
        assertEquals(GlitchTimeline.noiseAlpha(GlitchTimeline.noiseTone(0)), GlitchTimeline.noiseAlpha(GlitchTimeline.noiseTone(6)))
        assertTrue(
            GlitchTimeline.noiseAlpha(GlitchTimeline.NoiseTone.WHITE) >
                GlitchTimeline.noiseAlpha(GlitchTimeline.NoiseTone.TEAL),
        )
        assertEquals(
            GlitchTimeline.noiseAlpha(GlitchTimeline.NoiseTone.TEAL),
            GlitchTimeline.noiseAlpha(GlitchTimeline.NoiseTone.RED),
        )

        // 连续行序列恰好覆盖三桶且循环完整
        val tones = (0 until 12).map(GlitchTimeline::noiseTone)
        assertEquals(3, tones.toSet().size)
        assertEquals(tones.take(3), tones.takeLast(3))
    }

    @Test
    fun `待机呼吸透明度值域`() {
        var t = 0f
        while (t <= BreatheTimeline.PERIOD) {
            val alpha = BreatheTimeline.alpha(t)
            assertTrue(
                alpha >= BreatheTimeline.MIN_ALPHA - 1e-3f && alpha <= 1.001f,
                "alpha=$alpha at t=$t",
            )
            t += 0.1f
        }
        assertEquals(BreatheTimeline.MIN_ALPHA, BreatheTimeline.alpha(0f), 1e-3f)
        assertEquals(1f, BreatheTimeline.alpha(BreatheTimeline.PERIOD / 2f), 1e-2f)
    }

    @Test
    fun `特效延迟拷贝保留载荷`() {
        val stamp = TerminalEffect.StampSlam(StampKind.ACCEPTED).delayed(0.7f)
        assertEquals(0.7f, stamp.delaySeconds)
        assertEquals(StampKind.ACCEPTED, (stamp as TerminalEffect.StampSlam).kind)

        val receipt = ReceiptView("k", "s", "i18n", 100, null, 0, null, 0f, "d")
        val shown = TerminalEffect.ShowReceipt(receipt, GlitchSpec.HALF_LINE).delayed(0.8f)
        assertEquals(GlitchSpec.HALF_LINE, (shown as TerminalEffect.ShowReceipt).glitchSpec)
        assertEquals("s", shown.receipt.serial)

        val sound = TerminalEffect.PlaySound(TerminalSound.GLITCH).delayed(1.0f)
        assertEquals(TerminalSound.GLITCH, (sound as TerminalEffect.PlaySound).sound)
        assertEquals("ui_noise_static", TerminalSound.GLITCH.soundId)
    }
}
