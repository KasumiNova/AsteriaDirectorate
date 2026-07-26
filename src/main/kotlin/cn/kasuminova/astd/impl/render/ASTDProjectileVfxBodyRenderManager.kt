package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin

internal object ASTDProjectileVfxBodyRenderManager {
    private const val ENGINE_KEY = "astd_projectile_vfx_body_renderer"

    enum class BlendFactor(val glValue: Int) {
        SrcAlpha(GL11.GL_SRC_ALPHA),
        One(GL11.GL_ONE),
        OneMinusSrcAlpha(GL11.GL_ONE_MINUS_SRC_ALPHA),
    }

    data class BlendState(
        val sourceFactor: BlendFactor,
        val destinationFactor: BlendFactor,
    )

    data class Snapshot(
        val location: Vector2f,
        val facing: Float,
        val mesh: ASTDProjectileVfxBodyRenderer.Mesh,
        val renderOrder: Int = mesh.renderOrder,
    )

    fun ensure(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val renderer = Renderer()
        engine.addLayeredRenderingPlugin(renderer)
        engine.customData[ENGINE_KEY] = renderer
        return renderer
    }

    fun createHandle(engine: CombatEngineAPI): Handle = ensure(engine).createHandle()

    fun activeHandleCountForTests(engine: CombatEngineAPI): Int = rendererForTests(engine)?.activeHandleCountForTests() ?: 0

    fun activeSnapshotsForTests(engine: CombatEngineAPI): List<Snapshot> = rendererForTests(engine)?.activeSnapshotsForTests() ?: emptyList()

    fun transformLocalPointForTests(local: Vector2f, location: Vector2f, facing: Float): Vector2f = transformLocalPoint(local, location, facing)

    fun blendStateForTests(mesh: ASTDProjectileVfxBodyRenderer.Mesh): BlendState = blendState(mesh)

    private fun rendererForTests(engine: CombatEngineAPI): Renderer? = engine.customData[ENGINE_KEY] as? Renderer

    private fun blendState(mesh: ASTDProjectileVfxBodyRenderer.Mesh): BlendState {
        return when (mesh.blendMode.lowercase()) {
            "normal", "source-over" -> BlendState(BlendFactor.SrcAlpha, BlendFactor.OneMinusSrcAlpha)
            else -> BlendState(BlendFactor.SrcAlpha, BlendFactor.One)
        }
    }

    private fun transformLocalPoint(local: Vector2f, location: Vector2f, facing: Float): Vector2f {
        val radians = Math.toRadians(facing.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Vector2f(
            location.x + local.x * c - local.y * s,
            location.y + local.x * s + local.y * c,
        )
    }

    /**
     * 把一组快照的网格烘焙进顶点数组缓冲（按三角展开：每顶点 位置 x,y + 颜色 r,g,b,a），供一次 glDrawArrays 提交。
     * 两个缓冲先 clear 再填充，返回时均已 flip 到可读状态。顶点世界变换（旋转+平移）在 CPU 侧内联完成，不产生分配。
     * 注：Mesh.Triangle 持顶点对象而非索引，展开成非索引数组可避免每帧反查顶点下标。
     */
    internal fun fillMeshBuffers(snapshots: List<Snapshot>, positions: FloatBuffer, colors: FloatBuffer) {
        positions.clear()
        colors.clear()
        for (snapshot in snapshots) {
            val radians = Math.toRadians(snapshot.facing.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val lx = snapshot.location.x
            val ly = snapshot.location.y
            for (triangle in snapshot.mesh.triangles) {
                emitVertex(positions, colors, triangle.a, lx, ly, c, s)
                emitVertex(positions, colors, triangle.b, lx, ly, c, s)
                emitVertex(positions, colors, triangle.c, lx, ly, c, s)
            }
        }
        positions.flip()
        colors.flip()
    }

    private fun emitVertex(positions: FloatBuffer, colors: FloatBuffer, vertex: ASTDProjectileVfxBodyRenderer.Vertex, lx: Float, ly: Float, c: Float, s: Float) {
        positions.put(lx + vertex.position.x * c - vertex.position.y * s)
        positions.put(ly + vertex.position.x * s + vertex.position.y * c)
        val color = vertex.color
        colors.put(color.red.coerceIn(0f, 1f))
        colors.put(color.green.coerceIn(0f, 1f))
        colors.put(color.blue.coerceIn(0f, 1f))
        colors.put(color.alpha.coerceIn(0f, 1f))
    }

    class Handle internal constructor(
        private val renderer: Renderer,
        private val id: Int,
    ) {
        fun update(location: Vector2f, facing: Float, mesh: ASTDProjectileVfxBodyRenderer.Mesh) {
            renderer.update(id, Snapshot(Vector2f(location), facing, mesh, mesh.renderOrder))
        }

        fun delete() {
            renderer.delete(id)
        }
    }

    class Renderer internal constructor() : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_PARTICLES) {
        private val snapshots = LinkedHashMap<Int, Snapshot>()
        private var nextId = 1
        private var expired = false

        // 顶点数组缓冲，跨帧复用、按需扩容（容量翻倍），避免每帧分配
        private var positionBuffer: FloatBuffer = BufferUtils.createFloatBuffer(INITIAL_BUFFER_CAPACITY)
        private var colorBuffer: FloatBuffer = BufferUtils.createFloatBuffer(INITIAL_BUFFER_CAPACITY * 2)

        fun createHandle(): Handle = Handle(this, nextId++)

        fun update(id: Int, snapshot: Snapshot) {
            if (expired) return
            snapshots[id] = snapshot
        }

        fun delete(id: Int) {
            snapshots.remove(id)
        }

        fun activeHandleCountForTests(): Int = snapshots.size

        fun activeSnapshotsForTests(): List<Snapshot> = snapshots.values.toList()

        fun snapshotsForLayerForTests(layer: CombatEngineLayers): List<Snapshot> = snapshotsForLayer(layer)

        private fun snapshotsForLayer(layer: CombatEngineLayers): List<Snapshot> {
            return snapshots.values
                .filter { it.mesh.combatLayer == layer }
                .sortedWith(compareBy<Snapshot> { it.renderOrder }.thenBy { it.mesh.blendMode })
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
            val layers = EnumSet.noneOf(CombatEngineLayers::class.java)
            for (snapshot in snapshots.values) {
                layers += snapshot.mesh.combatLayer
            }
            if (layers.isEmpty()) layers += CombatEngineLayers.ABOVE_PARTICLES
            return layers
        }

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun isExpired(): Boolean = expired

        override fun cleanup() {
            snapshots.clear()
            expired = true
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || snapshots.isEmpty()) return
            val renderSnapshots = snapshotsForLayer(layer)
            if (renderSnapshots.isEmpty()) return

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            // GL_CLIENT_ALL_ATTRIB_BITS：LWJGL2 未暴露该常量（GL 规范值 0xFFFFFFFF，即 -1）
            GL11.glPushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS)
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY)
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY)
                for ((_, blendGroup) in renderSnapshots.groupBy { it.mesh.blendMode.lowercase() }) {
                    applyBlend(blendGroup.first().mesh)
                    drawBlendGroup(blendGroup)
                }
            } finally {
                GL11.glPopClientAttrib()
                GL11.glPopAttrib()
            }
        }

        /** 一个 blend 组一次 draw call：烘焙顶点数组后 glDrawArrays 提交（替代逐顶点立即模式）。 */
        private fun drawBlendGroup(blendGroup: List<Snapshot>) {
            val vertexCount = blendGroup.sumOf { it.mesh.triangles.size * 3 }
            if (vertexCount == 0) return
            if (positionBuffer.capacity() < vertexCount * 2) {
                positionBuffer = BufferUtils.createFloatBuffer(maxOf(vertexCount * 2, positionBuffer.capacity() * 2))
            }
            if (colorBuffer.capacity() < vertexCount * 4) {
                colorBuffer = BufferUtils.createFloatBuffer(maxOf(vertexCount * 4, colorBuffer.capacity() * 2))
            }

            fillMeshBuffers(blendGroup, positionBuffer, colorBuffer)
            GL11.glVertexPointer(2, 0, positionBuffer)
            GL11.glColorPointer(4, 0, colorBuffer)
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount)
        }

        private fun applyBlend(mesh: ASTDProjectileVfxBodyRenderer.Mesh) {
            val state = blendState(mesh)
            GL11.glBlendFunc(state.sourceFactor.glValue, state.destinationFactor.glValue)
        }

        private companion object {
            const val INITIAL_BUFFER_CAPACITY = 4096
            const val GL_CLIENT_ALL_ATTRIB_BITS = -1
        }
    }
}
