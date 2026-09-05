package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.ui.DirectorateTerminalKeys
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 剧情运行时维护脚本（约 1s 一跳）：
 *
 * - 序章酒馆事件 creator 注册（[BarEventManager] 就绪时机不保证早于本脚本，做一次性重试）；
 * - 消费终端核销意图焦点（[DirectorateTerminalKeys.SETTLEMENT_FOCUS]），把目标舰队标记为重要。
 */
class StoryRuntimeScript : EveryFrameScript {

    private companion object {
        val log: Logger = Global.getLogger(StoryRuntimeScript::class.java)
    }

    private var timer = 0f
    private var barCreatorRegistered = false

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 1f) return
        timer = 0f

        val sector = Global.getSector() ?: return
        ensureBarEventCreator()
        consumeSettlementFocus(sector)
    }

    private fun ensureBarEventCreator() {
        if (barCreatorRegistered) return
        val manager = BarEventManager.getInstance() ?: return
        if (!manager.hasEventCreator(PrologueAgentBarEventCreator::class.java)) {
            manager.addEventCreator(PrologueAgentBarEventCreator())
            log.info("[StoryRuntimeScript] 序章酒馆事件 creator 已注册到 BarEventManager。")
        }
        barCreatorRegistered = true
    }

    private fun consumeSettlementFocus(sector: SectorAPI) {
        val mem = sector.memoryWithoutUpdate
        val focus = mem.getString(DirectorateTerminalKeys.SETTLEMENT_FOCUS) ?: return
        mem.unset(DirectorateTerminalKeys.SETTLEMENT_FOCUS)

        val fleet = try {
            MagicBountyCoordinator.getInstance().getActiveBounty(focus)?.fleet
        } catch (t: Throwable) {
            log.warn("[StoryRuntimeScript] 核销意图焦点消费失败：MagicBountyCoordinator 不可用（$focus）：${t.message}")
            return
        }
        if (fleet != null) {
            Misc.makeImportant(fleet, "astd_terminal_settlement")
        } else {
            log.warn("[StoryRuntimeScript] 核销意图焦点 '$focus' 没有对应的活动赏金舰队，已仅清除焦点。")
        }
    }

}
