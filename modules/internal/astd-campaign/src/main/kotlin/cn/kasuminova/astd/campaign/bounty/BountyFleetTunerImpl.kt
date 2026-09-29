package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import cn.kasuminova.astd.campaign.bounty.core.BountySmodPresets
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.util.WeightedRandomPicker
import java.util.Random
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyLoader

class BountyFleetTunerImpl(
    private val smodPresets: BountySmodPresets = BountySmodPresetsImpl(),
    private val random: Random = Random(),
) : BountyFleetTuner {

    private val log: Logger = Global.getLogger(BountyFleetTunerImpl::class.java)

    override fun isAstdBountyFleet(fleet: CampaignFleetAPI): Boolean =
        fleet.tags.contains(MagicBountyLoader.BOUNTY_FLEET_TAG) && fleet.tags.any { it.startsWith(BOUNTY_KEY_PREFIX) }

    override fun tuneIfNeeded(fleet: CampaignFleetAPI) {
        val memory = fleet.memoryWithoutUpdate
        if (memory.getBoolean(TUNED_MEMKEY)) return

        replaceExcludedEscorts(fleet)
        fillToMaxFleetSize(fleet)
        installSmods(fleet)

        fleet.fleetData.setSyncNeeded()
        fleet.fleetData.syncIfNeeded()
        // 全部步骤成功后再落标记，中途异常可由下轮扫描重试补齐。
        memory.set(TUNED_MEMKEY, true)
        log.info("[ASTD] 赏金舰队 ${fleet.name} 后处理完成，最终规模 ${fleet.fleetData.membersListCopy.size} 艘 / ${fleet.fleetPoints} FP")
    }

    private fun replaceExcludedEscorts(fleet: CampaignFleetAPI) {
        val excluded = fleet.fleetData.membersListCopy.filter { !it.isFlagship && isEscortExcludedMember(it) }
        excluded.forEach { member ->
            fleet.fleetData.removeFleetMember(member)
            log.info("[ASTD] 赏金舰队 ${fleet.name} 清退排除舰 ${member.hullId}，重新抽取替代")
        }
        var guard = MAX_PICK_ATTEMPTS
        var replaced = 0
        while (replaced < excluded.size && guard-- > 0) {
            if (pickEscortIntoFleet(fleet)) replaced++
        }
    }

    private fun fillToMaxFleetSize(fleet: CampaignFleetAPI) {
        val cap = maxFleetSizeSetting()
        var guard = MAX_PICK_ATTEMPTS
        while (fleet.fleetData.membersListCopy.size < cap && guard-- > 0) {
            pickEscortIntoFleet(fleet)
        }
    }

    private fun pickEscortIntoFleet(fleet: CampaignFleetAPI): Boolean {
        val faction = Global.getSector().getFaction(ASTD_FACTION_ID)
        val rolePicker = WeightedRandomPicker<String>(random)
        ROLE_WEIGHTS.forEach { (role, weight) -> rolePicker.add(role, weight) }
        val role = rolePicker.pick() ?: return false

        val before = fleet.fleetData.membersListCopy.mapTo(HashSet()) { it.id }
        val addedFP = faction.pickShipAndAddToFleet(
            role,
            FactionAPI.ShipPickParams(FactionAPI.ShipPickMode.PRIORITY_THEN_ALL),
            fleet,
            random,
        )
        if (addedFP <= 0f) return false
        val added = fleet.fleetData.membersListCopy.firstOrNull { it.id !in before } ?: return false
        if (isEscortExcludedMember(added)) {
            fleet.fleetData.removeFleetMember(added)
            return false
        }
        // 与 MagicLib 补强一致：不额外生成军官；CR 拉满与赏金舰队初始状态对齐。
        added.captain = null
        added.repairTracker.cr = added.repairTracker.maxCR
        return true
    }

    private fun installSmods(fleet: CampaignFleetAPI) {
        val neutralStats = Global.getFactory().createPerson().stats
        for (member in fleet.fleetData.membersListCopy) {
            if (member.isCivilian) continue
            val hull = member.hullSpec
            var variant = member.variant
            var remaining = Misc.getMaxPermanentMods(member, neutralStats) - variant.sMods.size - variant.permaMods.size
            if (remaining <= 0) continue

            val candidates = smodPresets.prioritiesFor(
                hull.hullId,
                hull.isPhase,
                hull.hints.contains(ShipHullSpecAPI.ShipTypeHints.CARRIER),
                hull.hullSize == ShipAPI.HullSize.CAPITAL_SHIP,
            )
            var cloned = false
            for (id in candidates) {
                if (remaining <= 0) break
                val spec = Global.getSettings().getHullModSpec(id) ?: continue
                if (hull.builtInMods.contains(id) || variant.hullMods.contains(id)) continue
                if (variant.getUnusedOP(neutralStats) < smodCostFor(spec, hull.hullSize)) continue
                if (!cloned) {
                    // 赏金舰队成员可能共享全局变体实例，内插前必须先克隆，避免污染 stock 变体。
                    variant = variant.clone()
                    member.setVariant(variant, false, false)
                    // 防止舰队 inflater 之后重洗装配把 SMod 冲掉。
                    variant.addTag("no_autofit")
                    cloned = true
                }
                variant.addPermaMod(id, true)
                remaining--
            }
        }
    }

    companion object {
        const val BOUNTY_KEY_PREFIX: String = "astd_bounty_"
        const val TUNED_MEMKEY: String = "\$astd_bounty_tuned"
        const val ASTD_FACTION_ID: String = "asteria_directorate"

        /** 单次后处理中随机抽取的最大尝试次数（排除舰重抽与规模补齐共用，防止抽取失败死循环）。 */
        const val MAX_PICK_ATTEMPTS: Int = 200

        const val UNIQUE_HULL_TAG: String = "astd_unique"
        const val EXCLUDED_PRODUCTION_HULL: String = "astd_xc_104"

        /** 补抽护航时按原版 doctrine 角色加权抽取（独特舰角色命中后由排除逻辑重抽）。 */
        val ROLE_WEIGHTS: List<Pair<String, Float>> = listOf(
            "combatLarge" to 25f,
            "combatMedium" to 25f,
            "combatSmall" to 15f,
            "combatCapital" to 15f,
            "phaseMedium" to 8f,
            "carrierLarge" to 7f,
            "carrierSmall" to 5f,
        )

        /**
         * 护航排除判定：全部独特舰（astd_unique，含发布范围外的决明与当期旗舰同型舰）
         * 以及逐电 astd_xc_104 不得出现在赏金舰队护航位。
         */
        fun isEscortExcludedHull(hullId: String, astdUnique: Boolean): Boolean =
            astdUnique || hullId == EXCLUDED_PRODUCTION_HULL

        /** 原版 settings.json 的 AI 舰队最大舰船数（0.98 无 FP 口径的舰队上限配置）。 */
        fun maxFleetSizeSetting(): Int {
            val aiCap = Global.getSettings().getInt("maxShipsInAIFleet")
            return if (aiCap > 0) aiCap else Global.getSettings().getInt("maxShipsInFleet")
        }

        fun smodCostFor(spec: HullModSpecAPI, size: ShipAPI.HullSize): Int = when (size) {
            ShipAPI.HullSize.CAPITAL_SHIP -> spec.capitalCost
            ShipAPI.HullSize.CRUISER -> spec.cruiserCost
            ShipAPI.HullSize.DESTROYER -> spec.destroyerCost
            else -> spec.frigateCost
        }

        private fun isEscortExcludedMember(member: FleetMemberAPI): Boolean =
            isEscortExcludedHull(member.hullId, member.hullSpec.hasTag(UNIQUE_HULL_TAG))
    }
}
