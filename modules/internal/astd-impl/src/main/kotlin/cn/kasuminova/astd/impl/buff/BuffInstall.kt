package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.BuffBackend
import cn.kasuminova.astd.api.buff.BuffBackends
import cn.kasuminova.astd.api.buff.BuffHost
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

class BuffBackendImpl : BuffBackend {
    override fun hostFor(ship: ShipAPI): BuffHost {
        val existing = ship.customData[BuffHostImpl.HOST_KEY] as? BuffHostImpl
        if (existing != null) return existing

        // 字段未初始化时 getCustomData() 返回一次性空表，必须先 setCustomData 再取得本体表。
        ship.setCustomData(BuffHostImpl.HOST_KEY, HOST_PLACEHOLDER)
        val host = BuffHostImpl(ship.customData)
        ship.setCustomData(BuffHostImpl.HOST_KEY, host)
        BuffTickPlugin.ensure(Global.getCombatEngine())
        return host
    }

    private companion object {
        val HOST_PLACEHOLDER = Any()
    }
}

object BuffInstall {
    fun install() {
        BuffBackends.install(BuffBackendImpl())
    }
}
