package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.util.Highlights
import java.awt.Color
import java.util.ArrayDeque

/**
 * 用于实现“打开对话后，文案不会一次性弹出，而是按延迟逐条自动输出”。
 *
 * 设计：每条 [Entry] 的 [delay] 表示“距离上一条输出”的延迟（秒）。
 */
class TimedTextQueue(private val textPanel: TextPanelAPI) {

    data class Entry(
        val text: String,
        val delay: Float = 0f,
        val baseColor: Color? = null,
        val highlights: Highlights? = null,

        /** 淡入时长（秒）。为 0 表示立刻显示。 */
        val fadeIn: Float = 0f,
        /** 完全可见停留时长（秒）。为 0 表示不额外停留。 */
        val hold: Float = 0f,
        /** 淡出时长（秒）。为 0 表示不淡出。 */
        val fadeOut: Float = 0f,
        /** 最大不透明度（0..1）。默认 1。 */
        val maxOpacity: Float = 1f,
    )

    private data class Active(
        val label: LabelAPI,
        val fadeIn: Float,
        val hold: Float,
        val fadeOut: Float,
        val maxOpacity: Float,
        var age: Float = 0f,
    )

    private val queue = ArrayDeque<Entry>()
    private var acc = 0f

    private val actives = ArrayList<Active>(8)

    val hasPending: Boolean
        get() = queue.isNotEmpty()

    fun clear() {
        queue.clear()
        acc = 0f
        actives.clear()
    }

    fun enqueue(entry: Entry) {
        queue.addLast(
            entry.copy(
                delay = entry.delay.coerceAtLeast(0f),
                fadeIn = entry.fadeIn.coerceAtLeast(0f),
                hold = entry.hold.coerceAtLeast(0f),
                fadeOut = entry.fadeOut.coerceAtLeast(0f),
                maxOpacity = entry.maxOpacity.coerceIn(0f, 1f),
            )
        )
    }

    fun enqueue(text: String, delay: Float = 0f, baseColor: Color? = null) {
        enqueue(Entry(text = text, delay = delay, baseColor = baseColor))
    }

    fun enqueueFading(
        text: String,
        delay: Float = 0f,
        fadeIn: Float = 0.2f,
        hold: Float = 0f,
        fadeOut: Float = 0f,
        maxOpacity: Float = 1f,
        baseColor: Color? = null,
    ) {
        enqueue(
            Entry(
                text = text,
                delay = delay,
                baseColor = baseColor,
                fadeIn = fadeIn,
                hold = hold,
                fadeOut = fadeOut,
                maxOpacity = maxOpacity,
            )
        )
    }

    fun enqueue(rendered: I18n.Rendered, delay: Float = 0f, baseColor: Color? = null) {
        val hs = rendered.toHighlightsOrNull()
        enqueue(Entry(text = rendered.text, delay = delay, baseColor = baseColor, highlights = hs))
    }

    fun enqueueFading(
        rendered: I18n.Rendered,
        delay: Float = 0f,
        fadeIn: Float = 0.2f,
        hold: Float = 0f,
        fadeOut: Float = 0f,
        maxOpacity: Float = 1f,
        baseColor: Color? = null,
    ) {
        val hs = rendered.toHighlightsOrNull()
        enqueue(
            Entry(
                text = rendered.text,
                delay = delay,
                baseColor = baseColor,
                highlights = hs,
                fadeIn = fadeIn,
                hold = hold,
                fadeOut = fadeOut,
                maxOpacity = maxOpacity,
            )
        )
    }

    /**
     * 推进队列；返回本次实际输出了多少条。
     */
    fun advance(amount: Float): Int {
        if (amount <= 0f) return 0

        acc += amount
        var emitted = 0
        if (queue.isNotEmpty()) {
            while (queue.isNotEmpty()) {
                val next = queue.first()
                if (acc + 1e-6f < next.delay) break

                queue.removeFirst()
                acc -= next.delay
                emit(next, immediate = false)
                emitted++
            }
        }

        if (actives.isNotEmpty()) {
            advanceActives(amount)
        }
        return emitted
    }

    /**
     * 立刻输出所有剩余文本（用于“跳过/快进”）。
     */
    fun flush(): Int {
        var emitted = 0
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            // flush = 快进：不做淡入淡出，直接满透明度显示。
            emit(e.copy(delay = 0f), immediate = true)
            emitted++
        }
        acc = 0f
        return emitted
    }

    private fun emit(e: Entry, immediate: Boolean) {
        val label = if (e.baseColor != null) {
            textPanel.addPara(e.text, e.baseColor)
        } else {
            textPanel.addPara(e.text)
        }
        if (e.highlights != null) {
            textPanel.setHighlightsInLastPara(e.highlights)
        }

        if (immediate) {
            label.setOpacity(1f)
            return
        }

        val hasFade = (e.fadeIn > 0f) || (e.fadeOut > 0f) || (e.hold > 0f)

        // 仅调整最大透明度，但不需要随时间变化：直接设定并结束。
        if (!hasFade) {
            if (e.maxOpacity < 1f) {
                label.setOpacity(e.maxOpacity)
            }
            return
        }

        // 初始透明度：有 fadeIn 则从 0 开始，否则直接到 maxOpacity。
        val start = if (e.fadeIn > 0f) 0f else e.maxOpacity
        label.setOpacity(start)
        actives.add(
            Active(
                label = label,
                fadeIn = e.fadeIn,
                hold = e.hold,
                fadeOut = e.fadeOut,
                maxOpacity = e.maxOpacity,
            )
        )
    }

    private fun advanceActives(amount: Float) {
        var i = 0
        while (i < actives.size) {
            val a = actives[i]
            a.age += amount

            val t = a.age
            val fadeIn = a.fadeIn
            val hold = a.hold
            val fadeOut = a.fadeOut
            val max = a.maxOpacity

            val opacity = when {
                fadeIn > 0f && t < fadeIn -> (t / fadeIn).coerceIn(0f, 1f) * max
                t < fadeIn + hold -> max
                fadeOut > 0f && t < fadeIn + hold + fadeOut -> {
                    val u = (t - fadeIn - hold) / fadeOut
                    (1f - u.coerceIn(0f, 1f)) * max
                }
                else -> 0f
            }

            a.label.setOpacity(opacity)

            val done = when {
                fadeOut > 0f -> t >= fadeIn + hold + fadeOut
                hold > 0f -> t >= fadeIn + hold
                fadeIn > 0f -> t >= fadeIn
                else -> false
            }
            if (done) {
                actives.removeAt(i)
                continue
            }

            i++
        }
    }
}
