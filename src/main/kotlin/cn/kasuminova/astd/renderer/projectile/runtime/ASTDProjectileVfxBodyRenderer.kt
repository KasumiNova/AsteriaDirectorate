package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.max

object ASTDProjectileVfxBodyRenderer {
    data class Vertex(
        val position: Vector2f,
        val color: ASTDColor,
    )

    data class Triangle(
        val a: Vertex,
        val b: Vertex,
        val c: Vertex,
    )

    data class Mesh(
        val polygon: List<Vector2f>,
        val gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        val vertices: List<Vertex>,
        val triangles: List<Triangle>,
        val blendMode: String,
        val combatLayer: CombatEngineLayers,
    )

    fun meshForTests(
        trail: ASTDTrailEntitySpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): Mesh {
        val baseLayer = baseLayer(trail)
        val pulse = context.beamAlpha.coerceIn(0f, 1f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val polygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, pulse)
        val gradientStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)
        val vertices = bodyStripVertices(polygon, gradientStops, context.visibleLength, alphaScale)
        val scaledVertices = vertices.map { vertex ->
            vertex.copy(position = ASTDProjectileVfxLayout.scalePoint(vertex.position, context.worldUnitsPerPixel))
        }
        return Mesh(
            polygon = ASTDProjectileVfxLayout.scalePoints(polygon, context.worldUnitsPerPixel),
            gradientStops = gradientStops,
            vertices = scaledVertices,
            triangles = triangulateStrip(scaledVertices),
            blendMode = "additive",
            combatLayer = baseLayer.combatLayer,
        )
    }

    fun shadowMeshForTests(
        trail: ASTDTrailEntitySpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): Mesh {
        val baseLayer = baseLayer(trail)
        val pulse = context.beamAlpha.coerceIn(0f, 1f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val polygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, pulse)
        val gradientStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)
        val columns = bodyShadowColumns(baseLayer, polygon, gradientStops, context, widthBase, alphaScale)
        val shadow = ASTDProjectileVfxSoftMesh.symmetricOuterFalloff(columns, 8)
        return Mesh(
            polygon = shadow.vertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = shadow.vertices,
            triangles = shadow.triangles,
            blendMode = "additive",
            combatLayer = baseLayer.combatLayer,
        )
    }

    private fun baseLayer(trail: ASTDTrailEntitySpec): ASTDTrailLayerSpec = trail.layers.firstOrNull() ?: trail.layerSpec

    private fun triangulate(vertices: List<Vertex>): List<Triangle> {
        if (vertices.size < 3) return emptyList()
        return (1 until vertices.lastIndex).map { index ->
            Triangle(vertices[0], vertices[index], vertices[index + 1])
        }
    }

    private fun bodyStripVertices(
        polygon: List<Vector2f>,
        gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        visibleLength: Float,
        alphaScale: Float,
    ): List<Vertex> {
        if (polygon.size < 9) {
            return polygon.map { point ->
                Vertex(Vector2f(point), sampleGradient(gradientStops, gradientOffset(point, visibleLength), alphaScale))
            }
        }
        val ordered = listOf(
            polygon[0] to polygon[8],
            polygon[1] to polygon[7],
            polygon[2] to polygon[6],
            polygon[3] to polygon[5],
            polygon[4] to polygon[4],
        )
        return ordered.flatMap { (top, bottom) ->
            val topColor = sampleGradient(gradientStops, gradientOffset(top, visibleLength), alphaScale)
            val bottomColor = sampleGradient(gradientStops, gradientOffset(bottom, visibleLength), alphaScale)
            listOf(Vertex(Vector2f(top), topColor), Vertex(Vector2f(bottom), bottomColor))
        }
    }

    private fun bodyShadowColumns(
        baseLayer: ASTDTrailLayerSpec,
        polygon: List<Vector2f>,
        gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        alphaScale: Float,
    ): List<ASTDProjectileVfxSoftMesh.Column> {
        if (polygon.size < 9) return emptyList()
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val bodyEmissive = mix(baseLayer.endEmissive, baseLayer.startEmissive, 0.55f)
        val shadowBlur = max(8f, widthBase * 2.4f)
        val shadowAlpha = 0.86f * context.beamAlpha.coerceIn(0f, 1f)
        val ordered = listOf(
            polygon[0] to polygon[8],
            polygon[1] to polygon[7],
            polygon[2] to polygon[6],
            polygon[3] to polygon[5],
            polygon[4] to polygon[4],
        )
        return ordered.map { (top, bottom) ->
            val x = (top.x + bottom.x) * 0.5f
            val centerY = (top.y + bottom.y) * 0.5f
            val half = abs(bottom.y - top.y) * 0.5f
            val fillAlpha = sampleGradient(gradientStops, gradientOffset(Vector2f(x, centerY), context.visibleLength), alphaScale).alpha
            ASTDProjectileVfxSoftMesh.Column(
                x = x * scale,
                centerY = centerY * scale,
                innerHalf = half * scale,
                outerHalf = (half + shadowBlur * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_VISIBLE_RADIUS) * scale,
                color = bodyEmissive,
                alpha = (fillAlpha * shadowAlpha * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_KERNEL_ALPHA).coerceIn(0f, 1f),
            )
        }
    }

    internal fun triangulateStrip(vertices: List<Vertex>): List<Triangle> {
        if (vertices.size < 4) return emptyList()
        val triangles = ArrayList<Triangle>((vertices.size / 2 - 1) * 2)
        var index = 0
        while (index + 3 < vertices.size) {
            val a = vertices[index]
            val b = vertices[index + 1]
            val c = vertices[index + 2]
            val d = vertices[index + 3]
            triangles += Triangle(a, b, c)
            triangles += Triangle(c, b, d)
            index += 2
        }
        return triangles
    }

    private fun gradientOffset(point: Vector2f, visibleLength: Float): Float {
        val gradientLength = visibleLength.coerceAtLeast(6f) * 0.6f
        return ((point.x + gradientLength) / gradientLength.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    }

    private fun sampleGradient(stops: List<ASTDProjectileVfxLayout.BodyGradientStop>, offset: Float, alphaScale: Float): ASTDColor {
        if (stops.isEmpty()) return ASTDColor(1f, 1f, 1f, alphaScale.coerceIn(0f, 1f))
        val sorted = stops.sortedBy { it.offset }
        val left = sorted.lastOrNull { it.offset <= offset } ?: sorted.first()
        val right = sorted.firstOrNull { it.offset >= offset } ?: sorted.last()
        val range = (right.offset - left.offset).coerceAtLeast(0.0001f)
        val t = ((offset - left.offset) / range).coerceIn(0f, 1f)
        val color = mix(left.color, right.color, t)
        val stopAlpha = left.alpha + (right.alpha - left.alpha) * t
        return color.copy(alpha = (stopAlpha * alphaScale).coerceIn(0f, 1f))
    }

    internal fun halfHeightAt(polygon: List<Vector2f>, x: Float): Float {
        if (polygon.isEmpty()) return 0f
        val upper = polygon.filter { it.y <= 0f }.sortedBy { it.x }
        val lower = polygon.filter { it.y >= 0f }.sortedBy { it.x }
        val top = interpolateY(upper, x) ?: upper.minOfOrNull { it.y } ?: 0f
        val bottom = interpolateY(lower, x) ?: lower.maxOfOrNull { it.y } ?: 0f
        return kotlin.math.max(kotlin.math.abs(top), kotlin.math.abs(bottom)).coerceAtLeast(0.5f)
    }

    private fun interpolateY(points: List<Vector2f>, x: Float): Float? {
        if (points.isEmpty()) return null
        if (x <= points.first().x) return points.first().y
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            if (x >= a.x && x <= b.x) {
                val t = ((x - a.x) / (b.x - a.x).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return ASTDProjectileVfxMath.lerp(a.y, b.y, t)
            }
        }
        return points.last().y
    }

    internal fun mixForRuntime(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor = mix(a, b, t)

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            red = a.red + (b.red - a.red) * ratio,
            green = a.green + (b.green - a.green) * ratio,
            blue = a.blue + (b.blue - a.blue) * ratio,
            alpha = a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }
}

class ASTDProjectileVfxBodyRenderLayer(
    private val trail: ASTDTrailEntitySpec,
) : ASTDProjectileVfxRenderLayer {
    private val fade = ASTDProjectileVfxLayerFadeState()
    private var mesh: ASTDProjectileVfxBodyRenderer.Mesh? = null
    private var shadowMesh: ASTDProjectileVfxBodyRenderer.Mesh? = null
    private var handle: ASTDProjectileVfxBodyRenderManager.Handle? = null
    private var shadowHandle: ASTDProjectileVfxBodyRenderManager.Handle? = null

    fun meshForTests(): ASTDProjectileVfxBodyRenderer.Mesh = mesh ?: ASTDProjectileVfxBodyRenderer.meshForTests(trail, emptyContext())
    fun shadowMeshForTests(): ASTDProjectileVfxBodyRenderer.Mesh = shadowMesh ?: ASTDProjectileVfxBodyRenderer.shadowMeshForTests(trail, emptyContext())

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context, fade.alpha())
        shadowMesh = ASTDProjectileVfxBodyRenderer.shadowMeshForTests(trail, context, fade.alpha())
        if (engine == null) return false
        val currentHandle = handle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { handle = it }
        val currentShadowHandle = shadowHandle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { shadowHandle = it }
        currentHandle.update(context.location, context.renderFacing, mesh!!)
        currentShadowHandle.update(context.location, context.renderFacing, shadowMesh!!)
        return true
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context, fade.alpha())
        shadowMesh = ASTDProjectileVfxBodyRenderer.shadowMeshForTests(trail, context, fade.alpha())
        if (engine != null) {
            val currentHandle = handle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { handle = it }
            val currentShadowHandle = shadowHandle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { shadowHandle = it }
            currentHandle.update(context.location, context.renderFacing, mesh!!)
            currentShadowHandle.update(context.location, context.renderFacing, shadowMesh!!)
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handle?.delete()
        handle = null
        shadowHandle?.delete()
        shadowHandle = null
        mesh = null
        shadowMesh = null
    }

    private fun emptyContext(): ASTDProjectileVfxRenderContext = ASTDProjectileVfxRenderContext(
        location = Vector2f(),
        velocityFacing = 0f,
        projectileFacing = 0f,
        renderFacing = 0f,
        elapsed = 0f,
        logicElapsed = 0f,
        flightProgress = 0f,
        dissolve = 0f,
        visibleLength = 0f,
        beamAlpha = 0f,
        historyNodes = emptyList(),
        presetId = "test",
        projectileSpecId = "test",
    )
}
