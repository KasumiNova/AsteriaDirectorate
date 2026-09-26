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
 * 摧锋鱼雷的自定义追踪 AI（blue/30-superlative.md §机制）：目标有效性校验 → 0.25s 节流重选
 * （反舰口径：仅舰船入选，战机/导弹永不入选）→ 领先瞄准 → giveCommand 转向加速，外加
 * 二段式速度调速器（发射时 50% 航速，航程 25%→50% 间线性升至满速）。
 *
 * 形态对齐 [StellarMrmMissileAI][cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmMissileAI]
 * 先例；挂载点：由 [CuifengTorpedoOnFireEffect] 经 `missile.setMissileAI(...)` 安装。
 *
 * 二段式的实现约束（规格实装核查）：`MissileAPI` 无 `setMaxSpeed`，引擎加速由 `.proj`
 * engineSpec 持续供给；调速器在每帧命令下发后对 `missile.velocity` 超帽部分直接截速
 * （velocity 可变 Vector2f，仓库已有 `ASTDXc002DroneSubsystem` 同款直写先例）。
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

        // ---- 目标维护（0.25s 节流：有效保留，失效重选；反舰口径仅舰船入选）----
        reselectTimer -= amount
        if (reselectTimer <= 0f) {
            reselectTimer = CuifengTorpedoDifficulty.RETARGET_INTERVAL
            val current = target
            if (current == null || !isValidTarget(engine, current)) {
                val picked = selectTarget(engine)
                if (picked !== current) {
                    target = picked
                    if (picked != null) {
                        bump(engine, TELE_TARGET_SELECTED)
                        log.info(
                            "[摧锋] 鱼雷选定目标：hull=${picked.hullSpec?.hullId} " +
                                    "dist=${MathUtils.getDistance(missile.location, picked.location).toInt()}",
                        )
                    } else {
                        log.info("[摧锋] 鱼雷无可选目标（候选全空/全越射程），直飞")
                    }
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
