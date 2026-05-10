package cn.kasuminova.astd.ui.effect

import cn.kasuminova.astd.ui.render.ASTDStencilRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 星云渐变背景面板插件。
 *
 * 使用半透明星云贴图渲染暗色渐变背景，不再使用粒子特效，
 * 通过精确设置 [contentHeight] 确保背景与实际内容高度完全匹配，不会溢出 Tooltip 范围。
 *
 * 用法：
 * 1. 调用 [create] 在 tooltip 插入 0 高度面板并保存返回值
 * 2. 添加 tooltip 内容
 * 3. 设置 [contentHeight] = tooltip.heightSoFar - startHeight
 */
class ASTDParticleBackground private constructor(
    private val panelWidth: Float,
    private val themeColor: Color,
    private val enableStarTrails: Boolean = false,
) : BaseCustomUIPanelPlugin() {

    /** 由 DSL 在内容添加完毕后精确设置，确保背景高度与实际内容完全一致，不溢出。 */
    var contentHeight: Float = 0f

    private var pos: PositionAPI? = null
    private var timeAcc: Float = 0f

    override fun positionChanged(position: PositionAPI) {
        this.pos = position
    }

    override fun advance(amount: Float) {
        timeAcc += amount
    }

    override fun renderBelow(alphaMult: Float) {
        val h = contentHeight
        if (h <= 0f) return
        val p = pos ?: return
        val x = p.x
        val y = p.y - h

        GL11.glPushMatrix()
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        ASTDStencilRenderer.withStencilMask(x, y, panelWidth, h) {
            // 深色底层（-50% 不透明度），为星云色彩留出显示空间
            ASTDStencilRenderer.drawRect(x, y, panelWidth, h, 0f, 0f, 0f, 0.28f * alphaMult)

            val sprite = resolveNebulaSprite()
            if (sprite != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D)
                // 普通 alpha 混合：主题色与深色底混合，产生有颜色的渐变星云感
                // 注意：glColor4f 对 SpriteAPI 无效，必须使用 sprite.setColor(Color) 设置染色
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

                for (patch in NEBULA_PATCHES) {
                    val pulse = 0.75f + 0.25f * sin((timeAcc * patch.pulseSpeed + patch.phase).toDouble()).toFloat()
                    val patchW = panelWidth * patch.sizeRelW
                    val patchH = h * patch.sizeRelH
                    val cx = x + panelWidth * patch.relX
                    val cy = y + h * patch.relY
                    val alphaInt = (patch.baseAlpha * pulse * alphaMult * 255f).toInt().coerceIn(0, 255)
                    sprite.setColor(Color(themeColor.red, themeColor.green, themeColor.blue, alphaInt))
                    sprite.setSize(patchW, patchH)
                    sprite.renderAtCenter(cx, cy)
                }

                GL11.glDisable(GL11.GL_TEXTURE_2D)
            }

            // 延迟摄影风格星轨（仅 enableStarTrails = true 时渲染）
            if (enableStarTrails) {
                renderStarTrails(x, y, panelWidth, h, alphaMult)
            }
        }

        GL11.glPopMatrix()
    }

    private fun renderStarTrails(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE) // additive blend

        val r = themeColor.red / 255f
        val g = themeColor.green / 255f
        val b = themeColor.blue / 255f

        for (trail in STAR_TRAILS) {
            val cx = x + w * trail.centerRelX
            val cy = y + h * trail.centerRelY
            val radius = w * trail.radiusMul
            val currentStart = trail.startAngle + timeAcc * trail.rotationSpeed
            val alpha = trail.baseAlpha * alphaMult

            // Arc body
            GL11.glLineWidth(trail.width)
            GL11.glColor4f(r, g, b, alpha)
            GL11.glBegin(GL11.GL_LINE_STRIP)
            val steps = (trail.arcSpan / 2.5f).toInt().coerceAtLeast(10)
            for (i in 0..steps) {
                val a = Math.toRadians((currentStart + trail.arcSpan * i.toFloat() / steps).toDouble())
                GL11.glVertex2f(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
            }
            GL11.glEnd()

            // Rounded endpoints
            val endpointR = trail.width * 0.5f
            for (endAngle in floatArrayOf(currentStart, currentStart + trail.arcSpan)) {
                val rad = Math.toRadians(endAngle.toDouble())
                val ex = cx + radius * cos(rad).toFloat()
                val ey = cy + radius * sin(rad).toFloat()
                GL11.glBegin(GL11.GL_TRIANGLE_FAN)
                GL11.glColor4f(r, g, b, alpha)
                GL11.glVertex2f(ex, ey)
                for (j in 0..ENDPOINT_SEGMENTS) {
                    val ca = Math.toRadians(360.0 * j / ENDPOINT_SEGMENTS)
                    GL11.glVertex2f(ex + endpointR * cos(ca).toFloat(), ey + endpointR * sin(ca).toFloat())
                }
                GL11.glEnd()
            }
        }

        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glLineWidth(1f)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
    }

    private data class NebulaPatch(
        val relX: Float, val relY: Float,
        val sizeRelW: Float, val sizeRelH: Float,
        val baseAlpha: Float, val pulseSpeed: Float, val phase: Float,
    )

    companion object {

        private const val ENDPOINT_SEGMENTS = 12

        private val NEBULA_PATCHES = listOf(
            NebulaPatch(0.18f, 0.30f, 0.80f, 1.35f, 0.14f, 0.55f, 0.00f),
            NebulaPatch(0.78f, 0.65f, 0.65f, 1.20f, 0.12f, 0.48f, 2.10f),
            NebulaPatch(0.50f, 0.50f, 1.10f, 1.55f, 0.10f, 0.38f, 4.20f),
            NebulaPatch(0.28f, 0.80f, 0.55f, 1.00f, 0.13f, 0.62f, 1.05f),
        )

        private val NEBULA_SPRITE_PATHS = listOf(
            "graphics/fx/nebula_colorless.png",
            "graphics/fx/fx_clouds00.png",
            "graphics/fx/beamfringeb.png",
        )

        /**
         * 延迟摄影星轨参数。
         * @param centerRelX  圆心相对面板宽度 X 位置
         * @param centerRelY  圆心相对面板高度 Y 位置
         * @param radiusMul   半径（面板宽度的倍数）
         * @param startAngle  起始角度（度）
         * @param arcSpan     弧长（度）
         * @param width       线宽（像素）
         * @param baseAlpha   基础不透明度
         * @param rotationSpeed 旋转速度（度/秒）
         */
        private data class StarTrail(
            val centerRelX: Float, val centerRelY: Float,
            val radiusMul: Float,
            val startAngle: Float, val arcSpan: Float,
            val width: Float,
            val baseAlpha: Float,
            val rotationSpeed: Float,
        )

        private val STAR_TRAILS = listOf(
            // 内环 r=0.22w，3 条均匀分布（120° 间隔），低速旋转
            StarTrail(0.70f, 1.10f, 0.22f, 200f, 30f, 1.3f, 0.085f, 5.5f),
            StarTrail(0.70f, 1.10f, 0.22f, 320f, 30f, 1.3f, 0.085f, 5.5f),
            StarTrail(0.70f, 1.10f, 0.22f,  80f, 30f, 1.3f, 0.085f, 5.5f),
            // 中内环 r=0.32w
            StarTrail(0.70f, 1.10f, 0.32f, 190f, 38f, 1.6f, 0.070f, 4.0f),
            StarTrail(0.70f, 1.10f, 0.32f, 310f, 38f, 1.6f, 0.070f, 4.0f),
            StarTrail(0.70f, 1.10f, 0.32f,  70f, 38f, 1.6f, 0.070f, 4.0f),
            // 中环 r=0.44w
            StarTrail(0.70f, 1.10f, 0.44f, 215f, 42f, 1.9f, 0.060f, 3.0f),
            StarTrail(0.70f, 1.10f, 0.44f, 335f, 42f, 1.9f, 0.060f, 3.0f),
            StarTrail(0.70f, 1.10f, 0.44f,  95f, 42f, 1.9f, 0.060f, 3.0f),
            // 外环 r=0.55w
            StarTrail(0.70f, 1.10f, 0.55f, 205f, 48f, 2.2f, 0.050f, 2.5f),
            StarTrail(0.70f, 1.10f, 0.55f, 325f, 48f, 2.2f, 0.050f, 2.5f),
            StarTrail(0.70f, 1.10f, 0.55f,  85f, 48f, 2.2f, 0.050f, 2.5f),
            // 最外环 r=0.68w
            StarTrail(0.70f, 1.10f, 0.68f, 220f, 55f, 2.5f, 0.045f, 2.0f),
            StarTrail(0.70f, 1.10f, 0.68f, 340f, 55f, 2.5f, 0.045f, 2.0f),
            StarTrail(0.70f, 1.10f, 0.68f, 100f, 55f, 2.5f, 0.045f, 2.0f),
        )

        @Volatile private var cachedSprite: SpriteAPI? = null
        @Volatile private var loadAttempted = false
        @Volatile private var failureLogged = false

        /**
         * 向 tooltip 插入 0 高度的星云背景面板并返回实例。
         * 调用方在内容添加完毕后必须设置 [ASTDParticleBackground.contentHeight]。
         */
        @JvmStatic
        fun create(
            tooltip: TooltipMakerAPI,
            width: Float,
            color: Color,
            starTrails: Boolean = false,
        ): ASTDParticleBackground {
            val plugin = ASTDParticleBackground(width, color, starTrails)
            val panel = Global.getSettings().createCustom(0f, 0f, plugin)
            tooltip.addCustom(panel, 0f)
            return plugin
        }

        @Synchronized
        private fun resolveNebulaSprite(): SpriteAPI? {
            cachedSprite?.let { return it }
            if (!loadAttempted) {
                loadAttempted = true
                for (path in NEBULA_SPRITE_PATHS) {
                    try { Global.getSettings().loadTexture(path) } catch (_: Throwable) {}
                }
            }
            for (path in NEBULA_SPRITE_PATHS) {
                val s = try { Global.getSettings().getSprite(path) } catch (_: Throwable) { null }
                if (s != null) { cachedSprite = s; return s }
            }
            if (!failureLogged) {
                failureLogged = true
                try {
                    Global.getLogger(ASTDParticleBackground::class.java)
                        .warn("ASTDParticleBackground: no nebula sprite resolved from $NEBULA_SPRITE_PATHS")
                } catch (_: Throwable) {}
            }
            return null
        }
    }
}

