package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 引力时流干涉器：自身与目标舰之间的时流弧线（BoxUtil [CurveEntity] 线条实体）。
 *
 * 结构（2026-10 实机反馈二轮重做）：
 * - **主线条 1 条**：粗、直，仅保留一道大尺度缓弧（无高频抖动）；
 *   贴图 [TEX_MAIN]（astd_trails_surge，白底 alpha 载形，节点色染紫）；
 * - **细线条 [FILAMENT_COUNT] 条**：细装饰丝，**以主线条缓弧为中心**低幅度、低频率扭转
 *   （细丝横向偏移 = 主线缓弧偏移 + 自身扰动，不再绕源舰→目标舰直线打转——
 *   一轮实机判例：细丝偏离主束飘向另一侧）；贴图 [TEX_FILAMENT]；
 * - **流动三角碎片**：沿光束路径持续喷散 [TriShardComponent] 三角碎片，
 *   速度方向与光束方向对齐（源舰 → 目标舰），走统一粒子池（树外持续发射用法，
 *   对照 ASTDEngineShardSprayEffect）；
 * - **星云粒子**：沿光束路径按约每 [NEBULA_SPACING]px 一颗的密度持续补充紫色星云
 *   （[BoxUtilCombatVfx.addNebulaParticle] 标准入口——原版 nebula_particles 4x4 图集随机 tile，
 *   固定无移动向量，20~40px 随机尺寸），粒子寿终即补位，星云带随光束移动。
 *
 * 节点定义在局部空间（+X 为源舰 → 目标舰方向），进入 BoxUtil 变换前过
 * [BoxUtilCombatVfx.normalizeFacingDeg]；层 [CombatEngineLayers.BELOW_SHIPS_LAYER]——
 * 弧线被舰船盖住，不盖在舰船上（规格：purple/10-unique.md §1）。
 * 流动观感：贴图 UV 滚动（textureSpeed 正值 = 流向弧末端 = 目标舰，
 * 二轮实机判例：负值实际流向源舰，与一轮注释口径相反）+
 * 收敛的亮度脉动（向目标流动，亮度区间 0.8~1.0，不产生「整条线在抖」观感）。
 *
 * 贴图预载：[preloadTextures]（onApplicationLoad；SSOptimizer 延迟加载下
 * 未被数据文件引用的贴图不上传 GL，裸 getSprite 拿到 textureID=0 空壳），
 * 预载失败的路径有 WARN 日志且 attach 直接缺席（机制照常）。
 * 粒子贴图（三角碎片/星云）由统一粒子池/BoxUtil 星云控制器自行加载，无需在此预载。
 *
 * 生命周期：激活首帧 [attach]，持续期间每帧 [update] 跟随双舰位置重建节点并按
 * effectLevel 驱动整体 alpha 与粒子喷散速率（OUT 窗口自然淡出），
 * 持续结束/目标消亡/离场即 [dispose]（线条快速淡出后由实体定时器自收；
 * 已喷出的池化粒子按自身寿命包络自然消亡）。
 * 线条为低频事件级实体（每次激活 1 组，冷却 30s），不走实例池；
 * 碎片为高频粒子走 [PooledCombatVfx] 统一池，星云走 BoxUtil 内置星云控制器。
 */
class GravTimeflowLinkVfx private constructor(
    private val engine: CombatEngineAPI,
    private val source: ShipAPI,
    private val target: ShipAPI,
    private val mainArc: CurveEntity,
    private val filaments: List<CurveEntity>,
    private val shards: TriShardComponent,
) {

    private var time = 0f
    private var dead = false

    /** 碎片喷散累积器（每秒 [SHARD_RATE_PER_SEC] × effectLevel 颗）。 */
    private var shardEmitAcc = 0f

    /** 星云粒子补充累积器（稳态密度 ≈ 每 [NEBULA_SPACING]px 一颗）。 */
    private var nebulaEmitAcc = 0f

    /**
     * 每帧驱动：跟随双舰位置重建全部线条节点（局部 +X 指向目标），
     * 主线大尺度缓弧 + 细丝以主束为中心低频扭转 + 向目标流动的收敛亮度脉动 +
     * 沿束流动碎片与星云粒子补充；
     * [effectLevel] 作为整体 alpha 与粒子速率包络（IN 渐入 / ACTIVE 满值 / OUT 渐出）。
     */
    fun update(elapsed: Float, effectLevel: Float) {
        if (dead) return
        time += elapsed

        val from = source.location
        val to = target.location
        val dist = Misc.getDistance(from, to)
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(Misc.getAngleInDegrees(from, to))
        val alphaEnv = effectLevel.coerceIn(0f, 1f)

        // 主线缓弧参数（细丝与粒子共用同一基准，保证「以主束为中心」）
        val mainAmp = min(dist * MAIN_ARC_DIST_FRACTION, MAIN_ARC_MAX)
        val breathe = 0.85f + 0.15f * sin(time * MAIN_BREATHE_SPEED)

        updateMain(facing, dist, alphaEnv, mainAmp, breathe)
        for (i in filaments.indices) {
            updateFilament(filaments[i], i, facing, dist, alphaEnv, mainAmp, breathe)
        }
        updateParticles(elapsed, from, facing, dist, alphaEnv, mainAmp, breathe)
    }

    /** 收口：全部线条快速淡出（实体定时器自收，dispose 幂等）；池化粒子停喷后自然寿终。 */
    fun dispose() {
        if (dead) return
        dead = true
        if (!mainArc.hasDelete()) mainArc.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
        for (filament in filaments) {
            if (!filament.hasDelete()) filament.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
        }
    }

    /** 主线条缓弧横向偏移（局部 +Y；t 为弧长比例 0~1）。细丝与粒子共用，保证环绕/路径以主束为中心。 */
    private fun mainLateral(t: Float, mainAmp: Float, breathe: Float): Float =
        sin(PI.toFloat() * t) * mainAmp * breathe

    /** 亮度脉动（收敛）：向目标（t=1）流动，输出 0.8~1.0 的亮度系数。 */
    private fun flowBrightness(t: Float, phase: Float): Float {
        val flowHead = frac(time * FLOW_SPEED + phase)
        var pulseDist = abs(t - flowHead)
        if (pulseDist > 0.5f) pulseDist = 1f - pulseDist
        val pulse = (1f - pulseDist / PULSE_WIDTH).coerceIn(0f, 1f)
        return 0.8f + 0.2f * pulse
    }

    /** 弧两端收敛系数（t 接近 0/1 时线性收到 0）。 */
    private fun tipEnv(t: Float): Float =
        (t / TIP_FRACTION).coerceIn(0f, 1f) * ((1f - t) / TIP_FRACTION).coerceIn(0f, 1f)

    /** 主线条：一道大尺度缓弧（单波正弦包络，幅度随链长收敛），粗直无高频抖动。 */
    private fun updateMain(facing: Float, dist: Float, alphaEnv: Float, mainAmp: Float, breathe: Float) {
        if (mainArc.hasDelete()) return
        mainArc.setStateVanilla(Vector2f(source.location), facing)
        val nodes = mainArc.nodes ?: return
        val lastIdx = nodes.size - 1
        if (lastIdx <= 0) return

        for (i in 0..lastIdx) {
            val t = i.toFloat() / lastIdx.toFloat()
            val lateral = mainLateral(t, mainAmp, breathe)
            val tip = tipEnv(t)
            val node = nodes[i]
            node.setLocation(t * dist, lateral)
            node.width = MAIN_WIDTH * (0.5f + 0.5f * tip)
            val alpha = MAIN_ALPHA * alphaEnv * tip
            node.setColor(lerpColor(MAIN_DIM, MAIN_BRIGHT, flowBrightness(t, 0f), alpha))
            node.setEmissiveColor(lerpColor(MAIN_EMISSIVE_DIM, MAIN_EMISSIVE_BRIGHT, flowBrightness(t, 0f), alpha))
        }
        mainArc.setNodeRefreshAllFromCurrentIndex()
        mainArc.submitNodes()
    }

    /** 细装饰丝：以主束缓弧为中心做低频双正弦扭转（主波 + 低幅二次谐波），扰动幅度收敛在主线宽度 1~2 倍量级。 */
    private fun updateFilament(
        entity: CurveEntity,
        index: Int,
        facing: Float,
        dist: Float,
        alphaEnv: Float,
        mainAmp: Float,
        breathe: Float,
    ) {
        if (entity.hasDelete()) return
        entity.setStateVanilla(Vector2f(source.location), facing)
        val nodes = entity.nodes ?: return
        val lastIdx = nodes.size - 1
        if (lastIdx <= 0) return

        val phase = index.toFloat() / FILAMENT_COUNT
        val seed = index * 5.77f
        val amp = min(FILAMENT_AMP, dist * FILAMENT_AMP_DIST_FRACTION)
        for (i in 0..lastIdx) {
            val t = i.toFloat() / lastIdx.toFloat()
            val envelope = sin(PI.toFloat() * t)
            val sway = sin(2f * PI.toFloat() * (t * FILAMENT_WAVES + time * FILAMENT_SWAY_SPEED + phase)) * amp
            val wobble = sin(2f * PI.toFloat() * (t * FILAMENT_WOBBLE_WAVES + time * FILAMENT_WOBBLE_SPEED + seed)) * amp * FILAMENT_WOBBLE_FRACTION
            val lateral = mainLateral(t, mainAmp, breathe) + envelope * (sway + wobble)
            val tip = tipEnv(t)
            val node = nodes[i]
            node.setLocation(t * dist, lateral)
            node.width = FILAMENT_WIDTH * (0.5f + 0.5f * tip)
            val alpha = FILAMENT_ALPHA * alphaEnv * tip
            node.setColor(lerpColor(FILAMENT_DIM, FILAMENT_BRIGHT, flowBrightness(t, phase), alpha))
            node.setEmissiveColor(lerpColor(FILAMENT_DIM, FILAMENT_BRIGHT, flowBrightness(t, phase), alpha))
        }
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
    }

    /**
     * 沿光束路径喷散粒子：
     * - 三角碎片：随机落点（t ∈ [0.08, 0.92]，叠主束缓弧偏移），速度方向与光束方向对齐（流向目标）；
     * - 星云粒子：稳态密度 ≈ 每 [NEBULA_SPACING]px 一颗（寿终即补），固定无移动向量，
     *   落点叠主束缓弧偏移 + 少量横向散布，尺寸 20~40px 随机。
     * 两者速率均随 [alphaEnv] 缩放（IN/OUT 窗口自然渐入渐出），每帧灌入统一粒子池。
     */
    private fun updateParticles(
        elapsed: Float,
        from: Vector2f,
        facing: Float,
        dist: Float,
        alphaEnv: Float,
        mainAmp: Float,
        breathe: Float,
    ) {
        if (dist < 1f) return
        val rad = Math.toRadians(facing.toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()

        shardEmitAcc += SHARD_RATE_PER_SEC * alphaEnv * elapsed
        while (shardEmitAcc >= 1f) {
            shardEmitAcc -= 1f
            val t = MathUtils.getRandomNumberInRange(0.08f, 0.92f)
            val localX = t * dist
            val localY = mainLateral(t, mainAmp, breathe)
            val pos = Vector2f(from.x + dirX * localX - dirY * localY, from.y + dirY * localX + dirX * localY)
            val speed = SHARD_SPEED * MathUtils.getRandomNumberInRange(0.85f, 1.15f)
            shards.addShard(0, pos, Vector2f(dirX * speed, dirY * speed))
        }
        shards.activatePendingBatches(engine)

        // 星云：稳态密度 = dist / SPACING 颗，按寿命折算补充速率
        val nebulaRate = dist / NEBULA_SPACING / NEBULA_LIFE_SECONDS
        nebulaEmitAcc += nebulaRate * alphaEnv * elapsed
        if (nebulaEmitAcc >= 1f) {
            // 星云控制器闲置缺陷旁路（每次连续喷放的首颗前调一次，详见 resetNebulaControllerIfIdle 文档）
            BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        }
        while (nebulaEmitAcc >= 1f) {
            nebulaEmitAcc -= 1f
            val t = MathUtils.getRandomNumberInRange(0.03f, 0.97f)
            val localX = t * dist
            val localY = mainLateral(t, mainAmp, breathe) +
                    MathUtils.getRandomNumberInRange(-NEBULA_LATERAL_SPREAD, NEBULA_LATERAL_SPREAD)
            val alpha = (MathUtils.getRandomNumberInRange(NEBULA_ALPHA_LO, NEBULA_ALPHA_HI) * alphaEnv)
                .toInt().coerceIn(0, 255)
            BoxUtilCombatVfx.addNebulaParticle(
                engine,
                Vector2f(from.x + dirX * localX - dirY * localY, from.y + dirY * localX + dirX * localY),
                ZERO_VEL,
                MathUtils.getRandomNumberInRange(NEBULA_SIZE_MIN, NEBULA_SIZE_MAX),
                NEBULA_END_SIZE_MULT,
                NEBULA_RAMP_UP,
                NEBULA_FULL,
                MathUtils.getRandomNumberInRange(NEBULA_DURATION_LO, NEBULA_DURATION_HI),
                Color(NEBULA_COLOR.red, NEBULA_COLOR.green, NEBULA_COLOR.blue, alpha),
            )
        }
    }

    companion object {
        private val log = Global.getLogger(GravTimeflowLinkVfx::class.java)

        /** 主线条贴图（astd_trails_surge：白底 alpha 载形，节点色染紫）。 */
        private const val TEX_MAIN = "graphics/fx/astd_trails_surge.png"

        /** 细线条贴图（laser_282_i6_r13c3 复制入 contents 的约定路径，白底 alpha 载形）。 */
        private const val TEX_FILAMENT = "graphics/fx/astd_timeflow_filament.png"

        /** preloadTextures 加载成功的贴图路径集合；attach 只使用此集合内的贴图。 */
        private val loadedPaths = HashSet<String>()

        /** 细装饰丝条数。 */
        private const val FILAMENT_COUNT = 6

        /** 每条线节点数。 */
        private const val NODE_COUNT = 64

        /** 常驻实体时长（秒）：生命周期由系统脚本显式驱动 dispose，不自然到期。 */
        private const val FULL_SECONDS = 1e7f

        /** dispose 快速淡出（秒）。 */
        private const val FADE_OUT_SECONDS = 0.4f

        /** 主线条：宽度（su）、基准 alpha、缓弧幅度上限（su）与随链长比例、呼吸速度（弧度/秒）。 */
        private const val MAIN_WIDTH = 16f
        private const val MAIN_ALPHA = 0.55f
        private const val MAIN_ARC_MAX = 24f
        private const val MAIN_ARC_DIST_FRACTION = 0.05f
        private const val MAIN_BREATHE_SPEED = 0.5f

        /** 细线条：宽度（su）、基准 alpha、扰动幅度（主线宽度 1~2 倍量级）与随链长比例、波形参数（低频）。 */
        private const val FILAMENT_WIDTH = 3.5f
        private const val FILAMENT_ALPHA = 0.5f
        private const val FILAMENT_AMP = 24f
        private const val FILAMENT_AMP_DIST_FRACTION = 0.12f
        private const val FILAMENT_WAVES = 1.5f
        private const val FILAMENT_SWAY_SPEED = 0.35f
        private const val FILAMENT_WOBBLE_WAVES = 3f
        private const val FILAMENT_WOBBLE_SPEED = 0.22f
        private const val FILAMENT_WOBBLE_FRACTION = 0.35f

        /** 亮度脉动流向目标的速度（全弧/秒）与脉冲宽度（占弧长比例）。 */
        private const val FLOW_SPEED = 0.8f
        private const val PULSE_WIDTH = 0.3f

        /** 贴图 UV 滚动（su/s，正值 = 流向弧末端 = 目标舰；二轮实机判例：负值实际反向）与平铺周期（世界单位）。 */
        private const val TEX_SPEED_MAIN = 220f
        private const val TEX_SPEED_FILAMENT = 160f
        private const val TEX_PIXELS_MAIN = 192f
        private const val TEX_PIXELS_FILAMENT = 140f

        /** 弧两端收敛区（占弧长比例）。 */
        private const val TIP_FRACTION = 0.12f

        /** 流动三角碎片：每秒喷散基准速率、飞行速度（su/s，方向与光束对齐流向目标）。 */
        private const val SHARD_RATE_PER_SEC = 80f
        private const val SHARD_SPEED = 350f

        /** 星云粒子：稳态空间密度（px/颗，取 10~15 中值）、单颗平均寿命（折算补充速率用，
         * 实际单颗寿命 = fadeIn + full + fadeOut 包络 ≈ 1.65~2.05s，取均值 1.85）、
         * 尺寸域（px）、alpha 域、横向散布（su）、末端尺寸倍率与寿命包络比例（原版 addNebulaParticle 口径）、总时长域（秒）。 */
        private const val NEBULA_SPACING = 12.5f
        private const val NEBULA_LIFE_SECONDS = 1.85f
        private const val NEBULA_SIZE_MIN = 20f
        private const val NEBULA_SIZE_MAX = 40f
        private const val NEBULA_ALPHA_LO = 100f
        private const val NEBULA_ALPHA_HI = 200f
        private const val NEBULA_LATERAL_SPREAD = 12f
        private const val NEBULA_END_SIZE_MULT = 1.25f
        private const val NEBULA_RAMP_UP = 0.2f
        private const val NEBULA_FULL = 0.35f
        private const val NEBULA_DURATION_LO = 1.4f
        private const val NEBULA_DURATION_HI = 2.0f

        /** 静止速度矢量（星云粒子用，避免逐颗分配）。 */
        private val ZERO_VEL = Vector2f(0f, 0f)

        /** 配色（透镜协议紫；二轮实机反馈加深饱和，弃淡紫）。 */
        private val MAIN_DIM = Color(120, 50, 220)
        private val MAIN_BRIGHT = Color(185, 105, 255)
        private val MAIN_EMISSIVE_DIM = Color(140, 60, 235)
        private val MAIN_EMISSIVE_BRIGHT = Color(205, 140, 255)
        private val FILAMENT_DIM = Color(130, 60, 225)
        private val FILAMENT_BRIGHT = Color(200, 125, 255)

        /** 三角碎片配色：核心提亮紫 / 边缘紫。 */
        private val SHARD_CORE_COLOR = Color(215, 150, 255)
        private val SHARD_FRINGE_COLOR = Color(140, 60, 235)

        /** 星云粒子配色（柔和紫，alpha 由 spawn 参数逐颗给）。 */
        private val NEBULA_COLOR = Color(150, 70, 240)

        /** 一次性 WARN 闩键（engine.customData，随战斗清理）。 */
        private const val LOG_ONCE_TEXTURE = "astd_grav_timeflow_link_log_texture_once"
        private const val LOG_ONCE_CREATE = "astd_grav_timeflow_link_log_create_once"
        private const val LOG_ONCE_INVALID = "astd_grav_timeflow_link_log_invalid_once"
        private const val LOG_ONCE_ADD = "astd_grav_timeflow_link_log_add_once"

        /**
         * 预加载弧线贴图（onApplicationLoad 调用）：SSOptimizer 延迟加载下，
         * 未被数据文件引用的贴图不会上传 GL，裸 getSprite 拿到 textureID=0 空壳。
         * 失败逐路径 WARN（禁止空 catch），attach 只使用加载成功的贴图。
         */
        @JvmStatic
        fun preloadTextures() {
            for (path in listOf(TEX_MAIN, TEX_FILAMENT)) {
                try {
                    Global.getSettings().loadTexture(path)
                    loadedPaths += path
                } catch (t: Throwable) {
                    log.warn("[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线贴图预载失败: $path", t)
                }
            }
        }

        /** 创建并注册全部线条实体与碎片组件；贴图未预载成功、实体无效或注册失败记 WARN（每场战斗每类一次）并返回 null，本次弧线视觉缺席但系统机制照常。 */
        fun attach(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI): GravTimeflowLinkVfx? {
            if (TEX_MAIN !in loadedPaths || TEX_FILAMENT !in loadedPaths) {
                warnOnce(
                    engine, LOG_ONCE_TEXTURE,
                    "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线贴图未成功预载" +
                            "（main=${TEX_MAIN in loadedPaths}，filament=${TEX_FILAMENT in loadedPaths}），弧线视觉缺席",
                )
                return null
            }
            BoxUtilCombatVfx.ensureReady(engine)

            val created = ArrayList<CurveEntity>(1 + FILAMENT_COUNT)
            val main = createArc(engine, TEX_MAIN, TEX_PIXELS_MAIN, TEX_SPEED_MAIN, glowPower = 0.8f)
            if (main == null) {
                return null
            }
            created += main
            val filaments = ArrayList<CurveEntity>(FILAMENT_COUNT)
            repeat(FILAMENT_COUNT) {
                val filament = createArc(engine, TEX_FILAMENT, TEX_PIXELS_FILAMENT, TEX_SPEED_FILAMENT, glowPower = 1.4f)
                if (filament == null) {
                    for (entity in created) entity.delete()
                    return null
                }
                created += filament
                filaments += filament
            }

            // 流动三角碎片组件（树外持续发射用法；尺寸基准 80 → 边长 8su 档，域见 spec）
            val shards = TriShardComponent(
                "astd_grav_timeflow_shards/${source.id}",
                80f,
                SHARD_CORE_COLOR,
                SHARD_FRINGE_COLOR,
                TriShardSpec(
                    batchCount = 1,
                    layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
                    sizeMul = 0.1f,
                    sizeMin = 5f,
                    sizeMax = 12f,
                    spinMin = 60f,
                    spinMax = 240f,
                    alphaLo = 130,
                    alphaHi = 200,
                    timerFullLo = 0.35f,
                    timerFullHi = 0.7f,
                    timerFadeOut = 0.45f,
                ),
            )
            return GravTimeflowLinkVfx(engine, source, target, main, filaments, shards)
        }

        /** 单条线实体：贴图载形 + 节点色染紫（对照 PsiHelixComponent 的 CurveEntity 配置口径）。 */
        private fun createArc(
            engine: CombatEngineAPI,
            texturePath: String,
            texturePixels: Float,
            textureSpeed: Float,
            glowPower: Float,
        ): CurveEntity? {
            val entity = try {
                CurveEntity()
            } catch (t: Throwable) {
                warnOnce(engine, LOG_ONCE_CREATE, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线建实体失败（${t.javaClass.simpleName}），弧线视觉缺席", t)
                return null
            }
            if (!entity.isValid) {
                warnOnce(engine, LOG_ONCE_INVALID, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线实体无效（GL43/curve shader 不可用），弧线视觉缺席")
                entity.delete()
                return null
            }
            entity.isGlobalUV = true
            entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
            entity.setAdditiveBlend()
            val sprite = Global.getSettings().getSprite(texturePath)
            entity.materialData.setDiffuse(sprite)
            entity.materialData.setEmissive(sprite)
            entity.materialData.alphaToEmissive = 0f
            entity.materialData.isColorToEmissive = 0f
            entity.materialData.glowPower = glowPower
            entity.materialData.isIgnoreIllumination = true
            entity.texturePixels = texturePixels
            entity.textureSpeed = textureSpeed
            entity.setGlobalTimer(0f, FULL_SECONDS, 0f)

            val nodes = ArrayList<NodeData>(NODE_COUNT)
            repeat(NODE_COUNT) {
                val node = NodeData(Vector2f(0f, 0f))
                node.setWidth(MAIN_WIDTH)
                node.setColor(MAIN_DIM)
                node.setEmissiveColor(MAIN_EMISSIVE_DIM)
                nodes += node
            }
            entity.nodes = nodes
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                warnOnce(engine, LOG_ONCE_ADD, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线 addEntity 失败（state=$state），弧线视觉缺席")
                entity.delete()
                return null
            }
            return entity
        }

        private fun frac(x: Float): Float = x - x.toInt() + if (x < 0f) 1f else 0f

        private fun lerpColor(a: Color, b: Color, t: Float, alpha: Float): Color {
            val cl = t.coerceIn(0f, 1f)
            return Color(
                (a.red + (b.red - a.red) * cl).toInt(),
                (a.green + (b.green - a.green) * cl).toInt(),
                (a.blue + (b.blue - a.blue) * cl).toInt(),
                (alpha.coerceIn(0f, 1f) * 255f).toInt(),
            )
        }

        /** 一次性 WARN（engine.customData 闩，每场战斗每类失败一次，防逐帧刷屏；对齐 BoxUtilCombatVfx 先例口径）。 */
        private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
            if (engine.customData[key] == true) return
            engine.customData[key] = true
            if (t == null) log.warn(message) else log.warn(message, t)
        }
    }
}
