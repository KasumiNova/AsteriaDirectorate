package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.tan

/**
 * 共用锥面冲击特效组件（计划 00-锥面冲击特效重做计划 §3 分层）：一次性 RenderEntity 树的根节点。
 *
 * 分层（全部由 coreColor/fringeColor/flashColor 派生，色温统一；各层错开几十毫秒起止，破同亮同灭）：
 * - t=0：顶点闪光（2 颗 vanilla 粒子，一闪即走）+ 顶点烟雾批 1（6 颗 nebula）+ 两枚楔块子节点 attach；
 * - t=+0.03/0.08/0.13：三道朝前扩张弧（OglEllipseRingRenderer 弧段，轴上 0.35L/0.7L/1.0L，波前接力读感）；
 * - t=+0.06：顶点烟雾批 2（4 颗 nebula，轴上 0.3L 附近，衔接顶点与扇面）。
 *
 * 根自身无后端句柄：闪光/烟雾为 vanilla 粒子、弧为渲染器自管理实例，均不依赖树存活；
 * 楔块子节点（[ConeWedgeComponent] × 2）由基类递归推进。几何常量创建期定死，
 * 每帧只读 [RenderContext.frame] 的 elapsed 按阈值表恰好触发一次（布尔标记位，幂等）。
 */
class ConeImpactVfxComponent(
    id: String,
    private val origin: Vector2f,
    private val facingDeg: Float,
    private val halfAngleDeg: Float,
    private val length: Float,
    private val duration: Float,
    private val expandSeconds: Float,
    private val fadeOutSeconds: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val flashColor: Color,
    wedgeTexturePath: String,
    wedgePatternTexturePath: String,
    textureScrollSpeed: Float,
    angularSegs: Int,
    angularJitter: FloatArray,
) : RenderEntityImpl(id) {

    /** 各层错开触发的标记位（弧 1/2/3、烟雾批 2），保证每道恰好生成一次。 */
    private val fired = BooleanArray(TRIGGER_COUNT)

    init {
        // 楔块底层（填充/体积）：contrail 云状噪声，RGB = coreColor。
        addChild(
            ConeWedgeComponent(
                id = "$id/wedge_base",
                origin = Vector2f(origin),
                facingDeg = facingDeg,
                halfAngleDeg = halfAngleDeg,
                length = length,
                duration = duration,
                expandSeconds = expandSeconds,
                fadeOutSeconds = fadeOutSeconds,
                texturePath = wedgeTexturePath,
                color = coreColor,
                alphaMul = 1f,
                scrollSpeed = textureScrollSpeed,
                vLo = CONTRAIL_V_LO,
                vHi = CONTRAIL_V_HI,
                angularSegs = angularSegs,
                angularJitter = angularJitter,
                renderOrder = ConeWedgeComponent.RENDER_ORDER_BASE,
            )
        )
        // 楔块图案层（能量/动感）：surge 混乱条纹，RGB = fringeColor，alpha 减半、滚动 ×1.7（多层错参）。
        addChild(
            ConeWedgeComponent(
                id = "$id/wedge_pattern",
                origin = Vector2f(origin),
                facingDeg = facingDeg,
                halfAngleDeg = halfAngleDeg,
                length = length,
                duration = duration,
                expandSeconds = expandSeconds,
                fadeOutSeconds = fadeOutSeconds,
                texturePath = wedgePatternTexturePath,
                color = fringeColor,
                alphaMul = PATTERN_ALPHA_MUL,
                scrollSpeed = textureScrollSpeed * PATTERN_SCROLL_MUL,
                vLo = SURGE_V_LO,
                vHi = SURGE_V_HI,
                angularSegs = angularSegs,
                angularJitter = angularJitter,
                renderOrder = ConeWedgeComponent.RENDER_ORDER_BASE + 1,
            )
        )
    }

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        spawnVertexFlash(engine)
        // 烟雾批 1（t=0，6 颗）：顶点 10su 圆内喷出，补顶点体积感。
        spawnSmokePuff(engine, origin, SMOKE_BATCH1_COUNT)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val t = ctx.frame.elapsed
        val engine = ctx.engine ?: return
        for (index in 0 until ARC_COUNT) {
            if (!fired[index] && t >= ARC_DELAYS[index]) {
                fired[index] = true
                spawnArc(engine, index)
            }
        }
        if (!fired[TRIGGER_SMOKE_BATCH2] && t >= SMOKE_BATCH2_DELAY) {
            fired[TRIGGER_SMOKE_BATCH2] = true
            // 烟雾批 2（t=+0.06，4 颗）：轴上 0.3L 附近，衔接顶点与扇面起点。
            val axis = MathUtils.getPointOnCircumference(origin, length * SMOKE_BATCH2_AXIS_FRAC, facingDeg)
            spawnSmokePuff(engine, axis, SMOKE_BATCH2_COUNT)
        }
    }

    /** 顶点闪光：一口内亮核 + 一口外扩后尘（vanilla 粒子，点缀语义）。 */
    private fun spawnVertexFlash(engine: CombatEngineAPI) {
        val flashSize = (length * 0.30f).coerceIn(30f, 220f)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize, 1.6f, 0.18f, flashColor)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize * 0.55f, 2.2f, 0.12f, coreColor)
    }

    /**
     * 顶点烟雾一批（aod7 同式 nebula，方向反过来：沿锥向锥内喷出）：
     * 方向 = facing ± halfAngle×0.8 随机，速度 = length×0.35 ±40%，半径 = clamp(length×0.05, 12, 36)。
     */
    private fun spawnSmokePuff(engine: CombatEngineAPI, around: Vector2f, count: Int) {
        val smoke = Color(fringeColor.red, fringeColor.green, fringeColor.blue, SMOKE_ALPHA)
        val baseSpeed = length * SMOKE_SPEED_MUL
        val radius = (length * SMOKE_RADIUS_MUL).coerceIn(SMOKE_RADIUS_MIN, SMOKE_RADIUS_MAX)
        repeat(count) {
            val ang = facingDeg + MathUtils.getRandomNumberInRange(-halfAngleDeg * SMOKE_SPREAD_MUL, halfAngleDeg * SMOKE_SPREAD_MUL)
            val spd = baseSpeed * MathUtils.getRandomNumberInRange(SMOKE_SPEED_JITTER_LO, SMOKE_SPEED_JITTER_HI)
            val vel = MathUtils.getPointOnCircumference(ZERO_VEL, spd, ang)
            val pos = MathUtils.getRandomPointInCircle(around, SMOKE_SCATTER_RADIUS)
            engine.addNebulaParticle(
                pos, vel, radius, SMOKE_END_SIZE_MULT,
                SMOKE_RAMP_UP_FRAC, SMOKE_FULL_BRIGHTNESS_FRAC, SMOKE_DURATION,
                smoke, true,
            )
        }
    }

    /**
     * 一道朝前扩张弧（波前截面）：轴上 [ARC_AXIS_FRACS] 比例处的椭圆弧，
     * aSide = 位置 × tan(halfAngle) × 0.9（贴合锥面轮廓），bAlong = aSide × 0.5，sweep 140° 朝前。
     */
    private fun spawnArc(engine: CombatEngineAPI, index: Int) {
        val dist = length * ARC_AXIS_FRACS[index]
        val center = MathUtils.getPointOnCircumference(origin, dist, facingDeg)
        val aSide = dist * tan(Math.toRadians(halfAngleDeg.toDouble())).toFloat() * ARC_SIDE_MUL
        OglEllipseRingRenderer.spawn(
            engine,
            OglEllipseRingRenderer.RingSpec(
                center = center,
                facing = facingDeg,
                aSideHalf = aSide,
                bAlongHalf = aSide * ARC_B_RATIO,
                duration = ARC_DURATIONS[index],
                color = Color(fringeColor.red, fringeColor.green, fringeColor.blue, ARC_ALPHAS[index]),
                lineWidthPx = ARC_LINE_WIDTHS[index],
                segments = ARC_SEGMENTS,
                expandSpeed = ARC_EXPAND_SPEEDS[index],
                arcCenterDeg = ARC_CENTER_DEG,
                arcSweepDeg = ARC_SWEEP_DEG,
            )
        )
    }

    companion object {
        /** 顶点闪光粒子速度（静止）。 */
        private val ZERO_VEL = Vector2f(0f, 0f)

        // ---- 楔块贴图 v 带（与贴图 alpha 带实测配对，计划 §2.2）----

        private const val CONTRAIL_V_LO = -0.60f
        private const val CONTRAIL_V_HI = 0.56f
        private const val SURGE_V_LO = -0.76f
        private const val SURGE_V_HI = 0.90f

        /** 图案层整体 alpha 倍率 / 滚动倍率（相对底层，MagicTrail 多层错参手法）。 */
        private const val PATTERN_ALPHA_MUL = 0.5f
        private const val PATTERN_SCROLL_MUL = 1.7f

        // ---- 扩张弧 ×3（时序错开总表：t=+0.03/0.08/0.13，波前接力）----

        private const val ARC_COUNT = 3
        private val ARC_DELAYS = floatArrayOf(0.03f, 0.08f, 0.13f)
        private val ARC_AXIS_FRACS = floatArrayOf(0.35f, 0.7f, 1.0f)
        private val ARC_ALPHAS = intArrayOf(130, 100, 70)
        private val ARC_LINE_WIDTHS = floatArrayOf(1.3f, 1.15f, 1.05f)
        private val ARC_DURATIONS = floatArrayOf(0.20f, 0.18f, 0.16f)
        private val ARC_EXPAND_SPEEDS = floatArrayOf(240f, 300f, 360f)

        /** 弧心参数角：90° = 椭圆朝 +facing 最前点（OglEllipseRingRenderer 参数域）。 */
        private const val ARC_CENTER_DEG = 90f

        /** 弧扫掠角（度）：朝前的波前截面。 */
        private const val ARC_SWEEP_DEG = 140f
        private const val ARC_SEGMENTS = 48

        /** 弧侧向半轴 = 轴上位置 × tan(halfAngle) × 本值（略收进锥面轮廓内）；沿向 = 侧向 × [ARC_B_RATIO]。 */
        private const val ARC_SIDE_MUL = 0.9f
        private const val ARC_B_RATIO = 0.5f

        // ---- 顶点烟雾（aod7 同式 nebula，两批错开：t=0 / t=+0.06）----

        private const val SMOKE_BATCH1_COUNT = 6
        private const val SMOKE_BATCH2_COUNT = 4
        private const val SMOKE_BATCH2_DELAY = 0.06f
        private const val SMOKE_BATCH2_AXIS_FRAC = 0.3f
        private const val SMOKE_ALPHA = 70
        private const val SMOKE_SPEED_MUL = 0.35f
        private const val SMOKE_SPEED_JITTER_LO = 0.6f
        private const val SMOKE_SPEED_JITTER_HI = 1.4f
        private const val SMOKE_SPREAD_MUL = 0.8f
        private const val SMOKE_SCATTER_RADIUS = 10f
        private const val SMOKE_RADIUS_MUL = 0.05f
        private const val SMOKE_RADIUS_MIN = 12f
        private const val SMOKE_RADIUS_MAX = 36f
        private const val SMOKE_END_SIZE_MULT = 1.4f
        private const val SMOKE_RAMP_UP_FRAC = 0.08f
        private const val SMOKE_FULL_BRIGHTNESS_FRAC = 0.22f
        private const val SMOKE_DURATION = 0.7f

        /** 触发标记位总数：3 道弧 + 烟雾批 2。 */
        private const val TRIGGER_COUNT = 4
        private const val TRIGGER_SMOKE_BATCH2 = 3
    }
}
