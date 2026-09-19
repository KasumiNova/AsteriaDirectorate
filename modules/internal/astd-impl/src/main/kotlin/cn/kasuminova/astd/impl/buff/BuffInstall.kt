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

        // 实体级 customData 写入契约（2026-09-20 引力相位甲板断言C排查实证）：字段惰性为 null 时
        // getCustomData() 返回一次性空表，直接捕获会让 Buff 读写落进与舰船脱节的游离表。
        // 先占位登记完成字段初始化，再捕获本体表构造宿主并替换登记（单线程战斗循环内占位不可见）。
        ship.setCustomData(BuffHostImpl.HOST_KEY, HOST_PLACEHOLDER)
        val host = BuffHostImpl(ship.customData)
        ship.setCustomData(BuffHostImpl.HOST_KEY, host)
        BuffTickPlugin.ensure(Global.getCombatEngine())
        return host
    }

    private companion object {
        /** 字段初始化占位值：仅存在于 hostFor 两次 setCustomData 之间，任何读取方都不应见到。 */
        val HOST_PLACEHOLDER = Any()
    }
}

/** Buff 系统装配入口：mod 插件 onApplicationLoad 调用一次，把后端注入 api 侧 [BuffBackends]。 */
object BuffInstall {
    fun install() {
        BuffBackends.install(BuffBackendImpl())
    }
}
