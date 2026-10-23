package cn.kasuminova.astd.combat.effect.joint.stardust

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.impl.combat.MoteAIScript
import com.fs.starfarer.api.impl.combat.MoteControlScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sin

class StardustMoteAI(missile: MissileAPI) : MoteAIScript(missile) {

    init {
        // 本舰不运行原版 MoteControlScript，motes 共享列表的注册与死弹修剪由本类承担。
        if (missile.source != null) {
            data.motes.add(missile)
        }
    }

    override fun advance(amount: Float) {
        super.advance(amount)
        val current = target
        if (current != null && !isInEngageRange(current)) {
            target = null
        }
    }

    // 覆写原版 doFlocking 移除同伴 cohesion 聚合（光尘各自环绕源舰）；波动时基用自身 elapsed，
    // 原版 SharedMoteAIData.elapsed 由 MoteControlScript 驱动，本链路不运行该脚本（恒不增长）。
    override fun doFlocking() {
        val source = missile.source ?: return
        val engine = Global.getCombatEngine() ?: return

        val sourceRejoin = source.collisionRadius + 200f
        val sourceRepel = source.collisionRadius + 50f
        val sourceCohesion = source.collisionRadius + 600f
        val avoidRange = MoteAIScript.AVOID_RANGE * (1f + sin(elapsed) * 0.25f)

        val total = Vector2f()

        var hardAvoiding = false
        for (other in hardAvoidList) {
            val dist = MathUtils.getDistance(missile.location, other.location)
            val hardAvoidRange = other.collisionRadius + avoidRange + 50f
            if (dist < hardAvoidRange) {
                val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(other.location, missile.location))
                val f = 1f - dist / hardAvoidRange
                dir.scale(f * 5f)
                Vector2f.add(total, dir, total)
                hardAvoiding = f > 0.5f
            }
        }

        for (otherMote in data.motes) {
            if (otherMote === missile) continue
            val dist = MathUtils.getDistance(missile.location, otherMote.location)
            if (dist < avoidRange && !hardAvoiding) {
                val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(otherMote.location, missile.location))
                dir.scale(1f - dist / avoidRange)
                Vector2f.add(total, dir, total)
            }
        }

        val distSource = MathUtils.getDistance(missile.location, source.location)
        if (distSource > sourceRejoin) {
            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(missile.location, source.location))
            dir.scale((distSource / (sourceRejoin + 400f) - 1f) * 0.5f)
            Vector2f.add(total, dir, total)
        }
        if (distSource < sourceRepel) {
            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(source.location, missile.location))
            dir.scale((1f - distSource / sourceRepel) * 5f)
            Vector2f.add(total, dir, total)
        }
        if (distSource < sourceCohesion && source.velocity.length() > 20f) {
            val dir = Vector2f(source.velocity)
            Misc.normalise(dir)
            dir.scale(1f - distSource / sourceCohesion)
            Vector2f.add(total, dir, total)
        }

        if (total.length() <= 0.05f) {
            val offset = if (r > 0.5f) 90f else -90f
            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(missile.location, source.location) + offset)
            Vector2f.add(total, dir, total)
        }

        if (total.length() > 0f) {
            engine.headInDirectionWithoutTurning(missile, Misc.getAngleInDegrees(total), 10000f)
            missile.giveCommand(if (r > 0.5f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            missile.engineController.forceShowAccelerating()
        }
    }

    override fun acquireNewTargetIfNeeded() {
        val engine = Global.getCombatEngine() ?: return
        data.motes.removeIf { !engine.isMissileAlive(it) }
        val source = missile.source ?: return

        target = pickMissile(engine)
            ?: pickShip(engine, fighters = true)
                    ?: pickShip(engine, fighters = false)
        if (source.isHulk) target = null
    }

    private fun pickMissile(engine: CombatEngineAPI): CombatEntityAPI? =
        CombatUtils.getMissilesWithinRange(missile.location, resolveEngageRange())
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    private fun pickShip(engine: CombatEngineAPI, fighters: Boolean): CombatEntityAPI? =
        CombatUtils.getShipsWithinRange(missile.location, resolveEngageRange())
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { if (fighters) it.isFighter else !it.isFighter }
            .filter { it.isAlive && !it.isHulk && !it.isPhased && it.isTargetable }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    private fun isInEngageRange(entity: CombatEntityAPI): Boolean {
        if (missile.source == null) return false
        return MathUtils.getDistance(missile.location, entity.location) <= resolveEngageRange()
    }

    // WeaponAPI.getRange 每次调用都全量重算舰船统计（profiler 热点），接敌半径首次解析后缓存。
    private var cachedEngageRange: Float = -1f

    private fun resolveEngageRange(): Float {
        if (cachedEngageRange > 0f) return cachedEngageRange
        val weapon = missile.weapon
        if (weapon == null) {
            StardustMoteTuning.warnOnce("missingWeapon") {
                "星尘光尘 ${missile.projectileSpecId} 无武器实例可查射程（脚本生成路径？），按缺省 800su 接敌半径兜底"
            }
        }
        val range = weapon?.range ?: StardustMoteTuning.DEFAULT_ENGAGE_RANGE
        cachedEngageRange = range
        return range
    }
}
