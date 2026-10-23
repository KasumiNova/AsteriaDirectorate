package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.AICoreAdminPlugin
import com.fs.starfarer.api.campaign.AICoreOfficerPlugin
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.impl.campaign.BaseAICoreOfficerPluginImpl
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import org.apache.log4j.Logger
import java.util.Random

object StandardCores {

    private val log: Logger = Global.getLogger(StandardCores::class.java)

    val CHATTER_CHARACTER_IDS: List<String> = listOf(
        "astd_wanxing_core",
        "astd_wanxing_core_v2",
        "astd_wanxing_core_v3",
    )

    const val ESCORT_DROP_CHANCE: Float = 0.5f

    enum class Tier(
        val commodityId: String,
        val portrait: String,
        val officerLevel: Int,
        val officerSkills: List<String>,
        val autoPointsMult: Float,
        val droppable: Boolean,
    ) {
        G(
            "astd_ai_core_g", "graphics/portraits/astd_portrait_core_g.png", 4,
            listOf(COMBAT_INTEL_SKILL_ID, "helmsmanship", "impact_mitigation", "combat_endurance"), 2f, true,
        ),
        B(
            "astd_ai_core_b", "graphics/portraits/astd_portrait_core_b.png", 6,
            listOf(
                COMBAT_INTEL_SKILL_ID, "helmsmanship", "target_analysis", "impact_mitigation",
                "gunnery_implants", "combat_endurance",
            ),
            3f, true,
        ),
        A(
            "astd_ai_core_a", "graphics/portraits/astd_portrait_core_a.png", 8,
            listOf(
                COMBAT_INTEL_SKILL_ID, "helmsmanship", "target_analysis", "impact_mitigation",
                "field_modulation", "gunnery_implants", "combat_endurance", "damage_control",
            ),
            4f, true,
        ),
        O(
            "astd_ai_core_o", "graphics/portraits/astd_portrait_core_o.png", 11,
            listOf(
                COMBAT_INTEL_SKILL_ID, "helmsmanship", "target_analysis", "impact_mitigation",
                "field_modulation", "gunnery_implants", "combat_endurance", "damage_control",
                "point_defense", "energy_weapon_mastery", "omega_ecm",
            ),
            5f, false,
        ),
    }

    const val COMBAT_INTEL_SKILL_ID: String = "astd_combat_intel"

    /** SSOptimizer 延迟加载下裸 getSprite 拿不到已加载贴图，必须在 onApplicationLoad 先 loadTexture 进缓存。 */
    fun preloadPortraits() {
        for (tier in Tier.entries) {
            Global.getSettings().loadTexture(tier.portrait)
        }
    }

    fun byCommodity(commodityId: String?): Tier? = Tier.entries.firstOrNull { it.commodityId == commodityId }

    val NON_DROPPABLE_CORE_IDS: Set<String> = setOf("omega_core")

    fun isCoreDroppable(coreId: String): Boolean =
        byCommodity(coreId)?.droppable ?: (coreId !in NON_DROPPABLE_CORE_IDS)

    fun flagshipTier(threatTier: Int): Tier = when {
        threatTier <= 1 -> Tier.G
        threatTier <= 3 -> Tier.B
        threatTier == 4 -> Tier.A
        else -> Tier.O
    }

    fun escortTier(threatTier: Int, rnd: Random): Tier = when {
        threatTier <= 2 -> Tier.G
        threatTier == 3 -> if (rnd.nextFloat() < 0.6f) Tier.G else Tier.B
        threatTier == 4 -> if (rnd.nextFloat() < 0.6f) Tier.B else Tier.A
        else -> if (rnd.nextFloat() < 0.5f) Tier.B else Tier.A
    }

    fun planFleetCores(shipCount: Int, threatTier: Int, seed: Long): List<String> {
        if (shipCount <= 0) return emptyList()
        val rnd = Random(seed)
        val flagship = flagshipTier(threatTier).commodityId
        return List(shipCount) { index ->
            if (index == 0) flagship else escortTier(threatTier, rnd).commodityId
        }
    }

    fun rollCoreLoot(installedCoreIds: List<String>, seed: Long): Map<String, Int> {
        val rnd = Random(seed)
        val loot = LinkedHashMap<String, Int>()
        var firstDroppableId: String? = null
        installedCoreIds.forEachIndexed { index, id ->
            if (!isCoreDroppable(id)) return@forEachIndexed
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

    fun patchChatterBinding(sector: SectorAPI) {
        val fleet = sector.playerFleet ?: return
        for (member in fleet.fleetData.membersListCopy) {
            val captain = member.captain ?: continue
            if (!captain.isAICore) continue
            if (byCommodity(captain.aiCoreId) == null) continue
            if (captain.memoryWithoutUpdate.contains("\$chatterChar")) continue
            captain.memoryWithoutUpdate.set("\$chatterChar", CHATTER_CHARACTER_IDS.random())
            log.info("[ASTD] 存量核心军官 chatter 人格补绑：${captain.nameString}")
        }
    }

    fun createOfficerPerson(tier: Tier, factionId: String?): PersonAPI {
        val person = Global.getFactory().createPerson()
        person.setFaction(factionId)
        person.aiCoreId = tier.commodityId
        val spec = Global.getSettings().getCommoditySpec(tier.commodityId)
        person.stats.isSkipRefresh = true
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.portraitSprite = tier.portrait
        person.stats.level = tier.officerLevel
        for (skill in tier.officerSkills) {
            person.stats.setSkillLevel(skill, 2f)
        }
        person.memoryWithoutUpdate.set("\$autoPointsMult", tier.autoPointsMult)
        person.memoryWithoutUpdate.set("\$chatterChar", CHATTER_CHARACTER_IDS.random())
        person.setPersonality("reckless")
        person.rankId = Ranks.SPACE_CAPTAIN
        person.postId = null
        person.stats.isSkipRefresh = false
        return person
    }

    fun createAdminPerson(factionId: String?): PersonAPI {
        val tier = Tier.A
        val person = Global.getFactory().createPerson()
        person.setFaction(factionId)
        person.aiCoreId = tier.commodityId
        val spec = Global.getSettings().getCommoditySpec(tier.commodityId)
        person.name = FullName(spec.name, "", FullName.Gender.ANY)
        person.portraitSprite = tier.portrait
        person.rankId = null
        person.postId = Ranks.POST_ADMINISTRATOR
        person.stats.setSkillLevel("industrial_planning", 1f)
        person.stats.setSkillLevel("hypercognition", 1f)
        return person
    }

    internal fun logUnknownCoreId(aiCoreId: String?) {
        log.error("[ASTD] 制式核心插件收到未知核心 id：$aiCoreId")
    }
}

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

class StandardCoreAdminPlugin : AICoreAdminPlugin {
    override fun createPerson(aiCoreId: String?, factionId: String?, seed: Long): PersonAPI? {
        if (StandardCores.byCommodity(aiCoreId) != StandardCores.Tier.A) {
            StandardCores.logUnknownCoreId(aiCoreId)
            return null
        }
        return StandardCores.createAdminPerson(factionId)
    }
}

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
        const val PLUGIN_ID: String = "astd_standard_core_campaign_plugin"

        const val MEMORY_PLUGIN_ADDED: String = "\$astd_standard_core_plugin_added"
    }
}
