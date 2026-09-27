package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.base.SimpleParticleControlData
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 引力电磁力场（密蒙级 ZW-002 内置 hullmod）的 BoxUtil 视觉层，由 [GravEmFieldHullMod] 驱动。
 *
 * - **波形光斑**（[spawnWave]）：每波从舰体真实碰撞箱随机边缘向随机外方向发射若干
 *   FlareEntity 组合——SMOOTH 圆形光斑 + SMOOTH_DISC 柔和光柱，粒子速度取
 *   「生成时刻舰速快照 + 外散速度」（喷散图案整体随舰船平移，生成后不再跟踪），
 *   寿命包络（淡入/满值/淡出）由 BoxUtil 实例定时器托管。节拍级高频 spawn
 *   （节拍与每波数量见 [GravEmFieldTuning.WAVE_INTERVAL]/[GravEmFieldTuning.WAVE_COUNT_MAX]），
 *   两个池化常驻 FlareEntity + [SimpleParticleControlData] 实例槽（BoxUtil 自管理
 *   速度积分与包络），防 renderEntityMap 滞留泄漏（实体池化规范的高频池化口径）。
 * - **中心光斑**（[CenterFlare]）：力场存续期间常驻舰船中心的极大 SMOOTH 圆斑
 *   （碰撞半径 ×[CENTER_FLARE_RADIUS_MULT]），逐帧跟随舰位；相位/激活状态经调用方
 *   传入的过渡系数线性渐变（色系红紫、alpha 与尺寸随力场淡入淡出）；
 *   力场失效/残骸化/舰船离场时由调用方 dispose。
 * - **相位变红**：舰船处于相位状态时（密蒙为相位巡洋舰），波形光斑与中心光斑
 *   向红色系（[PHASE_FRINGE]/[PHASE_CORE]，对照 GravSpaceFoldHullMod 折跃红）线性过渡，
 *   退出相位渐变回透镜协议紫。
 */
internal object GravEmFieldVfx {

    private val log = Global.getLogger(GravEmFieldVfx::class.java)

    /** 波形光斑池（每战斗各一：SMOOTH 圆斑池 + SMOOTH_DISC 光柱池），存 engine.customData。 */
    private class FlarePool(
        val entity: FlareEntity,
        val controller: SimpleParticleControlData,
    )

    /**
     * 中心光斑句柄（逐舰一份，挂 FieldState）：常驻实体，逐帧跟随舰位并按过渡系数渐变；
     * [dispose] 后或实体被 BoxUtil 战斗切换清理后（[expired]）由调用方重建。
     * [baseSize] 为建斑时钉死的满态直径（碰撞半径 ×[CENTER_FLARE_RADIUS_MULT]，舰体碰撞半径
     * 战斗内不变），淡出经尺寸缩放实现。
     */
    internal class CenterFlare internal constructor(
        private val entity: FlareEntity,
        private val baseSize: Float,
    ) {
        val expired: Boolean get() = entity.hasDelete()

        /**
         * 逐帧跟随：位置取舰心；[phaseBlend]（0=力场紫，1=相位红）插值色系，[fieldBlend]
         * （0=冷却，1=激活）缩放 alpha 与尺寸（末态收缩到 [CENTER_MIN_SCALE_FRACTION]，不归零防跳变）。
         */
        fun update(ship: ShipAPI, phaseBlend: Float, fieldBlend: Float) {
            if (entity.hasDelete()) return
            entity.setLocation(Vector2f(ship.location))
            entity.setCoreColor(lerpColor(CENTER_CORE, CENTER_CORE_PHASE, phaseBlend, fieldBlend))
            entity.setFringeColor(lerpColor(CENTER_FRINGE, CENTER_FRINGE_PHASE, phaseBlend, fieldBlend))
            val scale = CENTER_MIN_SCALE_FRACTION + (1f - CENTER_MIN_SCALE_FRACTION) * fieldBlend
            entity.setSize(baseSize * scale, baseSize * scale)
        }

        fun dispose() {
            BoxUtilCombatVfx.removeEntity(entity)
        }
    }

    /**
     * 舰船离场（撤退等非残骸化移除）清场：delete 两个波形光斑池并摘除 customData 键。
     * 池按 engine 共享而非逐舰，多密蒙同场时先离场者拆池后，在场者的下一次 [spawnWave]
     * 会经 [poolOf] 按需重建，无需调用方区分。
     */
    fun disposeWavePools(engine: CombatEngineAPI) {
        disposePool(engine, GLOW_POOL_KEY)
        disposePool(engine, PILLAR_POOL_KEY)
    }

    private fun disposePool(engine: CombatEngineAPI, key: String) {
        val pool = engine.customData[key] as? FlarePool ?: return
        BoxUtilCombatVfx.removeEntity(pool.entity)
        engine.customData.remove(key)
    }

    /**
     * 发射一波波形光斑：常态从舰体碰撞箱随机边缘取点，向该点的随机外方向（±30° 抖动）
     * 投出一枚 SMOOTH 圆斑 + 一枚 SMOOTH_DISC 光柱。粒子速度 = 本波生成时刻的舰速快照 +
     * 外散速度（SimpleParticleControlData.addParticle 的速度向量直传口径），喷散图案整体
     * 随舰船平移；粒子生成后不再跟踪舰位。[phaseBlend]（0=力场紫，1=相位红）插值本波色系，
     * 过渡中途的波取中间色（粒子生成后颜色固定，渐变靠逐波新色推进）。
     * [chargeGather]（引力磁暴充能进度 0-1，由 GravStormSystemStats 写入 ship.customData）
     * > 0 时本波改为反向聚集：方向朝舰船本体（±30° 抖动不变），
     * 外散速度 × (1 + [CHARGE_GATHER_SPEED_SPAN] × 进度)（100% → 400% 线性）；
     * 聚集波寿命包络按抵达舰心时间收缩（抵达即淡出消散，不穿越舰体从另一侧外飞）。
     */
    fun spawnWave(engine: CombatEngineAPI, ship: ShipAPI, phaseBlend: Float, chargeGather: Float = 0f) {
        val glowPool = poolOf(engine, GLOW_POOL_KEY, smooth = true) ?: return
        val pillarPool = poolOf(engine, PILLAR_POOL_KEY, smooth = false) ?: return
        val core = lerpColor(FIELD_CORE, PHASE_CORE, phaseBlend)
        val fringe = lerpColor(FIELD_FRINGE, PHASE_FRINGE, phaseBlend)
        val shipVel = Vector2f(ship.velocity)
        val gather = chargeGather.coerceIn(0f, 1f)
        val speedMult = 1f + CHARGE_GATHER_SPEED_SPAN * gather

        val count = MathUtils.getRandomNumberInRange(
            GravEmFieldTuning.WAVE_COUNT_MIN, GravEmFieldTuning.WAVE_COUNT_MAX,
        )
        repeat(count) {
            val from = hullBoundaryPoint(ship)
            // 聚集口径：方向由「舰心→出生点」反转为「出生点→舰心」，抖动区间不变
            val baseAngle = if (gather > 0f) {
                Misc.getAngleInDegrees(from, ship.location)
            } else {
                Misc.getAngleInDegrees(ship.location, from)
            }
            val outAngle = BoxUtilCombatVfx.normalizeFacingDeg(
                baseAngle + MathUtils.getRandomNumberInRange(-30f, 30f),
            )
            val rad = Math.toRadians(outAngle.toDouble())
            val speed = MathUtils.getRandomNumberInRange(WAVE_SPEED_MIN, WAVE_SPEED_MAX) * speedMult
            val velocity = Vector2f(
                shipVel.x + (cos(rad) * speed).toFloat(),
                shipVel.y + (sin(rad) * speed).toFloat(),
            )
            // 聚集口径的寿命包络：按「抵达舰心时间」动态收缩，粒子在舰心附近淡出消散。
            // 不收缩则粒子以常态包络存活 2.5s——出生点到舰心不足 1s 航程，穿越舰体后
            // 以聚集加速（最高 400%）从另一侧继续外飞，视觉上仍是向外扩散。
            val fadeIn: Float
            val full: Float
            val fadeOut: Float
            if (gather > 0f) {
                val flightSeconds = Misc.getDistance(from, ship.location) / speed
                fadeIn = minOf(WAVE_FADE_IN, flightSeconds * CHARGE_GATHER_FADE_IN_FRACTION)
                full = 0f
                fadeOut = (flightSeconds - fadeIn).coerceAtLeast(CHARGE_GATHER_MIN_FADE_OUT_SECONDS)
            } else {
                fadeIn = WAVE_FADE_IN
                full = WAVE_FULL
                fadeOut = WAVE_FADE_OUT
            }
            val scale = MathUtils.getRandomNumberInRange(WAVE_SCALE_MIN, WAVE_SCALE_MAX)
            glowPool.controller.addParticle(
                from, 0f, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, fadeIn, full, fadeOut,
            )
            pillarPool.controller.addParticle(
                from, outAngle, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, fadeIn, full, fadeOut,
            )
        }
    }

    /**
     * 维持中心光斑：缺席/失效时重建并注册（[fieldBlend] ≤ 0 的冷却末态不凭空建斑），
     * 随后逐帧跟随与渐变（[phaseBlend]/[fieldBlend] 语义见 [CenterFlare.update]）。
     * 尺寸按创建时碰撞半径 ×[CENTER_FLARE_RADIUS_MULT] 钉死满态基准（舰体碰撞半径战斗内不变）。
     * 建斑/注册失败即闭锁本场战斗（WARN 一次，此后不再重试，防逐帧刷屏与实体抖动）。
     */
    fun maintainCenterFlare(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        current: CenterFlare?,
        phaseBlend: Float,
        fieldBlend: Float,
    ): CenterFlare? {
        if (engine.customData[CENTER_DISABLED_KEY] == DISABLED) return null
        if ((current == null || current.expired) && fieldBlend <= 0f) return null
        val flare = if (current == null || current.expired) createCenterFlare(engine, ship) else current
        flare?.update(ship, phaseBlend, fieldBlend)
        return flare
    }

    private fun createCenterFlare(engine: CombatEngineAPI, ship: ShipAPI): CenterFlare? {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            warnOnce(engine, CENTER_DISABLED_KEY, "[ASTD] 引力电磁力场中心光斑建实体失败（${t.javaClass.simpleName}），力场中心光斑视觉本场缺席", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        entity.setAdditiveBlend()
        entity.setSmooth()
        entity.isFlick = false
        entity.glowPower = 0.1f
        val radius = ship.collisionRadius * CENTER_FLARE_RADIUS_MULT
        entity.setSize(radius * 2f, radius * 2f)
        entity.setCoreColor(CENTER_CORE)
        entity.setFringeColor(CENTER_FRINGE)
        entity.setGlobalTimer(0f, RESIDENT_FULL_SECONDS, 0f)
        entity.setStateVanilla(Vector2f(ship.location), 0f)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            warnOnce(engine, CENTER_DISABLED_KEY, "[ASTD] 引力电磁力场中心光斑注册失败（addEntity 返回 $state，ship=${ship.id}），力场中心光斑视觉本场缺席")
            entity.delete()
            return null
        }
        return CenterFlare(entity, radius * 2f)
    }

    /** 取波形光斑池（缺席/实体失效时重建；建池失败闭锁本场战斗：WARN 一次后不再重试，本波视觉缺席）。 */
    private fun poolOf(engine: CombatEngineAPI, key: String, smooth: Boolean): FlarePool? {
        if (engine.customData[key] == DISABLED) return null
        val existing = engine.customData[key] as? FlarePool
        if (existing != null && !existing.entity.hasDelete()) return existing
        val created = createPool(engine, key, smooth) ?: return null
        engine.customData[key] = created
        return created
    }

    /**
     * 建池：一个常驻 FlareEntity（锚原点零朝向，实例坐标即世界坐标）+
     * [SimpleParticleControlData] 实例槽（速度积分与寿命包络由 BoxUtil 自管理；
     * dataDur < -3000 常驻不清槽）。实例色映射：color → 核心色，emissive → 边缘色。
     * 失败即在池键上写闭锁标记（一次性 WARN 口径，见 [warnOnce]）。
     */
    private fun createPool(engine: CombatEngineAPI, key: String, smooth: Boolean): FlarePool? {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            warnOnce(engine, key, "[ASTD] 引力电磁力场波形光斑池建实体失败（${t.javaClass.simpleName}，smooth=$smooth），本池视觉本场缺席", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        entity.isFlick = false
        entity.glowPower = 0.1f
        if (smooth) {
            entity.setSmooth()
            entity.setSize(WAVE_GLOW_SIZE, WAVE_GLOW_SIZE)
        } else {
            entity.setSmoothDisc()
            entity.setSize(WAVE_PILLAR_LENGTH, WAVE_PILLAR_WIDTH)
        }
        entity.autoAspect()
        entity.setStateVanilla(ZERO, 0f)
        entity.setGlobalTimer(0f, RESIDENT_FULL_SECONDS, 0f)

        val controller = SimpleParticleControlData(WAVE_POOL_CAPACITY, WAVE_MAX_DUR, -5120f, false)
        entity.setControlData(controller)
        entity.setAutoSubmitEntityData(false)
        entity.setAutoSubmitModelMatrix(false)
        entity.submitEntityData()
        entity.submitModelMatrix()

        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            warnOnce(engine, key, "[ASTD] 引力电磁力场波形光斑池注册失败（addEntity 返回 $state，smooth=$smooth），本池视觉本场缺席")
            entity.delete()
            return null
        }
        return FlarePool(entity, controller)
    }

    /**
     * 一次性 WARN + 闭锁（engine.customData 闩写 [DISABLED]，每场战斗每类失败一次；
     * 对齐 BoxUtilCombatVfx.KEY_LOG_ADD_ENTITY_FAIL_ONCE 先例口径，防逐帧/节拍刷屏）。
     */
    private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
        if (engine.customData[key] == DISABLED) return
        engine.customData[key] = DISABLED
        if (t == null) log.warn(message) else log.warn(message, t)
    }

    /** RGBA 线性插值：[t] 为 a→b 过渡系数（clamp 0..1），[alphaMult] 额外乘在插值后的 alpha 上（力场淡入淡出用）。 */
    private fun lerpColor(a: Color, b: Color, t: Float, alphaMult: Float = 1f): Color {
        val u = t.coerceIn(0f, 1f)
        fun channel(x: Int, y: Int) = (x + (y - x) * u).roundToInt().coerceIn(0, 255)
        val alpha = (channel(a.alpha, b.alpha) * alphaMult).roundToInt().coerceIn(0, 255)
        return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue), alpha)
    }

    /** 舰体真实碰撞箱随机边缘点（exactBounds 随机段上插值；无碰撞箱时按碰撞半径近似）。 */
    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = ship.exactBounds
        if (bounds != null) {
            bounds.update(ship.location, ship.facing)
            val segments = bounds.segments
            if (segments.isNotEmpty()) {
                val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                val t = MathUtils.getRandomNumberInRange(0f, 1f)
                return Vector2f(
                    seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                    seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                )
            }
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 0.90f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    /** 波形光斑池挂载键（engine.customData，随战斗清理）；失败闭锁复用同键（值为 [DISABLED] 时闭锁）。 */
    private const val GLOW_POOL_KEY = "astd_grav_em_field_glow_pool"
    private const val PILLAR_POOL_KEY = "astd_grav_em_field_pillar_pool"

    /** 中心光斑失败闭锁键（engine.customData，随战斗清理）。 */
    private const val CENTER_DISABLED_KEY = "astd_grav_em_field_center_disabled"

    /** 闭锁标记（warnOnce/poolOf/maintainCenterFlare 共用的 customData 值）。 */
    private const val DISABLED = "disabled"

    /** 池实例容量（单池）：在场峰值 = 每波上限 × 包络寿命 ÷ 节拍，本值留充足余量。 */
    private const val WAVE_POOL_CAPACITY = 256

    /** 控制器最大时长口径（秒）：仅兜底，逐实例寿命由 addParticle 三段包络钉死。 */
    private const val WAVE_MAX_DUR = 2f

    /** 常驻实体时长（秒）：生命周期由调用方显式驱动，不自然到期。 */
    private const val RESIDENT_FULL_SECONDS = 1e7f

    /** 磁暴充能聚集的速度增益跨度：聚集波外散速度倍率 = 1 + 本值 × 充能进度（100% → 400% 线性）。 */
    private const val CHARGE_GATHER_SPEED_SPAN = 3f

    /** 聚集波寿命包络：淡入占「抵达舰心时间」的比例上限（余下时长全部用于淡出，抵达舰心即消散）。 */
    private const val CHARGE_GATHER_FADE_IN_FRACTION = 0.3f

    /** 聚集波淡出时长下限（秒）：近中心出生点航程极短时保住可读性，防瞬生瞬灭。 */
    private const val CHARGE_GATHER_MIN_FADE_OUT_SECONDS = 0.05f

    /** 相位色系过渡时长（秒）：红/紫线性渐变的全程时长。 */
    const val PHASE_BLEND_SECONDS = 0.4f

    /** 力场激活过渡时长（秒）：中心光斑淡入/冷却淡出的全程时长。 */
    const val FIELD_BLEND_SECONDS = 0.5f

    /** 中心光斑淡出末态尺寸占比（不归零：配合 alpha 渐隐，避免尺寸归零的渲染跳变）。 */
    private const val CENTER_MIN_SCALE_FRACTION = 0.2f

    // 波形光斑参数：圆斑/光柱基尺寸（实例 scale 为倍率）、外飘速度、尺寸抖动、寿命包络
    private const val WAVE_GLOW_SIZE = 46f
    private const val WAVE_PILLAR_LENGTH = 150f
    private const val WAVE_PILLAR_WIDTH = 18f
    private const val WAVE_SPEED_MIN = 70f
    private const val WAVE_SPEED_MAX = 130f
    private const val WAVE_SCALE_MIN = 0.75f
    private const val WAVE_SCALE_MAX = 1.25f
    private const val WAVE_FADE_IN = 0.08f
    private const val WAVE_FULL = 0.20f
    private const val WAVE_FADE_OUT = 0.70f

    /** 中心光斑半径倍率（碰撞半径 ×3.5）与固定透明度（10%）。 */
    private const val CENTER_FLARE_RADIUS_MULT = 3.5f
    private const val CENTER_ALPHA = 26

    /** 力场紫（透镜协议）：波形光斑与中心光斑的常态度色。 */
    private val FIELD_CORE = Color(232, 205, 255, 200)
    private val FIELD_FRINGE = Color(186, 120, 255, 160)

    /** 相位红（对照 GravSpaceFoldHullMod FOLD_NEBULA_COLOR 红）：相位状态色。 */
    private val PHASE_CORE = Color(255, 170, 160, 200)
    private val PHASE_FRINGE = Color(215, 45, 60, 170)

    private val CENTER_CORE = Color(232, 205, 255, CENTER_ALPHA)
    private val CENTER_FRINGE = Color(186, 120, 255, CENTER_ALPHA)
    private val CENTER_CORE_PHASE = Color(255, 170, 160, CENTER_ALPHA)
    private val CENTER_FRINGE_PHASE = Color(215, 45, 60, CENTER_ALPHA)

    private val ZERO = Vector2f(0f, 0f)
}
