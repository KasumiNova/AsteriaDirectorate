package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBountyBridge
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.dialog.story.StoryDialogBackend
import cn.kasuminova.astd.campaign.dialog.story.StoryDialogBackends
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * [StoryDialogBackend] 的 campaign 侧实现与装配入口。
 *
 * 无状态单例：全部数据实时读 sector memory / [BountyState]，可被 XStream 安全序列化
 * （BarEvent 对象图里只出现 holder 调用路径，本对象不被事件持有——保险起见仍保持无字段）。
 *
 * 装配：mod 插件 onApplicationLoad 调用 [StoryDialogInstall.install]（先例：BuffInstall）。
 */
object StoryDialogInstall {

    fun install() {
        StoryDialogBackends.install(StoryDialogBackendImpl)
    }
}

private object StoryDialogBackendImpl : StoryDialogBackend {

    private val log: Logger = Global.getLogger(StoryDialogBackendImpl::class.java)

    /** 玩家舰队 memory：序章工单已接取（签署完成，doc 04 sign 节点动作口径）。 */
    const val MEM_PROLOGUE_ACCEPTED: String = "\$astd_prologue_accepted"

    /** 玩家舰队 memory：代办谈话已开始（中断恢复标记，doc 04）。 */
    const val MEM_PROLOGUE_AGENT_MET: String = "\$astd_prologue_agent_met"

    /** makeImportant 原因标记（分局空间站星图引导）。 */
    const val IMPORTANT_REASON_PROLOGUE: String = "astd_prologue"

    private fun playerMemory() = Global.getSector()?.playerFleet?.memoryWithoutUpdate

    override fun isPrologueAccepted(): Boolean {
        if (playerMemory()?.getBoolean(MEM_PROLOGUE_ACCEPTED) == true) return true
        // 状态兜底：工单已挂出/击毁/核销或承包商已注册，都视为已接取（如旧档缺 memory 标记）
        val state = BountyState.getOrCreate()
        return MainBounties.KEY_PROLOGUE in state.postedWorkOrders ||
            MainBounties.KEY_PROLOGUE in state.destroyedWorkOrders ||
            MainBounties.KEY_PROLOGUE in state.settledWorkOrders ||
            state.contractorLevel >= 1
    }

    override fun isPrologueAgentMet(): Boolean =
        playerMemory()?.getBoolean(MEM_PROLOGUE_AGENT_MET) == true

    override fun markPrologueAgentMet() {
        playerMemory()?.set(MEM_PROLOGUE_AGENT_MET, true)
            ?: log.error("[ASTD] 写入序章谈话标记失败：playerFleet 不可用")
    }

    override fun playerName(): String =
        Global.getSector()?.playerPerson?.nameString
            ?: throw IllegalStateException("playerName：sector/playerPerson 不可用（仅在生涯对话中调用）")

    /**
     * 签署副作用（doc 04 sign 节点动作）。
     *
     * 幂等：工单已挂出/击毁/核销时视为已签署成功，直接补齐 memory 标记并返回 true——
     * 重复触发（对话重试、旧档重进）不重复发文书、不重复标记星图。
     * 失败时不写任何标记：已接取标记只在工单挂出成功后写入，保证玩家可重试签署。
     */
    override fun onPrologueSigned(): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 序章签署失败：sector 不可用")
            return false
        }

        // 幂等短路：工单已进入挂出/击毁/核销任一状态即视为已签署
        val state = BountyState.getOrCreate()
        if (MainBounties.KEY_PROLOGUE in state.postedWorkOrders ||
            MainBounties.KEY_PROLOGUE in state.destroyedWorkOrders ||
            MainBounties.KEY_PROLOGUE in state.settledWorkOrders
        ) {
            val mem = playerMemory()
            if (mem == null) {
                log.error("[ASTD] 序章签署标记补写失败：playerFleet 不可用")
            } else {
                mem.set(MEM_PROLOGUE_ACCEPTED, true)
            }
            return true
        }

        // 挂出序章核销单（不挂牌条目，唯一接取入口，doc 03 节拍 3）
        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            log.error("[ASTD] 序章签署失败：MagicBountyCoordinator 不可用", t)
            return false
        }
        val posted = MainBountyBridge.acceptPrologueWorkOrder(state, coord)
        if (!posted) {
            log.error("[ASTD] 序章签署失败：acceptPrologueWorkOrder 拒绝（已接取或章节推进异常）")
            return false
        }

        // 工单挂出成功后才写入已接取标记（失败不留痕，签署可重试）
        val mem = playerMemory()
        if (mem == null) {
            log.error("[ASTD] 序章签署标记写入失败：playerFleet 不可用")
        } else {
            mem.set(MEM_PROLOGUE_ACCEPTED, true)
        }

        // 发放委托文书（物品化，可入 cargo/情报页复读，doc 03 节拍 2）
        StoryQuestItems.grantToPlayer(StoryQuestItems.DOC_XW_C206_0447)

        // 引导：分局空间站星图标记（核销点坐标显现，doc 03 节拍 3）
        val station = sector.getEntityById(StoryWorldIds.MAIN_STATION_BRANCH)
        if (station == null) {
            log.error("[ASTD] 序章引导失败：分局空间站实体缺失（${StoryWorldIds.MAIN_STATION_BRANCH}）")
        } else {
            Misc.makeImportant(station, IMPORTANT_REASON_PROLOGUE)
        }
        log.info("[ASTD] 序章工单已签署：${MainBounties.KEY_PROLOGUE}")
        return true
    }
}
