package cn.kasuminova.astd.combat.effect.arc.rare

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs

/**
 * RCT-6：一次性的“末端修正”导弹 AI。
 *
 * - 大部分时间转向保守（更像“计算好的拦截”而不是持续追踪）
 * - 进入近距离后触发一次短促的强修正窗口，提高命中率
 * - 同时负责持续喷焰/尾迹粒子（避免额外注册全局 combat plugin）
 */
class Rct6TerminalCorrectionAI(private val missile: MissileAPI) : MissileAIPlugin {

    private var target: CombatEntityAPI? = null

    private var correctionTimer = 0f
    private var correctionUsed = false

    private val smokeInterval = IntervalUtil(0.03f, 0.06f)

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        if (missile.isFading || missile.isExpired) return

        // acquire target
        if (!isTargetValid(target, engine)) {
            // MissileAPI 本身没有 target 字段；优先用发射舰的锁定目标
            target = missile.source?.shipTarget
            if (!isTargetValid(target, engine)) {
                target = AIUtils.getNearestEnemy(missile)
            }
        }

        val t = target
        if (t != null) {
            val dist = Misc.getDistance(missile.location, t.location)

            // 进入末端窗口：一次强修正
            if (!correctionUsed && dist <= 550f) {
                correctionUsed = true
                correctionTimer = 0.55f
            }

            val angleTo = Misc.getAngleInDegrees(missile.location, t.location)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)

            // 大部分时间“保守修正”，末端窗口“强修正”
            val turnAggressive = correctionTimer > 0f
            val turnThreshold = if (turnAggressive) 0.5f else 4.0f

            if (abs(diff) > turnThreshold) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }

            missile.giveCommand(ShipCommand.ACCELERATE)

            if (correctionTimer > 0f) {
                correctionTimer -= amount
                // 末端窗口再给一次加速，制造“末端爆发”的体感
                missile.giveCommand(ShipCommand.ACCELERATE)
            }
        } else {
            // 没目标：直射
            missile.giveCommand(ShipCommand.ACCELERATE)
        }

        // 尾迹/喷焰（轻量粒子）
        smokeInterval.advance(amount)
        if (smokeInterval.intervalElapsed()) {
            val loc = missile.location
            val vel = missile.velocity

            val back = MathUtils.getPointOnCircumference(null, 10f, missile.facing + 180f)
            val p = Vector2f(loc.x + back.x, loc.y + back.y)

            engine.addNebulaParticle(
                p,
                Vector2f(vel.x * 0.6f, vel.y * 0.6f),
                MathUtils.getRandomNumberInRange(16f, 22f),
                1.3f,
                0.1f,
                0.25f,
                MathUtils.getRandomNumberInRange(0.6f, 0.9f),
                Color(80, 110, 130, 75),
                true,
            )

            engine.addSmoothParticle(
                p,
                Vector2f(vel.x * 0.4f, vel.y * 0.4f),
                MathUtils.getRandomNumberInRange(8f, 12f),
                1.35f,
                0.18f,
                Color(200, 230, 255, 140),
            )
        }
    }

    private fun isTargetValid(t: CombatEntityAPI?, engine: com.fs.starfarer.api.combat.CombatEngineAPI): Boolean {
        if (t == null) return false
        if (!engine.isEntityInPlay(t)) return false
        if (t.owner == missile.owner) return false
        val ship = t as? ShipAPI
        if (ship != null) {
            if (ship.isHulk || ship.isPhased) return false
        }
        return true
    }
}
