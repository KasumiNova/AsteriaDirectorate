package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.impl.combat.ConeImpactHandler
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.atan2

/**
 * 正电子冲击波弹体引信脚本（规格 06 §2.2 状态机 FLYING → DONE，无定时器族）：
 * 每发弹体一个实例，由 [PositronShockwaveOnFireEffect] 在发射时注册。
 *
 * 每帧判定（优先级）：
 * 1. 近炸——以弹体为顶点、飞行方向为轴的锥状攻击范围内存在敌方导弹/战机/无人机 → 立即引爆；
 * 2. 满射程——弹体已飞距离达武器面板射程 → 无条件引爆（裁定：不会静默消散）。
 *
 * 引爆编排顺序固定：几何结算（基建 [ConeImpactHandler]）→ VFX/音效/浮字 → 移除弹体
 * （先结算后移除，保证引爆帧弹体仍是合法伤害来源上下文）。
 *
 * 0 值防线（规格 §2.4，全部有日志、不自爆不静默）：
 * - 弹体速度近零（生成首帧/外部减速）：本帧跳过引信判定并 WARN（每弹体一次）——方向矢量无意义时
 *   禁止产出错误锥形；
 * - moveSpeed <= 0：[PositronShockwaveDifficulty.reachedMaxRange] 记 ERROR 并立即引爆；
 * - 弹体在飞行中被移除（战斗结束/异常）：静默回收（非引爆路径，不产 VFX）。
 */
class PositronShockwaveFuseScript(
    private val projectile: DamagingProjectileAPI,
    private val source: ShipAPI?,
    private val spec: PositronShockwaveDifficulty.Resolved,
    private val maxRange: Float,
    private val spawnLoc: Vector2f,
) : BaseEveryFrameCombatPlugin() {
    private var done = false
    private var zeroVelocityWarned = false

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            done = true
            return
        }
        // 引擎暂停时本不回调，显式防线双保险
        if (engine.isPaused) return
        if (!engine.isEntityInPlay(projectile)) {
            // 弹体在飞行中被移除（战斗结束/异常）：静默回收，非引爆路径
            done = true
            return
        }

        val loc = projectile.location
        val vel = projectile.velocity
        if (vel.lengthSquared() < ZERO_VELOCITY_THRESHOLD) {
            if (!zeroVelocityWarned) {
                zeroVelocityWarned = true
                log.warn("正电子冲击波弹体速度近零（|v|²=${vel.lengthSquared()}），本帧跳过引信判定（方向矢量无意义，不自爆不静默）")
            }
            return
        }
        val dir = Vector2f(vel)
        dir.normalise()
        val fuseOwner = source?.owner ?: projectile.owner

        // 首帧心跳（每场战斗一次）：证实引信脚本 advance 真实运行并暴露弹体运行参数
        if (engine.customData[TELEMETRY_FIRST_FRAME_LOGGED] != true) {
            engine.customData[TELEMETRY_FIRST_FRAME_LOGGED] = true
            log.info(
                "正电子引信脚本首帧运行：elapsed=${projectile.elapsed}, moveSpeed=${projectile.moveSpeed}, " +
                    "maxRange=$maxRange, fading=${projectile.isFading}, halfAngle=${spec.halfAngleDeg}, " +
                    "coneRange=${spec.range}, damage=${spec.damage}",
            )
        }

        // 条件 1（优先）：近炸——锥内存在敌方导弹/战机/无人机（几何与结算同源，走基建锥筛纯函数）
        var detonate = CombatUtils.getEntitiesWithinRange(loc, spec.range).any { e ->
            e !== projectile &&
                PositronShockwaveDifficulty.isFuseTarget(e, fuseOwner) &&
                ConeImpactHandler.isInsideCone(loc, dir.x, dir.y, spec.halfAngleDeg, spec.range, e.location, e.collisionRadius)
        }

        // 条件 2：抵达最大射程——无条件自爆（裁定：不会静默消散）。
        // 原版弹体寿命被钳制为 range/projSpeed，淡出与满射程同帧发生；本判定须先于淡出兜底执行。
        var maxRangeDetonate = false
        if (!detonate) {
            maxRangeDetonate = PositronShockwaveDifficulty.reachedMaxRange(
                projectile.elapsed,
                projectile.moveSpeed,
                maxRange,
            )
            detonate = maxRangeDetonate
        }

        // 淡出兜底（裁定「不会静默消散」）：原版淡出先于上述引信触发属于异常时序
        // （浮点边界/数据面寿命配置失误等），记 WARN 并按满射程路径就地引爆
        if (!detonate && projectile.isFading) {
            log.warn(
                "正电子冲击波弹体淡出先于引信触发（elapsed=${projectile.elapsed}, " +
                    "moveSpeed=${projectile.moveSpeed}, maxRange=$maxRange），按满射程路径就地引爆",
            )
            detonateAndFinish(engine, loc, dir, fuseOwner, fuse = false)
            return
        }

        if (detonate) detonateAndFinish(engine, loc, dir, fuseOwner, fuse = !maxRangeDetonate)
    }

    /** 引爆编排：结算 → VFX/音效/浮字 → 移除弹体。 */
    private fun detonateAndFinish(
        engine: CombatEngineAPI,
        loc: Vector2f,
        dir: Vector2f,
        fuseOwner: Int,
        fuse: Boolean,
    ) {
        val targets = ConeImpactHandler.resolve(
            engine,
            ConeImpactSpec(
                origin = loc,
                direction = dir,
                halfAngleDeg = spec.halfAngleDeg,
                range = spec.range,
                damage = spec.damage,
                damageType = DamageType.FRAGMENTATION,
                empDamage = 0f,
                source = source,
                owner = fuseOwner,
                // 结算波及全部敌对目标（含舰船，裁定「自爆波及」）
                filter = ConeTargetFilter { e -> e.owner != fuseOwner },
                hitShips = true,
                hitFighters = true,
                hitMissiles = true,
            ),
        )
        bumpTelemetry(engine, if (fuse) TELEMETRY_DETONATE_FUSE else TELEMETRY_DETONATE_MAX_RANGE)
        engine.customData[TELEMETRY_LAST_DETONATE_DIST] = Misc.getDistance(spawnLoc, loc)
        for (target in targets) {
            when {
                target is MissileAPI -> bumpTelemetry(engine, TELEMETRY_CONE_MISSILE_HITS)
                target is ShipAPI && target.isFighter -> bumpTelemetry(engine, TELEMETRY_CONE_FIGHTER_HITS)
                target is ShipAPI -> bumpTelemetry(engine, TELEMETRY_CONE_SHIP_HITS)
            }
        }

        // 锥面冲击 VFX（基建共用组件，蓝色调缩小版，规模随 spec.range 参数化）+ 小型蓝闪 + 音效
        ConeImpactVfx.spawn(
            engine,
            ConeImpactVfxSpec(
                origin = Vector2f(loc),
                facingDeg = Math.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble())).toFloat(),
                halfAngleDeg = spec.halfAngleDeg,
                length = spec.range,
                coreColor = CONE_CORE_COLOR,
                fringeColor = CONE_FRINGE_COLOR,
            ),
        )
        bumpTelemetry(engine, TELEMETRY_CONE_VFX)
        engine.spawnExplosion(loc, ZERO, FLASH_COLOR, spec.range * 0.25f, 0.15f)
        Global.getSoundPlayer().playSound("explosion_flak", 1f, 0.9f, loc, ZERO)

        // 引爆计数浮字：仅 devMode + 玩家侧且有命中（2026-07-29 审批裁定：正常玩家只看锥面特效）
        if (Global.getSettings().isDevMode && source?.owner == 0 && targets.isNotEmpty()) {
            engine.addFloatingText(loc, "近炸命中 ×${targets.size}", 16f, FLOATY_COLOR, source, 0f, 0f)
            bumpTelemetry(engine, TELEMETRY_FLOATY)
        }

        engine.removeEntity(projectile)
        done = true
    }

    /** dev 自动化烟测证据计数（对齐 QJ/EDA 遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    companion object {
        private val log = Global.getLogger(PositronShockwaveFuseScript::class.java)

        /** 速度近零判定阈值（lengthSquared）：低于此值方向矢量无意义。 */
        private const val ZERO_VELOCITY_THRESHOLD = 1e-3f

        /** 零速度矢量（爆炸/音效用，避免逐次分配）。 */
        private val ZERO = Vector2f()

        /** 引爆锥面核心色（蓝色调缩小版，对照贯星族调色）。 */
        private val CONE_CORE_COLOR = Color(165, 210, 255)

        /** 引爆锥面辉光色。 */
        private val CONE_FRINGE_COLOR = Color(70, 130, 255)

        /** 引爆点小型蓝闪（规格 §2.2）。 */
        private val FLASH_COLOR = Color(140, 200, 255, 90)

        /** devMode 引爆计数浮字色。 */
        private val FLOATY_COLOR = Color(180, 220, 255)

        // ---- dev 自动化烟测遥测键（engine.customData）----
        const val TELEMETRY_DETONATE_FUSE = "astd_positron_detonate_fuse"
        const val TELEMETRY_DETONATE_MAX_RANGE = "astd_positron_detonate_max_range"
        const val TELEMETRY_CONE_VFX = "astd_positron_cone_vfx"
        const val TELEMETRY_CONE_SHIP_HITS = "astd_positron_cone_ship_hits"
        const val TELEMETRY_CONE_MISSILE_HITS = "astd_positron_cone_missile_hits"
        const val TELEMETRY_CONE_FIGHTER_HITS = "astd_positron_cone_fighter_hits"
        const val TELEMETRY_FLOATY = "astd_positron_floaty"
        const val TELEMETRY_LAST_DETONATE_DIST = "astd_positron_last_detonate_dist"
        const val TELEMETRY_FIRST_FRAME_LOGGED = "astd_positron_first_frame_logged"
        const val TELEMETRY_ONFIRE_LOGGED = "astd_positron_onfire_logged"

        /** 读整数遥测计数（无记录为 0）。 */
        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        /** 读浮点遥测值（无记录为 -1）。 */
        fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f
    }
}
