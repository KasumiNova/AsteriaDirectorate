package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDTrailRibbonDecorationSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxRibbonRenderer {
    data class RibbonPoint(val base: Vector2f, val position: Vector2f, val alpha: Float, val color: ASTDColor)

    fun pointsForTests(ribbon: ASTDTrailRibbonDecorationSpec, context: ASTDProjectileVfxRenderContext, sampleCount: Int): List<RibbonPoint> {
        val history = context.historyNodes.map { it.location }
        val histPixelsPerEntry = estimateHistoryPixelsPerEntry(history)
        return (0..sampleCount).map { index ->
            val t = index.toFloat() / sampleCount.coerceAtLeast(1).toFloat()
            val dist = context.visibleLength * t * ribbon.lengthScale + ribbon.startOffset
            val base = ASTDProjectileVfxMath.sampleHistoryAt(history, dist, histPixelsPerEntry)
            val wave = ASTDProjectileVfxMath.ribbonWave(
                ribbon.waveType,
                base.x,
                context.elapsed,
                ribbon.frequency,
                ribbon.waveSpeed,
                ribbon.amplitude,
                ribbon.noiseScale,
                17,
                0.48f,
            )
            val position = Vector2f(base.x, base.y + ribbon.endOffset + wave)
            RibbonPoint(base, position, ribbon.alphaScale * context.beamAlpha * (1f - t * 0.22f), sampleColor(ribbon, t))
        }
    }

    fun sampleColor(ribbon: ASTDTrailRibbonDecorationSpec, t: Float): ASTDColor {
        val stops = ribbon.colorGradient.stops.takeIf { ribbon.colorGradient.enabled && it.isNotEmpty() }
            ?.sortedBy { it.offset }
            ?: return mix(ribbon.startColor, ribbon.endColor, t)
        if (t <= stops.first().offset) return stops.first().color
        for (index in 0 until stops.lastIndex) {
            val left = stops[index]
            val right = stops[index + 1]
            if (t >= left.offset && t <= right.offset) {
                val ratio = ((t - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return mix(left.color, right.color, ratio)
            }
        }
        return stops.last().color
    }

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            a.red + (b.red - a.red) * ratio,
            a.green + (b.green - a.green) * ratio,
            a.blue + (b.blue - a.blue) * ratio,
            a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }

    private fun estimateHistoryPixelsPerEntry(history: List<Vector2f>): Float {
        if (history.size < 2) return 4f
        var total = 0f
        val sampleN = minOf(history.size - 1, 8)
        for (index in 0 until sampleN) {
            val a = history[index]
            val b = history[index + 1]
            val dx = a.x - b.x
            val dy = a.y - b.y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return max(0.5f, total / sampleN)
    }
}

class ASTDProjectileVfxRibbonRenderLayer(
    private val ribbons: List<ASTDTrailRibbonDecorationSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val ribbon: ASTDTrailRibbonDecorationSpec, val entity: TrailEntity)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        BoxUtilCombatVfx.ensureReady(engine)
        ribbons.filter { it.enabled }.forEach { ribbon ->
            val entity = TrailEntity()
            points(ribbon, context).forEach { entity.addNode(Vector2f(it.position)) }
            entity.submitNodes()
            apply(entity, ribbon, context)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
            if (state == 0) {
                handles += Handle(ribbon, entity)
            } else {
                entity.delete()
                Global.getLogger(ASTDProjectileVfxRibbonRenderLayer::class.java).warn(
                    "ASTD projectile VFX ribbon addEntity failed state=$state layer=${ribbon.id} preset=${context.presetId} projectile=${context.projectileSpecId}",
                )
            }
        }
        return handles.size == ribbons.count { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        handles.forEach { handle ->
            if (!handle.entity.hasDelete()) {
                handle.entity.setNodes(ArrayList(points(handle.ribbon, context).map { Vector2f(it.position) }))
                handle.entity.setNodeRefreshIndex(0)
                handle.entity.setNodeRefreshAllFromCurrentIndex()
                handle.entity.submitNodes()
                apply(handle.entity, handle.ribbon, context.copy(beamAlpha = context.beamAlpha * fade.alpha()))
            }
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.entity.delete() }
        handles.clear()
    }

    private fun points(ribbon: ASTDTrailRibbonDecorationSpec, context: ASTDProjectileVfxRenderContext): List<ASTDProjectileVfxRibbonRenderer.RibbonPoint> {
        val sampleCount = if (ribbon.renderMode == "byNodeCount") {
            max(8, (context.historyNodes.size * ribbon.nodeCountScale).toInt())
        } else {
            max(8, (context.visibleLength * ribbon.lengthScale / 8f).toInt())
        }
        return ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, context, sampleCount)
    }

    private fun apply(entity: TrailEntity, ribbon: ASTDTrailRibbonDecorationSpec, context: ASTDProjectileVfxRenderContext) {
        val alpha = ribbon.alphaScale * context.beamAlpha
        entity.setLayer(CombatEngineLayers.ABOVE_PARTICLES)
        entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        entity.setStartWidth(max(0.5f, ribbon.thickness * 4f))
        entity.setEndWidth(max(0.5f, ribbon.thickness * 2f))
        entity.setStartColor(ribbon.startColor.red, ribbon.startColor.green, ribbon.startColor.blue, ribbon.startColor.alpha * alpha)
        entity.setEndColor(ribbon.endColor.red, ribbon.endColor.green, ribbon.endColor.blue, ribbon.endColor.alpha * alpha)
        val start = ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 0f)
        val end = ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 1f)
        entity.setStartEmissive(start.red, start.green, start.blue, start.alpha * alpha)
        entity.setEndEmissive(end.red, end.green, end.blue, end.alpha * alpha)
        entity.setJitterPower(ribbon.blur * 0.02f)
        entity.materialData.setColor(ribbon.color.toAwtColor(alpha))
        entity.materialData.setEmissiveColor(ribbon.startColor.toAwtColor(alpha))
        entity.materialData.setAlphaToEmissive(0f)
        entity.materialData.setColorToEmissive(0f)
        entity.materialData.setGlowPower(1f)
    }
}
