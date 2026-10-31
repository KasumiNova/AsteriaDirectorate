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

class GravTimeflowLinkVfx private constructor(
    private val engine: CombatEngineAPI,
    private val source: ShipAPI,
    private val target: ShipAPI,
    private val mainArc: CurveEntity,
    private val filaments: List<CurveEntity>,
    private val shards: TriShardComponent,
    private val theme: Theme,
    private val fadeOutSeconds: Float,
) {

    private var time = 0f
    private var dead = false

    private var shardEmitAcc = 0f

    private var nebulaEmitAcc = 0f

    /**
     * @param intensity 传输/作用强度 0~1：调制亮度（下限 30%）、束体粗细（下限 60%）与摆动/jitter 幅度（全比例）。
     */
    fun update(elapsed: Float, effectLevel: Float, intensity: Float = 1f) {
        if (dead) return
        time += elapsed

        val from = source.location
        val to = target.location
        val dist = Misc.getDistance(from, to)
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(Misc.getAngleInDegrees(from, to))
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        val brightnessEnv = effectLevel.coerceIn(0f, 1f) * (0.3f + 0.7f * clampedIntensity)
        val widthScale = theme.sizeScale * (0.6f + 0.4f * clampedIntensity)
        val jitterScale = theme.jitterScale * clampedIntensity

        val mainAmp = min(dist * MAIN_ARC_DIST_FRACTION, MAIN_ARC_MAX) * jitterScale
        val breathe = 0.85f + 0.15f * sin(time * MAIN_BREATHE_SPEED)

        updateMain(facing, dist, brightnessEnv, widthScale, mainAmp, breathe)
        for (i in filaments.indices) {
            updateFilament(filaments[i], i, facing, dist, brightnessEnv, widthScale, mainAmp, breathe, jitterScale)
        }
        updateParticles(elapsed, from, facing, dist, brightnessEnv, mainAmp, breathe)
    }

    fun dispose() {
        if (dead) return
        dead = true
        if (!mainArc.hasDelete()) mainArc.setGlobalTimer(0f, 0f, fadeOutSeconds)
        for (filament in filaments) {
            if (!filament.hasDelete()) filament.setGlobalTimer(0f, 0f, fadeOutSeconds)
        }
    }

    private fun mainLateral(t: Float, mainAmp: Float, breathe: Float): Float =
        sin(PI.toFloat() * t) * mainAmp * breathe

    private fun flowBrightness(t: Float, phase: Float): Float {
        val flowHead = frac(time * FLOW_SPEED + phase)
        var pulseDist = abs(t - flowHead)
        if (pulseDist > 0.5f) pulseDist = 1f - pulseDist
        val pulse = (1f - pulseDist / PULSE_WIDTH).coerceIn(0f, 1f)
        return 0.8f + 0.2f * pulse
    }

    private fun tipEnv(t: Float): Float =
        (t / TIP_FRACTION).coerceIn(0f, 1f) * ((1f - t) / TIP_FRACTION).coerceIn(0f, 1f)

    private fun updateMain(facing: Float, dist: Float, alphaEnv: Float, widthScale: Float, mainAmp: Float, breathe: Float) {
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
            node.width = MAIN_WIDTH * widthScale * (0.5f + 0.5f * tip)
            val alpha = MAIN_ALPHA * alphaEnv * tip
            node.setColor(lerpColor(theme.mainDim, theme.mainBright, flowBrightness(t, 0f), alpha))
            node.setEmissiveColor(lerpColor(theme.mainEmissiveDim, theme.mainEmissiveBright, flowBrightness(t, 0f), alpha))
        }
        mainArc.setNodeRefreshAllFromCurrentIndex()
        mainArc.submitNodes()
    }

    private fun updateFilament(
        entity: CurveEntity,
        index: Int,
        facing: Float,
        dist: Float,
        alphaEnv: Float,
        widthScale: Float,
        mainAmp: Float,
        breathe: Float,
        jitterScale: Float,
    ) {
        if (entity.hasDelete()) return
        entity.setStateVanilla(Vector2f(source.location), facing)
        val nodes = entity.nodes ?: return
        val lastIdx = nodes.size - 1
        if (lastIdx <= 0) return

        val phase = index.toFloat() / FILAMENT_COUNT
        val seed = index * 5.77f
        val amp = min(FILAMENT_AMP, dist * FILAMENT_AMP_DIST_FRACTION) * jitterScale
        for (i in 0..lastIdx) {
            val t = i.toFloat() / lastIdx.toFloat()
            val envelope = sin(PI.toFloat() * t)
            val sway = sin(2f * PI.toFloat() * (t * FILAMENT_WAVES + time * FILAMENT_SWAY_SPEED + phase)) * amp
            val wobble = sin(2f * PI.toFloat() * (t * FILAMENT_WOBBLE_WAVES + time * FILAMENT_WOBBLE_SPEED + seed)) * amp * FILAMENT_WOBBLE_FRACTION
            val lateral = mainLateral(t, mainAmp, breathe) + envelope * (sway + wobble)
            val tip = tipEnv(t)
            val node = nodes[i]
            node.setLocation(t * dist, lateral)
            node.width = FILAMENT_WIDTH * widthScale * (0.5f + 0.5f * tip)
            val alpha = FILAMENT_ALPHA * alphaEnv * tip
            node.setColor(lerpColor(theme.filamentDim, theme.filamentBright, flowBrightness(t, phase), alpha))
            node.setEmissiveColor(lerpColor(theme.filamentDim, theme.filamentBright, flowBrightness(t, phase), alpha))
        }
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
    }

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

        val nebulaRate = dist / NEBULA_SPACING / NEBULA_LIFE_SECONDS
        nebulaEmitAcc += nebulaRate * alphaEnv * elapsed
        if (nebulaEmitAcc >= 1f) {
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
                Color(theme.nebula.red, theme.nebula.green, theme.nebula.blue, alpha),
            )
        }
    }

    companion object {
        private val log = Global.getLogger(GravTimeflowLinkVfx::class.java)

        private const val TEX_MAIN = "graphics/fx/astd_trails_surge.png"

        private const val TEX_FILAMENT = "graphics/fx/astd_timeflow_filament.png"

        private val loadedPaths = HashSet<String>()

        /**
         * 链路主题：颜色组与整体尺寸/摆动缩放。
         *
         * @property sizeScale 束体粗细缩放（辐能链接网络为 0.67，即时流干涉器的 2/3）
         * @property jitterScale 弧线摆动/jitter 幅度缩放
         */
        class Theme(
            val mainDim: Color,
            val mainBright: Color,
            val mainEmissiveDim: Color,
            val mainEmissiveBright: Color,
            val filamentDim: Color,
            val filamentBright: Color,
            val shardCore: Color,
            val shardFringe: Color,
            val nebula: Color,
            val sizeScale: Float = 1f,
            val jitterScale: Float = 1f,
        )

        /** 引力时流干涉器：紫色主题。 */
        val TIMEFLOW = Theme(
            mainDim = Color(120, 50, 220),
            mainBright = Color(185, 105, 255),
            mainEmissiveDim = Color(140, 60, 235),
            mainEmissiveBright = Color(205, 140, 255),
            filamentDim = Color(130, 60, 225),
            filamentBright = Color(200, 125, 255),
            shardCore = Color(215, 150, 255),
            shardFringe = Color(140, 60, 235),
            nebula = Color(150, 70, 240),
        )

        /** 辐能链接网络：蓝色主题，束体大小与 jitter 强度为时流干涉器的 2/3。 */
        val FLUX_LINK = Theme(
            mainDim = Color(40, 80, 220),
            mainBright = Color(90, 160, 255),
            mainEmissiveDim = Color(50, 100, 235),
            mainEmissiveBright = Color(120, 185, 255),
            filamentDim = Color(45, 90, 230),
            filamentBright = Color(110, 175, 255),
            shardCore = Color(150, 205, 255),
            shardFringe = Color(50, 100, 235),
            nebula = Color(60, 110, 240),
            sizeScale = 0.67f,
            jitterScale = 0.67f,
        )

        private const val FILAMENT_COUNT = 6

        private const val NODE_COUNT = 64

        private const val FULL_SECONDS = 1e7f

        private const val FADE_OUT_SECONDS = 0.4f

        private const val MAIN_WIDTH = 16f
        private const val MAIN_ALPHA = 0.55f
        private const val MAIN_ARC_MAX = 24f
        private const val MAIN_ARC_DIST_FRACTION = 0.05f
        private const val MAIN_BREATHE_SPEED = 0.5f

        private const val FILAMENT_WIDTH = 3.5f
        private const val FILAMENT_ALPHA = 0.5f
        private const val FILAMENT_AMP = 24f
        private const val FILAMENT_AMP_DIST_FRACTION = 0.12f
        private const val FILAMENT_WAVES = 1.5f
        private const val FILAMENT_SWAY_SPEED = 0.35f
        private const val FILAMENT_WOBBLE_WAVES = 3f
        private const val FILAMENT_WOBBLE_SPEED = 0.22f
        private const val FILAMENT_WOBBLE_FRACTION = 0.35f

        private const val FLOW_SPEED = 0.8f
        private const val PULSE_WIDTH = 0.3f

        private const val TEX_SPEED_MAIN = 220f
        private const val TEX_SPEED_FILAMENT = 160f
        private const val TEX_PIXELS_MAIN = 192f
        private const val TEX_PIXELS_FILAMENT = 140f

        private const val TIP_FRACTION = 0.12f

        private const val SHARD_RATE_PER_SEC = 80f
        private const val SHARD_SPEED = 350f

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

        private val ZERO_VEL = Vector2f(0f, 0f)

        private const val LOG_ONCE_TEXTURE = "_log_texture_once"
        private const val LOG_ONCE_CREATE = "_log_create_once"
        private const val LOG_ONCE_INVALID = "_log_invalid_once"
        private const val LOG_ONCE_ADD = "_log_add_once"

        // 未被数据文件引用的贴图必须在 onApplicationLoad 预载，否则 SSOptimizer 下 getSprite 可能返回 textureID=0 的实例。
        @JvmStatic
        fun preloadTextures() {
            for (path in listOf(TEX_MAIN, TEX_FILAMENT)) {
                try {
                    Global.getSettings().loadTexture(path)
                    loadedPaths += path
                } catch (t: Throwable) {
                    log.warn("[ASTD] 链路弧线贴图预载失败: $path", t)
                }
            }
        }

    /**
     * 挂载一条源舰到目标舰的链路弧。
     *
     * @param theme 颜色/尺寸主题，默认 [TIMEFLOW]
     * @param logTag 日志与 warn-once 键前缀（一般为调用方系统 id）
     * @param fadeIn 弧线淡入时间（秒）；默认瞬时出现（时流干涉器语义）
     * @param fadeOut 弧线淡出时间（秒）；[dispose] 后按此时长收束
     */
    fun attach(
        engine: CombatEngineAPI,
        source: ShipAPI,
        target: ShipAPI,
        theme: Theme = TIMEFLOW,
        logTag: String = GravTimeflowTuning.SYSTEM_ID,
        fadeIn: Float = 0f,
        fadeOut: Float = FADE_OUT_SECONDS,
    ): GravTimeflowLinkVfx? {
            if (TEX_MAIN !in loadedPaths || TEX_FILAMENT !in loadedPaths) {
                warnOnce(
                    engine, "$logTag$LOG_ONCE_TEXTURE",
                    "[ASTD] $logTag 链路弧线贴图未成功预载" +
                            "（main=${TEX_MAIN in loadedPaths}，filament=${TEX_FILAMENT in loadedPaths}），弧线视觉缺席",
                )
                return null
            }
            BoxUtilCombatVfx.ensureReady(engine)

            val created = ArrayList<CurveEntity>(1 + FILAMENT_COUNT)
            val main = createArc(engine, logTag, theme, TEX_MAIN, TEX_PIXELS_MAIN, TEX_SPEED_MAIN, glowPower = 0.8f, fadeIn)
            if (main == null) {
                return null
            }
            created += main
            val filaments = ArrayList<CurveEntity>(FILAMENT_COUNT)
            repeat(FILAMENT_COUNT) {
                val filament = createArc(engine, logTag, theme, TEX_FILAMENT, TEX_PIXELS_FILAMENT, TEX_SPEED_FILAMENT, glowPower = 1.4f, fadeIn)
                if (filament == null) {
                    for (entity in created) entity.delete()
                    return null
                }
                created += filament
                filaments += filament
            }

            val shards = TriShardComponent(
                "${logTag}_shards/${source.id}",
                80f,
                theme.shardCore,
                theme.shardFringe,
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
            return GravTimeflowLinkVfx(engine, source, target, main, filaments, shards, theme, fadeOut)
        }

        private fun createArc(
            engine: CombatEngineAPI,
            logTag: String,
            theme: Theme,
            texturePath: String,
            texturePixels: Float,
            textureSpeed: Float,
            glowPower: Float,
            fadeIn: Float,
        ): CurveEntity? {
            val entity = try {
                CurveEntity()
            } catch (t: Throwable) {
                warnOnce(engine, "$logTag$LOG_ONCE_CREATE", "[ASTD] $logTag 链路弧线建实体失败（${t.javaClass.simpleName}），弧线视觉缺席", t)
                return null
            }
            if (!entity.isValid) {
                warnOnce(engine, "$logTag$LOG_ONCE_INVALID", "[ASTD] $logTag 链路弧线实体无效（GL43/curve shader 不可用），弧线视觉缺席")
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
            entity.setGlobalTimer(fadeIn, FULL_SECONDS, 0f)

            val nodes = ArrayList<NodeData>(NODE_COUNT)
            repeat(NODE_COUNT) {
                val node = NodeData(Vector2f(0f, 0f))
                node.setWidth(MAIN_WIDTH)
                node.setColor(theme.mainDim)
                node.setEmissiveColor(theme.mainEmissiveDim)
                nodes += node
            }
            entity.nodes = nodes
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                warnOnce(engine, "$logTag$LOG_ONCE_ADD", "[ASTD] $logTag 链路弧线 addEntity 失败（state=$state），弧线视觉缺席")
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

        private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
            if (engine.customData[key] == true) return
            engine.customData[key] = true
            if (t == null) log.warn(message) else log.warn(message, t)
        }
    }
}
