package cn.kasuminova.astd.combat.effect.joint.stardust

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.impl.combat.MoteAIScript
import com.fs.starfarer.api.impl.combat.MoteControlScript
import org.lazywizard.lazylib.MathUtils

/**
 * 星尘光尘 AI：继承原版 [MoteAIScript] 白拿 600su 环绕 flocking（源舰引力/斥力/切向漂移），
 * 覆写 [acquireNewTargetIfNeeded] 实现设计案索敌——优先级 导弹 > 战机 > 舰船，
 * 接敌范围 = 以光尘自身位置为圆心、半径取武器面板射程（武器实例缺失时缺省
 * [StardustMoteTuning.DEFAULT_ENGAGE_RANGE]）。
 *
 * 与原版差异（原版仅 PD 向导弹/战机，由系统脚本生成）：本弹体由真实导弹武器发射
 * （弹药/恢复走 weapon_data.csv），AI 经 `ModPlugin.pickMissileAI` 覆盖钩子指派
 * （原版引擎不为 MOTE 型弹体自动指派 AI，见 ProjectileFactory 的 pickMissileAIOverride 分支）。
 *
 * 原版 [MoteControlScript.SharedMoteAIData.motes] 的注册/清理由本类承担
 * （本舰没有 mote 系统脚本代为修剪）：构造时入群（flocking 聚集/单目标堆叠上限的依据），
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

    /** 最近敌导弹（含鱼雷；排除 owner 100 的中全体）。 */
    private fun pickMissile(engine: CombatEngineAPI): CombatEntityAPI? =
        engine.missiles
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    /** 最近敌战机/舰船（fighters=true 取战机档，false 取舰船档；排除 hulk/相位/不可选）。 */
    private fun pickShip(engine: CombatEngineAPI, fighters: Boolean): CombatEntityAPI? =
        engine.ships
            .asSequence()
            .filter { it.owner != missile.owner && it.owner != 100 }
            .filter { if (fighters) it.isFighter else !it.isFighter }
            .filter { it.isAlive && !it.isHulk && !it.isPhased && it.isTargetable }
            .filter { isInEngageRange(it) }
            .filter { getNumMotesTargeting(it) < StardustMoteTuning.MAX_MOTES_PER_TARGET }
            .minByOrNull { MathUtils.getDistance(missile.location, it.location) }

    /** 接敌判定：目标距光尘自身 ≤ 武器面板射程（武器实例缺失时按缺省 600su 并一次性 WARN——配置异常不静默）。 */
    private fun isInEngageRange(entity: CombatEntityAPI): Boolean {
        // 源舰消亡后光尘不再接敌（孤儿弹体只环绕游荡至自然熄灭）
        if (missile.source == null) return false
        val weapon = missile.weapon
        if (weapon == null) {
            StardustMoteTuning.warnOnce("missingWeapon") {
                "星尘光尘 ${missile.projectileSpecId} 无武器实例可查射程（脚本生成路径？），按缺省 600su 接敌半径兜底"
            }
        }
        val range = weapon?.range ?: StardustMoteTuning.DEFAULT_ENGAGE_RANGE
        return MathUtils.getDistance(missile.location, entity.location) <= range
    }
}
