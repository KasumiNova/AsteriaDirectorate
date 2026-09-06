package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 弹体 VFX 布局/预览数学。
 *
 * Static Trail 迁移（2026-09）后运行期不再消费本对象（拖尾几何由 BoxUtil 托管）；
 * 保留的 preview 函数族仅供自动化取证（ASTDAutomationCombatPlugin 的 AOD-7 曲线截图场景）
 * 与 astd-impl 场景测试共用。head 网格几何已随代码弹头渲染栈删除。
 */
object ASTDProjectileVfxLayout {
    private const val EDITOR_CAPTURE_WIDTH = 1280f
    const val REFERENCE_MAX_ZOOM_VISIBLE_HEIGHT = 600f

    data class PreviewFlightTrack(
        val headOffset: Vector2f,
        val tailOffset: Vector2f,
        val centerOffset: Vector2f,
        val progress: Float,
        val elapsed: Float,
        val flightProgress: Float,
        val dissolve: Float,
        val visibleLength: Float,
        val beamAlpha: Float,
    )

    fun viewportTailCap(trailStartWidth: Float, viewportVisibleWidth: Float): Float {
        return max(viewportVisibleWidth.coerceAtLeast(0f) * 0.46f, trailStartWidth.coerceAtLeast(0f) * 4.8f)
    }

    fun previewFlightTrack(
        trailStartWidth: Float,
        elapsed: Float,
        durationSeconds: Float,
        flightEndRatio: Float,
        dissolveStartRatio: Float,
        preDissolveFraction: Float,
        captureWidth: Float = EDITOR_CAPTURE_WIDTH,
        captureHeight: Float,
        curveAmount: Float,
        curveFrequency: Float,
        curved: Boolean,
    ): PreviewFlightTrack {
        val duration = max(durationSeconds, 1.2f)
        val clampedElapsed = elapsed.coerceIn(0f, duration)
        val progress = (clampedElapsed / duration).coerceIn(0f, 1f)
        val flightEndSeconds = duration * flightEndRatio
        val dissolveStartSeconds = duration * dissolveStartRatio
        val flightRange = preDissolveFraction.coerceIn(0f, 1f)
        val dissolveRange = 1f - flightRange
        val dissolveDuration = max(duration - dissolveStartSeconds, 0.0001f)
        val flightSpeed = flightRange / max(flightEndSeconds, 0.0001f)
        val dissolveStartSlope = (flightSpeed * dissolveDuration) / max(dissolveRange, 0.0001f)
        val dissolveEndSlope = (flightSpeed * 0.25f * dissolveDuration) / max(dissolveRange, 0.0001f)
        val flightProgress = if (clampedElapsed <= dissolveStartSeconds) {
            flightRange * (clampedElapsed / max(flightEndSeconds, 0.0001f)).coerceIn(0f, 1f)
        } else {
            flightRange + dissolveRange * ASTDProjectileVfxMath.hermite01(
                ((clampedElapsed - dissolveStartSeconds) / dissolveDuration).coerceIn(0f, 1f),
                dissolveStartSlope,
                dissolveEndSlope,
            )
        }
        val dissolveStart = min(dissolveStartSeconds, duration - 0.2f)
        val dissolve = ASTDProjectileVfxMath.smoothstep(dissolveStart, duration, clampedElapsed)
        val startX = captureWidth * 0.14f
        val endX = captureWidth * 0.88f
        val travelX = ASTDProjectileVfxMath.lerp(startX, endX, flightProgress)
        val curveEnvelope = Math.pow(
            (
                ASTDProjectileVfxMath.smoothstep(0.08f, 0.28f, progress) *
                    (1f - ASTDProjectileVfxMath.smoothstep(0.72f, 0.98f, progress))
                ).toDouble(),
            0.9,
        ).toFloat()
        val curveDissolve = Math.pow((1f - dissolve).toDouble(), 1.35).toFloat()
        val fallbackCurveAmount = max(48f, captureHeight.coerceAtLeast(0f) * 0.16f)
        val effectiveCurveAmount = if (curved) max(curveAmount, fallbackCurveAmount) else 0f
        val curveY = if (effectiveCurveAmount > 0f) {
            sin(clampedElapsed * curveFrequency * PI.toFloat() * 2f) * effectiveCurveAmount * curveEnvelope * curveDissolve
        } else {
            0f
        }
        val traveledLength = max(0f, travelX - startX)
        val maxTailLength = viewportTailCap(trailStartWidth, captureWidth)
        val minTailLength = max(trailStartWidth * 0.22f, 6f)
        val grownLength = min(maxTailLength, max(minTailLength * ASTDProjectileVfxMath.smoothstep(0f, 0.08f, flightProgress), traveledLength))
        val visibleLength = grownLength * ASTDProjectileVfxMath.lerp(1f, 0.08f, dissolve)
        val beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve)
        return PreviewFlightTrack(
            headOffset = Vector2f(traveledLength, curveY),
            tailOffset = Vector2f(traveledLength - visibleLength, curveY),
            centerOffset = Vector2f(traveledLength - visibleLength * 0.4f, curveY),
            progress = progress,
            elapsed = clampedElapsed,
            flightProgress = flightProgress,
            dissolve = dissolve,
            visibleLength = visibleLength,
            beamAlpha = beamAlpha,
        )
    }

    fun referenceWorldUnitsPerPixel(displayPixelHeight: Float): Float {
        return REFERENCE_MAX_ZOOM_VISIBLE_HEIGHT / displayPixelHeight.coerceAtLeast(1f)
    }
}
