package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.impl.render.TexTrailComponent.Companion.RENDER_ORDER_BASE
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 锚点电弧（泛用组件，首发：贯星之矛）：从**固定世界锚点**（attach 时捕获，如武器发射点）到
 * 跟随端（每帧 [RenderContext] 的 frame.origin，如射弹头部）拉一条贴图带。
 *
 * 观感语义：一条走到底的连续电弧——带体是两端钉死的折线，折点由「弧长 × 时间桶」二维值噪声
 * 驱动（时间桶按 [ArcTrailSpec.jagFlickerHz] 重掷，形成轻微抖动的闪烁动画）；叠加整体透明度
 * 闪烁（[ArcTrailSpec.alphaFlicker]）。弹体消亡后跟随端消失，电弧按 [ArcTrailSpec.fadeOutCapSeconds]
 * 快速淡出（不跟随冻结帧的带头前飞，避免拉成橡皮筋）。
 *
 * 几何/渲染完全复用拖尾体系：节点烘成世界系三角条带顶点流（[texTrailStrip]，origin 取零向量、
 * facing 取 0，节点直接产世界坐标），写入 [TexTrailRenderer] 由渲染插件统一绘制。
 */
data class ArcTrailSpec(
    /** 电弧全宽（世界单位）。 */
    val width: Float,
    /** 贴图路径（约定同 astd_trails_*：X 横向、Y 带长向 REPEAT，形在 alpha、RGB 近白）。 */
    val texturePath: String,
    /** 叠层序号：绘制序 = [RENDER_ORDER_BASE] + 本值。 */
    val layer: Int = 4,
    /** 跟随端（头）颜色。 */
    val headColor: ASTDColor,
    /** 固定锚点端（尾）颜色。 */
    val tailColor: ASTDColor,
    /** 折线节点数（沿弧长均匀分布）。 */
    val nodeCount: Int = 24,
    /** 图案平铺周期（世界单位）与滚动速度（su/s，0 不滚动）。 */
    val tileLength: Float = 200f,
    val scrollSpeed: Float = 0f,
    /** 折点位移峰值振幅（世界单位，垂直于连线方向；两端钉死为 0，中段放开）。 */
    val jagAmplitude: Float = 12f,
    /** 折点噪声空间波长（世界单位，沿连线）：值越小折点越密。 */
    val jagWavelength: Float = 220f,
    /** 折点图案重掷频率（Hz）：每个时间桶换一组噪声种子，形成抖动闪烁动画。 */
    val jagFlickerHz: Float = 9f,
    /** 整体透明度闪烁幅度（0..1）：alpha 在 [1-幅度, 1] 间按时间桶伪随机摆动。 */
    val alphaFlicker: Float = 0.15f,
    /** 消亡淡出时长上限（秒）：树 fade 传入更长窗口时按本值快速收掉。 */
    val fadeOutCapSeconds: Float = 0.12f,
)

/**
 * 生成电弧折线节点（纯函数，可测）：[start] 固定锚点 → [end] 跟随端，世界坐标。
 *
 * 折点噪声种子 = （弧长桶, 时间桶）：同一桶内跨帧逐点一致（不闪），跨桶重掷（抖动动画）；
 * 两端点位移钉 0（锚点与跟随端不脱开），中段按 sin(πt) 包络放开。
 * 节点角为相邻折点连线方向（度，归一化 [0,360)）；颜色尾→头渐变，整体乘 [intensity] 与
 * 透明度闪烁系数。
 */
fun arcTrailNodes(
    start: Vector2f,
    end: Vector2f,
    spec: ArcTrailSpec,
    elapsed: Float,
    intensity: Float,
): List<TexTrailNode> {
    val count = spec.nodeCount.coerceAtLeast(2)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val dirX = dx / length
    val dirY = dy / length
    // 连线法向（与 texTrailStrip 法向约定无关，这里直接产世界坐标）
    val nx = -dirY
    val ny = dirX
    val timeBucket = floor(elapsed * spec.jagFlickerHz.coerceAtLeast(0.1f))
    val wavelength = spec.jagWavelength.coerceAtLeast(1f)

    val positions = (0 until count).map { i ->
        val t = i.toFloat() / (count - 1)
        val dist = t * length
        // 端点钉死、中段放开的包络
        val envelope = sin(Math.PI.toFloat() * t)
        val scaled = dist / wavelength
        val bucket = floor(scaled)
        val frac = (scaled - bucket).let { it * it * (3f - 2f * it) }
        val noise = arcBucketNoise(bucket, timeBucket) +
            (arcBucketNoise(bucket + 1f, timeBucket) - arcBucketNoise(bucket, timeBucket)) * frac
        val offset = spec.jagAmplitude * envelope * noise
        Vector2f(
            start.x + dirX * dist + nx * offset,
            start.y + dirY * dist + ny * offset,
        )
    }

    val alphaMul = intensity * arcAlphaFlicker(timeBucket, spec.alphaFlicker)
    return (0 until count).map { i ->
        val t = i.toFloat() / (count - 1)
        // 节点角：取与下一折点（首末点取唯一邻点）的连线方向
        val from = positions[maxOf(0, i - 1)]
        val to = positions[minOf(count - 1, i + 1)]
        val raw = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
        val angle = ((raw % 360f) + 360f) % 360f
        val color = lerpArcColor(spec.tailColor, spec.headColor, t).scaledAlpha(alphaMul)
        TexTrailNode(positions[i], angle, spec.width.coerceAtLeast(0.1f), color)
    }
}

/** 透明度闪烁系数（纯函数）：[amount] ≤ 0 恒 1；否则在时间桶上取 [1-amount, 1] 伪随机值。 */
fun arcAlphaFlicker(timeBucket: Float, amount: Float): Float {
    if (amount <= 0f) return 1f
    val noise = arcBucketNoise(timeBucket, 761f) * 0.5f + 0.5f
    return 1f - amount * (1f - noise)
}

/** （弧长桶, 时间桶）→ [-1, +1] 稳定伪随机值（整数散列）。 */
private fun arcBucketNoise(bucket: Float, timeBucket: Float): Float {
    var bits = bucket.toRawBits() * -0x61c88647 + timeBucket.toRawBits() * 0x5bd1e995.toInt()
    bits = bits xor (bits ushr 16)
    bits *= -0x27d4eb2d
    bits = bits xor (bits ushr 13)
    return (bits and 0xFFFF) / 65535f * 2f - 1f
}

private fun lerpArcColor(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor = ASTDColor(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * 锚点电弧组件：见 [ArcTrailSpec] 头部文档。attach 时捕获固定锚点（此刻 frame.origin 即发射点侧），
 * 之后每帧以当前 frame.origin 为跟随端重建折线顶点流。
 */
class ArcTrailComponent(
    id: String,
    internal val spec: ArcTrailSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_BASE + spec.layer) {

    private val log = Global.getLogger(ArcTrailComponent::class.java)
    private var handle: TexTrailRenderer.Handle? = null
    /** 固定锚点（世界坐标，attach 时捕获的发射点侧位置）。 */
    private var anchor: Vector2f? = null
    private var fadeStartElapsed = -1f
    private var fadeSeconds = spec.fadeOutCapSeconds

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD arc trail texture unreadable: id=$id path=${spec.texturePath}", e)
            return false
        }
        val created = TexTrailRenderer.createHandle(engine)
        if (created == null) {
            log.warn("ASTD arc trail createHandle failed: id=$id")
            return false
        }
        handle = created
        anchor = Vector2f(ctx.frame.origin)
        syncVertices(ctx)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (handle?.deleted == true) {
            handle = null
            return
        }
        syncVertices(ctx)
    }

    private fun syncVertices(ctx: RenderContext) {
        val current = handle ?: return
        val start = anchor ?: return
        val frame = ctx.frame
        if (fadeStartElapsed < 0f && frame.phase == cn.kasuminova.astd.api.render.RenderPhase.FadingOut) {
            fadeStartElapsed = frame.elapsed
        }
        val fadeMul = if (fadeStartElapsed < 0f) 1f
        else (1f - (frame.elapsed - fadeStartElapsed) / fadeSeconds).coerceIn(0f, 1f)
        val nodes = arcTrailNodes(start, frame.origin, spec, frame.logicElapsed, frame.intensity * fadeMul)
        val scroll = if (spec.scrollSpeed == 0f) 0f else frame.logicElapsed * spec.scrollSpeed / spec.tileLength
        // 节点已是世界坐标：origin 取零向量、facing 取 0，变换退化为恒等
        val vertices = texTrailStrip(nodes, ZERO, 0f, spec.tileLength, scroll)
        current.update(renderOrder, spec.texturePath, vertices)
    }

    /** 消亡后电弧快速淡出（窗口 capped），不跟随冻结帧的带头前飞拉长。 */
    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeSeconds = seconds.coerceAtMost(spec.fadeOutCapSeconds).coerceAtLeast(0.03f)
    }

    override fun onDetachSelf() {
        handle?.delete()
        handle = null
    }

    private companion object {
        private val ZERO = Vector2f(0f, 0f)
    }
}
