package cn.kasuminova.astd.renderer.effect.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer
import com.fs.starfarer.api.impl.campaign.terrain.RangeBlockerUtil
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.sin

/**
 * 引力电子干扰力场（密蒙-级）的磁场视觉。
 * 复用原版星球磁场（AuroraRenderer）的渲染，光带环绕力场边界，颜色与力场波一致。
 */
class GravEmFieldMagneticVisual private constructor(
    private val engine: CombatEngineAPI,
    private val ship: ShipAPI,
) : BaseCombatLayeredRenderingPlugin(LAYER), AuroraRenderer.AuroraRendererDelegate {

    private val auroraRenderer = AuroraRenderer(this)
    private val texture: SpriteAPI = Global.getSettings().getSprite(TEXTURE_CATEGORY, TEXTURE_ID)

    private var coreColor = Color(232, 205, 255, MAGNETIC_ALPHA)
    private var fringeColor = Color(186, 120, 255, MAGNETIC_ALPHA)
    private var fieldBlend = 0f
    private var innerRadius = 100f
    private var outerRadius = 350f
    private var elapsed = 0f
    private var disposed = false

    val expired: Boolean get() = disposed || entity == null

    fun update(core: Color, fringe: Color, fieldBlend: Float) {
        this.coreColor = withAlpha(core)
        this.fringeColor = withAlpha(fringe)
        this.fieldBlend = fieldBlend
        val base = ship.collisionRadius
        innerRadius = base * INNER_RADIUS_MULT
        outerRadius = base * OUTER_RADIUS_MULT
        entity?.location?.set(ship.location)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        entity?.let { engine.removeEntity(it) }
    }

    override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(LAYER)

    override fun getRenderRadius(): Float = outerRadius * 1.3f + 100f

    override fun advance(amount: Float) {
        if (disposed) return
        elapsed += amount
        auroraRenderer.advance(amount)
        if (ship.isAlive && !ship.isHulk) {
            entity?.location?.set(ship.location)
        }
    }

    override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
        if (disposed || layer != LAYER) return
        val alpha = fieldBlend * viewport.alphaMult * 0.5f
        if (alpha <= 0f) return

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        try {
            auroraRenderer.render(alpha)
        } finally {
            GL11.glPopAttrib()
        }
    }

    override fun getAuroraInnerRadius(): Float = innerRadius

    override fun getAuroraOuterRadius(): Float = outerRadius

    override fun getAuroraCenterLoc(): Vector2f = Vector2f(ship.location)

    override fun getAuroraColorForAngle(angle: Float): Color {
        val mix = (sin(Math.toRadians((angle * 2f).toDouble())).toFloat() + 1f) * 0.5f
        return Color(
            (fringeColor.red + (coreColor.red - fringeColor.red) * mix).toInt(),
            (fringeColor.green + (coreColor.green - fringeColor.green) * mix).toInt(),
            (fringeColor.blue + (coreColor.blue - fringeColor.blue) * mix).toInt(),
            (fringeColor.alpha + (coreColor.alpha - fringeColor.alpha) * mix).toInt(),
        )
    }

    override fun getAuroraAlphaMultForAngle(angle: Float): Float = 1f

    override fun getAuroraShortenMult(angle: Float): Float = PULSE_SHORTEN_MULT

    override fun getAuroraInnerOffsetMult(angle: Float): Float = 1f

    override fun getAuroraThicknessMult(angle: Float): Float =
        1f + THICKNESS_BREATHE_AMPLITUDE * sin(elapsed * THICKNESS_BREATHE_SPEED + Math.toRadians((angle * 2f).toDouble())).toFloat()

    override fun getAuroraThicknessFlat(angle: Float): Float = 0f

    override fun getAuroraTexPerSegmentMult(): Float = 1f

    override fun getAuroraBandWidthInTexture(): Float = BAND_WIDTH_IN_TEXTURE

    override fun getAuroraTexture(): SpriteAPI = texture

    override fun getAuroraBlocker(): RangeBlockerUtil? = null

    companion object {
        private val log = Global.getLogger(GravEmFieldMagneticVisual::class.java)

        private val LAYER = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER

        private const val TEXTURE_CATEGORY = "terrain"
        private const val TEXTURE_ID = "aurora"
        private const val TEXTURE_PATH = "graphics/planets/aurorae.png"

        private const val MAGNETIC_ALPHA = 110

        private const val INNER_RADIUS_MULT = 0.75f
        private const val OUTER_RADIUS_MULT = 7.5f

        private const val BAND_WIDTH_IN_TEXTURE = 256f

        private const val PULSE_SHORTEN_MULT = 0.3f

        private const val THICKNESS_BREATHE_AMPLITUDE = 0.2f
        private const val THICKNESS_BREATHE_SPEED = 1.3f

        fun spawn(engine: CombatEngineAPI, ship: ShipAPI): GravEmFieldMagneticVisual {
            Global.getSettings().loadTexture(TEXTURE_PATH)
            val plugin = GravEmFieldMagneticVisual(engine, ship)
            plugin.innerRadius = ship.collisionRadius * INNER_RADIUS_MULT
            plugin.outerRadius = ship.collisionRadius * OUTER_RADIUS_MULT
            val entity = engine.addLayeredRenderingPlugin(plugin)
            if (entity == null) {
                log.error("引力电子干扰力场磁场 addLayeredRenderingPlugin 返回 null（ship=${ship.id}），本次磁场视觉缺失")
                plugin.disposed = true
                return plugin
            }
            entity.location.set(ship.location)
            return plugin
        }

        private fun withAlpha(color: Color): Color = Color(color.red, color.green, color.blue, MAGNETIC_ALPHA)
    }
}
