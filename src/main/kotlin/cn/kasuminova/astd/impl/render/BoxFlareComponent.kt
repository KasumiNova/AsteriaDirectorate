package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx.addEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * BoxUtil 光斑（泛用组件，首发：贯星之矛）：在弹体视觉头部挂一枚 FlareEntity，
 * 每帧跟随 [RenderContext] 的 frame.origin / facing。
 *
 * 观感语义：横向 lens-flare 式盘状光斑（smoothDisc：沿朝向铺开、横向薄），core/fringe 双色，
 * 内置闪烁（宽度脉动 + 明灭同步，见 BoxUtil BUtil_FlareShader），emissive 通道吃 bloom。
 * 弹体消亡后按树 fade 窗口降 globalAlpha 淡出，detach 时删除实体。
 *
 * 本组件只做「配置 + 跟随 + 淡出」；绘制完全由 BoxUtil 渲染管线承担。
 */
data class BoxFlareSpec(
    /** 光斑全尺寸（世界单位）：width 沿朝向、height 横向；width >> height 即水平光条。 */
    val width: Float,
    val height: Float,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
    /** 边缘色（签名色光晕）。 */
    val fringeColor: ASTDColor,
    /** emissive 输出倍率（0..1+，bloom 强度）。 */
    val glowPower: Float = 1f,
    /** 盘厚参数（BoxUtil discRatio，越大越薄）。 */
    val discRatio: Float = 4f,
    /** 闪烁速度倍率（1 = BoxUtil 默认）。 */
    val flickerRate: Float = 1.2f,
    /** 边缘 fbm 噪点强度（0 = 关闭）。 */
    val noisePower: Float = 0.1f,
    /**
     * 局部 x 偏移（世界单位，负 = 向尾）：拖尾锚点前移到弹体头部（headLead）后，
     * 传 -headLead 把光斑锚回弹体中心。
     */
    val offsetX: Float = 0f,
    /** BoxUtil 实体渲染层。 */
    val boxLayer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
)

/**
 * BoxUtil 光斑组件：见 [BoxFlareSpec] 头部文档。
 *
 * BoxUtil 未就绪/建实体失败时 WARN 一次并禁用自身（不重试风暴），弹体其余特效层不受影响。
 */
class BoxFlareComponent(
    id: String,
    internal val spec: BoxFlareSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_FLARE) {

    private val log = Global.getLogger(BoxFlareComponent::class.java)
    private var flare: FlareEntity? = null
    private var disabled = false
    private var fadeStartElapsed = -1f
    private var fadeSeconds = 0.15f

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val entity = FlareEntity()
        entity.setLayer(spec.boxLayer)
        entity.setAdditiveBlend()
        entity.setSmoothDisc()
        entity.setDiscRatio(spec.discRatio)
        entity.setSize(spec.width, spec.height)
        entity.autoAspect()
        entity.setFlick(true)
        entity.setFlickerAnimationRateMulti(spec.flickerRate)
        entity.setGlowPower(spec.glowPower)
        entity.setNoisePower(spec.noisePower)
        // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
        entity.setCoreColor(spec.coreColor.toAwt())
        entity.setFringeColor(spec.fringeColor.toAwt())
        entity.setGlobalTimer(0.05f, FLARE_FULL_SECONDS, 0.1f)
        BoxUtilCombatVfx.ensureReady(engine)
        val state = addEntity(engine, BoxEnum.ENTITY_FLARE, entity)
        if (state != 0) {
            log.warn("ASTD box flare 注册失败（addEntity 返回 $state）：id=$id，本弹体光斑层缺失，其余特效层照常")
            entity.delete()
            disabled = true
            return true
        }
        flare = entity
        syncFlare(ctx)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (disabled) return
        val entity = flare ?: return
        if (entity.hasDelete()) {
            flare = null
            return
        }
        syncFlare(ctx)
    }

    private fun syncFlare(ctx: RenderContext) {
        val entity = flare ?: return
        val frame = ctx.frame
        if (fadeStartElapsed < 0f && frame.phase == cn.kasuminova.astd.api.render.RenderPhase.FadingOut) {
            fadeStartElapsed = frame.elapsed
        }
        val fadeMul = if (fadeStartElapsed < 0f) 1f
        else (1f - (frame.elapsed - fadeStartElapsed) / fadeSeconds).coerceIn(0f, 1f)
        // 锚点 + 局部 x 偏移（沿朝向；负值锚回弹体中心）
        val rad = Math.toRadians(frame.facing.toDouble())
        val pos = Vector2f(
            frame.origin.x + (cos(rad) * spec.offsetX).toFloat(),
            frame.origin.y + (sin(rad) * spec.offsetX).toFloat(),
        )
        entity.setStateVanilla(pos, frame.facing)
        entity.setGlobalAlpha(frame.intensity * fadeMul)
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeSeconds = seconds.coerceAtLeast(0.05f)
    }

    override fun onDetachSelf() {
        flare?.delete()
        flare = null
    }

    companion object {
        /** 贴图拖尾绘制序基线之上、弹头之下的层位（与 texTrail 叠层同族）。 */
        const val RENDER_ORDER_FLARE = TexTrailComponent.RENDER_ORDER_BASE + 8

        /** 光斑常驻时长（秒）：生命周期由树 fade/detach 接管，这里给一个永不自然到期的值。 */
        private const val FLARE_FULL_SECONDS = 1e7f
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255）。 */
private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
