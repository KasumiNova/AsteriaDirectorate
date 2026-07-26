package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

/**
 * 曲线弹芯（`core`）的节点规格：DSL `curveCore{}` 的产物。
 *
 * 弹芯+辉光+外晕合并为一条 BoxUtil [CurveEntity] 曲线带：横向衰减由烘焙灰度包络贴图表达，
 * 纵向（头→尾）明暗/收束由逐节点颜色与宽度表达。可选第二条更宽更淡的晕带（halo）。
 */
data class CurveCoreSpec(
    /** 头部带全宽（世界单位）。 */
    val width: Float,
    /** 尾部宽度相对头部的比例 0..1。 */
    val tailWidthScale: Float,
    /** 头部（弹体当前位置端）颜色。 */
    val headColor: ASTDColor,
    /** 尾部颜色。 */
    val tailColor: ASTDColor,
    /** 曲线带节点数（沿长度均匀分布）。 */
    val nodeCount: Int = 12,
    /** 烘焙灰度包络贴图路径（横向衰减；RGB 白，着色由节点颜色承载）。 */
    val texturePath: String = "graphics/fx/astd_curve_core.png",
    /** 贴图像素密度（沿带长多少个世界单位平铺一次）。 */
    val texturePixels: Float = 96f,
    /** 贴图滚动速度（0 不滚动）。 */
    val textureSpeed: Float = 0f,
    /** 第二条晕带的宽度倍率（相对 width）；0 = 不画。 */
    val haloWidthScale: Float = 0f,
    /** 第二条晕带的 alpha 倍率。 */
    val haloAlphaScale: Float = 0f,
)

/** 曲线带逐节点数据（弹头局部系：x 头=0、尾=-length，y 侧向；纯数据，可测）。 */
data class CurveCoreNode(val position: Vector2f, val width: Float, val color: ASTDColor)

/**
 * 由历史中线生成曲线带节点（纯函数，供 [CurveCoreComponent] 每帧调用）。
 *
 * 世界系历史点折进弹头局部系后，沿带长均匀重采样 [CurveCoreSpec.nodeCount] 个点，
 * 宽度头→尾线性收、颜色头→尾插值，整体再乘 [intensity]。历史不足 2 点时退化为直梁。
 */
fun curveCoreNodes(
    historyNodes: List<ASTDProjectileHistoryNode>,
    origin: Vector2f,
    facing: Float,
    length: Float,
    spec: CurveCoreSpec,
    intensity: Float,
): List<CurveCoreNode> {
    val safeLength = length.coerceAtLeast(1f)
    val localPath = toLocalPath(historyNodes, origin, facing, safeLength)

    return (0 until spec.nodeCount).map { index ->
        val t = if (spec.nodeCount > 1) index.toFloat() / (spec.nodeCount - 1) else 0f
        val position = sampleLocalPath(localPath, t * safeLength)
        val color = lerpColor(spec.headColor, spec.tailColor, t).scaledAlpha(intensity)
        val width = spec.width * (1f + (spec.tailWidthScale - 1f) * t)
        CurveCoreNode(position, width.coerceAtLeast(0.1f), color)
    }
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

/** 沿局部系路径按「距头弧长」取点；超出路径尾端时沿末段方向延长，路径为空时退化为直梁。 */
private fun sampleLocalPath(path: List<Vector2f>, distanceFromHead: Float): Vector2f {
    if (path.size < 2) return Vector2f(-distanceFromHead, 0f)
    var remaining = distanceFromHead
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val segX = b.x - a.x
        val segY = b.y - a.y
        val segLength = kotlin.math.sqrt(segX * segX + segY * segY)
        if (segLength <= 0.0001f) continue
        if (remaining <= segLength) {
            val ratio = remaining / segLength
            return Vector2f(a.x + segX * ratio, a.y + segY * ratio)
        }
        remaining -= segLength
    }
    // 超出尾端：沿末段方向继续延长
    val last = path[path.size - 1]
    val prev = path[path.size - 2]
    val dirX = last.x - prev.x
    val dirY = last.y - prev.y
    val dirLength = kotlin.math.sqrt(dirX * dirX + dirY * dirY)
    if (dirLength <= 0.0001f) return Vector2f(last)
    return Vector2f(last.x + dirX / dirLength * remaining, last.y + dirY / dirLength * remaining)
}

private fun lerpColor(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor = ASTDColor(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * 曲线弹芯组件：弹芯+辉光+外晕合并为一条（或带 halo 两条）BoxUtil [CurveEntity] 曲线带。
 *
 * 节点为弹头局部系（头=原点、尾=-x），实体级 [CurveEntity.setStateVanilla] 承担世界变换；
 * 每帧从 [FrameState.historyNodes] 重采样中线刷新节点（追踪真实飞行路径，弯道跟随）。
 */
class CurveCoreComponent(
    id: String,
    private val spec: CurveCoreSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    /** 一条曲线带的后端句柄与其宽度/alpha 缩放。 */
    private class Strip(val entity: CurveEntity, val widthScale: Float, val alphaScale: Float)

    private val log = Global.getLogger(CurveCoreComponent::class.java)
    private val fade = ASTDProjectileVfxLayerFadeState()
    private val strips = ArrayList<Strip>()

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        BoxUtilCombatVfx.ensureReady(engine)
        if (!createStrip(engine, ctx.frame, 1f, 1f)) return false
        if (spec.haloWidthScale > 0f && spec.haloAlphaScale > 0f) {
            if (!createStrip(engine, ctx.frame, spec.haloWidthScale, spec.haloAlphaScale)) return false
        }
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        strips.removeAll { it.entity.hasDelete() }
        if (strips.isEmpty()) return
        val frame = ctx.frame
        val alpha = frame.intensity * fade.alpha()
        for (strip in strips) {
            val nodes = curveCoreNodes(frame.historyNodes, frame.origin, frame.facing, frame.length, spec, alpha * strip.alphaScale)
            val entity = strip.entity
            entity.setNodes(nodes.map { it.toNodeData(strip.widthScale) })
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()
            entity.setStateVanilla(frame.origin, frame.facing)
        }
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun onDetachSelf() {
        strips.forEach { it.entity.delete() }
        strips.clear()
    }

    private fun createStrip(engine: com.fs.starfarer.api.combat.CombatEngineAPI, frame: FrameState, widthScale: Float, alphaScale: Float): Boolean {
        val entity = CurveEntity()
        val nodes = curveCoreNodes(frame.historyNodes, frame.origin, frame.facing, frame.length, spec, frame.intensity * alphaScale)
        entity.setNodes(nodes.map { it.toNodeData(widthScale) })
        entity.submitNodes()
        entity.setLayer(CombatEngineLayers.ABOVE_PARTICLES)
        entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        entity.setTexturePixels(spec.texturePixels)
        entity.setTextureSpeed(spec.textureSpeed)
        entity.materialData.setDiffuse(Global.getSettings().getSprite(spec.texturePath))
        val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_CURVE, entity)
        if (state != 0) {
            entity.delete()
            log.warn("ASTD projectile VFX CurveCoreComponent addEntity failed: state=$state id=$id widthScale=$widthScale")
            return false
        }
        entity.setStateVanilla(frame.origin, frame.facing)
        strips += Strip(entity, widthScale, alphaScale)
        return true
    }

    private fun CurveCoreNode.toNodeData(widthScale: Float): NodeData {
        val data = NodeData(Vector2f(position), width * widthScale)
        data.setColor(color.red.coerceIn(0f, 1f), color.green.coerceIn(0f, 1f), color.blue.coerceIn(0f, 1f), color.alpha.coerceIn(0f, 1f))
        return data
    }

    companion object {
        /** 弹芯绘制序：雾团(50)之上、侧丝(240)之下（对齐旧 glow 层位）。 */
        const val RENDER_ORDER = 100
    }
}
