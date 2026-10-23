package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.util.Highlights
import java.awt.Color
import java.util.ArrayDeque

/**
 * 延迟逐条输出文本的队列；每条 [Entry] 的 [Entry.delay] 为相对上一条输出的延迟（秒）。
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
        // 在播动画段落必须推至终态：fadeIn 起步的标签停在 opacity=0，不处理会永远全透明。
        for (a in actives) {
            a.label.opacity = if (a.fadeOut > 0f) 0f else a.maxOpacity
        }
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

    /** 推进队列；返回实际输出的条数。 */
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

    /** 立刻输出所有剩余文本（跳过/快进）。 */
    fun flush(): Int {
        var emitted = 0
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
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
            label.opacity = 1f
            return
        }

        val hasFade = (e.fadeIn > 0f) || (e.fadeOut > 0f) || (e.hold > 0f)

        if (!hasFade) {
            if (e.maxOpacity < 1f) {
                label.opacity = e.maxOpacity
            }
            return
        }

        val start = if (e.fadeIn > 0f) 0f else e.maxOpacity
        label.opacity = start
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
                // 有淡出时播完归为 0；无淡出时播完停留在 max，段落不消失。
                fadeOut > 0f -> 0f
                else -> max
            }

            a.label.opacity = opacity

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
