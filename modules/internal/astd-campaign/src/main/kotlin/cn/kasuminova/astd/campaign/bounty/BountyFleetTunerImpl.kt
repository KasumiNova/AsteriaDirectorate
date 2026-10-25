package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.api.campaign.BountySettings
import cn.kasuminova.astd.campaign.bounty.core.BountyFitRules
import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import cn.kasuminova.astd.campaign.bounty.core.BountyOfficerSkills
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import cn.kasuminova.astd.campaign.integration.sic.SicIntegration
import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry
import cn.kasuminova.astd.combat.hullmods.base.activateDualMode
import cn.kasuminova.astd.impl.campaign.BountySettingsImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.loading.VariantSource
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.util.WeightedRandomPicker
import java.util.Random
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyLoader

class BountyFleetTunerImpl(
    private val fitRules: BountyFitRules = BountyFitRulesImpl(),
    private val random: Random = Random(),
    private val bountySettings: BountySettings = BountySettingsImpl,
) : BountyFleetTuner {

    private val log: Logger = Global.getLogger(BountyFleetTunerImpl::class.java)

    override fun isAstdBountyFleet(fleet: CampaignFleetAPI): Boolean =
        fleet.tags.contains(MagicBountyLoader.BOUNTY_FLEET_TAG) && fleet.tags.any { it.startsWith(BOUNTY_KEY_PREFIX) }

    override fun tuneIfNeeded(fleet: CampaignFleetAPI) {
        val memory = fleet.memoryWithoutUpdate
        if (memory.getBoolean(TUNED_MEMKEY)) return

        val bountyKey = bountyKeyOf(fleet)
        val poolConfig = BountyPoolConfig.forBountyKey(bountyKey)
        val fillToFleetCap = bountySettings.fillToFleetCap
        refreshVariants(fleet)
        replaceExcludedEscorts(fleet)
        reinforceFleet(fleet, poolConfig, fillToFleetCap)
        if (fillToFleetCap) {
            enforceHalfAstd(fleet, poolConfig)
        }
        reorderFleet(fleet)
        assignCrew(fleet, bountyKey)
        installExtraFittings(fleet)
        SicIntegration.attachBountyOfficerIfEnabled(fleet)

        fleet.fleetData.setSyncNeeded()
        fleet.fleetData.syncIfNeeded()
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

    private fun reinforceFleet(fleet: CampaignFleetAPI, config: BountyPoolConfig, fillToFleetCap: Boolean) {
        val presetSize = fleet.fleetData.membersListCopy.size
        val fleetCap = if (fillToFleetCap) maxFleetSizeSetting() else 0
        val targetSize = reinforcementTargetSize(fillToFleetCap, presetSize, fleetCap)
        var addedAstd = 0
        var addedRemnant = 0
        var guard = MAX_PICK_ATTEMPTS
        while (fleet.fleetData.membersListCopy.size < targetSize && guard-- > 0) {
            val size = pickSizeByComposition(fleet) ?: break
            when (reinforcementPoolSide(fillToFleetCap, addedAstd, addedRemnant)) {
                PoolSide.ASTD -> if (pickAstdEscortIntoFleet(fleet, size, config)) addedAstd++
                PoolSide.REMNANT -> if (pickRemnantEscortIntoFleet(fleet, size, config)) addedRemnant++
            }
        }
        val finalSize = fleet.fleetData.membersListCopy.size
        if (finalSize < targetSize) {
            log.warn("[ASTD] 赏金舰队 ${fleet.name} 补齐未完成：目标 $targetSize 艘，实际 $finalSize 艘")
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
        val pool = remnantPoolFor(size)
        if (pool.isEmpty()) return false

        if (config.destroyerBestOf > 1 && size == ShipAPI.HullSize.DESTROYER) {
            val candidates = mutableListOf<FleetMemberAPI>()
            repeat(config.destroyerBestOf) {
                createMemberIntoFleet(fleet, pool[random.nextInt(pool.size)])?.let(candidates::add)
            }
            if (candidates.isEmpty()) return false
            val best = candidates.maxBy { it.deploymentPointsCost }
            candidates.filter { it !== best }.forEach { fleet.fleetData.removeFleetMember(it) }
            best.captain = createAiCoreCaptain(size, best.variant.hullVariantId)
            return true
        }
        val member = createMemberIntoFleet(fleet, pool[random.nextInt(pool.size)]) ?: return false
        member.captain = createAiCoreCaptain(size, member.variant.hullVariantId)
        return true
    }

    private val discoveredRemnantPools: Map<ShipAPI.HullSize, List<String>> by lazy { discoverRemnantPools() }

    private fun remnantPoolFor(size: ShipAPI.HullSize): List<String> {
        val discovered = discoveredRemnantPools[size].orEmpty()
        if (discovered.isEmpty()) {
            log.warn("[ASTD] 余晖 doctrine 未发现 $size 舰级候选，混编池该舰级退回硬编码兜底清单")
        }
        return resolveRemnantPool(discoveredRemnantPools, size)
    }

    private fun discoverRemnantPools(): Map<ShipAPI.HullSize, List<String>> {
        val settings = Global.getSettings()
        val faction = Global.getSector().getFaction(Factions.REMNANTS)
        if (faction == null) {
            log.warn("[ASTD] 余晖势力缺失，混编池退回硬编码兜底清单")
            return emptyMap()
        }

        val stockByHull = mutableMapOf<String, MutableList<ShipVariantAPI>>()
        for (variantId in settings.allVariantIds) {
            val variant = settings.getVariant(variantId)
            if (variant == null) {
                log.warn("[ASTD] settings 登记变体无法解析，跳过：$variantId")
                continue
            }
            if (!variant.isStockVariant || variant.source != VariantSource.STOCK) continue
            val variantHull = variant.hullSpec
            if (variantHull == null) {
                log.warn("[ASTD] settings 登记变体舰体无法解析，跳过：$variantId")
                continue
            }
            stockByHull.getOrPut(variantHull.hullId) { mutableListOf() }.add(variant)
        }

        val pools = mutableMapOf<ShipAPI.HullSize, MutableList<String>>()
        for (hullId in faction.knownShips) {
            val hullSpec = settings.getHullSpec(hullId)
            if (hullSpec == null) {
                log.warn("[ASTD] 余晖 doctrine 登记舰体无法解析，跳过：$hullId")
                continue
            }
            if (hullSpec.isDHull || hullSpec.hullSize == ShipAPI.HullSize.FIGHTER) continue
            if (hullSpec.hints.any { it in EXCLUDED_DOCTRINE_HULL_HINTS }) continue

            val candidates = stockByHull[hullId].orEmpty()
                .filterNot { it.hasTag("restricted") || it.hasTag("no_sim") }
                .filter { variant ->
                    val emptyFit = variant.fittedWeaponSlots.isEmpty() && variant.wings.isEmpty()
                    if (emptyFit) {
                        log.warn("[ASTD] 余晖 doctrine 变体为空装配，跳过：${variant.hullVariantId}")
                    }
                    !emptyFit
                }
            if (candidates.isEmpty()) continue
            val goal = candidates.filter { it.isGoalVariant }
            val chosen = goal.ifEmpty { candidates }
            pools.getOrPut(hullSpec.hullSize) { mutableListOf() }.addAll(chosen.map { it.hullVariantId })
        }
        log.info("[ASTD] 余晖 doctrine 混编池发现完成：" + pools.entries.joinToString { (size, ids) -> "$size=${ids.size}" })
        return pools
    }

    private fun createMemberIntoFleet(fleet: CampaignFleetAPI, variantId: String): FleetMemberAPI? {
        val variant = Global.getSettings().getVariant(variantId)
        if (variant == null) {
            log.warn("[ASTD] 混编池变体缺失：$variantId，本次抽取作废")
            return null
        }
        val cloned = variant.clone()
        cloned.addTag(Tags.TAG_NO_AUTOFIT)
        val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, cloned)
        fleet.fleetData.addFleetMember(member)
        member.repairTracker.cr = member.repairTracker.maxCR
        return member
    }

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

    private fun assignCrew(fleet: CampaignFleetAPI, bountyKey: String?) {
        val astdMembers = fleet.fleetData.membersListCopy
            .filter { !it.isFighterWing && it.hullId.startsWith(ASTD_HULL_PREFIX) }
        // 原版限制 AI 核心只能指挥全自动舰船，分配核心军官前必须先把 ASTD 成员翻转到无人模式
        for (member in astdMembers) {
            val config = ASTDDualModeRegistry.configForVariant(member.variant) ?: continue
            member.variant.activateDualMode(config, config.automatedModeId, null)
        }
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
                Global.getSector().memoryWithoutUpdate.set(CORES_MEMKEY_PREFIX + bountyKey, ArrayList(plan))
            }
        }

        for (member in fleet.fleetData.membersListCopy) {
            if (member.isFlagship || member.isFighterWing || member.hullId.startsWith(ASTD_HULL_PREFIX)) continue
            if (member.captain == null || member.captain.isDefault) {
                member.captain = createAiCoreCaptain(member.hullSpec.hullSize, member.variant.hullVariantId)
            }
        }
    }

    private fun availableRogueCorePool(): List<Pair<String, Float>> {
        val pool = BountyPoolConfig.ROGUE_ESCORT_CORE_POOL.filter { (id, _) ->
            Global.getSettings().getCommoditySpec(id) != null
        }
        if (pool.isEmpty()) {
            log.error("[ASTD] rogue 僚舰核心池软过滤后为空（原版核心 commodity 缺失）")
        }
        return pool
    }

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
            applyOfficerSkills(person, member.variant.hullVariantId)
        } else {
            val original = person.stats.skillsCopy.filter { it.level > 0f }.map { it.skill.id }
            val planned = planModuleCoreSkills(original, BountyOfficerSkills.forVariant(member.variant.hullVariantId))
            person.stats.skillsCopy.forEach { person.stats.setSkillLevel(it.skill.id, 0f) }
            planned.forEach { person.stats.setSkillLevel(it, 2f) }
            log.info(
                "[ASTD] 模组核心 $coreId 技能适配（沿用插件默认等级 ${person.stats.level}）：" +
                    "保留特殊技能 ${original.filter { it.startsWith(SMS_SPECIAL_SKILL_PREFIX) }}，" +
                    "原版技能 ${original.filterNot { it.startsWith(SMS_SPECIAL_SKILL_PREFIX) }} 已按变体技能表等量替换",
            )
        }
        person.stats.isSkipRefresh = false
        return person
    }

    private fun applyOfficerSkills(person: PersonAPI, variantId: String?) {
        val skills = resolveCoreOfficerSkills(
            BountyOfficerSkills.forVariant(variantId),
            person.stats.level,
            astdCore = StandardCores.byCommodity(person.aiCoreId) != null,
        )
        person.stats.skillsCopy.forEach { person.stats.setSkillLevel(it.skill.id, 0f) }
        skills.forEach { person.stats.setSkillLevel(it, 2f) }
    }

    private fun createAiCoreCaptain(size: ShipAPI.HullSize, variantId: String?): PersonAPI {
        val corePicker = WeightedRandomPicker<String>(random)
        AI_CORE_WEIGHTS.forEach { (core, weight) -> corePicker.add(core, weight) }
        val coreId = corePicker.pick() ?: AI_CORE_WEIGHTS.first().first
        val faction = Global.getSector().getFaction(Factions.REMNANTS)
        if (faction == null) {
            log.warn("[ASTD] 余晖势力缺失，AI 核心军官改挂中立势力")
        }
        val officer = OfficerManagerEvent.createOfficer(
            faction ?: Global.getSector().getFaction(Factions.NEUTRAL),
            AI_CORE_LEVELS.getValue(coreId),
            OfficerManagerEvent.SkillPickPreference.GENERIC,
            random,
        )
        officer.setAICoreId(coreId)
        officer.setPersonality("reckless")
        officer.portraitSprite = AI_CORE_PORTRAITS.getValue(coreId)
        officer.stats.isSkipRefresh = true
        applyOfficerSkills(officer, variantId)
        officer.stats.isSkipRefresh = false
        log.info("[ASTD] 余晖核心军官（$coreId L${officer.stats.level}）GENERIC 随机技能已被赏金技能口径整体覆盖")
        return officer
    }

    /** 赏金舰队的 inflater 势力 knownWeapons/knownFighters 为空，充气会把全舰重洗成空壳：以 stock 变体重新克隆修复并摘除 inflater；现状 tag 全量随克隆保留（MagicLib 的 SMod 打捞保留与旗舰可恢复标记只打在运行时变体上，stock 克隆不含）。 */
    private fun refreshVariants(fleet: CampaignFleetAPI) {
        for (member in fleet.fleetData.membersListCopy) {
            val current = member.variant
            val stockId = stockVariantIdOf(current.originalVariant, current.hullVariantId)
            val stock = Global.getSettings().getVariant(stockId)
            if (stock == null) {
                log.warn("[ASTD] 赏金舰队成员 ${member.hullId} 变体无法回溯 stock（$stockId），按现状克隆防护")
            }
            val cloned = (stock ?: current).clone()
            current.tags.forEach { cloned.addTag(it) }
            cloned.addTag(Tags.TAG_NO_AUTOFIT)
            cloned.setOriginalVariant(null)
            member.setVariant(cloned, false, false)
        }
        fleet.setInflater(null)
    }

    /** SHU 必须先于 SMod 安装：SMod 装上的护盾系船插会被等离子充能护盾的互斥拆除，名额不回填。 */
    private fun installExtraFittings(fleet: CampaignFleetAPI) {
        val neutralStats = Global.getFactory().createPerson().stats
        val shuAvailable = fitRules.shuHullmodIds().any { Global.getSettings().getHullModSpec(it) != null }
        if (!shuAvailable) {
            log.info("[ASTD] 未检测到 Special Hullmod Upgrades 模组，跳过 SHU 软联动装配")
        }
        var shuInstalled = 0
        for (member in fleet.fleetData.membersListCopy) {
            if (member.isCivilian || member.isFighterWing) continue
            val hull = member.hullSpec
            val variant = member.variant
            val phase = hull.isPhase
            val hasShield = hull.shieldType != ShieldAPI.ShieldType.NONE
            val missileCapable = hull.allWeaponSlotsCopy.any { it.weaponType in MISSILE_CAPABLE_SLOT_TYPES }

            val installedShu = if (shuAvailable) installShuHullmod(variant, hull, neutralStats) else null
            if (installedShu != null) shuInstalled++
            installPrioritySmods(variant, member, neutralStats, phase, hasShield, missileCapable, installedShu)
            fillExtraMods(variant, hull, neutralStats)
        }
        if (shuAvailable) {
            log.info("[ASTD] 赏金舰队 ${fleet.name} SHU 软联动装配完成，安装 $shuInstalled 件特殊升级船插")
        }
    }

    /** 0.98 起 permaMod（含 SMod）不计 OP 成本（HullVariantSpec.computeHullModOPCost 跳过 permaMods），addPermaMod(id, true) 安装的 SMod 免费。 */
    private fun installPrioritySmods(
        variant: ShipVariantAPI,
        member: FleetMemberAPI,
        neutralStats: MutableCharacterStatsAPI,
        phase: Boolean,
        hasShield: Boolean,
        missileCapable: Boolean,
        installedShu: String?,
    ) {
        val hull = member.hullSpec
        val bannedByController = HullmodIncompatibility.forbiddenCandidates(hull.builtInMods + variant.hullMods)
        // 名额与原版装配 UI 一致：Misc.getCurrSpecialMods 只计 perma∩sMod 的非内建/非隐藏/非 D 插船插
        val initial = Misc.getMaxPermanentMods(member, neutralStats) - Misc.getCurrSpecialMods(variant)
        if (initial <= 0) {
            log.warn(
                "[ASTD] ${hull.hullId} SMod 名额已占满" +
                    "（${Misc.getCurrSpecialMods(variant)}/${Misc.getMaxPermanentMods(member, neutralStats)}，原版内置上限口径），本舰 SMod 内插不生效",
            )
        }
        var remaining = initial
        var installed = 0
        for (id in fitRules.smodPriority()) {
            if (remaining <= 0) break
            Global.getSettings().getHullModSpec(id) ?: continue
            if (hull.builtInMods.contains(id) || variant.hullMods.contains(id)) continue
            if (id in bannedByController) continue
            if (fitRules.isSmodBlockedByShu(id, installedShu)) continue
            if (!fitRules.isSmodApplicable(id, phase, hasShield, missileCapable)) continue
            variant.addPermaMod(id, true)
            remaining--
            installed++
        }
        if (installed == 0 && initial > 0) {
            log.info("[ASTD] ${hull.hullId} SMod 候选全部被过滤（适用性/SHU 冲突/模组互斥），本舰无 SMod 内插")
        }
    }

    private fun installShuHullmod(
        variant: ShipVariantAPI,
        hull: ShipHullSpecAPI,
        neutralStats: MutableCharacterStatsAPI,
    ): String? {
        if (variant.hullMods.any { it.startsWith(BountyFitRules.SHU_ID_PREFIX) }) return null
        val carrier = hull.hints.contains(ShipHullSpecAPI.ShipTypeHints.CARRIER)
        val picker = WeightedRandomPicker<String>(random)
        for ((id, weight) in fitRules.shuCandidates(hull.hullSize, carrier)) {
            val spec = Global.getSettings().getHullModSpec(id) ?: continue
            if (!isShuApplicable(id, variant, hull)) continue
            val cost = smodCostFor(spec, hull.hullSize)
            if (variant.getUnusedOP(neutralStats) < cost) {
                log.info("[ASTD] ${hull.hullId} SHU 候选 $id OP 不足（需 $cost），跳过该候选")
                continue
            }
            picker.add(id, weight)
        }
        val pick = picker.pick() ?: return null

        if (pick == BountyFitRules.SHU_PLASMA_DYNAMO) {
            fitRules.shuConflicts(pick).forEach { conflictId ->
                variant.sMods.remove(conflictId)
                variant.removePermaMod(conflictId)
                variant.removeMod(conflictId)
            }
        }
        variant.addMod(pick)
        return pick
    }

    private fun isShuApplicable(hullmodId: String, variant: ShipVariantAPI, hull: ShipHullSpecAPI): Boolean =
        when (hullmodId) {
            BountyFitRules.SHU_PLASMA_DYNAMO -> hull.shieldType != ShieldAPI.ShieldType.NONE
            else -> fitRules.shuConflicts(hullmodId).none { variant.hullMods.contains(it) }
        }

    private fun fillExtraMods(variant: ShipVariantAPI, hull: ShipHullSpecAPI, neutralStats: MutableCharacterStatsAPI) {
        val installedShu = variant.hullMods.firstOrNull { it.startsWith(BountyFitRules.SHU_ID_PREFIX) }
        val shuConflicts = fitRules.shuConflicts(installedShu)
        val bannedByController = HullmodIncompatibility.forbiddenCandidates(hull.builtInMods + variant.hullMods)
        var budgetLeft = fitRules.reclaimBudget(hull.hullSize)
        for (id in fitRules.extraModPriority()) {
            if (id in shuConflicts) continue
            if (id in bannedByController) continue
            val spec = Global.getSettings().getHullModSpec(id) ?: continue
            if (hull.builtInMods.contains(id) || variant.hullMods.contains(id)) continue
            val cost = smodCostFor(spec, hull.hullSize)
            var unused = variant.getUnusedOP(neutralStats)
            if (unused < cost && budgetLeft > 0) {
                budgetLeft -= reclaimOp(variant, neutralStats, cost - unused, budgetLeft)
                unused = variant.getUnusedOP(neutralStats)
            }
            if (unused < cost) {
                log.info("[ASTD] ${hull.hullId} 填充船插 $id OP 不足（差 ${cost - unused}，回收预算余 $budgetLeft），放弃该候选")
                continue
            }
            variant.addMod(id)
        }
    }

    /** 先探测实测单点回收量并还原，实际拆除由 BountyFitRules.planOpReclaim 规划；前提是寄存器与耗散通道单点 OP 同价（原版一致），定价漂移时另一侧按同价估算会偏差。 */
    private fun reclaimOp(
        variant: ShipVariantAPI,
        neutralStats: MutableCharacterStatsAPI,
        neededOp: Int,
        budget: Int,
    ): Int {
        val caps = variant.numFluxCapacitors
        val vents = variant.numFluxVents
        if (caps == 0 && vents == 0) return 0

        val probeCap = caps >= vents
        val before = variant.getUnusedOP(neutralStats)
        if (probeCap) variant.numFluxCapacitors = caps - 1 else variant.numFluxVents = vents - 1
        val opPerUnit = variant.getUnusedOP(neutralStats) - before
        if (probeCap) variant.numFluxCapacitors = caps else variant.numFluxVents = vents
        if (opPerUnit <= 0) {
            log.warn("[ASTD] OP 回收探测异常：拆除辐能改装后可用 OP 未增加（差值 $opPerUnit），放弃本次回收")
            return 0
        }

        val (removeCaps, removeVents) = fitRules.planOpReclaim(caps, vents, opPerUnit, neededOp, budget)
        variant.numFluxCapacitors = caps - removeCaps
        variant.numFluxVents = vents - removeVents
        return (removeCaps + removeVents) * opPerUnit
    }

    companion object {
        const val BOUNTY_KEY_PREFIX: String = "astd_bounty_"
        const val TUNED_MEMKEY: String = "\$astd_bounty_tuned"
        const val ASTD_FACTION_ID: String = "asteria_directorate"
        const val ASTD_HULL_PREFIX: String = "astd_"

        const val MAX_PICK_ATTEMPTS: Int = 200

        const val CORES_MEMKEY_PREFIX: String = "\$astd_bounty_cores_"

        const val UNIQUE_HULL_TAG: String = "astd_unique"

        fun stockVariantIdOf(originalVariant: String?, hullVariantId: String): String =
            originalVariant ?: hullVariantId

        val EXCLUDED_HULL_IDS: Set<String> = setOf("astd_xc_104")

        val EXCLUDED_DOCTRINE_HULL_HINTS: Set<ShipHullSpecAPI.ShipTypeHints> = setOf(
            ShipHullSpecAPI.ShipTypeHints.STATION,
            ShipHullSpecAPI.ShipTypeHints.SHIP_WITH_MODULES,
            ShipHullSpecAPI.ShipTypeHints.MODULE,
        )

        fun resolveRemnantPool(
            discovered: Map<ShipAPI.HullSize, List<String>>,
            size: ShipAPI.HullSize,
        ): List<String> = discovered[size].orEmpty().ifEmpty { BountyPoolConfig.REMNANT_POOLS[size].orEmpty() }

        /** 权重、等级与头像对齐原版 AICoreOfficerPluginImpl。 */
        val AI_CORE_WEIGHTS: List<Pair<String, Float>> = listOf(
            "gamma_core" to 50f,
            "beta_core" to 35f,
            "alpha_core" to 15f,
        )
        val AI_CORE_LEVELS: Map<String, Int> = mapOf(
            "gamma_core" to 3,
            "beta_core" to 5,
            "alpha_core" to 7,
        )

        val AI_CORE_PORTRAITS: Map<String, String> = mapOf(
            "gamma_core" to "graphics/portraits/portrait_ai1b.png",
            "beta_core" to "graphics/portraits/portrait_ai3b.png",
            "alpha_core" to "graphics/portraits/portrait_ai2b.png",
        )

        val BOUNTY_OFFICER_SKILL_PRIORITY: List<String> = listOf(
            "helmsmanship",
            "field_modulation",
            "systems_expertise",
            "missile_specialization",
            "polarized_armor",
            "damage_control",
            "impact_mitigation",
            "gunnery_implants",
            "energy_weapon_mastery",
            "ordnance_expert",
            "target_analysis",
            "ballistic_mastery",
            "combat_endurance",
        )

        fun bountyOfficerSkills(level: Int): List<String> =
            BOUNTY_OFFICER_SKILL_PRIORITY.take(level.coerceIn(0, BOUNTY_OFFICER_SKILL_PRIORITY.size))

        fun resolveOfficerSkills(tableSkills: List<String>?, level: Int): List<String> {
            if (tableSkills == null) return bountyOfficerSkills(level)
            if (level >= tableSkills.size) return tableSkills
            val priorityIndex = BOUNTY_OFFICER_SKILL_PRIORITY.withIndex().associate { it.value to it.index }
            return tableSkills.withIndex()
                .sortedWith(compareBy({ priorityIndex[it.value] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
                .take(level.coerceAtLeast(0))
        }

        fun resolveCoreOfficerSkills(tableSkills: List<String>?, level: Int, astdCore: Boolean): List<String> {
            val picks = resolveOfficerSkills(tableSkills, if (astdCore) level - 1 else level)
            return if (astdCore) listOf(StandardCores.COMBAT_INTEL_SKILL_ID) + picks else picks
        }

        const val SMS_SPECIAL_SKILL_PREFIX: String = "sms_"

        fun planModuleCoreSkills(originalSkillIds: List<String>, tableSkills: List<String>?): List<String> {
            val special = originalSkillIds.filter { it.startsWith(SMS_SPECIAL_SKILL_PREFIX) }
            val slots = originalSkillIds.size - special.size
            if (slots <= 0) return special
            val picked = resolveOfficerSkills(tableSkills, slots).toMutableList()
            if (picked.size < slots) {
                BOUNTY_OFFICER_SKILL_PRIORITY
                    .filter { it !in picked }
                    .take(slots - picked.size)
                    .let(picked::addAll)
            }
            return special + picked
        }

        val MISSILE_CAPABLE_SLOT_TYPES: Set<WeaponAPI.WeaponType> = setOf(
            WeaponAPI.WeaponType.MISSILE,
            WeaponAPI.WeaponType.HYBRID,
            WeaponAPI.WeaponType.COMPOSITE,
            WeaponAPI.WeaponType.UNIVERSAL,
            WeaponAPI.WeaponType.SYNERGY,
        )

        fun isEscortExcludedHull(hullId: String, astdUnique: Boolean): Boolean =
            astdUnique || hullId in EXCLUDED_HULL_IDS

        fun maxFleetSizeSetting(): Int {
            val aiCap = Global.getSettings().getInt("maxShipsInAIFleet")
            return if (aiCap > 0) aiCap else Global.getSettings().getInt("maxShipsInFleet")
        }

        fun reinforcementTargetSize(fillToFleetCap: Boolean, presetSize: Int, fleetCap: Int): Int =
            if (fillToFleetCap) fleetCap else presetSize * 2

        fun reinforcementPoolSide(fillToFleetCap: Boolean, addedAstd: Int, addedRemnant: Int): PoolSide =
            if (fillToFleetCap) BountyPoolConfig.pickPoolSide(addedAstd, addedRemnant) else PoolSide.REMNANT

        fun smodCostFor(spec: HullModSpecAPI, size: ShipAPI.HullSize): Int = when (size) {
            ShipAPI.HullSize.CAPITAL_SHIP -> spec.capitalCost
            ShipAPI.HullSize.CRUISER -> spec.cruiserCost
            ShipAPI.HullSize.DESTROYER -> spec.destroyerCost
            else -> spec.frigateCost
        }

        fun sizeRank(size: ShipAPI.HullSize): Int = when (size) {
            ShipAPI.HullSize.CAPITAL_SHIP -> 0
            ShipAPI.HullSize.CRUISER -> 1
            ShipAPI.HullSize.DESTROYER -> 2
            else -> 3
        }

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
