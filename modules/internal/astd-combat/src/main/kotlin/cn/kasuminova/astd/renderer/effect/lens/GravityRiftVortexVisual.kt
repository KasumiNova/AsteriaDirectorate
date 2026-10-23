package cn.kasuminova.astd.renderer.effect.lens

import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.util.EnumSet

class GravityRiftVortexVisual private constructor(
    private val engine: CombatEngineAPI,
    private val target: com.fs.starfarer.api.combat.ShipAPI?,
    private val anchorOffset: Vector2f,
    private val radius: Float,
) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {

    private var elapsed = 0f
    private var forceFadeOutAt = -1f
    private var angle = MathUtils.getRandomNumberInRange(0f, 360f)

    private val lastAnchor = Vector2f(0f, 0f)
    private var anchorInitialized = false

    fun forceFadeOut() {
        if (forceFadeOutAt < 0f) forceFadeOutAt = elapsed
    }

    fun currentAnchor(): Vector2f {
        val t = target
        return if (t != null && t.isAlive && !t.isHulk) {
            Vector2f.add(t.location, anchorOffset, null)
        } else {
            Vector2f(lastAnchor)
        }
    }

    override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
        EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

    override fun getRenderRadius(): Float = radius * 2f + 50f

    override fun advance(amount: Float) {
        val entity = entity ?: return
        elapsed += amount
        angle = (angle + amount * GravityRiftTuning.VORTEX_SPIN_DEG_PER_SEC) % 360f

        val anchor = currentAnchor()
        if (!anchorInitialized) {
            lastAnchor.set(anchor)
            anchorInitialized = true
        }
        val t = target
        if (t != null && t.isAlive && !t.isHulk) {
            lastAnchor.set(anchor)
        }
        entity.location.set(lastAnchor)

        if (elapsed >= MAX_LIFETIME_SECONDS) {
            log.warn("引力旋涡超过 ${MAX_LIFETIME_SECONDS}s 未自然移除（系统脚本异常中断？），按超时路径回收")
            engine.removeEntity(entity)
            return
        }
        if (GravityRiftTuning.vortexAlpha(elapsed, forceFadeOutAt) <= 0f && elapsed > GravityRiftTuning.VORTEX_FADE_IN) {
            engine.removeEntity(entity)
        }
    }

    override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
        val entity = entity ?: return
        if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return

        val alpha = GravityRiftTuning.vortexAlpha(elapsed, forceFadeOutAt) * viewport.alphaMult
        if (alpha <= 0f) return

        val sprite = Global.getSettings().getSprite(TEXTURE_PATH)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        try {
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
            sprite.setAdditiveBlend()
            sprite.alphaMult = alpha.coerceIn(0f, 1f)
            sprite.setSize(radius * 2f, radius * 2f)
            sprite.angle = angle
            sprite.renderAtCenter(entity.location.x, entity.location.y)
        } finally {
            GL11.glPopAttrib()
        }
    }

    companion object {
        private val log = Global.getLogger(GravityRiftVortexVisual::class.java)

        private const val TEXTURE_PATH = "graphics/fx/astd_vortex_red.png"

        private const val MAX_LIFETIME_SECONDS = 10f

        fun spawn(
            engine: CombatEngineAPI,
            target: com.fs.starfarer.api.combat.ShipAPI?,
            anchorOffset: Vector2f,
            radius: Float,
        ): GravityRiftVortexVisual {
            Global.getSettings().loadTexture(TEXTURE_PATH)
            val plugin = GravityRiftVortexVisual(engine, target, Vector2f(anchorOffset), radius)
            val entity = engine.addLayeredRenderingPlugin(plugin)
            if (entity == null) {
                log.error("引力旋涡 addLayeredRenderingPlugin 返回 null（target=${target?.id}），本次旋涡视觉缺失")
                return plugin
            }
            entity.location.set(plugin.currentAnchor())
            plugin.lastAnchor.set(entity.location)
            plugin.anchorInitialized = true
            return plugin
        }
    }
}
