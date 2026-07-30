package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.combat.effect.generic.ImpactStrikeFx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.tan

/**
 * 共用锥面冲击特效组件（计划 00-锥面冲击特效重做计划 §10 v2 修订 → §10.7 v2.2）：一次性 RenderEntity 树的根节点。
 *
 * v2 定案（2026-07-31 实机评审）：锥面主体为 AOD7 开火特效配方（多道椭圆弧 + 扭曲 + 烟雾 + 闪光）
 * 的锥化变体。v2.1（同日二次反馈）补上 AOD7 **命中**配方的招牌层——spray 刺束簇（复用 [ImpactStrikeFx]）。
 * v2.2（同日三次反馈）：弧环加粗到 10px+ 并上逐顶点 alpha 包络（两端渐隐，治硬边收尾）；
 * 星云烟雾整体换成随机旋转的三角碎片（[ConeShardComponent] 子节点）。
 * 分层（全部由 coreColor/fringeColor/flashColor 派生，色温统一；各层错峰起止，破同亮同灭）：
 * - t=0：顶点闪光（收敛版 2 颗 vanilla 粒子）+ DistortionEntity 扭曲 + 锥化刺束簇 + 碎片顶点批（6 颗）；
 * - t=+0.03/0.07/0.11/0.15：四道朝前弧段环（轴上 0.3/0.5/0.7/0.9L，0.04s 接力，多层锥状壳体读感）；
 * - t=+0.05：碎片锥内批（8 颗，轴向 0.2~0.7L、角向 ±halfAngle×0.7 内随机，扇形覆盖读感）；
 * - t=+0.10：碎片锥缘批（4 颗，0.8~1.0L）。
 *
 * 根自身无后端常驻句柄：闪光/刺束为 vanilla 粒子与 BoxUtil 实体、弧为渲染器自管理实例、扭曲为
 * BoxUtil 自管理实体，均不依赖树存活；碎片由子节点 [ConeShardComponent] 持有并逐帧积分（树寿命
 * 因此不得短于碎片最长期，见 ConeImpactVfx.spawn 的 TTL 下限）。几何常量创建期定死，
 * 每帧只读 [RenderContext.frame] 的 elapsed 按阈值表恰好触发一次（布尔标记位，幂等）。
 */
class ConeImpactVfxComponent(
    id: String,
    private val origin: Vector2f,
    private val facingDeg: Float,
    private val halfAngleDeg: Float,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val flashColor: Color,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(ConeImpactVfxComponent::class.java)

    /** 三角碎片子节点（attach 时随树挂上；internal 供单测断言三批累计颗数）。 */
    internal val shardComponent = ConeShardComponent("$id/shards", length, coreColor, fringeColor)

    /** 各层错峰触发的标记位（弧 ×4 + 碎片锥内/锥缘批），保证每道恰好生成一次。 */
    private val fired = BooleanArray(TRIGGER_COUNT)

    init {
        addChild(shardComponent)
    }

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        spawnVertexFlash(engine)
        spawnDistortion(engine)
        spawnStrikeSpray(engine)
        // 碎片顶点批（t=0，6 颗）：顶点 10su 圆内喷出，补顶点体积感。
        spawnVertexShards(SHARD_BATCH1_COUNT)
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
        if (!fired[TRIGGER_SHARD_BATCH2] && t >= SHARD_BATCH2_DELAY) {
            fired[TRIGGER_SHARD_BATCH2] = true
            // 碎片锥内批（t=+0.05，8 颗）：轴向 0.2~0.7L 随机、角向 ±halfAngle×0.7 内随机，扇形覆盖读感。
            spawnConeShards(SHARD_BATCH2_COUNT, SHARD_BATCH2_AXIS_LO, SHARD_BATCH2_AXIS_HI)
        }
        if (!fired[TRIGGER_SHARD_BATCH3] && t >= SHARD_BATCH3_DELAY) {
            fired[TRIGGER_SHARD_BATCH3] = true
            // 碎片锥缘批（t=+0.10，4 颗）：0.8~1.0L，补锥缘体积。
            spawnConeShards(SHARD_BATCH3_COUNT, SHARD_BATCH3_AXIS_LO, SHARD_BATCH3_AXIS_HI)
        }
    }

    /** 顶点闪光（v2 收敛版）：尺寸 0.30→0.22、亮度与核心 alpha 下调，治「太曝」。 */
    private fun spawnVertexFlash(engine: CombatEngineAPI) {
        val flashSize = (length * FLASH_SIZE_MUL).coerceIn(FLASH_SIZE_MIN, FLASH_SIZE_MAX)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize, FLASH_BRIGHTNESS, FLASH_DURATION, flashColor)
        val core = Color(
            coreColor.red, coreColor.green, coreColor.blue,
            (coreColor.alpha * FLASH_CORE_ALPHA_MUL).toInt().coerceIn(0, 255),
        )
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize * FLASH_CORE_SIZE_MUL, FLASH_CORE_BRIGHTNESS, FLASH_CORE_DURATION, core)
    }

    /**
     * 顶点扭曲环（aod7 参数族：0.03/0.05/0.18 定时器、size 16/52/96、powerFull 0.34）。
     * 失败只缺席本层（闪光/碎片/弧照常），WARN 带异常/返回码——非静默兜底。
     */
    private fun spawnDistortion(engine: CombatEngineAPI) {
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            val e = DistortionEntity()
            e.setGlobalTimer(DISTORTION_FADE_IN, DISTORTION_FULL, DISTORTION_FADE_OUT)
            e.setInnerFull(0.30f, 0.30f)
            e.setInnerHardness(0.75f)
            e.setRingHardness(0.50f)
            e.setSizeIn(DISTORTION_SIZE_IN, DISTORTION_SIZE_IN)
            e.setSizeFull(DISTORTION_SIZE_FULL, DISTORTION_SIZE_FULL)
            e.setSizeOut(DISTORTION_SIZE_OUT, DISTORTION_SIZE_OUT)
            e.setPowerIn(0f)
            e.setPowerFull(DISTORTION_POWER_FULL)
            e.setPowerOut(0f)
            e.setLocation(Vector2f(origin))
            val result = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (result != 0) {
                log.warn("锥面冲击特效 DistortionEntity 注册失败（addEntity 返回 $result，id=$id），扭曲层缺席（闪光/碎片/弧照常）")
            }
        } catch (t: Throwable) {
            log.warn("锥面冲击特效 DistortionEntity 生成异常（id=$id），扭曲层缺席（闪光/碎片/弧照常）", t)
        }
    }

    /**
     * 锥化刺束簇（v2.1 补层，AOD7 命中特效招牌层，复用 [ImpactStrikeFx] 不新写渲染）：
     * 一簇沿锥轴朝外放射的细长刺条——「多条锥状体」主力读感。张角贴合锥角扇形
     * （halfAngle×2×0.8，封顶 65°：贯星 25° 半角 → 40°，敌版 80° 半角 → 顶到 65°）；
     * 刺长按锥长比例（0.30~0.70L，锥内放射）；其余锚 aod7 轻量档（比 aod7 的 7+3 略密，锥面更大）。
     */
    private fun spawnStrikeSpray(engine: CombatEngineAPI) {
        ImpactStrikeFx.spawnImpactSpray(
            engine = engine,
            point = origin,
            facing = facingDeg,
            coreColor = coreColor,
            fringeColor = fringeColor,
            baseRaysMin = SPRAY_RAYS_MIN,
            baseRaysExtra = SPRAY_RAYS_EXTRA,
            arc = (halfAngleDeg * 2f * SPRAY_ARC_MUL).coerceAtMost(SPRAY_ARC_MAX),
            lengthMin = length * SPRAY_LENGTH_MIN_MUL,
            lengthMax = length * SPRAY_LENGTH_MAX_MUL,
            widthMin = SPRAY_WIDTH_MIN,
            widthMax = SPRAY_WIDTH_MAX,
            fullMin = SPRAY_FULL_MIN,
            fullMax = SPRAY_FULL_MAX,
            fadeOutMin = SPRAY_FADE_OUT_MIN,
            fadeOutMax = SPRAY_FADE_OUT_MAX,
            speedMin = SPRAY_SPEED_MIN,
            speedMax = SPRAY_SPEED_MAX,
            impactScale = SPRAY_IMPACT_SCALE,
            introRampSeconds = SPRAY_INTRO_RAMP_SECONDS,
        )
    }

    /** 碎片顶点批：位置取顶点 10su 圆内随机点。 */
    private fun spawnVertexShards(count: Int) {
        repeat(count) {
            shardComponent.addShard(
                MathUtils.getRandomPointInCircle(origin, SHARD_VERTEX_SCATTER_RADIUS),
                shardVelocity(),
            )
        }
    }

    /** 碎片锥内/锥缘批：轴向 [axisFracLo, axisFracHi]×length 随机、角向 ±halfAngle×0.7 内随机。 */
    private fun spawnConeShards(count: Int, axisFracLo: Float, axisFracHi: Float) {
        repeat(count) {
            val dist = length * MathUtils.getRandomNumberInRange(axisFracLo, axisFracHi)
            val posAng = facingDeg + MathUtils.getRandomNumberInRange(-halfAngleDeg * SHARD_CONE_SPREAD_MUL, halfAngleDeg * SHARD_CONE_SPREAD_MUL)
            shardComponent.addShard(
                MathUtils.getPointOnCircumference(origin, dist, posAng),
                shardVelocity(),
            )
        }
    }

    /** 碎片初速（沿用 v2 烟雾模型）：方向 = facing ± halfAngle×0.8 随机、速率 = length×0.35 ±40%。 */
    private fun shardVelocity(): Vector2f {
        val velAng = facingDeg + MathUtils.getRandomNumberInRange(-halfAngleDeg * SHARD_VEL_SPREAD_MUL, halfAngleDeg * SHARD_VEL_SPREAD_MUL)
        val speed = length * SHARD_SPEED_MUL * MathUtils.getRandomNumberInRange(SHARD_SPEED_JITTER_LO, SHARD_SPEED_JITTER_HI)
        return MathUtils.getPointOnCircumference(ZERO_VEL, speed, velAng)
    }

    /**
     * 一道朝前弧段环（波前壳体截面）：轴上 [ARC_AXIS_FRACS] 比例处的椭圆弧，
     * aSide = 位置 × tan(halfAngle) × 0.85（贴合锥面轮廓），bAlong = aSide × 0.5，sweep 130° 朝前；
     * v2.2 起线宽 ≥10px 并开逐顶点 alpha 包络（峰值偏前 0.65，两端渐隐治硬边）。
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
                arcAlphaPeakPos = ARC_ALPHA_PEAK_POS,
            )
        )
    }

    companion object {
        /** 顶点闪光粒子速度（静止）。 */
        private val ZERO_VEL = Vector2f(0f, 0f)

        // ---- 顶点闪光（v2 收敛版）----

        private const val FLASH_SIZE_MUL = 0.22f
        private const val FLASH_SIZE_MIN = 24f
        private const val FLASH_SIZE_MAX = 180f
        private const val FLASH_BRIGHTNESS = 1.2f
        private const val FLASH_DURATION = 0.16f
        private const val FLASH_CORE_SIZE_MUL = 0.5f
        private const val FLASH_CORE_BRIGHTNESS = 1.5f
        private const val FLASH_CORE_ALPHA_MUL = 0.8f
        private const val FLASH_CORE_DURATION = 0.11f

        // ---- 顶点扭曲环（aod7 参数族）----

        private const val DISTORTION_FADE_IN = 0.03f
        private const val DISTORTION_FULL = 0.05f
        private const val DISTORTION_FADE_OUT = 0.18f
        private const val DISTORTION_SIZE_IN = 16f
        private const val DISTORTION_SIZE_FULL = 52f
        private const val DISTORTION_SIZE_OUT = 96f
        private const val DISTORTION_POWER_FULL = 0.34f

        // ---- 锥化刺束簇（v2.1，aod7 命中特效轻量档加密的锥化变体）----

        private const val SPRAY_RAYS_MIN = 9
        private const val SPRAY_RAYS_EXTRA = 4

        /** 刺束张角 = halfAngle×2×本值，封顶 [SPRAY_ARC_MAX]（贴合锥角扇形）。 */
        private const val SPRAY_ARC_MUL = 0.8f
        private const val SPRAY_ARC_MAX = 65f

        /** 刺长占锥长比例区间（锥内放射，不越界太多）。 */
        private const val SPRAY_LENGTH_MIN_MUL = 0.30f
        private const val SPRAY_LENGTH_MAX_MUL = 0.70f
        private const val SPRAY_WIDTH_MIN = 7.5f
        private const val SPRAY_WIDTH_MAX = 15f
        private const val SPRAY_FULL_MIN = 0.05f
        private const val SPRAY_FULL_MAX = 0.10f
        private const val SPRAY_FADE_OUT_MIN = 0.30f
        private const val SPRAY_FADE_OUT_MAX = 0.52f
        private const val SPRAY_SPEED_MIN = 220f
        private const val SPRAY_SPEED_MAX = 520f
        private const val SPRAY_IMPACT_SCALE = 0.85f
        private const val SPRAY_INTRO_RAMP_SECONDS = 0.05f

        // ---- 扩张弧 ×4（0.04s 接力：t=+0.03/0.07/0.11/0.15，多层锥状壳体读感）----

        private const val ARC_COUNT = 4
        private val ARC_DELAYS = floatArrayOf(0.03f, 0.07f, 0.11f, 0.15f)
        private val ARC_AXIS_FRACS = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f)
        private val ARC_ALPHAS = intArrayOf(120, 95, 75, 55)

        /** v2.2 起线宽 ≥10px（用户：「至少需要 10px」）；外层 2× 线圈超驱动 glLineWidth 上限会被驱动自然 clamp。 */
        private val ARC_LINE_WIDTHS = floatArrayOf(12f, 11f, 10f, 10f)
        private val ARC_DURATIONS = floatArrayOf(0.22f, 0.20f, 0.18f, 0.16f)
        private val ARC_EXPAND_SPEEDS = floatArrayOf(260f, 287f, 313f, 340f)

        /** 弧心参数角：90° = 椭圆朝 +facing 最前点（OglEllipseRingRenderer 参数域）。 */
        private const val ARC_CENTER_DEG = 90f

        /** 弧扫掠角（度）：朝前的波前截面。 */
        private const val ARC_SWEEP_DEG = 130f
        private const val ARC_SEGMENTS = 48

        /** 弧段逐顶点 alpha 包络峰值位置（v2.2：峰值偏前，读感「沿弧逐渐升高」，两端渐隐）。 */
        private const val ARC_ALPHA_PEAK_POS = 0.65f

        /** 弧侧向半轴 = 轴上位置 × tan(halfAngle) × 本值（略收进锥面轮廓内）；沿向 = 侧向 × [ARC_B_RATIO]。 */
        private const val ARC_SIDE_MUL = 0.85f
        private const val ARC_B_RATIO = 0.5f

        // ---- 三角碎片（v2.2 替换星云；三批错峰与散布模型不变：t=0 / +0.05 / +0.10）----

        private const val SHARD_BATCH1_COUNT = 6
        private const val SHARD_BATCH2_COUNT = 8
        private const val SHARD_BATCH3_COUNT = 4
        private const val SHARD_BATCH2_DELAY = 0.05f
        private const val SHARD_BATCH3_DELAY = 0.10f
        private const val SHARD_BATCH2_AXIS_LO = 0.2f
        private const val SHARD_BATCH2_AXIS_HI = 0.7f
        private const val SHARD_BATCH3_AXIS_LO = 0.8f
        private const val SHARD_BATCH3_AXIS_HI = 1.0f
        private const val SHARD_VERTEX_SCATTER_RADIUS = 10f

        /** 位置角向散布（±halfAngle×本值）；速度方向散布（±halfAngle×本值）。 */
        private const val SHARD_CONE_SPREAD_MUL = 0.7f
        private const val SHARD_VEL_SPREAD_MUL = 0.8f
        private const val SHARD_SPEED_MUL = 0.35f
        private const val SHARD_SPEED_JITTER_LO = 0.6f
        private const val SHARD_SPEED_JITTER_HI = 1.4f

        /** 触发标记位总数：4 道弧 + 碎片锥内批 + 碎片锥缘批。 */
        private const val TRIGGER_COUNT = 6
        private const val TRIGGER_SHARD_BATCH2 = 4
        private const val TRIGGER_SHARD_BATCH3 = 5
    }
}
