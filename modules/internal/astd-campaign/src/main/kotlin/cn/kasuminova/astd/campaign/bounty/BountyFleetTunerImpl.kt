package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.BountySmodPresets
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import cn.kasuminova.astd.campaign.bounty.core.RemnantPick
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import com.fs.starfarer.api.impl.campaign.ids.Factions
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

        val bountyKey = bountyKeyOf(fleet)
        val poolConfig = BountyPoolConfig.forBountyKey(bountyKey)
        replaceExcludedEscorts(fleet)
        fillToMaxFleetSize(fleet, poolConfig)
        enforceHalfAstd(fleet, poolConfig)
        assignCrew(fleet, bountyKey)
        installSmods(fleet)

        fleet.fleetData.setSyncNeeded()
        fleet.fleetData.syncIfNeeded()
        // 全部步骤成功后再落标记，中途异常可由下轮扫描重试补齐。
        memory.set(TUNED_MEMKEY, true)
        log.info("[ASTD] 赏金舰队 ${fleet.name} 后处理完成，最终规模 ${fleet.fleetData.membersListCopy.size} 艘 / ${fleet.fleetPoints} FP")
    }

    private fun bountyKeyOf(fleet: CampaignFleetAPI): String? =
        fleet.tags.firstOrNull { it.startsWith(BOUNTY_KEY_PREFIX) }

    private fun replaceExcludedEscorts(fleet: CampaignFleetAPI) {
        val excluded = fleet.fleetData.membersListCopy.filter { !it.isFlagship && isEscortExcludedMember(it) }
        excluded.forEach { member ->
            fleet.fleetData.removeFleetMember(member)
            log.info("[ASTD] 赏金舰队 ${fleet.name} 清退排除舰 ${member.hullId}，由补齐阶段重抽")
        }
    }

    /**
     * 随机混编补齐：ASTD 与余晖 1:1 交替抽取，直到达到原版 AI 舰队规模上限。
     * preset 编成全为 ASTD，叠加交替补齐后 ASTD 占比恒不小于一半（末尾 enforceHalfAstd 兜底）。
     */
    private fun fillToMaxFleetSize(fleet: CampaignFleetAPI, config: BountyPoolConfig) {
        val cap = maxFleetSizeSetting()
        var addedAstd = 0
        var addedRemnant = 0
        var guard = MAX_PICK_ATTEMPTS
        while (fleet.fleetData.membersListCopy.size < cap && guard-- > 0) {
            when (BountyPoolConfig.pickPoolSide(addedAstd, addedRemnant)) {
                PoolSide.ASTD -> if (pickAstdEscortIntoFleet(fleet, config)) addedAstd++
                PoolSide.REMNANT -> if (pickRemnantEscortIntoFleet(fleet, config)) addedRemnant++
            }
        }
    }

    private fun pickAstdEscortIntoFleet(fleet: CampaignFleetAPI, config: BountyPoolConfig): Boolean {
        val faction = Global.getSector().getFaction(ASTD_FACTION_ID)
        val role = pickRole(config) ?: return false

        if (config.destroyerBestOf > 1 && BountyPoolConfig.ROLE_TO_SIZE[role] == ShipAPI.HullSize.DESTROYER) {
            return pickAstdDestroyerBestOfIntoFleet(fleet, faction, role, config.destroyerBestOf)
        }

        val added = pickAstdMember(fleet, faction, role) ?: return false
        added.captain = null
        added.repairTracker.cr = added.repairTracker.maxCR
        return true
    }

    /** best-of-K：抽 K 次驱逐舰，保留部署点最高的个体，移除其余。 */
    private fun pickAstdDestroyerBestOfIntoFleet(
        fleet: CampaignFleetAPI,
        faction: FactionAPI,
        role: String,
        bestOf: Int,
    ): Boolean {
        val candidates = mutableListOf<FleetMemberAPI>()
        repeat(bestOf) {
            pickAstdMember(fleet, faction, role)?.let(candidates::add)
        }
        if (candidates.isEmpty()) return false
        val best = candidates.maxBy { it.deploymentPointsCost }
        candidates.filter { it !== best }.forEach { fleet.fleetData.removeFleetMember(it) }
        best.captain = null
        best.repairTracker.cr = best.repairTracker.maxCR
        return true
    }

    /** 走势力 doctrine 抽一艘 ASTD 成员入队；命中排除舰则移除并返回 null。 */
    private fun pickAstdMember(fleet: CampaignFleetAPI, faction: FactionAPI, role: String): FleetMemberAPI? {
        val before = fleet.fleetData.membersListCopy.mapTo(HashSet()) { it.id }
        val addedFP = faction.pickShipAndAddToFleet(
            role,
            FactionAPI.ShipPickParams(FactionAPI.ShipPickMode.PRIORITY_THEN_ALL),
            fleet,
            random,
        )
        if (addedFP <= 0f) return null
        val added = fleet.fleetData.membersListCopy.firstOrNull { it.id !in before } ?: return null
        if (isEscortExcludedMember(added)) {
            fleet.fleetData.removeFleetMember(added)
            return null
        }
        return added
    }

    /**
     * 余晖成员：remnant 势力无 doctrine 角色配置，按 ASTD 角色映射到同舰级池后直接实例化变体。
     * destroyerBestOf 生效时只从池内最高部署点档位抽取。
     */
    private fun pickRemnantEscortIntoFleet(fleet: CampaignFleetAPI, config: BountyPoolConfig): Boolean {
        val role = pickRole(config) ?: return false
        val size = BountyPoolConfig.ROLE_TO_SIZE[role] ?: return false
        var pool = BountyPoolConfig.REMNANT_POOLS[size].orEmpty()
        if (config.destroyerBestOf > 1 && size == ShipAPI.HullSize.DESTROYER) {
            pool = BountyPoolConfig.topFleetPointsPicks(pool)
        }
        if (pool.isEmpty()) return false

        val picker = WeightedRandomPicker<RemnantPick>(random)
        pool.forEach { picker.add(it) }
        val pick = picker.pick() ?: return false

        val variant = Global.getSettings().getVariant(pick.variantId)
        if (variant == null) {
            log.warn("[ASTD] 余晖混编池变体缺失：${pick.variantId}，本次抽取作废")
            return false
        }
        val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant)
        fleet.fleetData.addFleetMember(member)
        member.repairTracker.cr = member.repairTracker.maxCR
        member.captain = createAiCoreCaptain(size)
        return true
    }

    private fun pickRole(config: BountyPoolConfig): String? {
        val rolePicker = WeightedRandomPicker<String>(random)
        BountyPoolConfig.weightedRoles(ROLE_WEIGHTS, config).forEach { (role, weight) -> rolePicker.add(role, weight) }
        return rolePicker.pick()
    }

    /** ASTD 占比兜底：低于一半时移除一艘非旗舰余晖并补抽一艘 ASTD，直至达标。 */
    private fun enforceHalfAstd(fleet: CampaignFleetAPI, config: BountyPoolConfig) {
        var guard = MAX_PICK_ATTEMPTS
        while (guard-- > 0) {
            val members = fleet.fleetData.membersListCopy
            val astdCount = members.count { it.hullId.startsWith(ASTD_HULL_PREFIX) }
            if (astdCount * 2 >= members.size) return
            val victim = members.firstOrNull { !it.isFlagship && !it.hullId.startsWith(ASTD_HULL_PREFIX) } ?: return
            fleet.fleetData.removeFleetMember(victim)
            if (!pickAstdEscortIntoFleet(fleet, config)) return
            log.warn("[ASTD] 赏金舰队 ${fleet.name} ASTD 占比不足一半，移除 ${victim.hullId} 并补抽 ASTD 纠偏")
        }
    }

    /**
     * 军官分配：ASTD 成员（含旗舰）全部换装制式核心军官，档位由难度档映射
     * （StandardCores.planFleetCores，非唯一舰赏金旗舰 O 档封顶回 A 档）；
     * 装舰核心表写入 sector memory 供赏金成功后的核心打捞发放读取。
     * 余晖成员维持原版核心军官（正常路径在入队时已分配，此处兜底补漏）。
     */
    private fun assignCrew(fleet: CampaignFleetAPI, bountyKey: String?) {
        val astdMembers = fleet.fleetData.membersListCopy.filter { it.hullId.startsWith(ASTD_HULL_PREFIX) }
        val flagship = astdMembers.firstOrNull { it.isFlagship }
        if (bountyKey == null || flagship == null) {
            log.error("[ASTD] 赏金舰队 ${fleet.name} 缺少 bounty key 或旗舰，跳过制式核心军官分配")
        } else {
            val escorts = astdMembers.filter { !it.isFlagship }
            val seed = bountyKey.hashCode().toLong() * 31 + fleet.id.hashCode()
            val plan = planBountyCores(bountyKey, astdMembers.size, seed)
            plan.forEachIndexed { index, coreId ->
                val member = if (index == 0) flagship else escorts[index - 1]
                val tier = StandardCores.byCommodity(coreId)
                if (tier == null) {
                    log.error("[ASTD] 核心配置方案产出未知核心 id：$coreId（赏金 $bountyKey）")
                    return@forEachIndexed
                }
                member.captain = StandardCores.createOfficerPerson(tier, ASTD_FACTION_ID)
            }
            // 装舰表供 BountyCoreLootScript 在赏金成功后滚动打捞；舰队实体消亡后仍可读取。
            Global.getSector().memoryWithoutUpdate.set(CORES_MEMKEY_PREFIX + bountyKey, ArrayList(plan))
        }

        for (member in fleet.fleetData.membersListCopy) {
            if (member.isFlagship || member.hullId.startsWith(ASTD_HULL_PREFIX)) continue
            if (member.captain == null || member.captain.isDefault) {
                member.captain = createAiCoreCaptain(member.hullSpec.hullSize)
            }
        }
    }

    /** 余晖 AI 核心军官：核心按 gamma 50 / beta 35 / alpha 15 加权，对应等级 4/5/6。 */
    private fun createAiCoreCaptain(size: ShipAPI.HullSize): PersonAPI {
        val corePicker = WeightedRandomPicker<String>(random)
        AI_CORE_WEIGHTS.forEach { (core, weight) -> corePicker.add(core, weight) }
        val coreId = corePicker.pick() ?: AI_CORE_WEIGHTS.first().first
        val officer = OfficerManagerEvent.createOfficer(
            Global.getSector().getFaction(Factions.REMNANTS),
            AI_CORE_LEVELS.getValue(coreId),
            OfficerManagerEvent.SkillPickPreference.GENERIC,
            random,
        )
        officer.setAICoreId(coreId)
        return officer
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
        const val ASTD_HULL_PREFIX: String = "astd_"

        /** 单次后处理中随机抽取的最大尝试次数（排除舰重抽与规模补齐共用，防止抽取失败死循环）。 */
        const val MAX_PICK_ATTEMPTS: Int = 200

        /** 装舰核心表的 sector memory 键前缀（完整键 = 前缀 + bountyKey），供 BountyCoreLootScript 读取。 */
        const val CORES_MEMKEY_PREFIX: String = "\$astd_bounty_cores_"

        const val UNIQUE_HULL_TAG: String = "astd_unique"

        /** 发布范围外舰体的显式排除清单（与 astd_unique tag 并用的双保险：tag 漏标时仍然生效）。 */
        val EXCLUDED_HULL_IDS: Set<String> = setOf("astd_zw_001", "astd_xc_104")

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

        /** 余晖 AI 核心加权与对应军官等级。 */
        val AI_CORE_WEIGHTS: List<Pair<String, Float>> = listOf(
            "gamma_core" to 50f,
            "beta_core" to 35f,
            "alpha_core" to 15f,
        )
        val AI_CORE_LEVELS: Map<String, Int> = mapOf(
            "gamma_core" to 4,
            "beta_core" to 5,
            "alpha_core" to 6,
        )

        /**
         * 护航排除判定，对所有注入路径（MagicLib 生成清退、doctrine 随机补抽、纠偏补抽）统一生效：
         * 全部独特舰（astd_unique tag 标于 ship_data.csv，含发布范围外的决明与当期唯一舰旗舰同型舰）
         * 以及显式清单内的发布范围外舰体（决明 zw_001 / 逐电 xc_104）不得出现在赏金舰队护航位。
         * 当期旗舰同型：唯一舰旗舰的同型由 astd_unique tag 覆盖；量产旗舰的同型为主力舰，正常放行。
         */
        fun isEscortExcludedHull(hullId: String, astdUnique: Boolean): Boolean =
            astdUnique || hullId in EXCLUDED_HULL_IDS

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

        /**
         * 赏金舰队装舰核心表（索引 0 = 旗舰），在 StandardCores.planFleetCores 基础上做
         * 旗舰 O 档特判：仅唯一舰赏金（BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES）的旗舰
         * 允许 O 档，其余赏金旗舰为量产舰，O 档封顶回 A 档。
         */
        fun planBountyCores(bountyKey: String?, shipCount: Int, seed: Long): List<String> {
            val plan = StandardCores.planFleetCores(shipCount, BountyPoolConfig.threatTierOf(bountyKey), seed)
                .toMutableList()
            if (plan.isNotEmpty() &&
                bountyKey !in BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES &&
                plan[0] == StandardCores.Tier.O.commodityId
            ) {
                plan[0] = StandardCores.Tier.A.commodityId
            }
            return plan
        }

        private fun isEscortExcludedMember(member: FleetMemberAPI): Boolean =
            isEscortExcludedHull(member.hullId, member.hullSpec.hasTag(UNIQUE_HULL_TAG))
    }
}
