package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 锥面冲击特效的「连续扇形楔块」组件（计划 00-锥面冲击特效重做计划 §4.1）：
 * 一枚极坐标三角网格（底层 contrail / 图案层 surge 各一枚实例），走 [TexTrailRenderer] 管线。
 *
 * 职责（对齐 [TexTrailComponent] 先例）：
 * - onAttach：openStream 探测贴图可读 → [TexTrailRenderer.createHandle]；失败记 WARN 返回 false
 *   （本层缺席、其余层照常——故障隔离带日志，非静默兜底）；
 * - advance：调 [coneWedgeFan] 纯函数按帧重建顶点流推给句柄（包络/波前/滚动全部由帧时间驱动，无心跳）；
 * - beginFadeOut/onDetach：淡出乘进全局包络 / 删除句柄（幂等）。
 *
 * 几何常量（锥角/锥长/贴图/调色/角向抖动表）创建期定死，每帧只读 [RenderContext.frame] 的 elapsed。
 */
class ConeWedgeComponent(
    id: String,
    private val origin: Vector2f,
    private val facingDeg: Float,
    private val halfAngleDeg: Float,
    private val length: Float,
    private val duration: Float,
    private val expandSeconds: Float,
    private val fadeOutSeconds: Float,
    /** 平铺图案贴图路径（X=横向 CLAMP、Y=带长向 REPEAT，形在 alpha 通道）。 */
    private val texturePath: String,
    color: Color,
    /** 层整体 alpha 倍率（图案层 0.5，底层 1.0）。 */
    private val alphaMul: Float,
    /** 花纹径向外滚速度（su/s；u 相位 = t × 本值 / tileLength，tileLength = 锥长一张铺满）。 */
    private val scrollSpeed: Float,
    /** 角向 v 带下界/上界：与贴图 alpha 带实测配对（带外 alpha≈0 → 扇缘自然软边）。 */
    private val vLo: Float,
    private val vHi: Float,
    private val angularSegs: Int,
    /** 角向每列固定偏角（度，长度 = angularSegs + 1，spawn 时随机一次后不变）。 */
    private val angularJitter: FloatArray,
    renderOrder: Int,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, renderOrder) {

    private val log = Global.getLogger(ConeWedgeComponent::class.java)
    private val fade = ASTDProjectileVfxLayerFadeState()
    private var handle: TexTrailRenderer.Handle? = null

    private val colorRed = color.red / 255f
    private val colorGreen = color.green / 255f
    private val colorBlue = color.blue / 255f

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        // 校验贴图资源可读（实际解码上传由渲染插件自管，不进游戏贴图系统——见 TexTrailRenderer.trailTextures）
        try {
            Global.getSettings().openStream(texturePath).use { }
        } catch (e: Exception) {
            log.warn("锥面冲击特效楔块贴图不可读：id=$id path=$texturePath", e)
            return false
        }
        val created = TexTrailRenderer.createHandle(engine)
        if (created == null) {
            log.warn("锥面冲击特效楔块建句柄失败：id=$id")
            return false
        }
        handle = created
        pushVertices(ctx)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        if (handle?.deleted == true) {
            handle = null
            return
        }
        pushVertices(ctx)
    }

    /** 按帧时间重建顶点流推给渲染句柄：全局包络 × 淡出 × 层倍率 → 顶点 alpha；滚动相位随时间推进。 */
    private fun pushVertices(ctx: RenderContext) {
        val current = handle ?: return
        val t = ctx.frame.elapsed
        val envelope = wedgeEnvelope(t, expandSeconds, duration - fadeOutSeconds, duration) * fade.alpha() * alphaMul
        val frontR = wedgeFrontRadius(t, expandSeconds, length)
        val vertices = coneWedgeFan(
            origin = origin,
            facingDeg = facingDeg,
            halfAngleDeg = halfAngleDeg,
            length = length,
            radialSegs = RADIAL_SEGS,
            angularSegs = angularSegs,
            vLo = vLo,
            vHi = vHi,
            tileLength = length,
            scroll = t * scrollSpeed / length,
            frontR = frontR,
            envelopeAlpha = envelope,
            red = colorRed,
            green = colorGreen,
            blue = colorBlue,
            angularJitter = angularJitter,
        )
        current.update(renderOrder, texturePath, vertices, triangles = true)
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun onDetachSelf() {
        handle?.delete()
        handle = null
    }

    companion object {
        /** 径向分段数（网格分辨率；角向分段由 [wedgeAngularSegments] 按锥角推导）。 */
        const val RADIAL_SEGS = 6

        /** 楔块在 texTrail 管线层内的绘制序基线：压在各弹体拖尾（TexTrailComponent 361+）之下。 */
        const val RENDER_ORDER_BASE = 350
    }
}
