package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** 贴图拖尾逐节点数据（弹头局部系：x 头=0、尾=-length，y 侧向；angle 为该点带走向，纯数据，可测）。 */
data class TexTrailNode(
    val position: Vector2f,
    val angle: Float,
    val width: Float,
    val color: ASTDColor,
    /** 节点年龄（秒）：生成该处路径的历史点时间戳距当前时间。 */
    val age: Float = 0f,
    /** 年龄/寿命（0..1+，可超 1）；寿命 ≤0（宿主不提供）时恒 0，不做年龄衰减。 */
    val lifeProgress: Float = 0f,
    /** 平面内扭转角（度）：段随机初相沿路径插值 + 年龄累积；[texTrailStrip] 据此旋转横截偏移。 */
    val twistDeg: Float = 0f,
)

/**
 * 由历史中线生成贴图拖尾节点（纯函数，供 [TexTrailComponent] 每帧调用）。
 *
 * 世界系历史点折进弹头局部系后，沿带长均匀重采样 [nodeCount] 个点（默认 [TexTrailSpec.nodeCount]，
 * 调用方按实际带长动态细分——见 [dynamicTexTrailNodeCount]），
 * 颜色头→尾渐变；逐节点按年龄（[now] + [timeOffset] − 出生时刻，
 * 出生时刻沿历史段时间戳插值；[timeOffset] 为消亡后的加速偏移）算消散包络：寿命（[lifetimeSeconds]，
 * ≤0 时不衰减）内 [TexTrailSpec.dissolveStart] 之前满亮满宽、之后 alpha 线性降到 0、宽度按
 * [ageWidthEnvelope] 同步收细（越接近寿命末期越细，死亡时不再是一根等宽带子凭空淡出），
 * 整体再乘 [intensity]。
 * 历史不足 2 点时退化为直梁（年龄 0）。
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
    now: Float = 0f,
    lifetimeSeconds: Float = 0f,
    nodeCount: Int = spec.nodeCount,
    timeOffset: Float = 0f,
): List<TexTrailNode> {
    val safeLength = length.coerceAtLeast(1f)
    val localPath = toLocalPath(historyNodes, origin, facing, safeLength, now)
    val count = nodeCount.coerceAtLeast(2)
    val twistWavelength = spec.effectiveTwistWavelength()
    val clock = now + timeOffset

    return (0 until count).map { index ->
        val t = if (count > 1) index.toFloat() / (count - 1) else 0f
        val distanceFromHead = t * safeLength
        val sample = sampleLocalPath(localPath, distanceFromHead)
        val age = (clock - sample.birthElapsed).coerceAtLeast(0f)
        val lifeProgress = if (lifetimeSeconds > 0f) age / lifetimeSeconds else 0f
        val envelope = ageAlphaEnvelope(lifeProgress, spec.dissolveStart)
        val color = gradientColor(spec, t).scaledAlpha(intensity * envelope)
        val width = (spec.width * ageWidthEnvelope(lifeProgress, spec.dissolveStart)).coerceAtLeast(0.1f)
        // 扭转角 = 弧长平滑噪声（带体系跨帧稳定，前后段自动衔接）+ 年龄累积
        val twistDeg = segmentTwistBase(distanceFromHead, twistWavelength, spec.twistMaxAngleDeg) +
            age * spec.twistTurnDegPerSec
        TexTrailNode(
            wobbleOffset(sample.position, sample.angle, distanceFromHead, t, spec, wobbleAdvance),
            sample.angle, width, color, age, lifeProgress, twistDeg,
        )
    }
}

/**
 * 年龄消散包络（纯函数，可测）：[lifeProgress] ≤ [dissolveStart] 满亮（1），之后线性降到 1.0 处的 0。
 * 弹体消亡后不再产新段，各节点按自身年龄老去——尾部（最老）先消散、头部最后消失，取代旧的全局 fade alpha。
 */
fun ageAlphaEnvelope(lifeProgress: Float, dissolveStart: Float): Float {
    if (lifeProgress <= 0f) return 1f
    val start = dissolveStart.coerceIn(0f, 0.999f)
    if (lifeProgress <= start) return 1f
    return (1f - (lifeProgress - start) / (1f - start)).coerceIn(0f, 1f)
}

/** 收细下限：寿命末期宽度比例不低于本值（完全归零在 alpha 已透明后无意义，且避免零宽退化三角）。 */
private const val WIDTH_ENVELOPE_FLOOR = 0.15f

/**
 * 年龄收细包络（纯函数，可测）：[lifeProgress] ≤ [dissolveStart] 满宽（1），之后随 [ageAlphaEnvelope]
 * 同步线性收细到寿命末期的 [WIDTH_ENVELOPE_FLOOR]——段越接近寿命末期越细，消散期带体呈逐渐收窄的观感，
 * 而非等宽带子整体淡出。
 */
fun ageWidthEnvelope(lifeProgress: Float, dissolveStart: Float): Float {
    val alpha = ageAlphaEnvelope(lifeProgress, dissolveStart)
    return WIDTH_ENVELOPE_FLOOR + (1f - WIDTH_ENVELOPE_FLOOR) * alpha
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

/** 局部系路径点：位置 + 出生时刻（历史点时间戳），供逐节点年龄计算。 */
private data class LocalPathNode(val position: Vector2f, val birthElapsed: Float)

/** [sampleLocalPath] 的采样结果：局部系位置 + 带走向角（度）+ 出生时刻。 */
private data class PathSample(val position: Vector2f, val angle: Float, val birthElapsed: Float)

/**
 * 段随机扭转（纯函数，可测）：以距头弧长为坐标的一维平滑值噪声 → [-maxAngleDeg, +maxAngleDeg]。
 * 种子挂弧长桶（带体系坐标）而非历史点时间戳：带体系跨帧稳定，带体伸缩/物质向尾流动时图案不闪不爬；
 * 相邻桶值间 smoothstep 过渡，前后段自动衔接无折点。[wavelength] 为噪声空间波长（世界单位）。
 */
fun segmentTwistBase(arcDistance: Float, wavelength: Float, maxAngleDeg: Float): Float {
    if (maxAngleDeg <= 0f) return 0f
    val scaled = arcDistance / wavelength.coerceAtLeast(1f)
    val bucket = floor(scaled)
    val frac = (scaled - bucket).let { it * it * (3f - 2f * it) }   // smoothstep：桶界处导数为 0，衔接无折点
    val a = twistBucketNoise(bucket)
    val b = twistBucketNoise(bucket + 1f)
    return (a + (b - a) * frac) * maxAngleDeg
}

/** 弧长桶 → [-1, +1] 稳定伪随机值（整数散列）。 */
private fun twistBucketNoise(bucket: Float): Float {
    var bits = bucket.toRawBits()
    bits *= -0x61c88647
    bits = bits xor (bits ushr 16)
    return (bits and 0xFFFF) / 65535f * 2f - 1f
}

/** 世界系历史点 → 弹头局部系路径（头在原点、尾在 -x），按从头开始的累计弧长排列，保留出生时刻。 */
private fun toLocalPath(
    historyNodes: List<ASTDProjectileHistoryNode>,
    origin: Vector2f,
    facing: Float,
    length: Float,
    now: Float,
): List<LocalPathNode> {
    if (historyNodes.size < 2) return listOf(
        LocalPathNode(Vector2f(0f, 0f), now),
        LocalPathNode(Vector2f(-length, 0f), now),
    )
    val radians = Math.toRadians(facing.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    // 历史节点按时间升序（尾→头），反转为头→尾并折进局部系（前向为 +x，故头部投影接近 0、尾部为负）
    return historyNodes.asReversed().map { node ->
        val dx = node.location.x - origin.x
        val dy = node.location.y - origin.y
        LocalPathNode(
            Vector2f(dx * c + dy * s, -dx * s + dy * c),
            node.elapsed,
        )
    }
}

/**
 * 沿局部系路径按「距头弧长」取点：位置 + 带走向角（度，归一化到 [0,360)）+ 出生时刻（沿段插值）。
 * 超出尾端沿末段方向延长，出生时刻按末段的「时间/距离」速率外推（越延长越老）。
 */
private fun sampleLocalPath(path: List<LocalPathNode>, distanceFromHead: Float): PathSample {
    if (path.size < 2) {
        val only = path.firstOrNull()
        return PathSample(Vector2f(-distanceFromHead, 0f), 180f, only?.birthElapsed ?: 0f)
    }
    var remaining = distanceFromHead
    var lastAngle = 180f
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val segX = b.position.x - a.position.x
        val segY = b.position.y - a.position.y
        val segLength = sqrt(segX * segX + segY * segY)
        if (segLength <= 0.0001f) continue
        // atan2 对 -0.0 会返回 -180：归一化，同一走向不挑符号
        val raw = Math.toDegrees(kotlin.math.atan2(segY.toDouble(), segX.toDouble())).toFloat()
        lastAngle = if (raw < 0f) raw + 360f else raw
        if (remaining <= segLength) {
            val ratio = remaining / segLength
            return PathSample(
                Vector2f(a.position.x + segX * ratio, a.position.y + segY * ratio),
                lastAngle,
                a.birthElapsed + (b.birthElapsed - a.birthElapsed) * ratio,
            )
        }
        remaining -= segLength
    }
    // 超出尾端：沿末段方向继续延长
    val last = path[path.size - 1]
    val prev = path[path.size - 2]
    val dirX = last.position.x - prev.position.x
    val dirY = last.position.y - prev.position.y
    val dirLength = sqrt(dirX * dirX + dirY * dirY)
    if (dirLength <= 0.0001f) return PathSample(Vector2f(last.position), lastAngle, last.birthElapsed)
    // 末段「时间/距离」速率（头→尾方向 elapsed 递减）；零速率时沿用尾端时刻
    val elapsedRate = (prev.birthElapsed - last.birthElapsed) / dirLength
    val birthElapsed = last.birthElapsed - remaining * elapsedRate
    return PathSample(
        Vector2f(last.position.x + dirX / dirLength * remaining, last.position.y + dirY / dirLength * remaining),
        lastAngle,
        birthElapsed,
    )
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
        // 带走向法向 n 与切向 t；平面内扭转 φ：横截偏移 = n·cosφ + t·sinφ（φ=0 退化为纯法向，逐点等同旧行为）
        val nx = -sin(theta).toFloat()
        val ny = cos(theta).toFloat()
        val tx = cos(theta).toFloat()
        val ty = sin(theta).toFloat()
        val twist = Math.toRadians(node.twistDeg.toDouble())
        val twCos = cos(twist).toFloat()
        val twSin = sin(twist).toFloat()
        val offX = nx * twCos + tx * twSin
        val offY = ny * twCos + ty * twSin
        val half = (node.width / 2f).coerceAtLeast(0.05f)
        val u = arc / safeTile - scroll
        val r = node.color.red.coerceIn(0f, 1f)
        val g = node.color.green.coerceIn(0f, 1f)
        val b = node.color.blue.coerceIn(0f, 1f)
        val a = node.color.alpha.coerceIn(0f, 1f)
        // 上沿顶点（v=+1）
        out[cursor++] = wx + offX * half
        out[cursor++] = wy + offY * half
        out[cursor++] = u
        out[cursor++] = 1f
        out[cursor++] = r
        out[cursor++] = g
        out[cursor++] = b
        out[cursor++] = a
        // 下沿顶点（v=-1）
        out[cursor++] = wx - offX * half
        out[cursor++] = wy - offY * half
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
 * 渲染节点数按实际带长动态细分（纯函数，可测）：目标段长约 [SEGMENT_LENGTH_TARGET] 世界单位，
 * 下限 [baseCount]（spec 声明值）、上限 [MAX_DYNAMIC_NODE_COUNT]（顶点数护栏）。
 * 动机：带长上限 ×4 后 24 节点的定值采样在导弹急转时折角明显（段长可达 80+ su），按带长细分后
 * 段长稳定在二十余 su，弯道平滑；短带（速射炮走廊）仍取下限不增负。
 */
fun dynamicTexTrailNodeCount(visibleLength: Float, baseCount: Int): Int {
    val byLength = kotlin.math.ceil(visibleLength.coerceAtLeast(0f) / SEGMENT_LENGTH_TARGET).toInt()
    return byLength.coerceIn(baseCount.coerceAtLeast(2), MAX_DYNAMIC_NODE_COUNT)
}

/** 动态细分目标段长（世界单位）：与导弹典型转弯半径（50~100 su）匹配，折角不可辨。 */
private const val SEGMENT_LENGTH_TARGET = 22f

/** 动态细分节点数上限：3 层 × 2 顶点 × 节点数的顶点流护栏。 */
private const val MAX_DYNAMIC_NODE_COUNT = 72

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
        // 逐节点寿命：spec 显式覆写优先，否则取驱动按「预期带长/实测速度」的估算值
        val lifetime = if (spec.lifetimeSeconds > 0f) spec.lifetimeSeconds else frame.trailLifetimeSeconds
        val nodes = texTrailNodes(
            frame.historyNodes, frame.origin, frame.facing, frame.length,
            spec, frame.intensity, wobbleAdvance, frame.elapsed, lifetime,
            nodeCount = dynamicTexTrailNodeCount(frame.length, spec.nodeCount),
            timeOffset = frame.trailTimeOffsetSeconds,
        )
        val scroll = if (spec.scrollSpeed == 0f) 0f else frame.logicElapsed * spec.scrollSpeed / spec.tileLength
        val vertices = texTrailStrip(nodes, frame.origin, frame.facing, spec.tileLength, scroll, spec.recede)
        current.update(renderOrder, spec.texturePath, vertices)
    }

    /**
     * 拖尾不响应全局淡出：弹体消亡/命中后不再整带降 alpha，各节点按自身年龄老去
     * （尾先消、头后消；消亡后驱动经 [FrameState.trailTimeOffsetSeconds] 加速老化，
     * 整带在死亡消散窗口内收完，见 [texTrailNodes] 的年龄包络）；树传播保留给弹头等其他层。
     */
    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
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
