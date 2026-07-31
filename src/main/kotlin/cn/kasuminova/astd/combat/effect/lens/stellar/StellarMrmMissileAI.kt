package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.combat.StellarMrmTargeting
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import kotlin.math.abs

/**
 * 辉星导弹的自定义追踪 AI（规格 08 §2.2）：目标有效性校验 → 0.25s 节流重选
 * （[StellarMrmTargeting] 战机优先，导弹永不入选）→ 领先瞄准 → giveCommand 转向加速。
 *
 * 形态对齐 `ASTDPursuitVirtualParticleAI` 先例（同表 `engine.getShips()` 上按 isFighter 过滤、
 * TURN_LEFT/RIGHT + ACCELERATE 驱动）；挂载点：由 [StellarMrmOnFireEffect] 经
 * `missile.setMissileAI(...)` 安装。
 *
 * 目标选定/变更输出 INFO 日志（目标类型 + hull id）——烟测检查点「优先追猎」「不主动拦导弹」
 * 的日志佐证通道。
 */
class StellarMrmMissileAI(
    private val missile: MissileAPI,
    private val targeting: StellarMrmTargeting,
) : MissileAIPlugin {

    private val log = Global.getLogger(StellarMrmMissileAI::class.java)

    private var target: ShipAPI? = null
    private var reselectTimer = 0f

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        // ---- 目标维护（0.25s 节流：有效保留，失效重选）----
        reselectTimer -= amount
        if (reselectTimer <= 0f) {
            reselectTimer = StellarMrmDifficulty.RETARGET_INTERVAL
            val current = target
            if (current == null || !isValidTarget(engine, current)) {
                val picked = targeting.select(
                    engine.ships,
                    missile.location,
                    missile.owner,
                    ACQUIRE_RANGE,
                ) { engine.isEntityInPlay(it) }
                if (picked !== current) {
                    target = picked
                    if (picked != null) {
                        // 目标选择遥测（dev 自动化烟测证据：优先追猎/不主动拦导弹观测面）。
                        val selKey = if (picked.isFighter) TELE_SEL_FIGHTER else TELE_SEL_SHIP
                        engine.customData[selKey] = (engine.customData[selKey] as? Int ?: 0) + 1
                        if (engine.customData[TELE_FIRST_TARGET] == null) {
                            engine.customData[TELE_FIRST_TARGET] = if (picked.isFighter) "fighter" else "ship"
                        }
                        log.info(
                            "[辉星] 导弹选定目标：类型=${if (picked.isFighter) "战机" else "舰船"}" +
                                " hull=${picked.hullSpec?.hullId} dist=${MathUtils.getDistance(missile.location, picked.location).toInt()}",
                        )
                    } else {
                        log.info("[辉星] 导弹无可选目标（候选全空/全越射程），直飞")
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
    }

    /** 目标有效性：在场 + 存活 + 非 hulk + 敌方。 */
    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI): Boolean =
        engine.isEntityInPlay(t) && t.isAlive && !t.isHulk && t.owner != missile.owner

    companion object {
        /** 捕获射程（su）：与武器面板射程 2500 一致（定案）。 */
        private const val ACQUIRE_RANGE = 2500f

        /** 目标选择遥测键：选中战机次数（dev 自动化烟测证据）。 */
        const val TELE_SEL_FIGHTER = "astd_stellar_mrm_tele_sel_fighter"

        /** 目标选择遥测键：选中舰船次数（含无人机兜底）。 */
        const val TELE_SEL_SHIP = "astd_stellar_mrm_tele_sel_ship"

        /** 目标选择遥测键：全场首次选中类型（"fighter"/"ship"，优先追猎证据）。 */
        const val TELE_FIRST_TARGET = "astd_stellar_mrm_tele_first_target"
    }
}
