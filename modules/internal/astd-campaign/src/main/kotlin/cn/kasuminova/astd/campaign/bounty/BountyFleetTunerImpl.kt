package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFitRules
import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import cn.kasuminova.astd.campaign.bounty.core.BountyOfficerSkills
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
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
        installExtraFittings(fleet)

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
        val pool = remnantPoolFor(size)
        if (pool.isEmpty()) return false

        if (config.destroyerBestOf > 1 && size == ShipAPI.HullSize.DESTROYER) {
            // best-of-K：实例化 K 个候选，保留部署点最高者，移除其余（与 ASTD 侧同语义）
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

    /** 余晖 doctrine 发现结果（首次混编时构建一次）。 */
    private val discoveredRemnantPools: Map<ShipAPI.HullSize, List<String>> by lazy { discoverRemnantPools() }

    private fun remnantPoolFor(size: ShipAPI.HullSize): List<String> {
        val discovered = discoveredRemnantPools[size].orEmpty()
        if (discovered.isEmpty()) {
            log.warn("[ASTD] 余晖 doctrine 未发现 $size 舰级候选，混编池该舰级退回硬编码兜底清单")
        }
        return resolveRemnantPool(discoveredRemnantPools, size)
    }

    /**
     * 余晖混编池动态发现：以 remnant 势力 doctrine 已知舰体（knownShips）为真相来源，
     * 逐舰体解析可用 stock variant（goal variant 优先，过滤 restricted/no_sim tag 与空装配），
     * 按舰级分组建池。其他模组挂进余晖 doctrine 的舰体由此自然入池。
     */
    private fun discoverRemnantPools(): Map<ShipAPI.HullSize, List<String>> {
        val settings = Global.getSettings()
        val faction = Global.getSector().getFaction(Factions.REMNANTS)
        if (faction == null) {
            log.warn("[ASTD] 余晖势力缺失，混编池退回硬编码兜底清单")
            return emptyMap()
        }

        // 全量 stock variant 按舰体归组（一次性索引，避免逐舰体扫描 variant 表）
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
                    // 模组带入的异常变体不静默：空装配记日志并跳过
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
     * 军官统一经原版插件分发链创建，等级按核心档覆盖；技能以变体技能表为准
     * （技能位不足时按全局优先级取舍），未登记变体退回全局优先级表取前 N。
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
                member.captain = createAiCoreCaptain(member.hullSpec.hullSize, member.variant.hullVariantId)
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

    /** 核心军官工厂：原版插件分发链创建（原版/astd/SMS 核心各自插件），随后按核心档覆盖等级、套用变体技能表取舍。 */
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
        } else {
            log.info("[ASTD] 核心 $coreId 无登记档位等级（模组核心），沿用插件默认等级 ${person.stats.level}，插件默认技能将被整体覆盖")
        }
        applyOfficerSkills(person, member.variant.hullVariantId)
        person.stats.isSkipRefresh = false
        return person
    }

    /**
     * 赏金舰队 AI 核心军官技能应用：清空插件默认技能后按 resolveOfficerSkills 的
     * 取舍结果全 2 级套用——命中变体技能表以表为准（N 不足时按全局优先级截取），
     * 未命中变体（余晖等）退回全局优先级表顺序取前 N。
     */
    private fun applyOfficerSkills(person: PersonAPI, variantId: String?) {
        val skills = resolveOfficerSkills(BountyOfficerSkills.forVariant(variantId), person.stats.level)
        person.stats.skillsCopy.forEach { person.stats.setSkillLevel(it.skill.id, 0f) }
        skills.forEach { person.stats.setSkillLevel(it, 2f) }
    }

    /** 余晖 AI 核心军官：核心按 gamma 50 / beta 35 / alpha 15 加权，对应等级 3/5/7（对齐原版 AICoreOfficerPluginImpl），性格统一 reckless（原版核心军官同款）。 */
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

    /**
     * 额外装配：每艘非民用舰船成员依次处理 SHU 软联动加权安装、SMod 内插（统一优先级）、
     * 余 OP 普通船插填充（不足时按舰级预算拆辐能寄存器/耗散通道回收 OP）。
     * SHU 先于 SMod：避免 SMod 内插的强化护盾被等离子充能护盾拆掉而名额不回填。
     * 战斗机联队显式跳过（protectVariants 未克隆联队 variant，写入会外溢共享 stock 实例）。
     */
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
            // protectVariants 已完成克隆与 no_autofit，可直接内插
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

    /** SMod 统一优先级内插：不适用及与已装 SHU 冲突的条目跳过取下一个，直到 SMod 上限或候选耗尽。 */
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
        val initial = Misc.getMaxPermanentMods(member, neutralStats) - variant.sMods.size - variant.permaMods.size
        if (initial <= 0) {
            log.warn("[ASTD] ${hull.hullId} SMod 名额已占满（内建/永久船插计入，导入装配自带 permaMods），本舰 SMod 内插不生效")
        }
        var remaining = initial
        var installed = 0
        for (id in fitRules.smodPriority()) {
            if (remaining <= 0) break
            val spec = Global.getSettings().getHullModSpec(id) ?: continue
            if (hull.builtInMods.contains(id) || variant.hullMods.contains(id)) continue
            if (fitRules.isSmodBlockedByShu(id, installedShu)) continue
            if (!fitRules.isSmodApplicable(id, phase, hasShield, missileCapable)) continue
            if (variant.getUnusedOP(neutralStats) < smodCostFor(spec, hull.hullSize)) continue
            variant.addPermaMod(id, true)
            remaining--
            installed++
        }
        if (installed == 0 && initial > 0) {
            log.info("[ASTD] ${hull.hullId} SMod 候选全部被过滤（适用性/SHU 冲突/OP 不足），本舰无 SMod 内插")
        }
    }

    /**
     * SHU 软联动加权安装：每舰至多一件特殊升级（SHU 脚本自身互斥口径），返回安装的船插 id。
     * 权重全 0 或无适用项时不安装；候选 OP 装不下记日志跳过（与 SMod/填充同一 OP 口径）。
     * 非等离子候选按存在互斥件则跳过；等离子充能护盾发生器选中后先拆除其互斥护盾系船插
     * （sMods/permaMods/hullMods 三路），OP 随移除自然回收并在后续阶段再利用。
     */
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

    /** SHU 候选适用性等效判定：等离子充能护盾须有护盾（互斥件安装时拆除）；其余候选存在互斥件则跳过。 */
    private fun isShuApplicable(hullmodId: String, variant: ShipVariantAPI, hull: ShipHullSpecAPI): Boolean =
        when (hullmodId) {
            BountyFitRules.SHU_PLASMA_DYNAMO -> hull.shieldType != ShieldAPI.ShieldType.NONE
            else -> fitRules.shuConflicts(hullmodId).none { variant.hullMods.contains(it) }
        }

    /**
     * 余 OP 普通船插填充：按统一优先级能装就装、装不下取下一个；OP 不足时先按
     * 舰级预算拆辐能寄存器/耗散通道回收。已装 SHU 船插的互斥件跳过，避免与 SHU 冲突。
     */
    private fun fillExtraMods(variant: ShipVariantAPI, hull: ShipHullSpecAPI, neutralStats: MutableCharacterStatsAPI) {
        val installedShu = variant.hullMods.firstOrNull { it.startsWith(BountyFitRules.SHU_ID_PREFIX) }
        val shuConflicts = fitRules.shuConflicts(installedShu)
        var budgetLeft = fitRules.reclaimBudget(hull.hullSize)
        for (id in fitRules.extraModPriority()) {
            if (id in shuConflicts) continue
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

    /**
     * 拆辐能寄存器/耗散通道回收 OP：先探测实测单点回收量并还原，
     * 实际拆除统一由 BountyFitRules.planOpReclaim 按缺口 [neededOp] 与预算规划（回收总量不超预算）。
     * 前提：寄存器与耗散通道单点 OP 同价（原版口径一致）；若未来版本两侧定价漂移，
     * 探测只测了被拆侧单价，另一侧按同价估算会产生偏差。返回实际回收 OP。
     */
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
        // 探测拆除一律还原，实际拆除由 planOpReclaim 统一规划
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

        /** 单次后处理中随机抽取的最大尝试次数（排除舰重抽与规模补齐共用，防止抽取失败死循环）。 */
        const val MAX_PICK_ATTEMPTS: Int = 200

        /** 装舰核心表的 sector memory 键前缀（完整键 = 前缀 + bountyKey），供 BountyCoreLootScript 读取。 */
        const val CORES_MEMKEY_PREFIX: String = "\$astd_bounty_cores_"

        const val UNIQUE_HULL_TAG: String = "astd_unique"

        /** 发布范围外舰体的显式排除清单（与 astd_unique tag 并用的双保险：tag 漏标时仍然生效）。 */
        val EXCLUDED_HULL_IDS: Set<String> = setOf("astd_zw_001", "astd_xc_104")

        /** 余晖 doctrine 发现时跳过的舰体 hint：空间站与其模块不入作战混编池。 */
        val EXCLUDED_DOCTRINE_HULL_HINTS: Set<ShipHullSpecAPI.ShipTypeHints> = setOf(
            ShipHullSpecAPI.ShipTypeHints.STATION,
            ShipHullSpecAPI.ShipTypeHints.SHIP_WITH_MODULES,
            ShipHullSpecAPI.ShipTypeHints.MODULE,
        )

        /**
         * 余晖混编池解析：发现结果在该舰级非空时以 doctrine 为准，
         * 否则退回 BountyPoolConfig.REMNANT_POOLS 硬编码兜底清单。
         * 纯函数（单测直调）；兜底分支的 warn 由调用点负责。
         */
        fun resolveRemnantPool(
            discovered: Map<ShipAPI.HullSize, List<String>>,
            size: ShipAPI.HullSize,
        ): List<String> = discovered[size].orEmpty().ifEmpty { BountyPoolConfig.REMNANT_POOLS[size].orEmpty() }

        /** 余晖 AI 核心加权与对应军官等级（对齐原版 AICoreOfficerPluginImpl：gamma 3 / beta 5 / alpha 7）。 */
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

        /** 核心对应原版头像（对齐原版 AICoreOfficerPluginImpl 的 portrait 映射）。 */
        val AI_CORE_PORTRAITS: Map<String, String> = mapOf(
            "gamma_core" to "graphics/portraits/portrait_ai1b.png",
            "beta_core" to "graphics/portraits/portrait_ai3b.png",
            "alpha_core" to "graphics/portraits/portrait_ai2b.png",
        )

        /**
         * 赏金舰队 AI 核心军官技能全局优先级（高 → 低）：
         * 操舵技术 > 相场调制 > 系统专长 > 导弹特化 > 极化装甲 > 损伤管制 > 冲击缓解 >
         * 火控植入 > 能量精通 > 军械专长 > 目标解析 > 实弹精通 > 战斗耐力。
         * 角色：变体技能表技能位不足时的取舍顺序，以及未登记变体军官的 fallback 组成。
         */
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

        /**
         * 按军官等级从全局优先级表顺序取前 N 个技能（超出表长按表长截断）。
         * 技能位 = 军官等级：StandardCores 档位等级即技能位数的裁定
         * （原版 AICoreOfficerPluginImpl 的 gamma 3 / beta 5 / alpha 7 即按等级配等数技能，astd G/B/A/O 同口径对齐）。
         */
        fun bountyOfficerSkills(level: Int): List<String> =
            BOUNTY_OFFICER_SKILL_PRIORITY.take(level.coerceIn(0, BOUNTY_OFFICER_SKILL_PRIORITY.size))

        /**
         * 军官技能组成解析：变体技能表为真相来源，全局优先级表提供取舍顺序。
         * 技能位 N = 军官等级（口径依据见 [bountyOfficerSkills]）。
         * 命中变体表：N 不小于表长则装满全表；N 不足时表内技能按全局优先级排序
         * （不在优先级表中的技能排在后面、保持原相对顺序），取前 N 个。
         * 未命中（null）：退回全局优先级表顺序取前 N。
         */
        fun resolveOfficerSkills(tableSkills: List<String>?, level: Int): List<String> {
            if (tableSkills == null) return bountyOfficerSkills(level)
            if (level >= tableSkills.size) return tableSkills
            val priorityIndex = BOUNTY_OFFICER_SKILL_PRIORITY.withIndex().associate { it.value to it.index }
            return tableSkills.withIndex()
                .sortedWith(compareBy({ priorityIndex[it.value] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
                .take(level.coerceAtLeast(0))
        }

        /** 具备导弹搭载能力的武器槽类型（扩展弹舱适用性等效判定）。 */
        val MISSILE_CAPABLE_SLOT_TYPES: Set<WeaponAPI.WeaponType> = setOf(
            WeaponAPI.WeaponType.MISSILE,
            WeaponAPI.WeaponType.HYBRID,
            WeaponAPI.WeaponType.COMPOSITE,
            WeaponAPI.WeaponType.UNIVERSAL,
            WeaponAPI.WeaponType.SYNERGY,
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
