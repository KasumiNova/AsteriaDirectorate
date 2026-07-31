package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 贴图拖尾（DSL `texTrail{}`）的规格：拖尾主体层，横向图案来自平铺滚动贴图。
 *
 * 复刻 MagicTrail 语义：带体几何由 CPU 折线生成（历史中线等宽采样 + 逐节点渐变色），横向图案来自
 * 平铺滚动贴图（对标 astd_trails_* 素材：贴图 X=横向、Y=带长向，形在 alpha 通道，RGB 近白由节点色染色）。
 * 片元着色器只做「采样 × 顶点色」，图案质量完全由贴图保证。
 */
data class TexTrailSpec(
    /** 拖尾全宽（世界单位）。 */
    val width: Float,
    /** 平铺图案贴图路径（64×N，X=横向、Y=带长向，Y 向 REPEAT 平铺）。 */
    val texturePath: String,
    /** 叠层序号：同弹体多条贴图拖尾的绘制先后（1 垫底、2 其上，以此类推）。 */
    val layer: Int = 1,
    /** 头部颜色。 */
    val headColor: ASTDColor,
    /** 中段色（t=[midT] 处）；null 退化为两色插值。 */
    val midColor: ASTDColor? = null,
    /** 中段色在带长上的位置 0..1。 */
    val midT: Float = 0.25f,
    /** 尾部颜色。 */
    val tailColor: ASTDColor,
    /** 节点数（沿带长均匀分布，弯道平滑度）。 */
    val nodeCount: Int = 24,
    /** 图案沿带长的平铺周期（世界单位，即 MagicTrail 的 textureLoopLength）。 */
    val tileLength: Float = 180f,
    /** 图案沿带长滚动速度（世界单位/秒，0 不滚动；/tileLength 即每秒滚动整贴图次数）。 */
    val scrollSpeed: Float = 0f,
    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到弹头网格之后，让弹头尖在带体前清晰露出。 */
    val recede: Float = 0f,
    /** 横向扰动峰值振幅（世界单位，0 = 不扰动）：复刻 MagicTrail dispersion，正弦叠加横向漂移让带体散开摆动。 */
    val wobbleAmplitude: Float = 0f,
    /** 扰动主波长（带长向，世界单位）；第二分量取黄金比频率，两频率不可约、图案沿带长不自重复。 */
    val wobbleWavelength: Float = 90f,
    /** 扰动图案沿带长平移速度（世界单位/秒，0 静止；语义对齐 [scrollSpeed]，负值反向爬行）。 */
    val wobbleScroll: Float = 0f,
    /** 扰动初始相位（弧度）：错开同弹体多条叠层的扰动图案，避免两层同相摆动。 */
    val wobblePhase: Float = 0f,
)

/** 贴图拖尾逐节点数据（弹头局部系：x 头=0、尾=-length，y 侧向；angle 为该点带走向，纯数据，可测）。 */
data class TexTrailNode(val position: Vector2f, val angle: Float, val width: Float, val color: ASTDColor)

/**
 * 由历史中线生成贴图拖尾节点（纯函数，供 [TexTrailComponent] 每帧调用）。
 *
 * 世界系历史点折进弹头局部系后，沿带长均匀重采样 [TexTrailSpec.nodeCount] 个点，
 * 宽度恒为 [TexTrailSpec.width]、颜色头→尾渐变，整体再乘 [intensity]。历史不足 2 点时退化为直梁。
 *
 * [wobbleAdvance] 为横向扰动图案沿带长的平移相位（单位：主波长周期数，由调用方按逻辑时间推进，
 * 语义对齐 [texTrailStrip] 的 scroll）；扰动是「沿带长位置」的确定性函数，同参多次调用逐点一致（帧间不闪）。
 */
fun texTrailNodes(
    historyNodes: List<ASTDProjectileHistoryNode>,
    origin: Vector2f,
    facing: Float,
    length: Float,
    spec: TexTrailSpec,
    intensity: Float,
    wobbleAdvance: Float = 0f,
): List<TexTrailNode> {
    val safeLength = length.coerceAtLeast(1f)
    val localPath = toLocalPath(historyNodes, origin, facing, safeLength)

    return (0 until spec.nodeCount).map { index ->
        val t = if (spec.nodeCount > 1) index.toFloat() / (spec.nodeCount - 1) else 0f
        val (position, angle) = sampleLocalPath(localPath, t * safeLength)
        val color = gradientColor(spec, t).scaledAlpha(intensity)
        TexTrailNode(
            wobbleOffset(position, angle, t * safeLength, t, spec, wobbleAdvance),
            angle, spec.width.coerceAtLeast(0.1f), color,
        )
    }
}

/** 扰动第二分量频率比（黄金比）：与主分量不可约，叠加图案沿带长不自重复。 */
private const val WOBBLE_SECOND_FREQ = 1.618f

/** 扰动第二分量去相关相位（弧度）：避免两分量在头部附近同相叠加出规则节拍。 */
private const val WOBBLE_SECOND_PHASE = 1.7f

private val TWO_PI = (2.0 * Math.PI).toFloat()

/**
 * 横向扰动（复刻 MagicTrail dispersion）：两个不可约频率正弦加权叠加（权重和 1，横向漂移峰值不超
 * [TexTrailSpec.wobbleAmplitude]），沿垂直于节点带走向方向偏移。头部锚定（t=0 处扰动为 0，随 t 线性放开），
 * 弹头接缝不随扰动撕开。[wobbleAdvance] 以主波长周期数计，同一相位多次调用结果逐点一致。
 */
private fun wobbleOffset(
    position: Vector2f,
    angle: Float,
    distanceFromHead: Float,
    t: Float,
    spec: TexTrailSpec,
    wobbleAdvance: Float,
): Vector2f {
    if (spec.wobbleAmplitude <= 0f) return position
    val cycles = distanceFromHead / spec.wobbleWavelength.coerceAtLeast(1f) - wobbleAdvance
    val offset = spec.wobbleAmplitude * t * (
        0.6f * sin(TWO_PI * cycles + spec.wobblePhase) +
            0.4f * sin(TWO_PI * cycles * WOBBLE_SECOND_FREQ + spec.wobblePhase + WOBBLE_SECOND_PHASE)
        )
    // 带走向的法向（与 texTrailStrip 展开顶点的法向同一约定）
    val radians = Math.toRadians(angle.toDouble())
    return Vector2f(
        position.x - sin(radians).toFloat() * offset,
        position.y + cos(radians).toFloat() * offset,
    )
}

/** 三段渐变：head(t=0) → mid(t=midT) → tail(t=1)；无 mid 时两色线性。 */
private fun gradientColor(spec: TexTrailSpec, t: Float): ASTDColor {
    val mid = spec.midColor ?: return lerpColor(spec.headColor, spec.tailColor, t)
    val midT = spec.midT.coerceIn(0.001f, 0.999f)
    return if (t <= midT) lerpColor(spec.headColor, mid, t / midT)
    else lerpColor(mid, spec.tailColor, (t - midT) / (1f - midT))
}

/** 世界系历史点 → 弹头局部系路径（头在原点、尾在 -x），按从头开始的累计弧长排列。 */
private fun toLocalPath(
    historyNodes: List<ASTDProjectileHistoryNode>,
    origin: Vector2f,
    facing: Float,
    length: Float,
): List<Vector2f> {
    if (historyNodes.size < 2) return listOf(Vector2f(0f, 0f), Vector2f(-length, 0f))
    val radians = Math.toRadians(facing.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    // 历史节点按时间升序（尾→头），反转为头→尾并折进局部系（前向为 +x，故头部投影接近 0、尾部为负）
    return historyNodes.asReversed().map { node ->
        val dx = node.location.x - origin.x
        val dy = node.location.y - origin.y
        Vector2f(dx * c + dy * s, -dx * s + dy * c)
    }
}

/** 沿局部系路径按「距头弧长」取点（含该点带走向角度，度，归一化到 [0,360)）；超出尾端沿末段方向延长。 */
private fun sampleLocalPath(path: List<Vector2f>, distanceFromHead: Float): Pair<Vector2f, Float> {
    if (path.size < 2) return Vector2f(-distanceFromHead, 0f) to 180f
    var remaining = distanceFromHead
    var lastAngle = 180f
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val segX = b.x - a.x
        val segY = b.y - a.y
        val segLength = sqrt(segX * segX + segY * segY)
        if (segLength <= 0.0001f) continue
        // atan2 对 -0.0 会返回 -180：归一化，同一走向不挑符号
        val raw = Math.toDegrees(kotlin.math.atan2(segY.toDouble(), segX.toDouble())).toFloat()
        lastAngle = if (raw < 0f) raw + 360f else raw
        if (remaining <= segLength) {
            val ratio = remaining / segLength
            return Vector2f(a.x + segX * ratio, a.y + segY * ratio) to lastAngle
        }
        remaining -= segLength
    }
    // 超出尾端：沿末段方向继续延长
    val last = path[path.size - 1]
    val prev = path[path.size - 2]
    val dirX = last.x - prev.x
    val dirY = last.y - prev.y
    val dirLength = sqrt(dirX * dirX + dirY * dirY)
    if (dirLength <= 0.0001f) return Vector2f(last) to lastAngle
    return Vector2f(last.x + dirX / dirLength * remaining, last.y + dirY / dirLength * remaining) to lastAngle
}

private fun lerpColor(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor = ASTDColor(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)

/** 单个顶点占用的浮点数：x, y, u, v, r, g, b, a。 */
const val TEX_TRAIL_VERTEX_FLOATS = 8

/**
 * 把网格渲染器产出的局部系三角形网格（弹头/阴影）烘成世界系 8 浮点顶点流（纯函数，可测）。
 *
 * 供弹头并入 bloom 管线使用：u=v=0（渲染侧绑 1×1 白贴图，片元着色器退化为纯顶点色）。
 * 世界变换（旋转+平移）与 [texTrailStrip] 的烘焙语义一致。
 */
fun texTrailMeshTriangles(
    mesh: ASTDProjectileVfxBodyRenderer.Mesh,
    origin: Vector2f,
    facing: Float,
): FloatArray {
    val radians = Math.toRadians(facing.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val out = FloatArray(mesh.triangles.size * 3 * TEX_TRAIL_VERTEX_FLOATS)
    var cursor = 0
    for (triangle in mesh.triangles) {
        for (vertex in listOf(triangle.a, triangle.b, triangle.c)) {
            out[cursor++] = origin.x + vertex.position.x * c - vertex.position.y * s
            out[cursor++] = origin.y + vertex.position.x * s + vertex.position.y * c
            out[cursor++] = 0f
            out[cursor++] = 0f
            out[cursor++] = vertex.color.red.coerceIn(0f, 1f)
            out[cursor++] = vertex.color.green.coerceIn(0f, 1f)
            out[cursor++] = vertex.color.blue.coerceIn(0f, 1f)
            out[cursor++] = vertex.color.alpha.coerceIn(0f, 1f)
        }
    }
    return out
}

/**
 * 把局部系拖尾节点烘成世界系三角条带顶点流（纯函数，可测）。
 *
 * 每节点展开为上下两个顶点（v=+1/-1，法向取走向角垂直方向），u 为沿带长累计弧长/[tileLength]
 * 减去 [scroll]（滚动相位，由调用方按时间推进）。布局见 [TEX_TRAIL_VERTEX_FLOATS]。
 */
fun texTrailStrip(
    nodes: List<TexTrailNode>,
    origin: Vector2f,
    facing: Float,
    tileLength: Float,
    scroll: Float,
    recede: Float = 0f,
): FloatArray {
    if (nodes.isEmpty()) return FloatArray(0)
    val safeTile = tileLength.coerceAtLeast(1f)
    val radians = Math.toRadians(facing.toDouble())
    val rc = cos(radians).toFloat()
    val rs = sin(radians).toFloat()
    val out = FloatArray(nodes.size * 2 * TEX_TRAIL_VERTEX_FLOATS)
    var arc = 0f
    var cursor = 0
    var prev: Vector2f? = null
    for (node in nodes) {
        val local = node.position
        if (prev != null) {
            val dx = local.x - prev.x
            val dy = local.y - prev.y
            arc += sqrt(dx * dx + dy * dy)
        }
        prev = local
        // 局部 → 世界（先旋转后平移）；recede 沿局部 -x（带长向后）平移整条带
        val wx = origin.x + (local.x - recede) * rc - local.y * rs
        val wy = origin.y + (local.x - recede) * rs + local.y * rc
        val theta = Math.toRadians((node.angle + facing).toDouble())
        val nx = -sin(theta).toFloat()
        val ny = cos(theta).toFloat()
        val half = (node.width / 2f).coerceAtLeast(0.05f)
        val u = arc / safeTile - scroll
        val r = node.color.red.coerceIn(0f, 1f)
        val g = node.color.green.coerceIn(0f, 1f)
        val b = node.color.blue.coerceIn(0f, 1f)
        val a = node.color.alpha.coerceIn(0f, 1f)
        // 上沿顶点（v=+1）
        out[cursor++] = wx + nx * half
        out[cursor++] = wy + ny * half
        out[cursor++] = u
        out[cursor++] = 1f
        out[cursor++] = r
        out[cursor++] = g
        out[cursor++] = b
        out[cursor++] = a
        // 下沿顶点（v=-1）
        out[cursor++] = wx - nx * half
        out[cursor++] = wy - ny * half
        out[cursor++] = u
        out[cursor++] = -1f
        out[cursor++] = r
        out[cursor++] = g
        out[cursor++] = b
        out[cursor++] = a
    }
    return out
}

/**
 * 贴图拖尾组件：CPU 折线带体 + 平铺滚动贴图图案的拖尾主体层（复刻 MagicTrail）。
 *
 * 每帧重采样中线生成节点，烘成世界系三角条带顶点流写入 [TexTrailRenderer] 句柄；
 * 实际绘制由渲染插件在战斗渲染线程执行（贴图采样 × 顶点色，additive）。
 */
class TexTrailComponent(
    id: String,
    internal val spec: TexTrailSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_BASE + spec.layer) {

    private val log = Global.getLogger(TexTrailComponent::class.java)
    private val fade = ASTDProjectileVfxLayerFadeState()
    private var handle: TexTrailRenderer.Handle? = null

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        // 校验贴图资源可读（实际解码上传由渲染插件自管，不进游戏贴图系统——见 TexTrailRenderer.trailTextures）
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD projectile VFX TexTrailComponent texture unreadable: id=$id path=${spec.texturePath}", e)
            return false
        }
        val created = TexTrailRenderer.createHandle(engine)
        if (created == null) {
            log.warn("ASTD projectile VFX TexTrailComponent createHandle failed: id=$id")
            return false
        }
        handle = created
        syncVertices(ctx)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        if (handle?.deleted == true) {
            handle = null
            return
        }
        syncVertices(ctx)
    }

    private fun syncVertices(ctx: RenderContext) {
        val current = handle ?: return
        val frame = ctx.frame
        // 扰动图案平移相位（主波长周期数）：与贴图 scroll 同一推进语义，逻辑时间驱动、暂停即静止
        val wobbleAdvance = if (spec.wobbleAmplitude <= 0f || spec.wobbleScroll == 0f) 0f
        else frame.logicElapsed * spec.wobbleScroll / spec.wobbleWavelength
        val nodes = texTrailNodes(
            frame.historyNodes, frame.origin, frame.facing, frame.length,
            spec, frame.intensity * fade.alpha(), wobbleAdvance,
        )
        val scroll = if (spec.scrollSpeed == 0f) 0f else frame.logicElapsed * spec.scrollSpeed / spec.tileLength
        val vertices = texTrailStrip(nodes, frame.origin, frame.facing, spec.tileLength, scroll, spec.recede)
        current.update(renderOrder, spec.texturePath, vertices)
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun onDetachSelf() {
        handle?.delete()
        handle = null
    }

    companion object {
        /** 贴图拖尾绘制序基线：对齐旧 ribbon 层位（弹头之上）；实际绘制序 = 基线 + [TexTrailSpec.layer]。 */
        const val RENDER_ORDER_BASE = 360
    }
}
