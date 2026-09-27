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
 *   （碰撞半径 ×[CENTER_FLARE_RADIUS_MULT]，alpha 固定 10%），逐帧跟随舰位；
 *   力场失效/残骸化/舰船离场时由调用方 dispose。
 * - **相位变红**：舰船处于相位状态时（密蒙为相位巡洋舰），波形光斑与中心光斑
 *   切换为红色系（[PHASE_FRINGE]/[PHASE_CORE]，对照 GravSpaceFoldHullMod 折跃红），
 *   退出相位恢复透镜协议紫。
 */
internal object GravEmFieldVfx {

    private val log = Global.getLogger(GravEmFieldVfx::class.java)

    /** 波形光斑池（每战斗各一：SMOOTH 圆斑池 + SMOOTH_DISC 光柱池），存 engine.customData。 */
    private class FlarePool(
        val entity: FlareEntity,
        val controller: SimpleParticleControlData,
    )

    /**
     * 中心光斑句柄（逐舰一份，挂 FieldState）：常驻实体，逐帧跟随舰位并按相位状态换色；
     * [dispose] 后或实体被 BoxUtil 战斗切换清理后（[expired]）由调用方重建。
     */
    internal class CenterFlare internal constructor(private val entity: FlareEntity) {
        val expired: Boolean get() = entity.hasDelete()

        /** 逐帧跟随：位置取舰心，颜色按相位状态在红/紫之间切换（alpha 恒 10%）。 */
        fun update(ship: ShipAPI, phased: Boolean) {
            if (entity.hasDelete()) return
            entity.setLocation(Vector2f(ship.location))
            if (phased) {
                entity.setCoreColor(CENTER_CORE_PHASE)
                entity.setFringeColor(CENTER_FRINGE_PHASE)
            } else {
                entity.setCoreColor(CENTER_CORE)
                entity.setFringeColor(CENTER_FRINGE)
            }
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
     * 发射一波波形光斑：从舰体碰撞箱随机边缘取点，向该点的随机外方向（±30° 抖动）
     * 投出一枚 SMOOTH 圆斑 + 一枚 SMOOTH_DISC 光柱。粒子速度 = 本波生成时刻的舰速快照 +
     * 外散速度（SimpleParticleControlData.addParticle 的速度向量直传口径），喷散图案整体
     * 随舰船平移；粒子生成后不再跟踪舰位。
     */
    fun spawnWave(engine: CombatEngineAPI, ship: ShipAPI, phased: Boolean) {
        val glowPool = poolOf(engine, GLOW_POOL_KEY, smooth = true) ?: return
        val pillarPool = poolOf(engine, PILLAR_POOL_KEY, smooth = false) ?: return
        val core = if (phased) PHASE_CORE else FIELD_CORE
        val fringe = if (phased) PHASE_FRINGE else FIELD_FRINGE
        val shipVel = Vector2f(ship.velocity)

        val count = MathUtils.getRandomNumberInRange(
            GravEmFieldTuning.WAVE_COUNT_MIN, GravEmFieldTuning.WAVE_COUNT_MAX,
        )
        repeat(count) {
            val from = hullBoundaryPoint(ship)
            val outAngle = BoxUtilCombatVfx.normalizeFacingDeg(
                Misc.getAngleInDegrees(ship.location, from) + MathUtils.getRandomNumberInRange(-30f, 30f),
            )
            val rad = Math.toRadians(outAngle.toDouble())
            val speed = MathUtils.getRandomNumberInRange(WAVE_SPEED_MIN, WAVE_SPEED_MAX)
            val velocity = Vector2f(
                shipVel.x + (cos(rad) * speed).toFloat(),
                shipVel.y + (sin(rad) * speed).toFloat(),
            )
            val scale = MathUtils.getRandomNumberInRange(WAVE_SCALE_MIN, WAVE_SCALE_MAX)
            glowPool.controller.addParticle(
                from, 0f, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, WAVE_FADE_IN, WAVE_FULL, WAVE_FADE_OUT,
            )
            pillarPool.controller.addParticle(
                from, outAngle, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, WAVE_FADE_IN, WAVE_FULL, WAVE_FADE_OUT,
            )
        }
    }

    /**
     * 维持中心光斑：缺席/失效时重建并注册，随后逐帧跟随与换色。
     * 尺寸按创建时碰撞半径 ×[CENTER_FLARE_RADIUS_MULT] 固定（舰体碰撞半径战斗内不变）。
     * 建斑/注册失败即闭锁本场战斗（WARN 一次，此后不再重试，防逐帧刷屏与实体抖动）。
     */
    fun maintainCenterFlare(engine: CombatEngineAPI, ship: ShipAPI, current: CenterFlare?): CenterFlare? {
        if (engine.customData[CENTER_DISABLED_KEY] == DISABLED) return null
        val flare = if (current == null || current.expired) createCenterFlare(engine, ship) else current
        flare?.update(ship, ship.isPhased)
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
        return CenterFlare(entity)
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
