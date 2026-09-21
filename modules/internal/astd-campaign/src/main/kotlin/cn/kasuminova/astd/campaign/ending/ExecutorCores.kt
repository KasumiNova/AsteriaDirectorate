package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.ending.ExecutorCores.ADMIN_ACCESSIBILITY_BONUS
import cn.kasuminova.astd.campaign.ending.ExecutorCores.ADMIN_STABILITY_BONUS
import cn.kasuminova.astd.campaign.ending.ExecutorCores.COMMODITY_ID
import cn.kasuminova.astd.campaign.ending.ExecutorCores.ITEM_ADMIN
import cn.kasuminova.astd.campaign.ending.ExecutorCores.ITEM_COMBAT
import cn.kasuminova.astd.campaign.ending.ExecutorCores.reclaimDuplicateCores
import cn.kasuminova.astd.campaign.story.StoryQuestItems
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.AICoreAdminPlugin
import com.fs.starfarer.api.campaign.AICoreOfficerPlugin
import com.fs.starfarer.api.campaign.CampaignEventListener
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.PlayerMarketTransaction
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.impl.campaign.BaseAICoreOfficerPluginImpl
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import org.apache.log4j.Logger
import java.awt.Color

/**
 * 「执行官」核心保管纯规则（不触碰 Global，可单测）。
 *
 * 口径（85 §4.6「不可丢弃、不可出售」+ 原版机制核查）：
 * person 形态（在编军官/在任管理官）与 commodity 形态互斥——任命/卸载必然消耗或产生
 * commodity，二者不应同时存在；commodity 形态全宇宙总量封顶 1 枚（防多枚裁定）。
 */
object ExecutorCustodyRules {

    /**
     * 复制体回收量分配：总量超上限部分先扣仓储、再扣玩家货舱（仓储滞留优先回收，
     * 玩家手上那枚保留以维持「卸载军官 → 重新任命」的正常流程）。
     *
     * @param storageQty 全部仓储子市场中的 commodity 总量
     * @param cargoQty 玩家货舱中的 commodity 数量
     * @param assigned person 形态是否在用（在编军官或在任管理官持有核心）
     * @return 仓储移除量 to 货舱移除量（均为 0 = 无复制体）
     */
    fun reclaimAllocation(storageQty: Float, cargoQty: Float, assigned: Boolean): Pair<Float, Float> {
        val allowed = if (assigned) 0f else 1f
        val storage = storageQty.coerceAtLeast(0f)
        val cargo = cargoQty.coerceAtLeast(0f)
        var excess = (storage + cargo - allowed).coerceAtLeast(0f)
        val fromStorage = minOf(excess, storage)
        excess -= fromStorage
        return fromStorage to minOf(excess, cargo)
    }
}

/**
 * 「执行官」核心（第五章归档签署后签发；85 §4.6「一经签发，不予退换」）。
 *
 * 双载体的取舍（13/85 文档口径与原版机制折中）：
 * - 85 §6 设定写的是 SpecialItemData 物品——签发入舱本体即 special_items.csv 的
 *   [ITEM_COMBAT] / [ITEM_ADMIN]（tags `mission_item, no_drop, no_drop_salvage`，
 *   沿用剧情物品双层锁定口径，原版货运 UI 拒售拒存）；
 * - 但原版军官/行政官任命只认 `ai_core` 标签的 **commodity**（AICorePickerDialog 只扫
 *   货舱 commodity stack；PersonAPI.getAICoreId 命名取 CommoditySpec），故另注册隐藏
 *   backing commodity [COMMODITY_ID]（tags `nonecon, ai_core, no_drop, hide_in_codex`）
 *   作为任命出的 Person 的 aiCoreId 载体；任命入口在本模组终端（不经原版货舱拾取 UI），
 *   该 commodity 正常流程不入货舱——唯一流入路径是玩家在舰队界面手动卸载执行官军官
 *   （原版把 AI 核心军官卸回货舱的既有行为）。
 *
 * 保管口径（与 85 §4.6「不可丢弃、不可出售」对齐；原版机制核查结论见下）：
 * - 原版 AI 核心本来就能存入仓储（StoragePlugin.isIllegalOnSubmarket 仅按市场势力合法性
 *   判定，玩家自有市场恒为合法），完全锁死仓储不现实也不必——承诺收窄为
 *   「不可出售 / 不可丢弃 / 打捞不掉落」；
 * - 出售：[ExecutorCoreCustodyListener.reportPlayerMarketTransaction] 撤销（返还货舱 + HUD）；
 * - 丢弃：[ExecutorCoreCustodyListener.reportPlayerDumpedCargo] 撤销（no_drop 只管打捞
 *   掉落生成，玩家手动倾倒路径由监听兜底）；
 * - 防多枚：[ExecutorCoreCustodyListener.reportPlayerOpenedMarketAndCargoUpdated] 触发
 *   [reclaimDuplicateCores]——person 形态（在编军官/在任管理官）与 commodity 形态互斥，
 *   全宇宙 commodity 总量封顶 1 枚，超出部分（仓储优先）回收。
 *
 * 任命数值（近 Omega 档，13 文档未定案，提案值）：
 * - 战斗特化军官：alpha 七技能全 2 级 + level 8 + `$autoPointsMult` 4.5；
 * - 行政特化管理官：industrial_planning 1 + hypercognition 1，
 *   附任命市场稳定度 +1 / 可达性 +0.1 持久修正（[ADMIN_STABILITY_BONUS] / [ADMIN_ACCESSIBILITY_BONUS]）。
 */
object ExecutorCores {

    private const val CAT = "asteria_directorate"

    /** 战斗特化签发物品（special_items.csv）。 */
    const val ITEM_COMBAT: String = "astd_executor_core_combat"

    /** 行政特化签发物品（special_items.csv）。 */
    const val ITEM_ADMIN: String = "astd_executor_core_admin"

    /** backing commodity（commodities.csv；任命出的 Person 的 aiCoreId 载体，正常流程不入货舱）。 */
    const val COMMODITY_ID: String = "astd_executor_core"

    /** 任命市场 stat 修正的 sourceId。 */
    private const val ADMIN_MOD_ID: String = "astd_executor_admin"

    /** 行政特化：任命市场稳定度平加（13 文档未定案，提案值）。 */
    private const val ADMIN_STABILITY_BONUS: Float = 1f

    /** 行政特化：任命市场可达性平加（13 文档未定案，提案值）。 */
    private const val ADMIN_ACCESSIBILITY_BONUS: Float = 0.1f

    private val log: Logger = Global.getLogger(ExecutorCores::class.java)

    private val RECEIPT_COLOR = Color(200, 170, 120)

    /** 签发入舱（特化选择的状态部分在 [EndingProgression.issueExecutor]，本函数只发物品）。 */
    fun issueToPlayer(spec: EndingProgression.ExecutorSpec) {
        val itemId = when (spec) {
            EndingProgression.ExecutorSpec.COMBAT -> ITEM_COMBAT
            EndingProgression.ExecutorSpec.ADMIN -> ITEM_ADMIN
        }
        StoryQuestItems.grantToPlayer(itemId)
        HudMessages.campaign(I18n.t(I18n.Categories.MOD, "story.item.grant.$itemId"), RECEIPT_COLOR)
    }

    /** 玩家是否持有任一特化的「执行官」签发物品（分局站对话入口 gating）。 */
    fun playerHasCore(): Boolean =
        StoryQuestItems.playerHas(ITEM_COMBAT) || StoryQuestItems.playerHas(ITEM_ADMIN)

    /**
     * 复制体回收（防多枚，保管口径见类 KDoc）：
     * 统计玩家货舱与全部已初始化仓储子市场中 backing commodity 的总量，按
     * [ExecutorCustodyRules.reclaimAllocation] 分配移除量（仓储优先、货舱兜底）。
     *
     * @return 本次回收数量（0 = 无复制体）
     */
    fun reclaimDuplicateCores(): Int {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 执行官核心复制体回收失败：sector 不可用")
            return 0
        }

        // person 形态互斥判定：在编军官（含舰长位）或在任管理官持有核心时，commodity 不应存在
        val officerAssigned = sector.playerFleet?.fleetData?.officersCopy
            ?.any { it.person?.aiCoreId == COMMODITY_ID } == true
        val adminAssigned = sector.economy.marketsCopy
            .any { it.isPlayerOwned && it.admin?.aiCoreId == COMMODITY_ID }

        val playerCargo = sector.playerFleet?.cargo
        val cargoQty = playerCargo?.getQuantity(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID) ?: 0f
        val storageCargoes = sector.economy.marketsCopy
            .mapNotNull { it.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargoNullOk }
        val storageQty = storageCargoes.sumOf {
            it.getQuantity(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID).toDouble()
        }.toFloat()

        val (fromStorage, fromCargo) =
            ExecutorCustodyRules.reclaimAllocation(storageQty, cargoQty, officerAssigned || adminAssigned)
        val reclaimed = (fromStorage + fromCargo).toInt()
        if (reclaimed <= 0) return 0

        var remaining = fromStorage
        if (remaining > 0f) {
            for (storage in storageCargoes) {
                if (remaining <= 0f) break
                val qty = storage.getQuantity(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID)
                if (qty <= 0f) continue
                val take = minOf(qty, remaining)
                storage.removeItems(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID, take)
                remaining -= take
            }
        }
        if (fromCargo > 0f && playerCargo != null) {
            playerCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID, fromCargo)
        }

        HudMessages.campaign(
            I18n.t(I18n.Categories.MOD, "hud.ending.executor_custody_reclaim", "amount" to reclaimed),
            RECEIPT_COLOR,
        )
        log.info(
            "[ASTD] 执行官核心复制体已回收：$reclaimed 枚（仓储 $fromStorage / 货舱 $fromCargo；" +
                    "person 形态在编=${officerAssigned || adminAssigned}）",
        )
        return reclaimed
    }

    /**
     * 战斗特化军官 Person 工厂（近 Omega 档提案值，见类 KDoc）。
     * 结构与原版 AICoreOfficerPluginImpl.createPerson 一致（skipRefresh / autoPointsMult / reckless）。
     */
    fun createOfficerPerson(): PersonAPI {
        val person = Global.getFactory().createPerson()
        person.setFaction("player")
        person.aiCoreId = COMMODITY_ID
        val spec = Global.getSettings().getCommoditySpec(COMMODITY_ID)
        person.stats.isSkipRefresh = true
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.portraitSprite = "graphics/portraits/astd_portrait_core_o.png"
        person.stats.level = 8
        person.stats.setSkillLevel("helmsmanship", 2f)
        person.stats.setSkillLevel("target_analysis", 2f)
        person.stats.setSkillLevel("impact_mitigation", 2f)
        person.stats.setSkillLevel("field_modulation", 2f)
        person.stats.setSkillLevel("gunnery_implants", 2f)
        person.stats.setSkillLevel("combat_endurance", 2f)
        person.stats.setSkillLevel("damage_control", 2f)
        person.memoryWithoutUpdate.set("\$autoPointsMult", 4.5f)
        person.setPersonality("reckless")
        person.rankId = Ranks.SPACE_CAPTAIN
        person.postId = null
        person.stats.isSkipRefresh = false
        return person
    }

    /**
     * 行政特化管理官 Person 工厂（industrial_planning 1 + hypercognition 1；
     * 结构与原版 AICoreAdminPluginImpl.createPerson 一致）。
     */
    fun createAdminPerson(): PersonAPI {
        val person = Global.getFactory().createPerson()
        person.setFaction("player")
        person.aiCoreId = COMMODITY_ID
        val spec = Global.getSettings().getCommoditySpec(COMMODITY_ID)
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.portraitSprite = "graphics/portraits/astd_portrait_core_a.png"
        person.rankId = null
        person.postId = Ranks.POST_ADMINISTRATOR
        person.stats.setSkillLevel("industrial_planning", 1f)
        person.stats.setSkillLevel("hypercognition", 1f)
        return person
    }

    /**
     * 指定指挥舰（战斗特化）：执行官军官上任目标舰。
     *
     * 口径：
     * - 目标舰原有舰长保留在军官名册（与原版替换军官一致，仅卸任不除名）；
     * - 唯一性（全宇宙同时只存在一枚该军官）：指派前先回收既有执行官军官——
     *   凡舰长位/军官名册中 aiCoreId == [COMMODITY_ID] 者一律卸任移出
     *   （覆盖 state 记录的旧指挥舰，也覆盖军官失踪后名册残留等状态漂移）；
     * - 军官死亡/失踪后允许重新指派（重新生成 Person；本体物品仍在玩家处即可再造军官）；
     * - 指挥舰识别口径：state.executorCommandShipId 为记录源，舰上任执行官以
     *   captain.aiCoreId == [COMMODITY_ID] 举证（FleetMemberAPI 无 memory 通道）。
     *
     * @return 是否成功（舰只不在玩家舰队 → false 并记告警）
     */
    fun assignCommandShip(memberId: String): Boolean {
        val fleetData = Global.getSector()?.playerFleet?.fleetData
        if (fleetData == null) {
            log.error("[ASTD] 指定指挥舰失败：playerFleet 不可用")
            return false
        }
        val member = fleetData.membersListCopy.firstOrNull { it.id == memberId }
        if (member == null) {
            log.warn("[ASTD] 指定指挥舰失败：舰只不在玩家舰队（$memberId）")
            return false
        }

        // 回收既有执行官军官（唯一性口径，见上）：先卸舰长位，再移出名册
        var reclaimed = false
        for (m in fleetData.membersListCopy) {
            val captain = m.captain
            if (captain != null && captain.aiCoreId == COMMODITY_ID) {
                m.captain = null
                reclaimed = true
            }
        }
        for (data in fleetData.officersCopy) {
            val person = data.person
            if (person != null && person.aiCoreId == COMMODITY_ID) {
                fleetData.removeOfficer(person)
                reclaimed = true
            }
        }
        if (reclaimed) {
            log.info("[ASTD] 既有执行官军官已卸任回收（重新指派前置）")
        }

        val person = createOfficerPerson()
        fleetData.addOfficer(person)
        member.captain = person
        log.info("[ASTD] 执行官已上任指挥舰：${member.shipName}（$memberId）")
        return true
    }

    /**
     * 任命市场管理官（行政特化）。
     *
     * 口径：
     * - 唯一性（全宇宙同时只存在一枚该管理官）：任命前清扫全部市场——凡
     *   admin.aiCoreId == [COMMODITY_ID] 且非目标市场者一律卸任
     *   （ai_core_admin 条件仅在本模组任命时存在，随卸任移除；
     *   稳定度/可达性修正按 sourceId 卸载；setAdmin(null) 恢复原版默认行政官，
     *   与原版 DecivTracker/HIActionStage 的 setAdmin(null) 用法一致）；
     * - 管理官被撤换/市场易主后允许重新任命（重新生成 Person）。
     *
     * @return 是否成功（市场不存在 → false 并记告警）
     */
    fun appointAdmin(marketId: String): Boolean {
        val economy = Global.getSector()?.economy
        if (economy == null) {
            log.error("[ASTD] 任命行政官失败：economy 不可用")
            return false
        }
        val market = economy.getMarket(marketId)
        if (market == null) {
            log.warn("[ASTD] 任命行政官失败：市场不存在（$marketId）")
            return false
        }

        // 旧任命清扫（唯一性口径，见上；覆盖 state 记录漂移与市场易主残留）
        for (m in economy.marketsCopy) {
            if (m.id == marketId) continue
            if (m.admin?.aiCoreId != COMMODITY_ID) continue
            m.admin = null
            if (m.hasCondition("ai_core_admin")) m.removeCondition("ai_core_admin")
            m.stability.unmodify(ADMIN_MOD_ID)
            m.accessibilityMod.unmodify(ADMIN_MOD_ID)
            log.info("[ASTD] 旧任命市场执行官已卸任：${m.name}（${m.id}）")
        }

        market.admin = createAdminPerson()
        if (!market.hasCondition("ai_core_admin")) market.addCondition("ai_core_admin")
        val desc = I18n[CAT, "ending.executor_admin_mod_desc"]
        market.stability.modifyFlat(ADMIN_MOD_ID, ADMIN_STABILITY_BONUS, desc)
        market.accessibilityMod.modifyFlat(ADMIN_MOD_ID, ADMIN_ACCESSIBILITY_BONUS, desc)
        log.info("[ASTD] 执行官已任命市场管理官：${market.name}（$marketId）")
        return true
    }
}

/**
 * 「执行官」军官插件（commodityId = [ExecutorCores.COMMODITY_ID] 时由 [ExecutorCampaignPlugin] 经
 * pickAICoreOfficerPlugin 返回；createPerson 委托 [ExecutorCores.createOfficerPerson]）。
 */
class ExecutorOfficerPlugin : BaseAICoreOfficerPluginImpl() {
    override fun createPerson(aiCoreId: String?, factionId: String?, random: java.util.Random?): PersonAPI =
        ExecutorCores.createOfficerPerson()
}

/**
 * 「执行官」行政官插件（createPerson 委托 [ExecutorCores.createAdminPerson]）。
 */
class ExecutorAdminPlugin : AICoreAdminPlugin {
    override fun createPerson(aiCoreId: String?, factionId: String?, seed: Long): PersonAPI =
        ExecutorCores.createAdminPerson()
}

/**
 * 「执行官」战役插件：把两个核心插件接入原版分发链（ModAndPluginData 按优先级拾取）。
 *
 * ModPlugin 不继承 CampaignPlugin，本插件经 `SectorAPI.registerPlugin` 注册
 * （[cn.kasuminova.astd.campaign.bounty.BountyBootstrapper.onGameLoad]，memory key 去重）。
 */
class ExecutorCampaignPlugin : com.fs.starfarer.api.campaign.BaseCampaignPlugin() {

    override fun getId(): String = PLUGIN_ID

    /**
     * 非瞬时：插件表随存档序列化（writeReplace 剔除瞬时插件），配合
     * BountyBootstrapper 的 memory key 去重保证读档后不重复注册、也不丢注册。
     */
    override fun isTransient(): Boolean = false

    override fun pickAICoreOfficerPlugin(commodityId: String?): PluginPick<AICoreOfficerPlugin>? =
        if (commodityId == COMMODITY_ID) {
            PluginPick(ExecutorOfficerPlugin(), CampaignPlugin.PickPriority.MOD_SET)
        } else {
            null
        }

    override fun pickAICoreAdminPlugin(commodityId: String?): PluginPick<AICoreAdminPlugin>? =
        if (commodityId == COMMODITY_ID) {
            PluginPick(ExecutorAdminPlugin(), CampaignPlugin.PickPriority.MOD_SET)
        } else {
            null
        }

    companion object {
        /** 插件注册 id（unregisterPlugin 键）。 */
        const val PLUGIN_ID: String = "astd_executor_campaign_plugin"
    }
}

/**
 * 「执行官」backing commodity 保管监听：正常流程该 commodity 不入货舱，唯一流入路径是
 * 玩家手动卸载执行官军官（原版行为）；保管口径（与 85 §4.6 对齐，详见 [ExecutorCores] 类 KDoc）：
 * - 出售一律撤销——数量返还玩家货舱并 HUD 提示（「不予退换」，85 §4.6）；
 * - 手动倾倒一律撤销——从倾倒货堆摘除并返还货舱；
 * - 仓储侧检测——市场/仓储界面货变动时回收复制体（[ExecutorCores.reclaimDuplicateCores]）。
 *
 * 注册：[cn.kasuminova.astd.campaign.bounty.BountyBootstrapper.onGameLoad]（memory key 去重）。
 */
class ExecutorCoreCustodyListener : CampaignEventListener {

    private companion object {
        private val log: Logger = Global.getLogger(ExecutorCoreCustodyListener::class.java)
        private val RECEIPT_COLOR = Color(200, 170, 120)
    }

    override fun reportPlayerMarketTransaction(transaction: PlayerMarketTransaction?) {
        if (transaction == null) return
        val sold = transaction.getQuantitySold(COMMODITY_ID)
        if (sold <= 0f) return
        val cargo = Global.getSector()?.playerFleet?.cargo
        if (cargo == null) {
            log.error("[ASTD] 执行官核心出售撤销失败：playerFleet 不可用（数量 $sold）")
            return
        }
        cargo.addCommodity(COMMODITY_ID, sold)
        HudMessages.campaign(
            I18n[I18n.Categories.MOD, "hud.ending.executor_custody"],
            RECEIPT_COLOR,
        )
        log.info("[ASTD] 执行官核心出售已撤销并返还货舱（数量 $sold）")
    }

    /** 手动倾倒撤销（不可丢弃兜底：从倾倒货堆摘除并返还货舱）。 */
    override fun reportPlayerDumpedCargo(cargo: CargoAPI?) {
        if (cargo == null) return
        val dumped = cargo.getQuantity(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID)
        if (dumped <= 0f) return
        val playerCargo = Global.getSector()?.playerFleet?.cargo
        if (playerCargo == null) {
            log.error("[ASTD] 执行官核心倾倒撤销失败：playerFleet 不可用（数量 $dumped）")
            return
        }
        cargo.removeItems(CargoAPI.CargoItemType.RESOURCES, COMMODITY_ID, dumped)
        playerCargo.addCommodity(COMMODITY_ID, dumped)
        HudMessages.campaign(
            I18n[I18n.Categories.MOD, "hud.ending.executor_custody"],
            RECEIPT_COLOR,
        )
        log.info("[ASTD] 执行官核心倾倒已撤销并返还货舱（数量 $dumped）")
    }

    /** 仓储侧检测（市场/仓储界面货变动时触发）：回收复制体，防多枚。 */
    override fun reportPlayerOpenedMarketAndCargoUpdated(market: MarketAPI?) {
        reclaimDuplicateCores()
    }

    override fun reportPlayerOpenedMarket(market: MarketAPI?) = Unit
    override fun reportPlayerClosedMarket(market: MarketAPI?) = Unit
    override fun reportEncounterLootGenerated(
        context: com.fs.starfarer.api.campaign.FleetEncounterContextPlugin?,
        loot: CargoAPI?,
    ) = Unit

    override fun reportBattleOccurred(
        fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?,
        battle: com.fs.starfarer.api.campaign.BattleAPI?,
    ) = Unit

    override fun reportBattleFinished(
        fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?,
        battle: com.fs.starfarer.api.campaign.BattleAPI?,
    ) = Unit

    override fun reportPlayerEngagement(result: com.fs.starfarer.api.combat.EngagementResultAPI?) = Unit
    override fun reportFleetDespawned(
        fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?,
        reason: CampaignEventListener.FleetDespawnReason?,
        param: Any?,
    ) = Unit

    override fun reportFleetSpawned(fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?) = Unit
    override fun reportFleetReachedEntity(
        fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?,
        entity: com.fs.starfarer.api.campaign.SectorEntityToken?,
    ) = Unit

    override fun reportFleetJumped(
        fleet: com.fs.starfarer.api.campaign.CampaignFleetAPI?,
        from: com.fs.starfarer.api.campaign.SectorEntityToken?,
        to: com.fs.starfarer.api.campaign.JumpPointAPI.JumpDestination?,
    ) = Unit

    override fun reportShownInteractionDialog(dialog: com.fs.starfarer.api.campaign.InteractionDialogAPI?) = Unit
    override fun reportPlayerReputationChange(factionId: String?, delta: Float) = Unit
    override fun reportPlayerReputationChange(person: PersonAPI?, delta: Float) = Unit
    override fun reportPlayerActivatedAbility(ability: com.fs.starfarer.api.characters.AbilityPlugin?, param: Any?) = Unit
    override fun reportPlayerDeactivatedAbility(ability: com.fs.starfarer.api.characters.AbilityPlugin?, param: Any?) = Unit
    override fun reportPlayerDidNotTakeCargo(cargo: CargoAPI?) = Unit
    override fun reportEconomyTick(iter: Int) = Unit
    override fun reportEconomyMonthEnd() = Unit
}
