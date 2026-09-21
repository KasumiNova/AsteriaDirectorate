package cn.kasuminova.astd.campaign.ui.terminal

import cn.kasuminova.astd.campaign.ui.terminal.LinePrinter.Companion.JAM_CHAR_RATIO
import cn.kasuminova.astd.campaign.ui.terminal.LinePrinter.Companion.JAM_HOLD_MS
import cn.kasuminova.astd.campaign.ui.terminal.LinePrinter.Companion.LINE_GAP_MS
import cn.kasuminova.astd.campaign.ui.terminal.LinePrinter.Companion.TICK_MS
import cn.kasuminova.astd.campaign.ui.terminal.LinePrinter.Companion.charsPerTick
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 分局终端六动效的纯时间线逻辑（doc 00「动效与特效清单」，时序参数对齐
 * `tools/mod-ui-preview/app.js` 原型）。
 *
 * 本文件只做「时间 → 进度/位移/透明度/可见字符数」的纯计算，不触碰 GL/音效/游戏对象；
 * GL 直绘与音效触发由 UI 装配层按本层输出驱动，时序行为可脱离游戏环境单测。
 *
 * 六动效对应：
 * - [BootTimeline] 终端开机（扫描线点亮 → 徽记淡入 → 进入，可跳过）；
 * - [LinePrinter] 逐行打印（打字机推进，行首触发打印音事件）；
 * - [StampTimeline] 盖章（砸落 + 震屏 + 墨渍扩散）；
 * - [ReceiptRoll] 回执金额数字滚动（三次缓出）；
 * - [GlitchTimeline] 闪现（噪点 + 横向撕裂，<0.5s 自愈）；
 * - [BreatheTimeline] 待机呼吸（低频扫描线明暗波动）。
 */

/** 终端开机时间线（原型：0.05s 扫描线 0.55s、0.6s 徽记淡入 0.45s、约 1.5s 自动进入）。 */
object BootTimeline {
    /** 扫描线开始时刻（s）。 */
    const val SWEEP_START: Float = 0.05f

    /** 扫描线持续时间（s）。 */
    const val SWEEP_DURATION: Float = 0.55f

    /** 徽记淡入开始时刻（s）。 */
    const val EMBLEM_START: Float = 0.60f

    /** 徽记淡入时长（s）。 */
    const val EMBLEM_DURATION: Float = 0.45f

    /** 开机场总时长（自动进入时刻，s）。 */
    const val DURATION: Float = 1.5f

    /** 扫描线纵向进度（0=顶部，1=底部；未开始为 0，结束后保持 1）。 */
    fun sweepProgress(t: Float): Float =
        ((t - SWEEP_START) / SWEEP_DURATION).coerceIn(0f, 1f)

    /** 徽记透明度（0~1 线性淡入）。 */
    fun emblemAlpha(t: Float): Float =
        ((t - EMBLEM_START) / EMBLEM_DURATION).coerceIn(0f, 1f)

    /** 开机场是否应结束（自然播完）。 */
    fun finished(t: Float): Boolean = t >= DURATION
}

/**
 * 逐行打印推进器（打字机）。
 *
 * 原型节奏：行内每 [TICK_MS]ms 推进 [charsPerTick] 字（长行加速：len/40），
 * 行完成后停顿 [LINE_GAP_MS]ms 进入下一行；每行开始时产生一次打印音事件。
 *
 * 卡死行（三章末 glitch）：打印至 [JAM_CHAR_RATIO] 后停滞 [JAM_HOLD_MS]ms，
 * 随后整行抹除（自愈，不解释），打印队列继续推进。
 *
 * 用法：构造后每帧 [advance] 推进并按事件刷新文本；[visibleText] 取某行当前可见前缀
 * （卡死行抹除后返回空串）。
 */
class LinePrinter(
    private val lines: List<String>,
    /** 卡死行下标集合（三章末半行工单 glitch 注入）。 */
    private val jammedLines: Set<Int> = emptySet(),
) {

    /** 打印推进事件。 */
    sealed interface Event {
        /** 一行开始打印（UI 层据此播打印音）。 */
        data class LineStarted(val index: Int) : Event

        /** 卡死行被抹除（自愈；UI 层清空对应文本）。 */
        data class JamLineErased(val index: Int) : Event

        /** 全部行打印完成。 */
        data object AllDone : Event
    }

    private enum class Phase { TYPING, GAP, JAM_HOLD }

    /** 当前行下标（尚未开始的行不显示）。 */
    var lineIndex: Int = 0
        private set

    /** 当前行已打印字符数。 */
    var charIndex: Int = 0
        private set

    private var phase: Phase = Phase.TYPING
    private var phaseAccMs: Float = 0f
    private var lineStartFired: Boolean = false
    private val erasedLines = HashSet<Int>(jammedLines.size)

    /** 是否全部打印完成。 */
    var done: Boolean = lines.isEmpty()
        private set

    init {
        for (i in jammedLines) {
            require(i in lines.indices) { "卡死行下标越界：$i（共 ${lines.size} 行）" }
        }
    }

    /** 推进 [dtSeconds] 秒，返回本次推进产生的事件（按发生顺序）。 */
    fun advance(dtSeconds: Float): List<Event> {
        if (done) return emptyList()
        val events = mutableListOf<Event>()
        if (!lineStartFired) {
            lineStartFired = true
            events += Event.LineStarted(lineIndex)
        }
        var remaining = dtSeconds * 1000f
        while (remaining > 0f && !done) {
            when (phase) {
                Phase.TYPING -> {
                    val line = lines[lineIndex]
                    val limit = if (lineIndex in jammedLines) jamCharLimit(line.length) else line.length
                    val need = TICK_MS - phaseAccMs
                    val step = min(remaining, need)
                    phaseAccMs += step
                    remaining -= step
                    if (phaseAccMs >= TICK_MS) {
                        phaseAccMs = 0f
                        charIndex = min(limit, charIndex + charsPerTick(line.length))
                        if (charIndex >= limit) {
                            phase = if (lineIndex in jammedLines) Phase.JAM_HOLD else Phase.GAP
                        }
                    }
                }

                Phase.GAP -> {
                    val need = LINE_GAP_MS - phaseAccMs
                    val step = min(remaining, need)
                    phaseAccMs += step
                    remaining -= step
                    if (phaseAccMs >= LINE_GAP_MS) nextLine(events)
                }

                Phase.JAM_HOLD -> {
                    val need = JAM_HOLD_MS - phaseAccMs
                    val step = min(remaining, need)
                    phaseAccMs += step
                    remaining -= step
                    if (phaseAccMs >= JAM_HOLD_MS) {
                        erasedLines += lineIndex
                        events += Event.JamLineErased(lineIndex)
                        nextLine(events)
                    }
                }
            }
        }
        return events
    }

    /** 第 [index] 行当前可见文本（未开始的行与已抹除的卡死行为空串）。 */
    fun visibleText(index: Int): String {
        if (index > lineIndex || index in erasedLines) return ""
        val line = lines[index]
        if (index < lineIndex) return line
        return line.substring(0, charIndex.coerceIn(0, line.length))
    }

    private fun nextLine(events: MutableList<Event>) {
        charIndex = 0
        phase = Phase.TYPING
        phaseAccMs = 0f
        if (lineIndex + 1 >= lines.size) {
            done = true
            events += Event.AllDone
        } else {
            lineIndex++
            events += Event.LineStarted(lineIndex)
        }
    }

    companion object {
        /** 行内逐字推进节拍（ms）。 */
        const val TICK_MS: Float = 12f

        /** 行间停顿（ms）。 */
        const val LINE_GAP_MS: Float = 45f

        /** 卡死行停滞时长（ms；控制在 glitch 自愈窗口内）。 */
        const val JAM_HOLD_MS: Float = 300f

        /** 卡死行打印至此比例后停滞（半行观感）。 */
        const val JAM_CHAR_RATIO: Float = 0.4f

        /** 每节拍推进字符数（长行加速：原型 step = max(1, len/40)）。 */
        fun charsPerTick(lineLength: Int): Int = max(1, lineLength / 40)

        /** 卡死行的停滞字符数（半行观感：len 的 [JAM_CHAR_RATIO]，至少 1 字）。 */
        fun jamCharLimit(lineLength: Int): Int =
            max(1, (lineLength * JAM_CHAR_RATIO).roundToInt()).coerceAtMost(lineLength)
    }
}

/** 盖章时间线（原型：0.12s 砸落、0.13s 起震屏 0.28s、墨渍 0.6s 扩散、0.7s 收尾）。 */
object StampTimeline {
    /** 砸落时长（s，章面 scale 2.6 → 1）。 */
    const val SLAM_DURATION: Float = 0.12f

    /** 震屏开始时刻（s）。 */
    const val SHAKE_START: Float = 0.13f

    /** 震屏时长（s）。 */
    const val SHAKE_DURATION: Float = 0.28f

    /** 墨渍扩散时长（s，自震屏开始）。 */
    const val SPLASH_DURATION: Float = 0.6f

    /** 动效收尾时刻（s，此后章面保持常驻观感）。 */
    const val SETTLE: Float = 0.7f

    /** 震屏峰值位移（px）。 */
    const val SHAKE_AMPLITUDE: Float = 6f

    /** 章面缩放（砸落前 2.6 倍，砸落到位为 1；缓入近似原型 cubic-bezier(.6,0,1,.6)）。 */
    fun scale(t: Float): Float {
        val p = (t / SLAM_DURATION).coerceIn(0f, 1f)
        return 2.6f - 1.6f * p * p
    }

    /** 章面透明度（0.1s 内淡入至 0.92）。 */
    fun stampAlpha(t: Float): Float = (t / 0.1f).coerceIn(0f, 1f) * 0.92f

    /** 震屏是否激活。 */
    fun shaking(t: Float): Boolean = t >= SHAKE_START && t < SHAKE_START + SHAKE_DURATION

    /**
     * 震屏位移（px，确定性衰减振荡；[seed] 区分多次盖章的相位）。
     * 窗口外返回 0,0（震屏结束画面必须回正）。
     */
    fun shakeOffset(t: Float, seed: Int = 0): Pair<Float, Float> {
        if (!shaking(t)) return 0f to 0f
        val p = (t - SHAKE_START) / SHAKE_DURATION
        val decay = 1f - p
        val phase = seed * 1.7f
        val dx = SHAKE_AMPLITUDE * decay * sin(p * 31f + phase)
        val dy = SHAKE_AMPLITUDE * 0.7f * decay * cos(p * 41f + phase)
        return dx to dy
    }

    /** 墨渍缩放（震屏起 1 → 3.2，缓出）。 */
    fun splashScale(t: Float): Float {
        val p = ((t - SHAKE_START) / SPLASH_DURATION).coerceIn(0f, 1f)
        return 1f + 2.2f * (1f - (1f - p) * (1f - p) * (1f - p))
    }

    /** 墨渍透明度（扩散至 0.5，收尾后常驻 0.25）。 */
    fun splashAlpha(t: Float): Float {
        if (t < SHAKE_START) return 0f
        val p = ((t - SHAKE_START) / SPLASH_DURATION).coerceIn(0f, 1f)
        return if (t < SETTLE) 0.5f * p else 0.25f
    }

    /** 动效是否收尾（章面转入常驻静态观感）。 */
    fun settled(t: Float): Boolean = t >= SETTLE
}

/** 回执金额数字滚动（原型：900ms 三次缓出到位）。 */
object ReceiptRoll {
    /** 滚动时长（s）。 */
    const val DURATION: Float = 0.9f

    /** [t] 时刻的显示金额（0 → target，三次缓出）。 */
    fun amountAt(target: Int, t: Float): Int {
        val p = (t / DURATION).coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        return (target * eased).roundToInt()
    }

    /** 滚动是否到位。 */
    fun done(t: Float): Boolean = t >= DURATION
}

/** 闪现 glitch 时间线（<0.5s 自愈，系统不解释）。 */
object GlitchTimeline {
    /** 自愈时刻（s；原型 450ms）。 */
    const val DURATION: Float = 0.45f

    /** 噪点横向抖动幅度（px）。 */
    const val JITTER_AMPLITUDE: Float = 3f

    /** 撕裂带最大横向错位（px）。 */
    const val TEAR_AMPLITUDE: Float = 14f

    /** 噪点行色桶（白/teal/红交替；同桶行颜色与透明度一致，渲染侧据此合批）。 */
    enum class NoiseTone { WHITE, TEAL, RED }

    fun active(t: Float): Boolean = t in 0f..DURATION

    /** 第 [row] 条噪点行（2px 间距自上而下编号）的色桶。 */
    fun noiseTone(row: Int): NoiseTone = when (row % 3) {
        0 -> NoiseTone.WHITE
        1 -> NoiseTone.TEAL
        else -> NoiseTone.RED
    }

    /** 噪点行基准透明度（白桶略亮；渲染侧乘 alphaMult 后使用）。 */
    fun noiseAlpha(tone: NoiseTone): Float = if (tone == NoiseTone.WHITE) 0.05f else 0.04f

    /** 全屏噪点层抖动位移（px，每 ~60ms 跳变一次，steps(2) 观感）。 */
    fun jitterOffset(t: Float, seed: Int = 0): Pair<Float, Float> {
        if (!active(t)) return 0f to 0f
        val step = (t / 0.06f).toInt()
        val a = (step * 37 + seed * 11) % 7 - 3
        val b = (step * 53 + seed * 5) % 5 - 2
        return a * JITTER_AMPLITUDE / 3f to b.toFloat()
    }

    /**
     * 第 [band] 条横向撕裂带的错位（px；撕裂带自上而下等分，
     * 随时间滚动 —— 同一帧内不同带错位不同，帧间跳变）。
     */
    fun tearOffset(t: Float, band: Int, seed: Int = 0): Float {
        if (!active(t)) return 0f
        val step = (t / 0.06f).toInt()
        val hash = (band * 31 + step * 17 + seed * 13) % 11
        // 约半数带保持不动，其余带正/负错位
        if (hash % 3 == 0) return 0f
        val sign = if (hash % 2 == 0) 1f else -1f
        return sign * TEAR_AMPLITUDE * (hash % 5) / 4f
    }
}

/** 待机呼吸（原型：7s 周期，扫描线明暗 0.35 ~ 1.0 波动）。 */
object BreatheTimeline {
    /** 呼吸周期（s）。 */
    const val PERIOD: Float = 7f

    /** 呼吸最低亮度系数。 */
    const val MIN_ALPHA: Float = 0.35f

    /** [t] 时刻的亮度系数（0.35 ~ 1.0，余弦缓动）。 */
    fun alpha(t: Float): Float {
        val p = (t % PERIOD) / PERIOD
        return MIN_ALPHA + (1f - MIN_ALPHA) * (0.5f - 0.5f * cos(p * 2f * Math.PI.toFloat()))
    }
}
