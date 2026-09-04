package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 战役侧管理脚本：监听 MagicBounty 的 ActiveBounty 状态，
 * 在“接受”后对目标 fleet 应用难度缩放与词缀重建，并在结算后维护 [BountyState]。
 *
 * 主线内容由后续设定填充：新内容接入时在此补充对应推进逻辑。
 */
class BountyCampaignManager : EveryFrameScript {

    private companion object {
        private val log: Logger = Global.getLogger(BountyCampaignManager::class.java)
    }
    private var timer = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 0.7f) return
        timer = 0f

        val sector = Global.getSector() ?: return

        val state = BountyState.getOrCreate()

        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            return
        }

        val active = coord.activeBounties
        if (active.isEmpty()) return

        for ((key, bounty) in active) {
            if (!key.startsWith(BountyKeys.BOUNTY_KEY_PREFIX)) continue

            // 1) 接受后：动态重建舰队
            if (bounty.stage == ActiveBounty.Stage.Accepted && key !in state.patchedBountyKeys) {
                patchAcceptedBounty(key, bounty, state)
            }

            // 2) 结算：维护完成记录
            if (bounty.stage.ordinal >= ActiveBounty.Stage.Succeeded.ordinal && key !in state.concludedBountyKeys) {
                concludeBounty(key, bounty, state)
            }
        }
    }

    private fun patchAcceptedBounty(key: String, bounty: ActiveBounty, state: BountyState) {
        val fleet = bounty.fleet
        val flagship = fleet.flagship ?: return
        val tier = parseThreatTierFromSpec(bounty.spec.job_difficultyDescription)
        val baselineFP = bounty.spec.fleet_min_FP.coerceAtLeast(50)

        val def = BountyDef(
            key = key,
            title = bounty.spec.job_name ?: key,
            shortDesc = bounty.spec.job_description ?: "",
            threatTier = tier,
            baselineFP = baselineFP,
            flagshipVariantId = bounty.spec.fleet_flagship_variant,
            requiredPreviousMainKey = null,
            isMain = false,
        )

        val seed = Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()
        val comp = FleetComposer.buildComposition(def, state, seed xor key.hashCode().toLong())
        // 动态 bounty：没有对应 i18n 表时，提供一个简短的结算提示。
        val successText = I18n["asteria_directorate_bounty", "generic.success_text"]
        patchFleetMembers(key, fleet, flagship, comp, successText = successText)
        state.patchedBountyKeys.add(key)
    }

    private fun patchFleetMembers(
        key: String,
        fleet: CampaignFleetAPI,
        flagship: FleetMemberAPI,
        comp: FleetComposer.Composition,
        successText: String?,
    ) {
        if (fleet.memoryWithoutUpdate.getBoolean(BountyKeys.MEM_FLEET_PATCHED)) {
            return
        }
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_FLEET_PATCHED, true)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_BOUNTY_KEY, key)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_AFFIXES, comp.affixHullMods.joinToString(","))
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_K, comp.k)
        fleet.memoryWithoutUpdate.set(BountyKeys.MEM_TOTAL_MULT, comp.totalMult)

        // 让该舰队在交互时使用自定义的 FleetInteractionDialog 配置。
        // 注意：key 名是原版内部约定（拼写如此）。
        fleet.memoryWithoutUpdate.set("\$fidConifgGen", BountyFidConfigGen(key))

        // 某些原版逻辑会读取该 flag 来倾向“死战到底”。这里先开着，后续可按案子细化。
        fleet.memoryWithoutUpdate.set("\$core_fightToTheLast", true)

        if (!successText.isNullOrBlank()) {
            fleet.memoryWithoutUpdate.set(BountyKeys.MEM_SUCCESS_TEXT, successText)
        }

        val data = fleet.fleetData
        val existing = data.membersListCopy
        for (m in existing) {
            if (m !== flagship) {
                data.removeFleetMember(m)
            }
        }

        val created = FleetComposer.rebuildFleetMembers(
            bountyKey = key,
            fleetMembers = comp.pickedVariantIds,
            k = comp.k,
            totalMult = comp.totalMult,
            affixHullMods = comp.affixHullMods,
            flagship = flagship,
        )

        // 把创建出来的成员添加到 fleet
        for (m in created) {
            if (m !== flagship) {
                data.addFleetMember(m)
            }
        }
        data.setFlagship(flagship)
    }

    private fun concludeBounty(key: String, bounty: ActiveBounty, state: BountyState) {
        state.concludedBountyKeys.add(key)

        // 保险：显式写入 memKey，作为后续内容 gating 条件。
        // MagicBounty 通常会处理这一点，但这里主动写入可避免版本/配置差异导致的门槛失效。
        if (bounty.stage == ActiveBounty.Stage.Succeeded) {
            Global.getSector()?.memoryWithoutUpdate?.set("\$$key", true)
        }
    }

    private fun parseThreatTierFromSpec(desc: String?): Int {
        if (desc == null) return 3
        val idx = desc.indexOf('T')
        if (idx >= 0 && idx + 1 < desc.length) {
            val c = desc[idx + 1]
            if (c in '1'..'5') return (c - '0')
        }
        return 3
    }
}
