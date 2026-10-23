package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx.addEntity
import com.fs.starfarer.api.Global
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
        entity.applyFlareStyle(spec.style)
        entity.discRatio = spec.discRatio
        entity.setSize(spec.width, spec.height)
        entity.autoAspect()
        entity.isFlick = spec.flick
        entity.flickerAnimationRateMulti = spec.flickerRate
        entity.glowPower = spec.glowPower
        entity.noisePower = spec.noisePower
        // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
        entity.setCoreColor(spec.coreColor.toAwt())
        entity.setFringeColor(spec.fringeColor.toAwt())
        entity.setGlobalTimer(0.05f, FLARE_FULL_SECONDS, 0.1f)
        BoxUtilCombatVfx.ensureReady(engine)
        val state = addEntity(engine, entity)
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
        val rad = Math.toRadians(frame.facing.toDouble())
        val pos = Vector2f(
            frame.origin.x + (cos(rad) * spec.offsetX).toFloat(),
            frame.origin.y + (sin(rad) * spec.offsetX).toFloat(),
        )
        val facing = spec.fixedFacingDeg ?: BoxUtilCombatVfx.normalizeFacingDeg(frame.facing + spec.facingOffsetDeg)
        entity.setStateVanilla(pos, facing)
        entity.globalAlpha = frame.intensity * fadeMul
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeSeconds = seconds.coerceAtLeast(0.05f)
    }

    override fun onDetachSelf() {
        flare?.delete()
        flare = null
    }

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
