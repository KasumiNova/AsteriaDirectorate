package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.impl.combat.ConeImpactHandler
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * 贯星之矛锥状冲击结算的组装与触发层（规格 09 §2.2）。
 *
 * 动机：隔离 OnHitEffect 引擎回调与可测纯逻辑——命中矢量推导、难度取值（同一次命中只取一次，
 * 禁止同帧二次取值，基建 §4.1 口径）、`ConeImpactSpec` 组装均为无副作用纯函数 [buildConeSpec]；
 * [resolve] 一次性串联「基建结算 → 逐目标浮字 → 特效触发」，结算顺序固定、无状态机。
 *
 * 机制要点（设计案定稿）：命中时沿射弹矢量产生锥状冲击，对锥内所有目标额外造成
 * 面板 × 倍率的 FRAGMENTATION 破片伤害与同锚 EMP；命中本体豁免（引擎已结算主伤害）。
 * 伤害基准用 [PiercingLanceDifficulty.PANEL_DAMAGE] 常量口径，不取 projectile.damageAmount
 * （不吃目标侧易伤、不被 NaN 污染）。
 */
object PiercingLanceConeStrike {
    private val log = Global.getLogger(PiercingLanceConeStrike::class.java)

    /** 速度近零判定阈值（lengthSquared）：低于此值方向矢量无意义，走 source→target 回退。 */
    private const val ZERO_VELOCITY_THRESHOLD = 1e-3f

    /** 锥面破片伤害浮字色（ARC 冷蓝白）。 */
    private val FRAG_TEXT_COLOR = Color(170, 215, 255)

    /**
     * 组装一次锥状冲击结算声明（纯函数，无副作用；难度取值在此处发生恰好一次）。
     *
     * 命中矢量推导（§2.5 0 值防线）：
     * 1. 首选弹体速度方向归一化；
     * 2. 速度零向量/非法（命中瞬间速度被引擎改写）→ 回退 source→directTarget 方向归一化；
     * 3. 仍不可得（速度为零且 source 或 target 缺失/同点）→ 记 WARN 并返回 null 放弃本次锥面结算
     *    （命中本体伤害已由引擎结算，不静默吞机制）。
     *
     * [directTarget] 命中本体（可空，陨石等路径）：仅被 filter 豁免，不重复吃锥面伤害。
     * [hitPoint] 命中点（OnHitEffect 已完成 point null → 弹体位置回退）。
     */
    fun buildConeSpec(
        projectile: DamagingProjectileAPI,
        directTarget: CombatEntityAPI?,
        hitPoint: Vector2f,
    ): ConeImpactSpec? {
        val direction = resolveHitDirection(projectile, directTarget) ?: return null

        // 来源船同帧被毁的极端帧：按敌版取值并记 DEBUG；owner 回退弹体归属保证敌我过滤不退化。
        val source = projectile.weapon?.ship
        if (source == null) {
            log.debug("贯星之矛弹体无来源舰船（来源船同帧被毁？），难度取值按敌方口径，owner 回退弹体归属 ${projectile.owner}")
        }

        // 难度取值调用点唯一（§2.2 结算顺序铁律）：同一次命中三项各取一次。
        val arcDeg = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_ARC)
        val range = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_RANGE)
        val dmgMult = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_DAMAGE)

        return ConeImpactSpec(
            origin = hitPoint,
            direction = direction,
            halfAngleDeg = arcDeg / 2f,
            range = range,
            damage = PiercingLanceDifficulty.PANEL_DAMAGE * dmgMult,
            damageType = DamageType.FRAGMENTATION,
            // EMP 与破片同锚同值（2026-07-28 裁定）
            empDamage = PiercingLanceDifficulty.PANEL_DAMAGE * dmgMult,
            source = source,
            owner = source?.owner ?: projectile.owner,
            // 命中本体豁免（基建 §2.2-2 语义）：本体主伤害已由引擎结算
            filter = ConeTargetFilter { it !== directTarget },
            hitShips = true,
            hitFighters = true,
            hitMissiles = true,
        )
    }

    /**
     * 触发一次锥状冲击：基建几何结算 → 逐目标伤害浮字 → 顶点闪光 + 大光柱 + 锥面特效（同帧，
     * 机制可视化铁律）。锥内无额外目标时照常绘制特效（机制存在性反馈），无浮字。
     */
    fun resolve(engine: CombatEngineAPI, spec: ConeImpactSpec, directTarget: CombatEntityAPI?) {
        val hits = ConeImpactHandler.resolve(engine, spec)
        bumpTelemetry(engine, TELEMETRY_RESOLVE)
        engine.customData[TELEMETRY_LAST_HALF_ANGLE] = spec.halfAngleDeg
        engine.customData[TELEMETRY_LAST_RANGE] = spec.range
        engine.customData[TELEMETRY_LAST_DAMAGE] = spec.damage
        engine.customData[TELEMETRY_LAST_CONE_HITS] = hits.size

        // 契约守护：命中本体被基建 filter 豁免；若契约被破坏必须有 ERROR（不静默）。
        if (directTarget != null && hits.any { it === directTarget }) {
            log.error("贯星之矛锥面结算命中本体豁免契约被破坏（target=$directTarget），锥面伤害重复落于本体")
            bumpTelemetry(engine, TELEMETRY_DIRECT_EXEMPT_VIOLATION)
        }

        // 玩家可见反馈（基建 §4.2）：逐目标破片伤害浮字（落点与基建伤害落点同一点位）。
        for (target in hits) {
            engine.addFloatingDamageText(
                ConeImpactHandler.surfacePoint(spec.origin, target.location, target.collisionRadius),
                spec.damage,
                FRAG_TEXT_COLOR,
                target as? ShipAPI,
                spec.source,
            )
            bumpTelemetry(engine, TELEMETRY_CONE_HITS)
            bumpTelemetry(engine, TELEMETRY_FLOATY)
        }

        PiercingLanceVfx.spawnImpact(engine, spec)
    }

    /**
     * 命中矢量归一化（纯函数）：弹体速度方向 → 零向量回退 source→target 方向 → 仍不可得记 WARN 返回 null。
     */
    private fun resolveHitDirection(projectile: DamagingProjectileAPI, directTarget: CombatEntityAPI?): Vector2f? {
        val velocity = projectile.velocity
        if (velocity != null) {
            val lenSq = velocity.x * velocity.x + velocity.y * velocity.y
            if (!lenSq.isNaN() && lenSq >= ZERO_VELOCITY_THRESHOLD) {
                val len = sqrt(lenSq)
                return Vector2f(velocity.x / len, velocity.y / len)
            }
        }

        // 速度零向量/非法（命中瞬间速度被引擎改写）：回退 source→target 方向。
        val source = projectile.weapon?.ship
        if (source != null && directTarget != null) {
            val dx = directTarget.location.x - source.location.x
            val dy = directTarget.location.y - source.location.y
            val lenSq = dx * dx + dy * dy
            if (!lenSq.isNaN() && lenSq >= ZERO_VELOCITY_THRESHOLD) {
                val len = sqrt(lenSq)
                return Vector2f(dx / len, dy / len)
            }
        }

        log.warn("贯星之矛命中矢量不可得（弹体速度近零且 source→target 方向不可用），放弃本次锥面结算（命中本体伤害已由引擎结算）")
        return null
    }

    /** dev 自动化烟测证据计数（对齐正电子/七星遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    // ---- dev 自动化烟测遥测键（engine.customData）----
    const val TELEMETRY_RESOLVE = "astd_piercing_lance_resolve"
    const val TELEMETRY_CONE_HITS = "astd_piercing_lance_cone_hits"
    const val TELEMETRY_FLOATY = "astd_piercing_lance_floaty"
    const val TELEMETRY_LAST_CONE_HITS = "astd_piercing_lance_last_cone_hits"
    const val TELEMETRY_LAST_HALF_ANGLE = "astd_piercing_lance_last_half_angle"
    const val TELEMETRY_LAST_RANGE = "astd_piercing_lance_last_range"
    const val TELEMETRY_LAST_DAMAGE = "astd_piercing_lance_last_damage"
    const val TELEMETRY_DIRECT_EXEMPT_VIOLATION = "astd_piercing_lance_direct_exempt_violation"

    /** 读整数遥测计数（无记录为 0）。 */
    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    /** 读浮点遥测值（无记录为 -1）。 */
    fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f
}
