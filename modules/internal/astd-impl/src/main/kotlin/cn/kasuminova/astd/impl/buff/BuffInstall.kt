package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.BuffBackend
import cn.kasuminova.astd.api.buff.BuffBackends
import cn.kasuminova.astd.api.buff.BuffHost
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

/** [BuffBackend] 默认实现：customData 惰性创建 + 心跳插件登记（原 api 侧 BuffAccess 的实现体）。 */
class BuffBackendImpl : BuffBackend {
    override fun hostFor(ship: ShipAPI): BuffHost {
        val existing = ship.customData[BuffHostImpl.HOST_KEY] as? BuffHostImpl
        if (existing != null) return existing

        val host = BuffHostImpl(ship.customData)
        ship.setCustomData(BuffHostImpl.HOST_KEY, host)
        BuffTickPlugin.ensure(Global.getCombatEngine())
        return host
    }
}

/** Buff 系统装配入口：mod 插件 onApplicationLoad 调用一次，把后端注入 api 侧 [BuffBackends]。 */
object BuffInstall {
    fun install() {
        BuffBackends.install(BuffBackendImpl())
    }
}
