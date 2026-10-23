package cn.kasuminova.astd.api.buff

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 提供舰船 Buff 宿主。实现必须在 ModPlugin.onApplicationLoad 中通过 [BuffBackends.install] 注册。
 * 公共 API 只保留宿主访问；武器强类型入口禁止进入 api 包，api 不得反向依赖 impl。
 */
interface BuffBackend {
    /**
     * 取该船的 [BuffHost]；首次访问时在 `ship.customData` 惰性创建并登记心跳插件。
     * 引擎不可用（仅测试环境/非战斗场景）时跳过插件登记，host 本体仍可正常读写，
     * 下一次引擎可用的调用会补登记。
     */
    fun hostFor(ship: ShipAPI): BuffHost
}

/** 保存已注册的 [BuffBackend]。未注册时 [get] 抛出 [IllegalStateException]。 */
object BuffBackends {
    @Volatile
    private var current: BuffBackend? = null

    fun install(backend: BuffBackend) {
        current = backend
    }

    fun get(): BuffBackend =
        current ?: throw IllegalStateException("BuffBackend 未注册：mod 插件 onApplicationLoad 应调用 impl 侧的 BuffInstall.install()")
}

/** 返回该船的 [BuffHost]，行为与异常同 [BuffBackend.hostFor]。 */
fun ShipAPI.buffHost(): BuffHost = BuffBackends.get().hostFor(this)

/** 返回该船的舰船级 Buff，不存在时返回 null。 */
fun ShipAPI.getBuff(id: String): Buff? = buffHost().find(id)

/** 返回该船的舰船级 Buff，不存在时调用 [creator] 创建并注册。 */
fun ShipAPI.getOrCreateBuff(id: String, creator: () -> Buff): Buff {
    val host = buffHost()
    return host.find(id) ?: creator().also { host.register(it) }
}

/** 返回关联指定武器的 Buff；不存在或槽位已换装时返回 null。 */
fun ShipAPI.getBuffByWeapon(id: String, weapon: WeaponAPI): Buff? = buffHost().findByWeapon(id, weapon)

/** 返回关联指定武器的 Buff，不存在时调用 [creator] 创建并注册。 */
fun ShipAPI.getOrCreateBuffByWeapon(id: String, weapon: WeaponAPI, creator: () -> Buff): Buff {
    val host = buffHost()
    return host.findByWeapon(id, weapon) ?: creator().also { host.register(it, weapon) }
}
