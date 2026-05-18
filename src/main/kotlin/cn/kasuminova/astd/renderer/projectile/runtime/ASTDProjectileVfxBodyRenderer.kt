package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f

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
        val xScale: Float = 1f,
        val yScale: Float = 1f,
        val shaderQuad: ASTDProjectileVfxShaderRenderer.Quad? = null,
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
        val envelope = bodyShadowEnvelope(polygon, widthBase, baseLayer, pulse, alphaScale)
        val spine = brightSpine(context.visibleLength, widthBase, baseLayer, pulse, alphaScale)
        val allVertices = vertices + envelope.vertices + spine.vertices
        return Mesh(
            polygon = polygon,
            gradientStops = gradientStops,
            vertices = allVertices,
            triangles = triangulateStrip(vertices) + envelope.triangles + spine.triangles,
            blendMode = "additive",
            combatLayer = baseLayer.combatLayer,
            xScale = 1.55f,
            yScale = 0.58f,
            shaderQuad = ASTDProjectileVfxShaderRenderer.bodyQuadForTests(trail, context, alphaScale),
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
        val tailReach = visibleLength.coerceAtLeast(6f) * 0.86f
        return ((point.x + tailReach) / tailReach.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
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
        val hotAttenuation = when {
            offset >= 0.84f -> 0.006f
            offset >= 0.62f -> 0.014f
            else -> 1f
        }
        return color.copy(alpha = (stopAlpha * alphaScale * hotAttenuation).coerceIn(0f, 1f))
    }

    private data class MeshPart(
        val vertices: List<Vertex>,
        val triangles: List<Triangle>,
    )

    private fun bodyShadowEnvelope(
        polygon: List<Vector2f>,
        widthBase: Float,
        baseLayer: ASTDTrailLayerSpec,
        pulse: Float,
        alphaScale: Float,
    ): MeshPart {
        if (polygon.size < 4 || pulse <= 0.001f) return MeshPart(emptyList(), emptyList())
        val blur = kotlin.math.max(8f, widthBase * 2.4f)
        val color = mix(baseLayer.endEmissive, baseLayer.startEmissive, 0.55f)
        val xs = polygon.map { it.x }
        val minX = xs.minOrNull() ?: return MeshPart(emptyList(), emptyList())
        val maxX = xs.maxOrNull() ?: return MeshPart(emptyList(), emptyList())
        val length = (maxX - minX).coerceAtLeast(1f)
        val samples = listOf(0f, 0.24f, 0.52f, 0.78f, 1f)
        val vertices = ArrayList<Vertex>(samples.size * 6)
        for (band in 0..2) {
            val expand = blur * (0.38f + band * 0.34f)
            val alpha = 0.86f * pulse * alphaScale * (0.006f / (band + 1f))
            for (t in samples) {
                val x = minX + length * t
                val baseHalf = halfHeightAt(polygon, x)
                val profile = ASTDProjectileVfxMath.smoothstep(0.05f, 0.24f, t) *
                    (1f - ASTDProjectileVfxMath.smoothstep(0.92f, 1f, t))
                val half = baseHalf + expand * (0.45f + 0.55f * profile)
                val stopAlpha = alpha * profile
                val vertexColor = color.copy(alpha = stopAlpha.coerceIn(0f, 1f))
                vertices += Vertex(Vector2f(x, -half), vertexColor)
                vertices += Vertex(Vector2f(x, half), vertexColor)
            }
        }
        val triangles = ArrayList<Triangle>()
        val stripSize = samples.size * 2
        for (band in 0..2) {
            triangles += triangulateStrip(vertices.subList(band * stripSize, band * stripSize + stripSize))
        }
        return MeshPart(vertices, triangles)
    }

    private fun brightSpine(
        visibleLength: Float,
        widthBase: Float,
        baseLayer: ASTDTrailLayerSpec,
        pulse: Float,
        alphaScale: Float,
    ): MeshPart {
        if (pulse <= 0.001f) return MeshPart(emptyList(), emptyList())
        val samples = listOf(0f, 0.22f, 0.62f, 0.86f, 1f)
        val startX = -visibleLength.coerceAtLeast(6f) * 0.24f
        val endX = -widthBase * 0.65f
        val hot = mix(baseLayer.startEmissive, ASTDColor(1f, 1f, 1f, 1f), 0.86f)
        val vertices = ArrayList<Vertex>(samples.size * 2)
        for (t in samples) {
            val x = ASTDProjectileVfxMath.lerp(startX, endX, t)
            val half = widthBase * ASTDProjectileVfxMath.lerp(2.3f, 4.2f, t)
            val profile = ASTDProjectileVfxMath.smoothstep(0f, 0.22f, t) *
                (1f - ASTDProjectileVfxMath.smoothstep(0.94f, 1f, t))
            val alpha = pulse * alphaScale * 0.22f * profile
            val color = if (t < 0.72f) mix(baseLayer.startColor, hot, 0.5f) else hot
            vertices += Vertex(Vector2f(x, -half), color.copy(alpha = alpha.coerceIn(0f, 1f)))
            vertices += Vertex(Vector2f(x, half), color.copy(alpha = alpha.coerceIn(0f, 1f)))
        }
        return MeshPart(vertices, triangulateStrip(vertices))
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
    private var handle: ASTDProjectileVfxBodyRenderManager.Handle? = null

    fun meshForTests(): ASTDProjectileVfxBodyRenderer.Mesh = mesh ?: ASTDProjectileVfxBodyRenderer.meshForTests(trail, emptyContext())

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context, fade.alpha())
        if (engine == null) return false
        val currentHandle = handle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { handle = it }
        currentHandle.update(context.location, context.renderFacing, mesh!!)
        return true
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context, fade.alpha())
        if (engine != null) {
            val currentHandle = handle ?: ASTDProjectileVfxBodyRenderManager.createHandle(engine).also { handle = it }
            currentHandle.update(context.location, context.renderFacing, mesh!!)
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handle?.delete()
        handle = null
        mesh = null
    }

    private fun emptyContext(): ASTDProjectileVfxRenderContext = ASTDProjectileVfxRenderContext(
        location = Vector2f(),
        velocityFacing = 0f,
        projectileFacing = 0f,
        renderFacing = 0f,
        elapsed = 0f,
        flightProgress = 0f,
        dissolve = 0f,
        visibleLength = 0f,
        beamAlpha = 0f,
        historyNodes = emptyList(),
        presetId = "test",
        projectileSpecId = "test",
    )
}
