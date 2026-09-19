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

/**
 * 引力裂隙发生器：目标舰体表面的红色旋涡贴图视觉
 * （purple/20-production.md §2，2026-09-20 二轮重做）。
 *
 * 单贴图方案（contents/graphics/fx/astd_vortex_red.png，由 astd_vortex.png 亮度映射转红）：
 * - 加法混合，每秒自旋 [GravityRiftTuning.VORTEX_SPIN_DEG_PER_SEC] 度；
 * - alpha 包络 = 1s 淡入 + 全亮 + 1s 淡出（共 [GravityRiftTuning.VORTEX_DURATION]s），
 *   由 [GravityRiftTuning.vortexAlpha] 统一计算；
 * - 位置锚定目标舰：目标存活时 = 目标实时位置 + 激活时采样偏移（随目标移动），
 *   目标消亡后停在最后位置自然淡出；
 * - [forceFadeOut] 为系统被打断的收口路径（自调用时刻起 1s 内压暗到 0）；
 *   正常路径下旋涡在系统 OUT 段自然淡出完毕并自移除。
 *
 * 注：加法混合素材必须「圆形、边缘 alpha 归零、无烘焙底色」——前两轮实机事故
 * （方形渐变红方块 / 蓝白底粉白团）的教训，转红副本已目检确认满足。
 */
class GravityRiftVortexVisual private constructor(
    private val engine: CombatEngineAPI,
    private val target: com.fs.starfarer.api.combat.ShipAPI?,
    private val anchorOffset: Vector2f,
    private val radius: Float,
) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {

    private var elapsed = 0f
    private var forceFadeOutAt = -1f
    private var angle = MathUtils.getRandomNumberInRange(0f, 360f)

    /** 目标消亡后的最后锚点（旋涡停在原地淡出）。 */
    private val lastAnchor = Vector2f(0f, 0f)
    private var anchorInitialized = false

    /** 系统被打断（unapply）时调用：自当前时刻起 1s 内压暗到 0。 */
    fun forceFadeOut() {
        if (forceFadeOutAt < 0f) forceFadeOutAt = elapsed
    }

    /** 当前旋涡中心（光束 drone 每帧定位用）：目标存活时随目标移动。 */
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

        // 自超时收口：正常路径在 VORTEX_DURATION 内淡出归零；forceFadeOut 路径 1s 内归零。
        // 远超系统全程仍未归零说明系统脚本异常中断，不得滞留到战斗结束。
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

        /** 旋涡贴图：astd_vortex.png 的红色换色副本（加法混合安全素材）。 */
        private const val TEXTURE_PATH = "graphics/fx/astd_vortex_red.png"

        /** 自超时（秒）：远超系统 chargeUp+active+down 全程与强制淡出窗口，仅兜异常中断路径。 */
        private const val MAX_LIFETIME_SECONDS = 10f

        /**
         * 生成旋涡：注册分层渲染插件并预加载贴图
         * （SSOptimizer 延迟加载下先 loadTexture，否则拿到 textureID=0 的空壳）。
         * [target] 为锚定目标舰（可空 = 固定点旋涡），[anchorOffset] 为相对目标位置的偏移。
         */
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
                // 插件未入场：渲染不会回调，但必须可见
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
