package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.AICoreAdminPlugin
import com.fs.starfarer.api.campaign.AICoreOfficerPlugin
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.impl.campaign.BaseAICoreOfficerPluginImpl
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import java.util.Random
import org.apache.log4j.Logger

/**
 * ASTD 量产级（制式）AI 核心（docs/design/85 §2：G/B/A 三级可打捞，O 级仅作设定标尺）。
 *
 * 职责：
 * - 分档表 [Tier]：军官等级/技能/自动点数倍率与原版同档核心逐项对齐
 *   （对照 dev-resources/sources 的 AICoreOfficerPluginImpl.createPerson）；
 * - 赏金舰队核心配置方案 [planFleetCores]：威胁等级 → 旗舰/僚舰核心档位，种子确定性；
 * - 核心打捞表 [rollCoreLoot]：按舰队实际装舰核心滚动掉落（MagicBounty job_item_reward 语义）；
 * - 军官/行政官 Person 工厂与原版插件分发链接入（[StandardCoreCampaignPlugin]）。
 *
 * 分档映射（威胁等级 T = 主线 threatTier / 无限赏金 danger / 动态赏金解析档）：
 *
 * | T | 僚舰 | 旗舰 |
 * |---|---|---|
 * | 1 | G 100% | G |
 * | 2 | G 100% | B |
 * | 3 | G 60% / B 40% | B |
 * | 4 | B 60% / A 40% | A |
 * | 5+ | B 50% / A 50% | O |
 *
 * 动机：T1~2（序章/一章）目标舰队为低强度编队，全员 G 档对齐原版无人低阶编队观感；
 * T3~4（二/三章）随危险等级逐档抬升；T5+（四章 ZQ、无限赏金 danger 5）旗舰使用 O 档——
 * 85 文档口径「O 级不作为可获取物品存在」，故 O 档仅为军官标尺、不参与打捞（[Tier.droppable]）。
 *
 * 打捞口径（[rollCoreLoot]）：
 * - 掉落池 = 舰队实际装舰的可打捞核心（G/B/A），保证「掉落的正是舰队里装的」；
 * - 旗舰核心（索引 0）可打捞时保底必掉——击毁旗舰即触发 MagicBounty 打捞生成，
 *   「目标旗舰核心可回收」是设计承诺；
 * - 僚舰核心逐枚 50% 独立判定，数量区间 [1, 可打捞装舰数]，随舰队规模自然伸缩；
 * - 若独立判定全空且舰队装有可打捞核心，强制掉落索引最小者 1 枚（至少 1 枚的设计承诺）；
 * - 同一锁定种子重复计算结果不变（配合 [lockFleetPlan] 的接取时锁定）。
 */
object StandardCores {

    private val log: Logger = Global.getLogger(StandardCores::class.java)

    /** 旗舰核心保底之外，僚舰核心的单枚掉落概率。 */
    const val ESCORT_DROP_CHANCE: Float = 0.5f

    /**
     * 量产核心分档。
     *
     * @property commodityId commodities.csv 物品 id（打捞/安装/军官 aiCoreId 载体）
     * @property portrait 军官头像（contents/graphics/portraits/）
     * @property officerLevel 军官等级（原版同档对齐：G=3 / B=5 / A=7 / O=9）
     * @property officerSkills 军官技能表（全 2 级；原版同档技能集逐项对齐，O 档含 omega_ecm）
     * @property autoPointsMult 自动船点数倍率（原版 GAMMA/BETA/ALPHA/OMEGA_MULT：2/3/4/5）
     * @property droppable 是否可打捞（O 档不可获取，85 文档口径）
     */
    enum class Tier(
        val commodityId: String,
        val portrait: String,
        val officerLevel: Int,
        val officerSkills: List<String>,
        val autoPointsMult: Float,
        val droppable: Boolean,
    ) {
        G(
            "astd_ai_core_g", "graphics/portraits/astd_portrait_core_g.png", 3,
            listOf("helmsmanship", "impact_mitigation", "combat_endurance"), 2f, true,
        ),
        B(
            "astd_ai_core_b", "graphics/portraits/astd_portrait_core_b.png", 5,
            listOf("helmsmanship", "target_analysis", "impact_mitigation", "gunnery_implants", "combat_endurance"),
            3f, true,
        ),
        A(
            "astd_ai_core_a", "graphics/portraits/astd_portrait_core_a.png", 7,
            listOf(
                "helmsmanship", "target_analysis", "impact_mitigation", "field_modulation",
                "gunnery_implants", "combat_endurance", "damage_control",
            ),
            4f, true,
        ),
        O(
            "astd_ai_core_o", "graphics/portraits/astd_portrait_core_o.png", 9,
            listOf(
                "helmsmanship", "target_analysis", "impact_mitigation", "field_modulation",
                "gunnery_implants", "combat_endurance", "damage_control", "point_defense",
                "energy_weapon_mastery", "omega_ecm",
            ),
            5f, false,
        ),
    }

    /**
     * 预加载全部档位军官头像贴图。
     *
     * SSOptimizer 延迟加载下裸 getSprite 会拿到 textureID=0 的黑壳（同 ConeShardComponent 的
     * 已知坑），AI 核心指派界面的头像渲染正是裸 getSprite 路径，必须在 onApplicationLoad
     * 先 loadTexture 进缓存。
     */
    fun preloadPortraits() {
        for (tier in Tier.entries) {
            Global.getSettings().loadTexture(tier.portrait)
        }
    }

    /** 按 commodity id 反查分档（未知 id → null，调用方记日志）。 */
    fun byCommodity(commodityId: String?): Tier? = Tier.entries.firstOrNull { it.commodityId == commodityId }

    /** 旗舰档位（分档映射表见类 KDoc）。 */
    fun flagshipTier(threatTier: Int): Tier = when {
        threatTier <= 1 -> Tier.G
        threatTier <= 3 -> Tier.B
        threatTier == 4 -> Tier.A
        else -> Tier.O
    }

    /**
     * 僚舰档位抽取（分档映射表见类 KDoc；rnd 由调用方按种子供给，顺序消费保证确定性）。
     */
    fun escortTier(threatTier: Int, rnd: Random): Tier = when {
        threatTier <= 2 -> Tier.G
        threatTier == 3 -> if (rnd.nextFloat() < 0.6f) Tier.G else Tier.B
        threatTier == 4 -> if (rnd.nextFloat() < 0.6f) Tier.B else Tier.A
        else -> if (rnd.nextFloat() < 0.5f) Tier.B else Tier.A
    }

    /**
     * 舰队核心配置方案：返回与舰船列表同下标对齐的核心 commodity id 表（索引 0 = 旗舰）。
     * 同（shipCount, threatTier, seed）结果确定。
     */
    fun planFleetCores(shipCount: Int, threatTier: Int, seed: Long): List<String> {
        if (shipCount <= 0) return emptyList()
        val rnd = Random(seed)
        val flagship = flagshipTier(threatTier).commodityId
        return List(shipCount) { index ->
            if (index == 0) flagship else escortTier(threatTier, rnd).commodityId
        }
    }

    /**
     * 核心打捞表滚动（口径见类 KDoc）。
     *
     * @param installedCoreIds 舰队实际装舰核心 id 表（索引 0 = 旗舰）
     * @param seed 打捞随机种子（同输入同种子结果确定）
     * @return 物品 id → 数量（MagicBounty job_item_reward 语义；保持装舰序的 LinkedHashMap）
     */
    fun rollCoreLoot(installedCoreIds: List<String>, seed: Long): Map<String, Int> {
        val rnd = Random(seed)
        val loot = LinkedHashMap<String, Int>()
        var firstDroppableId: String? = null
        installedCoreIds.forEachIndexed { index, id ->
            val tier = byCommodity(id) ?: return@forEachIndexed
            if (!tier.droppable) return@forEachIndexed
            if (firstDroppableId == null) firstDroppableId = id
            if (index == 0 || rnd.nextFloat() < ESCORT_DROP_CHANCE) {
                loot.merge(id, 1, Int::plus)
            }
        }
        if (loot.isEmpty()) {
            firstDroppableId?.let { loot[it] = 1 }
        }
        return loot
    }

    /**
     * 接取时锁定口径（与 [BountyState.quotedRewards] 同模式）：同一 lockKey 首次构建后锁定，
     * 后续重复构建（失败重挂/幂等 tick）一律沿用首次锁定结果，不掉换核心种类与数量。
     */
    fun lockFleetPlan(
        locks: MutableMap<String, LockedFleetPlan>,
        lockKey: String,
        build: () -> LockedFleetPlan,
    ): LockedFleetPlan = locks.getOrPut(lockKey) { build() }

    /**
     * 军官 Person 工厂（结构与原版 AICoreOfficerPluginImpl.createPerson / 本模组
     * [cn.kasuminova.astd.campaign.ending.ExecutorCores.createOfficerPerson] 一致：
     * skipRefresh / autoPointsMult / reckless / SPACE_CAPTAIN）。
     */
    fun createOfficerPerson(tier: Tier, factionId: String?): PersonAPI {
        val person = Global.getFactory().createPerson()
        person.setFaction(factionId)
        person.setAICoreId(tier.commodityId)
        val spec = Global.getSettings().getCommoditySpec(tier.commodityId)
        person.stats.setSkipRefresh(true)
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.setPortraitSprite(tier.portrait)
        person.stats.setLevel(tier.officerLevel)
        for (skill in tier.officerSkills) {
            person.stats.setSkillLevel(skill, 2f)
        }
        person.memoryWithoutUpdate.set("\$autoPointsMult", tier.autoPointsMult)
        person.setPersonality("reckless")
        person.setRankId(Ranks.SPACE_CAPTAIN)
        person.setPostId(null)
        person.stats.setSkipRefresh(false)
        return person
    }

    /**
     * 行政官 Person 工厂（仅 A 档；85 §2「A 级可担任行政官」，结构与原版
     * AICoreAdminPluginImpl.createPerson 一致）。
     */
    fun createAdminPerson(factionId: String?): PersonAPI {
        val tier = Tier.A
        val person = Global.getFactory().createPerson()
        person.setFaction(factionId)
        person.setAICoreId(tier.commodityId)
        val spec = Global.getSettings().getCommoditySpec(tier.commodityId)
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.setPortraitSprite(tier.portrait)
        person.setRankId(null)
        person.setPostId(Ranks.POST_ADMINISTRATOR)
        person.stats.setSkillLevel("industrial_planning", 1f)
        person.stats.setSkillLevel("hypercognition", 1f)
        return person
    }

    /** 插件分发链收到未知 id（注册表与 pick 守卫不一致，属编写错误）。 */
    internal fun logUnknownCoreId(aiCoreId: String?) {
        log.error("[ASTD] 制式核心插件收到未知核心 id：$aiCoreId")
    }
}

/**
 * 制式核心军官插件（原版插件分发链入口：玩家从货舱安装核心为军官时由
 * Misc.getAICoreOfficerPlugin → [StandardCoreCampaignPlugin] 拾取）。
 */
class StandardCoreOfficerPlugin : BaseAICoreOfficerPluginImpl() {
    override fun createPerson(aiCoreId: String?, factionId: String?, random: Random?): PersonAPI? {
        val tier = StandardCores.byCommodity(aiCoreId)
        if (tier == null) {
            StandardCores.logUnknownCoreId(aiCoreId)
            return null
        }
        return StandardCores.createOfficerPerson(tier, factionId)
    }
}

/**
 * 制式核心行政官插件（仅 A 档可担任行政官，85 §2 口径）。
 */
class StandardCoreAdminPlugin : AICoreAdminPlugin {
    override fun createPerson(aiCoreId: String?, factionId: String?, seed: Long): PersonAPI? {
        if (StandardCores.byCommodity(aiCoreId) != StandardCores.Tier.A) {
            StandardCores.logUnknownCoreId(aiCoreId)
            return null
        }
        return StandardCores.createAdminPerson(factionId)
    }
}

/**
 * 制式核心战役插件：把军官/行政官插件接入原版分发链（注册见
 * [BountyBootstrapper.onGameLoad]，memory key 去重；非瞬时口径同
 * [cn.kasuminova.astd.campaign.ending.ExecutorCampaignPlugin]）。
 */
class StandardCoreCampaignPlugin : com.fs.starfarer.api.campaign.BaseCampaignPlugin() {

    override fun getId(): String = PLUGIN_ID

    override fun isTransient(): Boolean = false

    override fun pickAICoreOfficerPlugin(commodityId: String?): PluginPick<AICoreOfficerPlugin>? =
        if (StandardCores.byCommodity(commodityId) != null) {
            PluginPick(StandardCoreOfficerPlugin(), CampaignPlugin.PickPriority.MOD_SET)
        } else {
            null
        }

    override fun pickAICoreAdminPlugin(commodityId: String?): PluginPick<AICoreAdminPlugin>? =
        if (commodityId == StandardCores.Tier.A.commodityId) {
            PluginPick(StandardCoreAdminPlugin(), CampaignPlugin.PickPriority.MOD_SET)
        } else {
            null
        }

    companion object {
        /** 插件注册 id（unregisterPlugin 键）。 */
        const val PLUGIN_ID: String = "astd_standard_core_campaign_plugin"
    }
}
