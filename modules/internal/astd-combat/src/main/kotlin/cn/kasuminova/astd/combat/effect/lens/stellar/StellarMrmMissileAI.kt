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

    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI): Boolean =
        engine.isEntityInPlay(t) && t.isAlive && !t.isHulk && t.owner != missile.owner

    companion object {
        private const val ACQUIRE_RANGE = 2500f

        const val TELE_SEL_FIGHTER = "astd_stellar_mrm_tele_sel_fighter"

        const val TELE_SEL_SHIP = "astd_stellar_mrm_tele_sel_ship"

        const val TELE_FIRST_TARGET = "astd_stellar_mrm_tele_first_target"
    }
}
