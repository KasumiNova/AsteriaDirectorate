package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 引力时流干涉器：自身与目标舰之间的时流弧线（BoxUtil [CurveEntity] 线条实体）。
 *
 * 结构（2026-10 实机反馈重做，主次分明）：
 * - **主线条 1 条**：粗、直，仅保留一道大尺度缓弧（无高频抖动）；
 *   贴图 [TEX_MAIN]（astd_trails_surge，白底 alpha 载形，节点色染紫）；
 * - **细线条 [FILAMENT_COUNT] 条**：细装饰丝，在主线条周围低幅度、低频率扭转
 *   （扰动幅度为主线条宽度的 1~2 倍量级，无锯齿乱抖）；贴图 [TEX_FILAMENT]。
 * 节点定义在局部空间（+X 为源舰 → 目标舰方向），进入 BoxUtil 变换前过
 * [BoxUtilCombatVfx.normalizeFacingDeg]；层 [CombatEngineLayers.BELOW_SHIPS_LAYER]——
 * 弧线被舰船盖住，不盖在舰船上（规格：purple/10-unique.md §1）。
 * 流动观感：贴图 UV 滚动（textureSpeed，对照 PsiHelixComponent 口径）+
 * 收敛的亮度脉动（向目标流动，亮度区间 0.8~1.0，不产生「整条线在抖」观感）。
 *
 * 贴图预载：[preloadTextures]（onApplicationLoad；SSOptimizer 延迟加载下
 * 未被数据文件引用的贴图不上传 GL，裸 getSprite 拿到 textureID=0 空壳），
 * 预载失败的路径有 WARN 日志且 attach 直接缺席（机制照常）。
 *
 * 生命周期：激活首帧 [attach]，持续期间每帧 [update] 跟随双舰位置重建节点并按
 * effectLevel 驱动整体 alpha（OUT 窗口自然淡出），持续结束/目标消亡/离场即 [dispose]
 * （快速淡出后由实体定时器自收）；低频事件级实体（每次激活 1 组，冷却 30s），
 * 不走实例池（对照 boxutil 实体池化规范的低频例外口径）。
 */
class GravTimeflowLinkVfx private constructor(
    private val source: ShipAPI,
    private val target: ShipAPI,
    private val mainArc: CurveEntity,
    private val filaments: List<CurveEntity>,
) {

    private var time = 0f
    private var dead = false

    /**
     * 每帧驱动：跟随双舰位置重建全部线条节点（局部 +X 指向目标），
     * 主线大尺度缓弧 + 细丝低频扭转 + 向目标流动的收敛亮度脉动；
     * [effectLevel] 作为整体 alpha 包络（IN 渐入 / ACTIVE 满值 / OUT 渐出）。
     */
    fun update(elapsed: Float, effectLevel: Float) {
        if (dead) return
        time += elapsed

        val from = source.location
        val to = target.location
        val dist = Misc.getDistance(from, to)
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(Misc.getAngleInDegrees(from, to))
        val alphaEnv = effectLevel.coerceIn(0f, 1f)

        updateMain(facing, dist, alphaEnv)
        for (i in filaments.indices) {
            updateFilament(filaments[i], i, facing, dist, alphaEnv)
        }
    }

    /** 收口：全部线条快速淡出（实体定时器自收，dispose 幂等）。 */
    fun dispose() {
        if (dead) return
        dead = true
        if (!mainArc.hasDelete()) mainArc.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
        for (filament in filaments) {
            if (!filament.hasDelete()) filament.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
        }
    }

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
    private fun updateMain(facing: Float, dist: Float, alphaEnv: Float) {
        if (mainArc.hasDelete()) return
        mainArc.setStateVanilla(Vector2f(source.location), facing)
        val nodes = mainArc.nodes ?: return
        val lastIdx = nodes.size - 1
        if (lastIdx <= 0) return

        val amp = min(dist * MAIN_ARC_DIST_FRACTION, MAIN_ARC_MAX)
        // 缓弧呼吸：幅度随时间缓慢收放，避免完全静止的贴线观感
        val breathe = 0.85f + 0.15f * sin(time * MAIN_BREATHE_SPEED)
        for (i in 0..lastIdx) {
            val t = i.toFloat() / lastIdx.toFloat()
            val lateral = sin(PI.toFloat() * t) * amp * breathe
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

    /** 细装饰丝：低频双正弦扭转（主波 + 低幅二次谐波），扰动幅度收敛在主线宽度 1~2 倍量级。 */
    private fun updateFilament(entity: CurveEntity, index: Int, facing: Float, dist: Float, alphaEnv: Float) {
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
            val lateral = envelope * (sway + wobble)
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

    companion object {
        private val log = Global.getLogger(GravTimeflowLinkVfx::class.java)

        /** 主线条贴图（astd_trails_surge：白底 alpha 载形，节点色染紫）。 */
        private const val TEX_MAIN = "graphics/fx/astd_trails_surge.png"

        /** 细线条贴图（laser_282_i6_r13c3 复制入 contents 的约定路径，白底 alpha 载形）。 */
        private const val TEX_FILAMENT = "graphics/fx/astd_timeflow_filament.png"

        /** preloadTextures 加载成功的贴图路径集合；attach 只使用此集合内的贴图。 */
        private val loadedPaths = HashSet<String>()

        /** 细装饰丝条数。 */
        private const val FILAMENT_COUNT = 3

        /** 每条线节点数。 */
        private const val NODE_COUNT = 24

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

        /** 贴图 UV 滚动（su/s，负值对照 PsiHelixComponent 口径：流向弧末端 = 目标舰）与平铺周期（世界单位）。 */
        private const val TEX_SPEED_MAIN = -220f
        private const val TEX_SPEED_FILAMENT = -160f
        private const val TEX_PIXELS_MAIN = 192f
        private const val TEX_PIXELS_FILAMENT = 140f

        /** 弧两端收敛区（占弧长比例）。 */
        private const val TIP_FRACTION = 0.12f

        /** 配色（透镜协议紫，对照 GravStormSystemStats 电弧/jitter 用色）。 */
        private val MAIN_DIM = Color(150, 100, 230)
        private val MAIN_BRIGHT = Color(215, 170, 255)
        private val MAIN_EMISSIVE_DIM = Color(170, 120, 240)
        private val MAIN_EMISSIVE_BRIGHT = Color(240, 220, 255)
        private val FILAMENT_DIM = Color(160, 110, 235)
        private val FILAMENT_BRIGHT = Color(230, 205, 255)

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

        /** 创建并注册全部线条实体；贴图未预载成功、实体无效或注册失败记 WARN（每场战斗每类一次）并返回 null，本次弧线视觉缺席但系统机制照常。 */
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
            val main = createArc(engine, TEX_MAIN, TEX_PIXELS_MAIN, TEX_SPEED_MAIN, glowPower = 0.4f)
            if (main == null) {
                return null
            }
            created += main
            val filaments = ArrayList<CurveEntity>(FILAMENT_COUNT)
            repeat(FILAMENT_COUNT) {
                val filament = createArc(engine, TEX_FILAMENT, TEX_PIXELS_FILAMENT, TEX_SPEED_FILAMENT, glowPower = 0.9f)
                if (filament == null) {
                    for (entity in created) entity.delete()
                    return null
                }
                created += filament
                filaments += filament
            }
            return GravTimeflowLinkVfx(source, target, main, filaments)
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
