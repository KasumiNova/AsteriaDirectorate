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
    const val RENDER_ORDER_GLOW = 100
    const val RENDER_ORDER_BODY_SHADOW = 180
    const val RENDER_ORDER_BODY = 200
    const val RENDER_ORDER_SIDE_WISP = 240
    const val RENDER_ORDER_HEAD_SHADOW = 280
    const val RENDER_ORDER_HEAD = 300
    const val RENDER_ORDER_RIBBON = 360

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
        val renderOrder: Int = 0,
    )

    fun meshForTests(
        trail: ASTDTrailEntitySpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): Mesh {
        val baseLayer = baseLayer(trail)
        val pulse = context.beamAlpha.coerceIn(0f, 1f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val polygon = bodyPolygonForContext(context, widthBase, pulse)
        val gradientStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)
        val vertices = if (isCurvedContext(context)) {
            centerlineBodyStripVertices(polygon, gradientStops, context.visibleLength, alphaScale)
        } else {
            bodyStripVertices(polygon, gradientStops, context.visibleLength, alphaScale)
        }
        val noiseVertices = if (isCurvedContext(context)) {
            centerlineBodyNoiseVertices(gradientStops, context, widthBase, alphaScale)
        } else {
            bodyNoiseVertices(polygon, gradientStops, context, alphaScale)
        }
        val scaledVertices = vertices.map { vertex ->
            vertex.copy(position = ASTDProjectileVfxLayout.scalePoint(vertex.position, context.worldUnitsPerPixel))
        }
        val scaledNoiseVertices = noiseVertices.map { vertex ->
            vertex.copy(position = ASTDProjectileVfxLayout.scalePoint(vertex.position, context.worldUnitsPerPixel))
        }
        return Mesh(
            polygon = ASTDProjectileVfxLayout.scalePoints(polygon, context.worldUnitsPerPixel),
            gradientStops = gradientStops,
            vertices = scaledVertices + scaledNoiseVertices,
            triangles = triangulateStrip(scaledVertices) + triangulateNoiseColumns(scaledNoiseVertices),
            blendMode = "additive",
            combatLayer = baseLayer.combatLayer,
            renderOrder = RENDER_ORDER_BODY,
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
        val polygon = bodyPolygonForContext(context, widthBase, pulse)
        val gradientStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)
        val columns = if (isCurvedContext(context)) {
            centerlineBodyShadowColumns(baseLayer, context, widthBase, alphaScale)
        } else {
            bodyShadowColumns(baseLayer, polygon, gradientStops, context, widthBase, alphaScale)
        }
        val shadow = ASTDProjectileVfxSoftMesh.symmetricOuterFalloff(columns, 8)
        return Mesh(
            polygon = shadow.vertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = shadow.vertices,
            triangles = shadow.triangles,
            blendMode = "additive",
            combatLayer = baseLayer.combatLayer,
            renderOrder = RENDER_ORDER_BODY_SHADOW,
        )
    }

    private fun baseLayer(trail: ASTDTrailEntitySpec): ASTDTrailLayerSpec = trail.layers.firstOrNull() ?: trail.layerSpec

    private fun isCurvedContext(context: ASTDProjectileVfxRenderContext): Boolean {
        return context.historyNodes.size >= 3 && !ASTDProjectileVfxCenterline.isEffectivelyStraight(context)
    }

    private fun bodyPolygonForContext(context: ASTDProjectileVfxRenderContext, widthBase: Float, pulse: Float): List<Vector2f> {
        return if (isCurvedContext(context)) {
            ASTDProjectileVfxCenterline.bodyPolygon(context, widthBase, pulse)
        } else {
            ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, pulse)
        }
    }

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

    private fun bodyNoiseVertices(
        polygon: List<Vector2f>,
        gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float,
    ): List<Vertex> {
        if (polygon.size < 9 || context.beamAlpha <= 0.001f) return emptyList()
        val length = context.visibleLength.coerceAtLeast(6f)
        val startX = max(polygon[1].x, -length * 0.58f)
        val endX = -max(2f, length * 0.015f)
        val columns = 9
        val vertices = ArrayList<Vertex>(columns * 3)
        for (index in 0 until columns) {
            val t = index.toFloat() / (columns - 1).toFloat()
            val x = ASTDProjectileVfxMath.lerp(startX, endX, t)
            val half = halfHeightAt(polygon, x) * 0.66f
            val baseColor = sampleGradient(gradientStops, gradientOffset(Vector2f(x, 0f), context.visibleLength), alphaScale)
            vertices += Vertex(Vector2f(x, -half), noisyBodyColor(baseColor, x, -half, half, context, 0.32f))
            vertices += Vertex(Vector2f(x, 0f), noisyBodyColor(baseColor, x, 0f, half, context, 0.42f))
            vertices += Vertex(Vector2f(x, half), noisyBodyColor(baseColor, x, half, half, context, 0.32f))
        }
        return vertices
    }

    private fun centerlineBodyNoiseVertices(
        gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        alphaScale: Float,
    ): List<Vertex> {
        if (context.beamAlpha <= 0.001f) return emptyList()
        val length = context.visibleLength.coerceAtLeast(6f)
        val centerline = ASTDProjectileVfxCenterline.build(context)
        if (centerline.size < 2) return emptyList()
        val startDistance = (length * 0.36f).coerceAtMost(length)
        val endDistance = max(2f, length * 0.015f)
        val columns = 9
        val vertices = ArrayList<Vertex>(columns * 3)
        for (index in 0 until columns) {
            val t = index.toFloat() / (columns - 1).toFloat()
            val distance = ASTDProjectileVfxMath.lerp(startDistance, endDistance, t)
            val ratio = (distance / length).coerceIn(0f, 1f)
            val center = ASTDProjectileVfxCenterline.sampleByRatio(centerline, ratio)
            val normal = ASTDProjectileVfxCenterline.normalAt(centerline, ratio)
            val half = ASTDProjectileVfxCenterline.bodyHalfWidthAt(ratio, widthBase, context.beamAlpha) * 0.66f
            val baseColor = sampleGradient(gradientStops, 1f - distance / (length * 0.6f).coerceAtLeast(0.0001f), alphaScale)
            val offsets = listOf(-half, 0f, half)
            for (offset in offsets) {
                val point = Vector2f(center.position.x + normal.x * offset, center.position.y + normal.y * offset)
                vertices += Vertex(point, noisyBodyColor(baseColor, -distance, offset, half, context, if (offset == 0f) 0.42f else 0.32f))
            }
        }
        return vertices
    }

    private fun triangulateNoiseColumns(vertices: List<Vertex>): List<Triangle> {
        if (vertices.size < 6) return emptyList()
        val triangles = ArrayList<Triangle>((vertices.size / 3 - 1) * 4)
        var index = 0
        while (index + 5 < vertices.size) {
            val topA = vertices[index]
            val midA = vertices[index + 1]
            val bottomA = vertices[index + 2]
            val topB = vertices[index + 3]
            val midB = vertices[index + 4]
            val bottomB = vertices[index + 5]
            triangles += Triangle(topA, midA, topB)
            triangles += Triangle(topB, midA, midB)
            triangles += Triangle(midA, bottomA, midB)
            triangles += Triangle(midB, bottomA, bottomB)
            index += 3
        }
        return triangles
    }

    private fun noisyBodyColor(
        base: ASTDColor,
        x: Float,
        y: Float,
        halfHeight: Float,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float,
    ): ASTDColor {
        val length = context.visibleLength.coerceAtLeast(6f)
        val u = ((x + length) / length).coerceIn(0f, 1f)
        val v = ((y / max(1f, halfHeight)) + 1f) * 0.5f
        val time = context.logicElapsed
        val grain = ASTDProjectileVfxMath.layeredNoise(u * 18f - time * 3.2f, v * 5f)
        val vertical = ASTDProjectileVfxMath.layeredNoise(u * 5f - time * 0.8f, v * 22f)
        val beamNoise = ASTDProjectileVfxMath.lerp(0.82f, 1.28f, grain) + vertical * 0.16f
        val alpha = base.alpha * alphaScale * context.beamAlpha * ASTDProjectileVfxMath.smoothstep(0.02f, 0.22f, u)
        return ASTDColor(
            red = (base.red * beamNoise + (beamNoise - 1f) * 0.045f).coerceIn(0f, 1f),
            green = (base.green * beamNoise + (beamNoise - 1f) * 0.055f).coerceIn(0f, 1f),
            blue = (base.blue * (0.92f + (beamNoise - 1f) * 0.34f)).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }

    private fun centerlineBodyStripVertices(
        polygon: List<Vector2f>,
        gradientStops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        visibleLength: Float,
        alphaScale: Float,
    ): List<Vertex> {
        if (polygon.size < 4 || polygon.size % 2 != 0) {
            return polygon.map { point ->
                Vertex(Vector2f(point), sampleGradient(gradientStops, gradientOffset(point, visibleLength), alphaScale))
            }
        }
        val half = polygon.size / 2
        val topReversed = polygon.take(half)
        val bottom = polygon.drop(half)
        val top = topReversed.asReversed()
        return top.indices.flatMap { index ->
            val topPoint = top[index]
            val bottomPoint = bottom[index]
            val t = index.toFloat() / (top.size - 1).coerceAtLeast(1).toFloat()
            val color = sampleGradient(gradientStops, t, alphaScale)
            listOf(Vertex(Vector2f(topPoint), color), Vertex(Vector2f(bottomPoint), color))
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

    private fun centerlineBodyShadowColumns(
        baseLayer: ASTDTrailLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        alphaScale: Float,
    ): List<ASTDProjectileVfxSoftMesh.Column> {
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val pulse = context.beamAlpha.coerceIn(0f, 1f)
        val centerline = ASTDProjectileVfxCenterline.build(context)
        if (centerline.size < 2) return emptyList()
        val bodyEmissive = mix(baseLayer.endEmissive, baseLayer.startEmissive, 0.55f)
        val shadowBlur = max(8f, widthBase * 2.4f)
        val shadowAlpha = 0.86f * pulse
        val gradientStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)
        return centerline.map { point ->
            val half = ASTDProjectileVfxCenterline.bodyHalfWidthAt(point.t, widthBase, pulse)
            val fillAlpha = sampleGradient(gradientStops, point.t, alphaScale).alpha
            ASTDProjectileVfxSoftMesh.Column(
                x = point.position.x * scale,
                centerY = point.position.y * scale,
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
