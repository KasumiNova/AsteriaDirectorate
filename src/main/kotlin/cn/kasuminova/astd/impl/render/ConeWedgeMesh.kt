package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 锥面冲击特效「连续扇形楔块」的网格/包络纯函数族（计划 00-锥面冲击特效重做计划 §4.3）。
 *
 * 动机：离散射线扇面（等角距、同亮同灭）被实机评审判死；楔块改连续扇形三角网格，
 * 走既有 [TexTrailRenderer] 管线（世界系 8 浮点交错顶点流 + GL_TRIANGLES + 平铺滚动贴图）。
 * 全部函数零引擎依赖、可直接单测，组件侧（ConeWedgeComponent）每帧只负责喂参数、推顶点流。
 *
 * UV 约定（片元着色器语义 `tex.x = v*0.5+0.5`、`tex.y = u`，见 TexTrailRenderer）：
 * - u（径向）= r / tileLength − scroll，scroll 随时间推进 → 花纹沿径向向锥缘爬行；
 * - v（角向）：θ ∈ [−halfAngle, +halfAngle] 线性映射进贴图 alpha 带 [vLo, vHi]（带外 alpha≈0 → 扇缘自然软边）。
 */

/** 角向分段数下限：窄锥不少于 8 段保圆弧读感。 */
const val WEDGE_ANGULAR_SEGS_MIN = 8

/** 角向分段数上限：宽锥（破晓 80° 半角档）的防爆闸。 */
const val WEDGE_ANGULAR_SEGS_MAX = 36

/** 径向 alpha 基座：已抵达区的底色亮度（全局包络再乘在其上）。 */
const val WEDGE_ALPHA_BASE = 0.35f

/** 径向 alpha 峰增量：波前亮带在基座上再叠的三角峰高度。 */
const val WEDGE_ALPHA_PEAK = 0.65f

/** 未抵达区淡影倍率：r > frontR 的扇形边界（±halfAngle 全貌）恒隐约可见，机制覆盖范围从第一帧可读。 */
const val WEDGE_FAINT_MUL = 0.3f

/** 波前亮带宽度占锥长比例（三角峰的半宽 = 0.3 × length）。 */
const val WEDGE_FRONT_BAND_RATIO = 0.3f

/**
 * 角向分段数（纯函数）：ceil(全角 / 5°) clamp [WEDGE_ANGULAR_SEGS_MIN, WEDGE_ANGULAR_SEGS_MAX]。
 * 贯星 40° 半角（80° 全角）→ 16 段；破晓 80° 半角（160° 全角）→ 32 段。
 */
fun wedgeAngularSegments(halfAngleDeg: Float): Int =
    ceil(halfAngleDeg * 2f / 5f).toInt().coerceIn(WEDGE_ANGULAR_SEGS_MIN, WEDGE_ANGULAR_SEGS_MAX)

/**
 * 全局 alpha 包络（纯函数）：expand 段 0→1（smoothstep）→ hold 段恒 1（至 [holdEnd] = duration − fadeOut）
 * → fadeOut 段线性 1→0（duration 末归零）。
 */
fun wedgeEnvelope(t: Float, expandSeconds: Float, holdEnd: Float, duration: Float): Float {
    if (t <= 0f) return 0f
    if (t < expandSeconds) return wedgeSmoothstep(t / expandSeconds)
    if (t <= holdEnd) return 1f
    if (t >= duration) return 0f
    return (1f - (t - holdEnd) / (duration - holdEnd)).coerceIn(0f, 1f)
}

/** 波前半径（纯函数）：length × smoothstep(t / expand)，expand 末抵达全长。 */
fun wedgeFrontRadius(t: Float, expandSeconds: Float, length: Float): Float =
    length * wedgeSmoothstep((t / expandSeconds).coerceIn(0f, 1f))

/**
 * 径向 alpha profile（纯函数）：已抵达区 = BASE + PEAK × 三角峰((frontR − r) / band)（亮带从顶点推向锥缘）；
 * r > frontR 的未抵达区保留 BASE × [WEDGE_FAINT_MUL] 淡影（扇形边界恒可读）。
 */
fun wedgeRadialAlpha(r: Float, frontR: Float, band: Float): Float {
    if (r > frontR) return WEDGE_ALPHA_BASE * WEDGE_FAINT_MUL
    val peak = (1f - abs((frontR - r) / band.coerceAtLeast(1e-3f))).coerceIn(0f, 1f)
    return WEDGE_ALPHA_BASE + WEDGE_ALPHA_PEAK * peak
}

/**
 * 楔块顶点流主函数（纯函数）：极坐标网格（径向 [radialSegs] 段 × 角向 [angularSegs] 段）
 * 烘成世界系 8 浮点交错（x,y,u,v,r,g,b,a，布局同 [TEX_TRAIL_VERTEX_FLOATS]）的 GL_TRIANGLES 流，
 * 三角形数 = 2 × radialSegs × angularSegs。
 *
 * [angularJitter] 为角向每列的固定偏角（度，长度 = angularSegs + 1，spawn 时随机一次、生命周期不变——
 * MagicTrail dispersion 的静化版，破等角距机械感）；[envelopeAlpha] 已含全局包络与层倍率。
 * 首圈（r=0）全部顶点收在 [origin]；扇缘角向恒对齐 ±halfAngle + 对应列抖动。
 */
fun coneWedgeFan(
    origin: Vector2f,
    facingDeg: Float,
    halfAngleDeg: Float,
    length: Float,
    radialSegs: Int,
    angularSegs: Int,
    vLo: Float,
    vHi: Float,
    tileLength: Float,
    scroll: Float,
    frontR: Float,
    envelopeAlpha: Float,
    red: Float,
    green: Float,
    blue: Float,
    angularJitter: FloatArray,
): FloatArray {
    val radial = radialSegs.coerceAtLeast(1)
    val angular = angularSegs.coerceAtLeast(1)
    val safeTile = tileLength.coerceAtLeast(1f)
    val band = WEDGE_FRONT_BAND_RATIO * length
    val cols = angular + 1

    // 先逐格点算好位置/UV/alpha，再按单元格烘三角形（每格点只算一次，避免 4 倍重复三角函数）。
    val px = FloatArray((radial + 1) * cols)
    val py = FloatArray(px.size)
    val pu = FloatArray(px.size)
    val pv = FloatArray(px.size)
    val pa = FloatArray(px.size)
    for (i in 0..radial) {
        val r = length * i / radial
        val u = r / safeTile - scroll
        val alpha = (envelopeAlpha * wedgeRadialAlpha(r, frontR, band)).coerceIn(0f, 1f)
        for (j in 0..angular) {
            val t = j.toFloat() / angular
            val thetaDeg = -halfAngleDeg + 2f * halfAngleDeg * t + angularJitter[j]
            val rad = Math.toRadians((facingDeg + thetaDeg).toDouble())
            val idx = i * cols + j
            px[idx] = origin.x + r * cos(rad).toFloat()
            py[idx] = origin.y + r * sin(rad).toFloat()
            pu[idx] = u
            pv[idx] = vLo + (vHi - vLo) * t
            pa[idx] = alpha
        }
    }

    val out = FloatArray(2 * radial * angular * 3 * TEX_TRAIL_VERTEX_FLOATS)
    var cursor = 0
    for (i in 0 until radial) {
        for (j in 0 until angular) {
            val v00 = i * cols + j
            val v10 = (i + 1) * cols + j
            val v01 = i * cols + j + 1
            val v11 = (i + 1) * cols + j + 1
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v00, red, green, blue)
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v10, red, green, blue)
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v11, red, green, blue)
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v00, red, green, blue)
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v11, red, green, blue)
            cursor = emitWedgeVertex(out, cursor, px, py, pu, pv, pa, v01, red, green, blue)
        }
    }
    return out
}

/** 往顶点流写一个 8 浮点顶点（x,y,u,v,r,g,b,a），返回推进后的游标。 */
private fun emitWedgeVertex(
    out: FloatArray,
    cursor: Int,
    px: FloatArray,
    py: FloatArray,
    pu: FloatArray,
    pv: FloatArray,
    pa: FloatArray,
    idx: Int,
    red: Float,
    green: Float,
    blue: Float,
): Int {
    var c = cursor
    out[c++] = px[idx]
    out[c++] = py[idx]
    out[c++] = pu[idx]
    out[c++] = pv[idx]
    out[c++] = red.coerceIn(0f, 1f)
    out[c++] = green.coerceIn(0f, 1f)
    out[c++] = blue.coerceIn(0f, 1f)
    out[c++] = pa[idx]
    return c
}

private fun wedgeSmoothstep(x: Float): Float = x * x * (3f - 2f * x)
