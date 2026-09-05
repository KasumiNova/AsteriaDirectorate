package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MagicBountyBridge
import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.story.PrologueAgentDialog
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import org.apache.log4j.Logger
import java.awt.Color
import java.util.Locale

/**
 * 序章签字回调（[PrologueAgentDialog.CALLBACK_ACCEPT]）的业务实现：
 * 玩家签下首份文书后——
 *
 * - 写 [BountyKeys.MEM_PROLOGUE_DOC_RECEIVED]（序章 MagicBounty 的出现门槛，签字即刻挂出）；
 * - 登记承包商账户（编号 + 注册日期，落 [BountyState] 存档）；
 * - 兜底补齐 MagicBounty 注册器数据（管理脚本通常已注册）；
 * - HUD 回执告知工单已挂出。
 *
 * 文书为纸质剧情道具，不进玩家货舱（数据侧无对应物品），任务状态即“文书已签收”。
 */
object PrologueContract {

    private val log: Logger = Global.getLogger(PrologueContract::class.java)
    private val MAIN_COLOR = Color(120, 200, 255)

    /** 序章对话用的命名回调表（目前仅签字回调）。 */
    fun callbacks(): Map<String, (DialogContext) -> Unit> =
        mapOf(PrologueAgentDialog.CALLBACK_ACCEPT to { onSigned() })

    private fun onSigned() {
        val sector = Global.getSector() ?: return
        if (sector.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_PROLOGUE_DOC_RECEIVED)) return
        sector.memoryWithoutUpdate.set(BountyKeys.MEM_PROLOGUE_DOC_RECEIVED, true)

        val state = BountyState.getOrCreate()
        if (state.contractorId.isEmpty()) {
            state.contractorId = generateContractorId(sector)
            state.registerCycle = sector.clock.getDateString()
        }

        if (!MagicBountyBridge.mainBountiesRegistered()) {
            log.info("[PrologueContract] MagicBounty 注册器数据缺失，签字回调内补注册。")
            MagicBountyBridge.registerMainBounties(overwrite = true)
        }

        HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "prologue.doc_received"], MAIN_COLOR)
        log.info("[PrologueContract] 序章文书已签收（contractor=${state.contractorId}），序章工单出现门槛已放开。")
    }

    /** 分局登记口径的承包商编号（标识符，非展示文案）：C7- + 6 位十六进制。 */
    private fun generateContractorId(sector: SectorAPI): String {
        val seedText = (sector.playerPerson?.nameString ?: "") + sector.clock.timestamp
        return "C7-" + String.format(Locale.ROOT, "%06X", seedText.hashCode() and 0xFFFFFF)
    }
}
