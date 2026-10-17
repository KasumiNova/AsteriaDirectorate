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

/**
 * 星尘光尘 AI：继承原版 [MoteAIScript]，覆写 [acquireNewTargetIfNeeded] 实现设计案索敌——
 * 优先级 导弹 > 战机 > 舰船，接敌范围 = 以光尘自身位置为圆心、半径取武器面板射程
 * （武器实例缺失时缺省 [StardustMoteTuning.DEFAULT_ENGAGE_RANGE]）。
 *
 * 环绕行为（2026-10-17 实机裁定）：覆写 [doFlocking] 移除原版同伴聚合（cohesion）力——
 * 光尘各自环绕源舰航行，不允许自发聚集成团；保留源舰中心三力（太近排斥/太远重聚/随行聚合）、
 * 同伴间适度排斥与硬避障，见覆写实现注释。
 *
 * 与原版差异（原版仅 PD 向导弹/战机，由系统脚本生成）：本弹体由真实导弹武器发射
 * （弹药/恢复走 weapon_data.csv），AI 经 `ModPlugin.pickMissileAI` 覆盖钩子指派
 * （原版引擎不为 MOTE 型弹体自动指派 AI，见 ProjectileFactory 的 pickMissileAIOverride 分支）。
 *
 * 原版 [MoteControlScript.SharedMoteAIData.motes] 的注册/清理由本类承担
 * （本舰没有 mote 系统脚本代为修剪）：构造时入群（flocking 排斥/单目标堆叠上限的依据），
 * 每次索敌扫描时顺带修剪死弹。
 */
class StardustMoteAI(missile: MissileAPI) : MoteAIScript(missile) {

    init {
        // 源舰缺失的孤儿弹体不入群（共享列表无宿主可依附，入群只会滞留至战斗结束）
        if (missile.source != null) {
            data.motes.add(missile)
        }
    }

    override fun advance(amount: Float) {
        super.advance(amount)
        // 目标跑出接敌半径时放弃（回到环绕态；isTargetValid 只判存活/归属，不管距离）
        val current = target
        if (current != null && !isInEngageRange(current)) {
            target = null
        }
    }

    /**
     * 环绕力合成（覆写原版 [MoteAIScript.doFlocking]）：各自环绕源舰，**不含同伴聚合**。
     *
     * 相对原版移除的力：同伴 cohesion（[MoteAIScript.COHESION_RANGE] 100su 内沿同伴速度方向吸引）——
     * 该力让光尘自发聚集成群，与「各自环绕」裁定相悖。
     *
     * 保留的力（参数与原版逐字一致）：
     * - 硬避障：舰船/陨石斥力（父类 advance 以 0.05~0.1s 节拍刷新 [hardAvoidList]）；
     * - 同伴排斥：[MoteAIScript.AVOID_RANGE] 随全局相位 ±25% 波动——聚集的对立面，防叠成一团；
     * - 源舰三力：太远重聚（半径+200）、太近排斥（半径+50）、随行聚合（半径+600 且源舰速度>20）；
     * - 无合力时绕源舰切向漂移（原版兜底，保证环绕而非悬停）。
     *
     * 原版 attractor 拉力分支不保留：ASTD 链路无 mote 系统脚本写入
     * attractorTarget/attractorLock（恒 null），该分支在本模组为恒死代码。
     */
    override fun doFlocking() {
        val source = missile.source ?: return
        val engine = Global.getCombatEngine() ?: return

        val sourceRejoin = source.collisionRadius + 200f
        val sourceRepel = source.collisionRadius + 50f
        val sourceCohesion = source.collisionRadius + 600f
        val avoidRange = MoteAIScript.AVOID_RANGE * (1f + sin(data.elapsed) * 0.25f)

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

        // 同伴间仅保留适度排斥（原版的 cohesion 聚合分支已移除）
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
        // 修剪死弹不依赖源舰（source==null 时父类不会再调本方法，这里防的是调用期源舰刚消亡）
        data.motes.removeIf { !engine.isMissileAlive(it) }
        val source = missile.source ?: return

        target = pickMissile(engine)
            ?: pickShip(engine, fighters = true)
                    ?: pickShip(engine, fighters = false)
        // 环绕基准跟随源舰（不使用原版 attractor 机制，data.attractorLock 恒 null）
        if (source.isHulk) target = null
    }

    /** 最近敌导弹（含鱼雷；排除 owner 100 的中全体）。空间网格粗筛后再按原口径精判。 */
    private fun pickMissile(engine: CombatEngineAPI): CombatEntityAPI? =
        CombatUtils.getMissilesWithinRange(missile.location, resolveEngageRange())
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    /** 最近敌战机/舰船（fighters=true 取战机档，false 取舰船档；排除 hulk/相位/不可选）。空间网格粗筛后再按原口径精判。 */
    private fun pickShip(engine: CombatEngineAPI, fighters: Boolean): CombatEntityAPI? =
        CombatUtils.getShipsWithinRange(missile.location, resolveEngageRange())
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { if (fighters) it.isFighter else !it.isFighter }
            .filter { it.isAlive && !it.isHulk && !it.isPhased && it.isTargetable }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    /** 接敌判定：目标距光尘自身 ≤ 武器面板射程（武器实例缺失时按缺省 800su 并一次性 WARN——配置异常不静默）。 */
    private fun isInEngageRange(entity: CombatEntityAPI): Boolean {
        // 源舰消亡后光尘不再接敌（孤儿弹体只环绕游荡至自然熄灭）
        if (missile.source == null) return false
        return MathUtils.getDistance(missile.location, entity.location) <= resolveEngageRange()
    }

    /**
     * 接敌半径解析（带缓存）：武器面板射程在光尘存续期（秒级）内视为不变——
     * WeaponAPI.getRange 每次调用都全量重算舰船统计（射程乘区/基础射程/平值加算），
     * 逐候选调用是实锤的 profiler 热点，故首次解析后缓存复用；武器实例恒缺失时
     * 一次性 WARN 并缓存缺省值（[StardustMoteTuning.DEFAULT_ENGAGE_RANGE]）。
     */
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
