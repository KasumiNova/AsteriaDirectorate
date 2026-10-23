package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

class BountyCoreLootScript : EveryFrameScript {

    private val log: Logger = Global.getLogger(BountyCoreLootScript::class.java)
    private var timer: Float = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < SCAN_INTERVAL) return
        timer = 0f

        val sector = Global.getSector() ?: return
        val memory = sector.memoryWithoutUpdate
        for (bountyKey in BountyPoolConfig.THREAT_TIERS.keys) {
            if (!memory.getBoolean(bountyKey + SUCCEEDED_MEMKEY_SUFFIX)) continue
            if (memory.getBoolean(PAID_MEMKEY_PREFIX + bountyKey)) continue

            val raw = memory.get(BountyFleetTunerImpl.CORES_MEMKEY_PREFIX + bountyKey)
            if (raw !is List<*>) {
                log.error("[ASTD] 赏金 $bountyKey 已成功但缺少装舰核心表，无法发放核心打捞")
                memory.set(PAID_MEMKEY_PREFIX + bountyKey, true)
                continue
            }

            val installed = raw.filterIsInstance<String>()
            val loot = StandardCores.rollCoreLoot(installed, installed.hashCode().toLong())
            if (loot.isEmpty()) {
                log.warn("[ASTD] 赏金 $bountyKey 装舰核心均不可打捞（全 O 档），无核心掉落")
            } else {
                val cargo = sector.playerFleet.cargo
                loot.forEach { (coreId, count) -> cargo.addCommodity(coreId, count.toFloat()) }
                log.info("[ASTD] 赏金 $bountyKey 核心打捞发放：$loot（装舰 ${installed.size} 枚）")
            }
            memory.set(PAID_MEMKEY_PREFIX + bountyKey, true)
            memory.unset(BountyFleetTunerImpl.CORES_MEMKEY_PREFIX + bountyKey)
        }
    }

    companion object {
        const val SCAN_INTERVAL: Float = 1f

        const val SUCCEEDED_MEMKEY_SUFFIX: String = "_succeeded"

        const val PAID_MEMKEY_PREFIX: String = "\$astd_bounty_loot_paid_"
    }
}
