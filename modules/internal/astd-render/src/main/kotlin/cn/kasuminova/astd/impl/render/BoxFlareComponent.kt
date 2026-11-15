package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

internal fun FlareEntity.applyFlareStyle(style: BoxFlareStyle) {
    when (style) {
        BoxFlareStyle.SMOOTH -> setSmooth()
        BoxFlareStyle.SHARP -> setSharp()
        BoxFlareStyle.SMOOTH_DISC -> setSmoothDisc()
        BoxFlareStyle.SHARP_DISC -> setSharpDisc()
    }
}

class BoxFlareComponent(
    id: String,
    internal val spec: BoxFlareSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_FLARE) {

    private var lease: FlareLease? = null
    private var disabled = false
    private var fadeStartElapsed = -1f
    private var fadeSeconds = 0.15f
    private var visualScale = 1f

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        visualScale = ProjectileVisualScale.read(ctx.host) ?: 1f
        val entity = FlareEntityPool.checkout(engine, spec.boxLayer)
        if (entity == null) {
            disabled = true
            return true
        }
        applyLeaseConfig(entity)
        lease = FlareLease(engine, entity)
        syncFlare(ctx)
        return true
    }

    /** 租约复用时实体带着上一次的形态，必须整体复位后按本弹 spec 重配。 */
    private fun applyLeaseConfig(entity: FlareEntity) {
        entity.reset()
        entity.setLayer(spec.boxLayer)
        entity.setAdditiveBlend()
        entity.applyFlareStyle(spec.style)
        entity.discRatio = spec.discRatio
        entity.setSize(spec.width * visualScale, spec.height * visualScale)
        entity.autoAspect()
        entity.isFlick = spec.flick
        entity.flickerAnimationRateMulti = spec.flickerRate
        entity.glowPower = spec.glowPower
        entity.noisePower = spec.noisePower
        // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
        entity.setCoreColor(spec.coreColor.toAwt())
        entity.setFringeColor(spec.fringeColor.toAwt())
        entity.setGlobalTimer(0.05f, FLARE_FULL_SECONDS, 0.1f)
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (disabled) return
        val current = lease ?: return
        if (current.entity.hasDelete()) {
            lease = null
            return
        }
        syncFlare(ctx)
    }

    private fun syncFlare(ctx: RenderContext) {
        val entity = lease?.entity ?: return
        val frame = ctx.frame
        if (fadeStartElapsed < 0f && frame.phase == cn.kasuminova.astd.api.render.RenderPhase.FadingOut) {
            fadeStartElapsed = frame.elapsed
        }
        val fadeMul = if (fadeStartElapsed < 0f) 1f
        else (1f - (frame.elapsed - fadeStartElapsed) / fadeSeconds).coerceIn(0f, 1f)
        val rad = Math.toRadians(frame.facing.toDouble())
        // 位移量与光斑尺寸同源，随视觉缩放同步，保持光斑相对弹体的对位
        val offset = spec.offsetX * visualScale
        val pos = Vector2f(
            frame.origin.x + (cos(rad) * offset).toFloat(),
            frame.origin.y + (sin(rad) * offset).toFloat(),
        )
        val facing = spec.fixedFacingDeg ?: BoxUtilCombatVfx.normalizeFacingDeg(frame.facing + spec.facingOffsetDeg)
        entity.setStateVanilla(pos, facing)
        entity.globalAlpha = frame.intensity * fadeMul
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeSeconds = seconds.coerceAtLeast(0.05f)
    }

    override fun onDetachSelf() {
        val current = lease ?: return
        lease = null
        FlareEntityPool.release(current.engine, spec.boxLayer, current.entity)
    }

    private class FlareLease(val engine: CombatEngineAPI, val entity: FlareEntity)

    companion object {
        const val RENDER_ORDER_FLARE = StaticTrailComponent.RENDER_ORDER_BASE + 8

        private const val FLARE_FULL_SECONDS = 1e7f
    }
}

private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
