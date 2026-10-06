package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeMath
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 摧锋鱼雷的自定义追踪 AI（blue/30-superlative.md §机制）：发射舰锁定目标（shipTarget）优先 →
 * 目标有效性校验 → 0.25s 节流重选（反舰口径：仅舰船入选，战机/导弹永不入选）→ 领先瞄准 →
 * giveCommand 转向加速，外加二段式速度调速器（发射时 50% 航速，航程 25%→50% 间线性升至满速）。
 *
 * 锁定遵循语义对齐原版 GuidedMissileAI：发射舰存在有效锁定目标时每轮重选直接切到锁定目标
 * （不受捕获射程限制，但仍受反舰口径过滤）；无锁定或锁定失效时退化为就近捕获。
 *
 * 形态对齐 [StellarMrmMissileAI][cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmMissileAI]
 * 先例；挂载点：由 [CuifengTorpedoOnFireEffect] 经 `missile.setMissileAI(...)` 安装。
 *
 * 二段式的实现约束（规格实装核查）：`MissileAPI` 无 `setMaxSpeed`，引擎加速由 `.proj`
 * engineSpec 持续供给；调速器在每帧命令下发后对 `missile.velocity` 超帽部分直接截速
 * （velocity 为可变 Vector2f，引擎命令下发后直写截速是仓库既有先例）。
 */
class CuifengTorpedoAI(
    private val missile: MissileAPI,
    private val launchLoc: Vector2f,
    private val maxRange: Float,
) : MissileAIPlugin {

    private val log = Global.getLogger(CuifengTorpedoAI::class.java)

    private var target: ShipAPI? = null
    private var reselectTimer = 0f

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        // ---- 目标维护（0.25s 节流：锁定目标优先切换，其次有效保留，失效重选）----
        reselectTimer -= amount
        if (reselectTimer <= 0f) {
            reselectTimer = CuifengTorpedoDifficulty.RETARGET_INTERVAL
            val current = target
            val locked = lockedTarget(engine)
            val picked = when {
                locked != null -> locked
                current == null || !isValidTarget(engine, current) -> selectTarget(engine)
                else -> current
            }
            if (picked !== current) {
                target = picked
                if (picked != null) {
                    bump(engine, TELE_TARGET_SELECTED)
                    log.info(
                        "[摧锋] 鱼雷选定目标：hull=${picked.hullSpec?.hullId} " +
                                "dist=${MathUtils.getDistance(missile.location, picked.location).toInt()}" +
                                if (picked === locked) "（遵循发射舰锁定）" else "",
                    )
                } else {
                    log.info("[摧锋] 鱼雷无可选目标（候选全空/全越射程），直飞")
                }
            }
        }

        // ---- 领先瞄准 + 转向（目标在帧间失效时本帧不转向，仅加速）----
        val t = target
        if (t != null && isValidTarget(engine, t)) {
            val dist = MathUtils.getDistance(missile.location, t.location)
            val lead = StellarMrmStrikeMath.leadPoint(t.location, t.velocity, dist, missile.maxSpeed)
            val angleTo = VectorUtils.getAngle(missile.location, lead)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            if (abs(diff) > 1f) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }
        missile.giveCommand(ShipCommand.ACCELERATE)

        // ---- 二段式调速器：超帽截速（引擎持续推进，本段负责把速度压回曲线帽）----
        val maxSpeed = missile.maxSpeed
        if (maxSpeed <= 0f) return
        val progress = CuifengTorpedoMath.progressOf(MathUtils.getDistance(launchLoc, missile.location), maxRange)
        val factor = CuifengTorpedoMath.speedFactor(progress)
        val cap = maxSpeed * factor
        val vel = missile.velocity
        val speed = vel.length()
        if (speed > cap) {
            vel.scale(cap / speed)
            bump(engine, TELE_GOVERNED_FRAMES)
        }
        engine.customData[TELE_LAST_SPEED_FACTOR] = factor
    }

    /**
     * 发射舰锁定目标：存在且有效（反舰口径内）时返回之，供重选优先切换。
     * 不做捕获射程限制——锁定语义是"指哪打哪"，射程约束只作用于就近捕获兜底。
     */
    private fun lockedTarget(engine: CombatEngineAPI): ShipAPI? {
        val locked = missile.source?.shipTarget ?: return null
        if (locked.isFighter || locked.isDrone) return null
        return if (isValidTarget(engine, locked)) locked else null
    }

    /** 反舰目标筛选：捕获射程内最近的敌方存活舰船（战机/无人机/导弹不入选）。 */
    private fun selectTarget(engine: CombatEngineAPI): ShipAPI? {
        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (ship in engine.ships) {
            if (!isValidTarget(engine, ship)) continue
            if (ship.isFighter || ship.isDrone) continue
            val dist = MathUtils.getDistance(missile.location, ship.location)
            if (dist > maxRange * ACQUIRE_RANGE_MULT) continue
            if (dist < bestDist) {
                bestDist = dist
                best = ship
            }
        }
        return best
    }

    /** 目标有效性：在场 + 存活 + 非 hulk + 敌方。 */
    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI): Boolean =
        engine.isEntityInPlay(t) && t.isAlive && !t.isHulk && t.owner != missile.owner

    companion object {
        /** 捕获射程倍率：武器面板射程 ×1.25（追踪冗余，目检面）。 */
        private const val ACQUIRE_RANGE_MULT = 1.25f

        /** 遥测键：目标选定次数（dev 自动化烟测证据）。 */
        const val TELE_TARGET_SELECTED = "astd_cuifeng_tele_target_selected"

        /** 遥测键：调速器截速生效帧数（二段式慢速段证据面）。 */
        const val TELE_GOVERNED_FRAMES = "astd_cuifeng_tele_governed_frames"

        /** 遥测键：最近一次速度系数（0.5~1.0，二段式曲线观测面）。 */
        const val TELE_LAST_SPEED_FACTOR = "astd_cuifeng_tele_last_speed_factor"

        /** 遥测计数自增（缺省 0 起）。 */
        private fun bump(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
        }
    }
}
