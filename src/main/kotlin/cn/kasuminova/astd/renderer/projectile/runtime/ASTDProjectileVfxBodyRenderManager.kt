package cn.kasuminova.astd.renderer.projectile.runtime

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.util.vector.Vector2f
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin

internal object ASTDProjectileVfxBodyRenderManager {
    private const val ENGINE_KEY = "astd_projectile_vfx_body_renderer"

    data class Snapshot(
        val location: Vector2f,
        val facing: Float,
        val mesh: ASTDProjectileVfxBodyRenderer.Mesh,
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

    private fun rendererForTests(engine: CombatEngineAPI): Renderer? = engine.customData[ENGINE_KEY] as? Renderer

    private fun transformLocalPoint(local: Vector2f, location: Vector2f, facing: Float): Vector2f {
        val radians = Math.toRadians(facing.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Vector2f(
            location.x + local.x * c - local.y * s,
            location.y + local.x * s + local.y * c,
        )
    }

    class Handle internal constructor(
        private val renderer: Renderer,
        private val id: Int,
    ) {
        fun update(location: Vector2f, facing: Float, mesh: ASTDProjectileVfxBodyRenderer.Mesh) {
            renderer.update(id, Snapshot(Vector2f(location), facing, mesh))
        }

        fun delete() {
            renderer.delete(id)
        }
    }

    class Renderer internal constructor() : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_PARTICLES) {
        private val snapshots = LinkedHashMap<Int, Snapshot>()
        private var nextId = 1
        private var expired = false
        private var shaderProgram: ASTDProjectileVfxShaderRenderer.Program? = null

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
            return snapshots.values.filter { it.mesh.combatLayer == layer }
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
            shaderProgram?.delete()
            shaderProgram = null
            expired = true
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || snapshots.isEmpty()) return
            val renderSnapshots = snapshotsForLayer(layer)
            if (renderSnapshots.isEmpty()) return

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_CURRENT_BIT)
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)

                val shaderSnapshots = renderSnapshots.filter { it.mesh.shaderQuad != null }
                val meshSnapshots = renderSnapshots.filter { it.mesh.shaderQuad == null }
                if (shaderSnapshots.isNotEmpty()) {
                    if (!org.lwjgl.opengl.GLContext.getCapabilities().OpenGL20) {
                        error("ASTD projectile shader renderer requires OpenGL 2.0")
                    }
                    val program = shaderProgram ?: ASTDProjectileVfxShaderRenderer.Program().also { shaderProgram = it }
                    for (snapshot in shaderSnapshots) {
                        program.render(snapshot, snapshot.mesh.shaderQuad!!)
                    }
                    GL20.glUseProgram(0)
                }

                if (meshSnapshots.isNotEmpty()) {
                    GL11.glBegin(GL11.GL_TRIANGLES)
                    try {
                        for (snapshot in meshSnapshots) {
                            for (triangle in snapshot.mesh.triangles) {
                                emitVertex(snapshot, triangle.a)
                                emitVertex(snapshot, triangle.b)
                                emitVertex(snapshot, triangle.c)
                            }
                        }
                    } finally {
                        GL11.glEnd()
                    }
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun emitVertex(snapshot: Snapshot, vertex: ASTDProjectileVfxBodyRenderer.Vertex) {
            val color = vertex.color
            val scaled = Vector2f(vertex.position.x * snapshot.mesh.xScale, vertex.position.y * snapshot.mesh.yScale)
            val world = transformLocalPoint(scaled, snapshot.location, snapshot.facing)
            GL11.glColor4f(
                color.red.coerceIn(0f, 1f),
                color.green.coerceIn(0f, 1f),
                color.blue.coerceIn(0f, 1f),
                color.alpha.coerceIn(0f, 1f),
            )
            GL11.glVertex2f(world.x, world.y)
        }
    }
}
