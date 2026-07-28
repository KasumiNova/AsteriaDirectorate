package cn.kasuminova.astd.api.buff

import cn.kasuminova.astd.impl.buff.BuffHostImpl
import cn.kasuminova.astd.impl.buff.BuffTickPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * ShipAPI 的 Buff 便捷入口扩展函数族。
 *
 * 动机：把「customData 取 host、惰性创建、心跳插件登记」收敛为调用侧一行；
 * 具体武器的强类型入口（如 `ShipAPI.chargeNeedleBuff()`）不沉淀进公共 API，
 * 由各武器在自己包内基于本族扩展一行实现，避免公共包反向依赖武器包。
 */

/**
 * 取该船的 [BuffHost]；首次访问时在 `ship.customData` 惰性创建并登记心跳插件。
 * 引擎不可用（仅测试环境/非战斗场景）时跳过插件登记，host 本体仍可正常读写，
 * 下一次引擎可用的调用会补登记。
 */
fun ShipAPI.buffHost(): BuffHost {
    val existing = customData[BuffHostImpl.HOST_KEY] as? BuffHostImpl
    if (existing != null) return existing

    val host = BuffHostImpl(customData)
    setCustomData(BuffHostImpl.HOST_KEY, host)
    BuffTickPlugin.ensure(Global.getCombatEngine())
    return host
}

/**
 * 按 id 查该船的 Ship 级 Buff；不存在返回 null。
 */
fun ShipAPI.getBuff(id: String): Buff? = buffHost().find(id)

/**
 * 查该船的 Ship 级 Buff，不存在时用 [creator] 创建并注册；幂等，[creator] 至多执行一次。
 */
fun ShipAPI.getOrCreateBuff(id: String, creator: () -> Buff): Buff {
    val host = buffHost()
    return host.find(id) ?: creator().also { host.register(it) }
}

/**
 * Weapon 级：按 id 查指定武器的 Buff；内部走复合键（WeaponAPI 无 customData，已核实 jar）。
 * 槽位换装后旧 Buff 视为不存在（登记 weaponId 与当前武器不符）。
 */
fun ShipAPI.getBuffByWeapon(id: String, weapon: WeaponAPI): Buff? = buffHost().findByWeapon(id, weapon)

/**
 * Weapon 级：查指定武器的 Buff，不存在时用 [creator] 创建并注册到复合键。
 */
fun ShipAPI.getOrCreateBuffByWeapon(id: String, weapon: WeaponAPI, creator: () -> Buff): Buff {
    val host = buffHost()
    return host.findByWeapon(id, weapon) ?: creator().also { host.register(it, weapon) }
}
