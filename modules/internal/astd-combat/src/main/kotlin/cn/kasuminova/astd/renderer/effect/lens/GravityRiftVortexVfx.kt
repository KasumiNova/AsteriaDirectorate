package cn.kasuminova.astd.renderer.effect.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.sin

/**
 * 引力裂隙发生器：目标点的红色引力旋涡视觉（purple/20-production.md §2）。
 *
 * 生命周期由系统脚本驱动：[spawn] 于系统 chargeUp（1s）开始、[detonate] 于光束发射时——
 * 旋涡先渐显并加速自旋，detonate 后核心脉冲增亮、整体在 [FADE_OUT_SECONDS] 内扩散淡出并自行移除。
 *
 * 观感组成（统一红色正色，不使用反色特效）：
 * - 三条旋转**螺旋臂**：臂根角随时间推进，每个发射间隔从当前臂的圆周位置发射
 *   nebula 粒子（速度 = 向心 + 同向切向），粒子在寿命内沿螺旋轨迹被吸入核心，
 *   形成星系式旋臂结构；
 * - 外层稀疏大型弥散粒子（低 alpha、长寿），提供体积感；
 * - 中心小型柔光核心（hit_glow 双层：热核 + 红晕），半径随充能收拢、亮度脉动。
 *
 * 注一：旋涡本体**不使用大贴图**——曾误用 planets/atmosphere.png（方形渐变 → 旋转红方块）
 * 与 fx/wormhole_corona.png（自带蓝白底色 → 加法混合成粉白团）两轮实机事故（2026-09），
 * 最终定为全粒子 + 小核心方案；加法混合素材必须「圆形、边缘 alpha 归零、无烘焙底色」。
 * 注二：旋涡不施加引力牵引——引力牵引对本舰护航机群同样生效，近距离格斗定位下副作用过大，
 * 「引力」仅以视觉语义表达（内吸粒子 + 收拢旋臂）。
 */
class GravityRiftVortexVfx private constructor(
    private val engine: CombatEngineAPI,
) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {

    private var elapsed = 0f
    private var detonateElapsed = -1f

    /** 臂根角（度）：随时间推进，粒子从各臂的圆周位置陆续发射。 */
    private var armAngle = MathUtils.getRandomNumberInRange(0f, 360f)
    private var armIndex = 0
    private var armAcc = 0f
    private var wispAcc = 0f

    /** 光束发射时刻调用：旋涡转入脉冲增亮 + 扩散淡出。 */
    fun detonate() {
        if (detonateElapsed < 0f) detonateElapsed = 0f
    }

    override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
        EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

    override fun getRenderRadius(): Float = RENDER_RADIUS

    override fun advance(amount: Float) {
        val entity = entity ?: return
        elapsed += amount

        if (detonateElapsed >= 0f) {
            detonateElapsed += amount
            if (detonateElapsed >= FADE_OUT_SECONDS) {
                engine.removeEntity(entity)
            }
            return
        }

        // 蓄能态自超时收口：正常路径由系统脚本 detonate（ACTIVE 首帧或 unapply 补发）；
        // 舰船在蓄能期被击毁等异常路径下系统脚本不再回调，旋涡不得滞留到战斗结束
        if (elapsed >= MAX_CHARGE_SECONDS) {
            log.warn("引力旋涡蓄能态超过 ${MAX_CHARGE_SECONDS}s 未 detonate（系统脚本异常中断？），按超时路径淡出回收")
            detonate()
            return
        }

        armAngle = (armAngle + amount * ARM_SPIN_DEG_PER_SEC) % 360f

        armAcc += amount
        while (armAcc >= ARM_INTERVAL) {
            armAcc -= ARM_INTERVAL
            spawnArmParticle(entity.location, armIndex % ARM_COUNT)
            armIndex++
        }

        wispAcc += amount
        while (wispAcc >= WISP_INTERVAL) {
            wispAcc -= WISP_INTERVAL
            spawnOuterWisp(entity.location)
        }
    }

    /**
     * 螺旋臂粒子：从第 [arm] 条臂的圆周位置发射，速度 = 向心分量（略慢于直达，拖出螺旋轨迹）
     * + 与臂旋转同向的切向分量；寿命内被「吸入」核心区域。
     */
    private fun spawnArmParticle(center: Vector2f, arm: Int) {
        val angle = armAngle + arm * (360f / ARM_COUNT) + MathUtils.getRandomNumberInRange(-ARM_JITTER_DEG, ARM_JITTER_DEG)
        val from = Misc.getUnitVectorAtDegreeAngle(angle)
        from.scale(SPAWN_RADIUS)
        Vector2f.add(center, from, from)

        val life = MathUtils.getRandomNumberInRange(0.75f, 1.05f)
        val radial = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(from, center))
        val tangent = Misc.getUnitVectorAtDegreeAngle(angle + 90f)
        val vel = Vector2f(
            radial.x * (SPAWN_RADIUS / life * INWARD_SPEED_FRAC) + tangent.x * TANGENT_SPEED,
            radial.y * (SPAWN_RADIUS / life * INWARD_SPEED_FRAC) + tangent.y * TANGENT_SPEED,
        )
        engine.addSwirlyNebulaParticle(
            from, vel, MathUtils.getRandomNumberInRange(26f, 42f),
            0.5f, 0.15f, 0.55f, life, ARM_COLOR, false,
        )
    }

    /** 外层弥散粒子：更大、更淡、更长寿，提供旋涡体积感。 */
    private fun spawnOuterWisp(center: Vector2f) {
        val from = Misc.getPointAtRadius(center, WISP_RADIUS)
        val life = MathUtils.getRandomNumberInRange(1.2f, 1.6f)
        val radial = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(from, center))
        val vel = Vector2f(
            radial.x * (WISP_RADIUS / life * 0.45f),
            radial.y * (WISP_RADIUS / life * 0.45f),
        )
        engine.addSwirlyNebulaParticle(
            from, vel, MathUtils.getRandomNumberInRange(50f, 80f),
            0.5f, 0.2f, 0.6f, life, WISP_COLOR, false,
        )
    }

    override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
        val entity = entity ?: return
        if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return

        val fadeIn = (elapsed / FADE_IN_SECONDS).coerceIn(0f, 1f)
        val fadeOut = if (detonateElapsed >= 0f) {
            (1f - detonateElapsed / FADE_OUT_SECONDS).coerceIn(0f, 1f)
        } else {
            1f
        }
        val alpha = fadeIn * fadeOut * viewport.alphaMult
        if (alpha <= 0f) return

        // 充能期间核心收拢；detonate 瞬间脉冲增亮放大，随后扩散淡出
        val charge = (elapsed / CHARGE_SECONDS).coerceIn(0f, 1f)
        val expand = if (detonateElapsed >= 0f) 1f + detonateElapsed * EXPAND_RATE else 1f
        val detonateSpike = if (detonateElapsed in 0f..SPIKE_SECONDS) {
            1f + (SPIKE_SECONDS - detonateElapsed) / SPIKE_SECONDS * SPIKE_STRENGTH
        } else {
            1f
        }
        val coreRadius = (CORE_MAX_RADIUS - (CORE_MAX_RADIUS - CORE_MIN_RADIUS) * charge) * expand * detonateSpike
        val pulse = 0.85f + 0.15f * sin(elapsed * PULSE_SPEED)

        val sprite = Global.getSettings().getSprite(CORE_PATH)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        try {
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
            renderGlow(sprite, entity.location, coreRadius, CORE_COLOR, alpha * 0.5f * pulse * detonateSpike)
            renderGlow(sprite, entity.location, coreRadius * 0.55f, CORE_HOT_COLOR, alpha * 0.8f * pulse * detonateSpike)
        } finally {
            GL11.glPopAttrib()
        }
    }

    private fun renderGlow(
        sprite: com.fs.starfarer.api.graphics.SpriteAPI,
        at: Vector2f,
        radius: Float,
        color: Color,
        alpha: Float,
    ) {
        sprite.setAdditiveBlend()
        sprite.color = color
        sprite.alphaMult = alpha.coerceIn(0f, 1f)
        sprite.setSize(radius * 2f, radius * 2f)
        sprite.angle = 0f
        sprite.renderAtCenter(at.x, at.y)
    }

    companion object {
        private val log = Global.getLogger(GravityRiftVortexVfx::class.java)

        /** 核心柔光贴图：圆形径向渐变、边缘 alpha 归零、无烘焙底色。 */
        private const val CORE_PATH = "graphics/fx/hit_glow.png"

        private const val CHARGE_SECONDS = 1.0f
        private const val FADE_IN_SECONDS = 0.15f
        private const val FADE_OUT_SECONDS = 0.6f
        private const val EXPAND_RATE = 1.6f

        /** 蓄能态自超时（秒）：远超系统 chargeUp+active+down 全程，仅兜异常中断路径。 */
        private const val MAX_CHARGE_SECONDS = 10f

        /** detonate 脉冲：持续时间与强度（亮度/半径瞬时放大）。 */
        private const val SPIKE_SECONDS = 0.25f
        private const val SPIKE_STRENGTH = 0.9f

        /** 螺旋臂结构：臂数、单臂发射间隔、臂根角速度（度/秒）、圆周发射半径。 */
        private const val ARM_COUNT = 3
        private const val ARM_INTERVAL = 0.018f
        private const val ARM_SPIN_DEG_PER_SEC = 210f
        private const val SPAWN_RADIUS = 175f

        /** 臂位置随机抖动（度）、向心速度占直达速度比例、切向速度（su/s）。 */
        private const val ARM_JITTER_DEG = 8f
        private const val INWARD_SPEED_FRAC = 0.62f
        private const val TANGENT_SPEED = 190f

        /** 外层弥散粒子：发射间隔与圆周半径。 */
        private const val WISP_INTERVAL = 0.11f
        private const val WISP_RADIUS = 235f

        private const val CORE_MAX_RADIUS = 46f
        private const val CORE_MIN_RADIUS = 28f
        private const val PULSE_SPEED = 9f

        private const val RENDER_RADIUS = 500f

        private val ARM_COLOR = Color(255, 64, 56, 215)
        private val WISP_COLOR = Color(235, 50, 60, 80)
        private val CORE_COLOR = Color(255, 70, 60, 255)
        private val CORE_HOT_COLOR = Color(255, 140, 110, 255)

        /**
         * 在 [location] 生成旋涡：注册分层渲染插件并预加载贴图
         * （SSOptimizer 延迟加载下先 loadTexture，否则拿到 textureID=0 的空壳）。
         * 返回插件实例，调用方持有并在光束发射时调用 [detonate]。
         */
        fun spawn(engine: CombatEngineAPI, location: Vector2f): GravityRiftVortexVfx {
            Global.getSettings().loadTexture(CORE_PATH)
            val plugin = GravityRiftVortexVfx(engine)
            val entity = engine.addLayeredRenderingPlugin(plugin)
            if (entity == null) {
                // 插件未入场：渲染/粒子均不会回调，调用方持有的实例退化为纯计时器（无害），但必须可见
                log.error("引力旋涡 addLayeredRenderingPlugin 返回 null（location=$location），本次旋涡视觉缺失")
                return plugin
            }
            entity.location.set(location)
            return plugin
        }
    }
}
