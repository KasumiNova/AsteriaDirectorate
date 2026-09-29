package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import cn.kasuminova.astd.campaign.bounty.core.BountyOfficerSkills
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.BountySmodPresets
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
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
        reorderFleet(fleet)
        assignCrew(fleet, bountyKey)
        protectVariants(fleet)
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
     * 随机混编补齐：ASTD 与余晖 1:1 交替，舰级按舰队当前 ASTD 成员（preset 编成主体）
     * 的计数分布加权抽取，使补齐跟随该赏金 preset 的构成比例。
     */
    private fun fillToMaxFleetSize(fleet: CampaignFleetAPI, config: BountyPoolConfig) {
        val cap = maxFleetSizeSetting()
        var addedAstd = 0
        var addedRemnant = 0
        var guard = MAX_PICK_ATTEMPTS
        while (fleet.fleetData.membersListCopy.size < cap && guard-- > 0) {
            val size = pickSizeByComposition(fleet) ?: return
            when (BountyPoolConfig.pickPoolSide(addedAstd, addedRemnant)) {
                PoolSide.ASTD -> if (pickAstdEscortIntoFleet(fleet, size, config)) addedAstd++
                PoolSide.REMNANT -> if (pickRemnantEscortIntoFleet(fleet, size, config)) addedRemnant++
            }
        }
    }

    private fun pickSizeByComposition(fleet: CampaignFleetAPI): ShipAPI.HullSize? {
        val picker = WeightedRandomPicker<ShipAPI.HullSize>(random)
        fleet.fleetData.membersListCopy
            .filter { !it.isFighterWing && it.hullId.startsWith(ASTD_HULL_PREFIX) }
            .forEach { picker.add(it.hullSpec.hullSize) }
        return picker.pick()
    }

    private fun pickAstdEscortIntoFleet(
        fleet: CampaignFleetAPI,
        size: ShipAPI.HullSize,
        config: BountyPoolConfig,
    ): Boolean {
        val pool = BountyPoolConfig.ASTD_POOLS[size].orEmpty()
        if (pool.isEmpty()) return false

        if (config.destroyerBestOf > 1 && size == ShipAPI.HullSize.DESTROYER) {
            // best-of-K：实例化 K 个候选，保留部署点最高者，移除其余
            val candidates = mutableListOf<FleetMemberAPI>()
            repeat(config.destroyerBestOf) {
                createMemberIntoFleet(fleet, pool[random.nextInt(pool.size)])?.let(candidates::add)
            }
            if (candidates.isEmpty()) return false
            val best = candidates.maxBy { it.deploymentPointsCost }
            candidates.filter { it !== best }.forEach { fleet.fleetData.removeFleetMember(it) }
            return true
        }
        return createMemberIntoFleet(fleet, pool[random.nextInt(pool.size)]) != null
    }

    private fun pickRemnantEscortIntoFleet(
        fleet: CampaignFleetAPI,
        size: ShipAPI.HullSize,
        config: BountyPoolConfig,
    ): Boolean {
        var pool = BountyPoolConfig.REMNANT_POOLS[size].orEmpty()
        if (config.destroyerBestOf > 1 && size == ShipAPI.HullSize.DESTROYER) {
            pool = BountyPoolConfig.topFleetPointsPicks(pool)
        }
        if (pool.isEmpty()) return false

        val member = createMemberIntoFleet(fleet, pool[random.nextInt(pool.size)].variantId) ?: return false
        member.captain = createAiCoreCaptain(size)
        return true
    }

    /** 按显式变体池实例化成员入队（stock variant 为共享实例，克隆防污染），CR 拉满与赏金舰队初始状态对齐。 */
    private fun createMemberIntoFleet(fleet: CampaignFleetAPI, variantId: String): FleetMemberAPI? {
        val variant = Global.getSettings().getVariant(variantId)
        if (variant == null) {
            log.warn("[ASTD] 混编池变体缺失：$variantId，本次抽取作废")
            return null
        }
        val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant.clone())
        fleet.fleetData.addFleetMember(member)
        member.repairTracker.cr = member.repairTracker.maxCR
        return member
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
            val size = pickSizeByComposition(fleet) ?: return
            if (!pickAstdEscortIntoFleet(fleet, size, config)) return
            log.warn("[ASTD] 赏金舰队 ${fleet.name} ASTD 占比不足一半，移除 ${victim.hullId} 并补抽 ASTD 纠偏")
        }
    }

    /** 舰队成员重排：旗舰永远首位，其余按舰级降序（主力→巡洋→驱逐→护卫），同舰级内 ASTD 在前余晖在后。 */
    private fun reorderFleet(fleet: CampaignFleetAPI) {
        val members = fleet.fleetData.membersListCopy
        val ships = members.filter { !it.isFighterWing }
        val sorted = ships.sortedWith(
            compareBy(
                { member: FleetMemberAPI -> if (member.isFlagship) 0 else 1 },
                { member: FleetMemberAPI -> sizeRank(member.hullSpec.hullSize) },
                { member: FleetMemberAPI -> if (member.hullId.startsWith(ASTD_HULL_PREFIX)) 0 else 1 },
            ),
        )
        fleet.fleetData.sortToMatchOrder(sorted + members.filter { it.isFighterWing })
    }

    /**
     * 军官分配：ASTD 成员（含旗舰）全部换装核心军官。
     * 三个唯一舰赏金走 StandardCores.planFleetCores 难度档（旗舰 astd O 档）；
     * rogue 系旗舰固定原版 Alpha 核心，僚舰走核心池（原版/astd/SMS 加权，软联动过滤）。
     * 军官统一经原版插件分发链创建，等级按核心档覆盖，ASTD 导入变体套用素材技能表。
     * 装舰核心表写入 sector memory 供赏金成功后的核心打捞发放读取。
     * 余晖成员维持原版核心军官（正常路径在入队时已分配，此处兜底补漏）。
     */
    private fun assignCrew(fleet: CampaignFleetAPI, bountyKey: String?) {
        val astdMembers = fleet.fleetData.membersListCopy
            .filter { !it.isFighterWing && it.hullId.startsWith(ASTD_HULL_PREFIX) }
        val flagship = astdMembers.firstOrNull { it.isFlagship }
        if (bountyKey == null || flagship == null) {
            log.error("[ASTD] 赏金舰队 ${fleet.name} 缺少 bounty key 或旗舰，跳过核心军官分配")
        } else {
            val escorts = astdMembers.filter { !it.isFlagship }
            val seed = bountyKey.hashCode().toLong() * 31 + fleet.id.hashCode()
            val plan = if (bountyKey in BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES) {
                planBountyCores(bountyKey, astdMembers.size, seed)
            } else {
                planRogueCores(astdMembers.size, availableRogueCorePool(), random)
            }
            if (plan.isEmpty()) {
                log.error("[ASTD] 赏金 $bountyKey 核心方案为空，跳过核心军官分配")
            } else {
                plan.forEachIndexed { index, coreId ->
                    val member = if (index == 0) flagship else escorts[index - 1]
                    val officer = createCoreOfficer(coreId, member)
                    if (officer != null) member.captain = officer
                }
                // 装舰表供 BountyCoreLootScript 在赏金成功后滚动打捞；舰队实体消亡后仍可读取。
                Global.getSector().memoryWithoutUpdate.set(CORES_MEMKEY_PREFIX + bountyKey, ArrayList(plan))
            }
        }

        for (member in fleet.fleetData.membersListCopy) {
            if (member.isFlagship || member.isFighterWing || member.hullId.startsWith(ASTD_HULL_PREFIX)) continue
            if (member.captain == null || member.captain.isDefault) {
                member.captain = createAiCoreCaptain(member.hullSpec.hullSize)
            }
        }
    }

    /** rogue 僚舰核心池软联动过滤：未安装的模组核心（SMS）条目自然消失。 */
    private fun availableRogueCorePool(): List<Pair<String, Float>> {
        val pool = BountyPoolConfig.ROGUE_ESCORT_CORE_POOL.filter { (id, _) ->
            Global.getSettings().getCommoditySpec(id) != null
        }
        if (pool.isEmpty()) {
            log.error("[ASTD] rogue 僚舰核心池软过滤后为空（原版核心 commodity 缺失）")
        }
        return pool
    }

    /** 核心军官工厂：原版插件分发链创建（原版/astd/SMS 核心各自插件），随后按核心档覆盖等级、按变体套用技能表。 */
    private fun createCoreOfficer(coreId: String, member: FleetMemberAPI): PersonAPI? {
        val plugin = Misc.getAICoreOfficerPlugin(coreId)
        if (plugin == null) {
            log.error("[ASTD] 核心 $coreId 无军官插件（分发链异常），跳过该成员军官分配")
            return null
        }
        val person = plugin.createPerson(coreId, ASTD_FACTION_ID, random)
        if (person == null) {
            log.error("[ASTD] 核心 $coreId 军官插件未产出 Person，跳过该成员军官分配")
            return null
        }
        person.stats.isSkipRefresh = true
        val level = StandardCores.byCommodity(coreId)?.officerLevel
            ?: BountyPoolConfig.VANILLA_CORE_LEVELS[coreId]
        if (level != null) {
            person.stats.level = level
        }
        applyVariantSkills(person, member.variant.hullVariantId)
        person.stats.isSkipRefresh = false
        return person
    }

    /** ASTD 导入变体套用素材技能表（全 2 级）；非登记变体保持核心插件默认技能。 */
    private fun applyVariantSkills(person: PersonAPI, variantId: String?) {
        val skills = BountyOfficerSkills.forVariant(variantId) ?: return
        person.stats.skillsCopy.forEach { person.stats.setSkillLevel(it.skill.id, 0f) }
        skills.forEach { person.stats.setSkillLevel(it, 2f) }
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

    /**
     * 变体防护：全体舰船成员克隆变体并打 no_autofit。
     * 赏金舰队挂 ML_bounty 虚拟势力，fleet inflater 会把不认识的装配重洗成空壳
     * （余晖 stock variant 空装配实机案例），克隆 + no_autofit 是原版标准防护。
     */
    private fun protectVariants(fleet: CampaignFleetAPI) {
        for (member in fleet.fleetData.membersListCopy) {
            if (member.isFighterWing) continue
            val cloned = member.variant.clone()
            cloned.addTag("no_autofit")
            member.setVariant(cloned, false, false)
        }
    }

    private fun installSmods(fleet: CampaignFleetAPI) {
        val neutralStats = Global.getFactory().createPerson().stats
        for (member in fleet.fleetData.membersListCopy) {
            if (member.isCivilian) continue
            val hull = member.hullSpec
            // protectVariants 已完成克隆与 no_autofit，可直接内插
            val variant = member.variant
            var remaining = Misc.getMaxPermanentMods(member, neutralStats) - variant.sMods.size - variant.permaMods.size
            if (remaining <= 0) continue

            val candidates = smodPresets.prioritiesFor(
                hull.hullId,
                hull.isPhase,
                hull.hints.contains(ShipHullSpecAPI.ShipTypeHints.CARRIER),
                hull.hullSize == ShipAPI.HullSize.CAPITAL_SHIP,
            )
            for (id in candidates) {
                if (remaining <= 0) break
                val spec = Global.getSettings().getHullModSpec(id) ?: continue
                if (hull.builtInMods.contains(id) || variant.hullMods.contains(id)) continue
                if (variant.getUnusedOP(neutralStats) < smodCostFor(spec, hull.hullSize)) continue
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
         * 护航排除判定，对所有注入路径（MagicLib 生成清退、随机补抽、纠偏补抽）统一生效：
         * 全部独特舰（astd_unique tag 标于 ship_data.csv，含发布范围外的决明与当期唯一舰旗舰同型舰）
         * 以及显式清单内的发布范围外舰体（决明 zw_001 / 逐电 xc_104）不得出现在赏金舰队护航位。
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

        /** 舰级排序档位（主力 0 → 护卫 3，升序即舰级降序）。 */
        fun sizeRank(size: ShipAPI.HullSize): Int = when (size) {
            ShipAPI.HullSize.CAPITAL_SHIP -> 0
            ShipAPI.HullSize.CRUISER -> 1
            ShipAPI.HullSize.DESTROYER -> 2
            else -> 3
        }

        /**
         * 唯一舰赏金装舰核心表（索引 0 = 旗舰），在 StandardCores.planFleetCores 基础上做
         * 旗舰 O 档特判：仅唯一舰赏金（BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES）的旗舰
         * 允许 astd O 档。rogue 系赏金不走此路径（见 planRogueCores）。
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

        /**
         * rogue 系赏金装舰核心表（索引 0 = 旗舰，固定原版 Alpha 核心）；
         * 僚舰从软过滤后的核心池加权抽取。池为空（数据异常）返回空表，由调用方记日志跳过。
         */
        fun planRogueCores(shipCount: Int, pool: List<Pair<String, Float>>, random: Random): List<String> {
            if (shipCount <= 0 || pool.isEmpty()) return emptyList()
            val picker = WeightedRandomPicker<String>(random)
            pool.forEach { (id, weight) -> picker.add(id, weight) }
            return List(shipCount) { index ->
                if (index == 0) BountyPoolConfig.ROGUE_FLAGSHIP_CORE else picker.pick()
            }
        }

        private fun isEscortExcludedMember(member: FleetMemberAPI): Boolean =
            isEscortExcludedHull(member.hullId, member.hullSpec.hasTag(UNIQUE_HULL_TAG))
    }
}
