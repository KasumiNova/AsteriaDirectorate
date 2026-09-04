package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.campaign.companions.CompanionOfferDialogPlugin
import cn.kasuminova.astd.campaign.companions.CompanionService
import cn.kasuminova.astd.campaign.companions.CompanionState
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import java.awt.Color

/**
 * 战役侧管理脚本：
 * - 监听 MagicBounty 的 ActiveBounty 状态
 * - 在“接受”后对目标 fleet 进行动态重建（1500% 上限）
 * - 在“完成主线”后解锁更多词缀并生成若干唯一支线
 */
class BountyCampaignManager : EveryFrameScript {

    private companion object {
        private val log: Logger = Global.getLogger(BountyCampaignManager::class.java)
    }
    private var timer = 0f
    private var registered = false

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 0.7f) return
        timer = 0f

        val sector = Global.getSector() ?: return
        if (!registered) {
            try {
                // 只注册一次（同一局内）。
                MagicBountyBridge.registerMainBounties(overwrite = true)
                registered = true
            } catch (t: Throwable) {
                // 若 MagicLib 未加载/类缺失，这里会直接抛；但本模组默认依赖 MagicLib。
                log.warn("[BountyCampaignManager] Failed to register bounties: ${t.message}", t)
                registered = true
            }
        }

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

            // 2) 结算：成功后刷新支线 + 解锁词缀
            if (bounty.stage.ordinal >= ActiveBounty.Stage.Succeeded.ordinal && key !in state.concludedBountyKeys) {
                concludeBounty(key, bounty, state)
            }
        }

        // 主线结束后的同伴彩蛋：若待触发且 UI 空闲，则弹出一次选择对话。
        maybeShowCompanionOffer()
    }

    private fun maybeShowCompanionOffer() {
        val sector = Global.getSector() ?: return
        val ui = sector.campaignUI ?: return
        if (ui.isShowingDialog || ui.isShowingMenu) return

        val cState = CompanionState.getOrCreate()
        if (!cState.offerPending) return

        val target = sector.playerFleet ?: return
        ui.showInteractionDialog(CompanionOfferDialogPlugin(), target)
        // 对话中若选择“以后再说”会重新置回 offerPending=true。
        cState.offerPending = false
        CompanionService.ensureIntelAdded(cState)
    }

    private fun patchAcceptedBounty(key: String, bounty: ActiveBounty, state: BountyState) {
        val def = StoryBounties.mainsByKey[key]
        if (def == null) {
            // 支线：也做缩放/词缀，但 threatTier/FP 从 spec 读。
            patchAcceptedBountyGeneric(key, bounty, state)
            return
        }

        val fleet = bounty.fleet
        val flagship = fleet.flagship ?: return

        val seed = Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()
        val comp = FleetComposer.buildComposition(def, state, seed xor key.hashCode().toLong())
        // 主线完成文案：由舰队接触对话框在战斗结束后从 i18n 表读取
        patchFleetMembers(key, fleet, flagship, comp, successText = null)

        state.patchedBountyKeys.add(key)
            HudMessages.campaign(
                I18n.t("asteria_directorate_bounty", "hud.fleet_rebuilt", "scale" to "${(comp.totalMult * 100).toInt()}%"),
                Color(120, 200, 255)
            )
    }

    private fun patchAcceptedBountyGeneric(key: String, bounty: ActiveBounty, state: BountyState) {
        val fleet = bounty.fleet
        val flagship = fleet.flagship ?: return
        val tier = parseThreatTierFromSpec(bounty.spec.job_difficultyDescription)
        val baselineFP = bounty.spec.fleet_min_FP.coerceAtLeast(50)

        val pseudo = BountyDef(
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
        val comp = FleetComposer.buildComposition(pseudo, state, seed xor key.hashCode().toLong())
        // 支线/动态 bounty：没有对应 i18n 表时，提供一个简短的结算提示
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

        // 保险：显式写入 memKey，作为后续主线/支线 gating 条件。
        // MagicBounty 通常会处理这一点，但这里主动写入可避免版本/配置差异导致的门槛失效。
        if (bounty.stage == ActiveBounty.Stage.Succeeded) {
            Global.getSector()?.memoryWithoutUpdate?.set("\$$key", true)
        }

        val isMain = key in StoryBounties.mainsByKey
        if (bounty.stage == ActiveBounty.Stage.Succeeded && isMain) {
            state.mainCompleted += 1

            // 解锁更多词缀
            val newUnlocks = AffixRegistry.unlockForMainCompleted(state.mainCompleted)
            val before = state.unlockedAffixIds.size
            state.unlockedAffixIds.addAll(newUnlocks)
            val after = state.unlockedAffixIds.size

            // 刷出若干唯一支线
            MagicBountyBridge.registerSideBountiesAfterMain(key, count = 2)

            if (after > before) {
                    HudMessages.campaign(
                        I18n.t("asteria_directorate_bounty", "hud.main_progress_affix", "delta" to (after - before).toString()),
                        Color(170, 120, 255)
                    )
                } else {
                    HudMessages.campaign(I18n["asteria_directorate_bounty", "hud.main_progress_side"], Color(170, 120, 255))
            }

            // 主线最后节点：触发同伴彩蛋（一次性）。
            val lastKey = StoryBounties.mains.lastOrNull()?.key
            if (lastKey != null && key == lastKey) {
                val cState = CompanionState.getOrCreate()
                if (!cState.offered) {
                    cState.offerPending = true
                    CompanionService.ensureIntelAdded(cState)
                }
            }
        }

        // 支线成功：额外打捞奖励
        val isSide = key.startsWith("astd_side_")
        if (bounty.stage == ActiveBounty.Stage.Succeeded && isSide) {
            val fleet = bounty.fleet
            val k = try {
                fleet.memoryWithoutUpdate.getFloat(BountyKeys.MEM_K)
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val tier = parseThreatTierFromSpec(bounty.spec.job_difficultyDescription)
            val seed = (Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()) xor key.hashCode().toLong()
            BountyRewards.grantSideLoot(key, tier, k, seed)
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
