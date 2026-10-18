package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCAptitudeSection
import second_in_command.specs.SCBaseAptitudePlugin

/**
 * 「菀星智能」SIC 天赋插件（astd_sc_wanxing，docs/design/skills/10-sic-skills.md）。
 *
 * 注册：contents/data/config/secondInCommand/SCAptitudes.csv（SIC 纯数据驱动加载，
 * SIC 缺失时该 CSV 不会被读取，天然软依赖）。互斥语义：category `sc_cat_automated`，
 * 与 SIC 自带「无人战舰」及其他自动化天赋互斥（同 category 天赋舰队内只能存在一名，
 * 由 SIC SCData.setOfficerInEmptySlotIfAvailable 的 category 检查强制）。
 *
 * 技能树分段（createSections 必须幂等只 add，SIC 每次取段落后立即清空）：
 * - section1（0 前置）：武备升级 / 引力调谐 / 六相冰辐能网络 / 天幕强化
 * - section2（2 前置）：维度分流 / 精英狼群 / 天幕升格
 * - section3（4 前置）：智能联结 / 天幕解限
 *
 * 生成权重：
 * - NPC 舰队：任一成员舰体 id 以 `astd_` 开头 → 3，否则 0（菀星天赋只流向菀星舰队）；
 * - 市场：ASTD 剧情空间站市场（[STORY_MARKET_IDS]）→ spec.spawnWeight × [STORY_MARKET_WEIGHT_MULT]；
 * - 低温休眠舱：spec.spawnWeight × [CRYOPOD_WEIGHT_MULT]（菀星遗产封存，低概率外流）。
 */
class WanxingAptitude : SCBaseAptitudePlugin() {

    override fun getOriginSkillId(): String = ORIGIN_SKILL_ID

    override fun createSections() {
        val section1 = SCAptitudeSection(true, 0, "technology1")
        section1.addSkill("astd_sc_armament_upgrade")
        section1.addSkill("astd_sc_grav_tuning")
        section1.addSkill("astd_sc_frost_flux_grid")
        section1.addSkill("astd_sc_skydome_reinforce")
        addSection(section1)

        val section2 = SCAptitudeSection(true, 2, "technology3")
        section2.addSkill("astd_sc_dim_shunt")
        section2.addSkill("astd_sc_elite_wolfpack")
        section2.addSkill("astd_sc_skydome_ascend")
        addSection(section2)

        val section3 = SCAptitudeSection(true, 4, "technology5")
        section3.addSkill("astd_sc_intel_link")
        section3.addSkill("astd_sc_skydome_unbound")
        addSection(section3)
    }

    override fun getNPCFleetSpawnWeight(data: SCData, fleet: CampaignFleetAPI): Float {
        if (fleet.fleetData.membersListCopy.any { it.hullId.startsWith(ASTD_HULL_ID_PREFIX) }) {
            return NPC_FLEET_WEIGHT_WITH_ASTD_SHIPS
        }
        return 0f
    }

    override fun getMarketSpawnweight(market: MarketAPI): Float {
        if (market.id in STORY_MARKET_IDS) return spec.spawnWeight * STORY_MARKET_WEIGHT_MULT
        return spec.spawnWeight
    }

    override fun getCryopodSpawnWeight(system: StarSystemAPI): Float =
        spec.spawnWeight * CRYOPOD_WEIGHT_MULT

    override fun addCodexDescription(tooltip: TooltipMakerAPI) {
        tooltip.addPara(I18n[I18n.Categories.MOD, "ui.sic.aptitude.codex"], Misc.getTextColor(), 0f)
    }

    companion object {
        /** 基石技能（天幕网络，随天赋自动解锁）。 */
        const val ORIGIN_SKILL_ID = "astd_sc_skydome_network"

        /** ASTD 舰体 id 前缀（NPC 舰队权重判定）。 */
        const val ASTD_HULL_ID_PREFIX = "astd_"

        /** 舰队含 ASTD 舰体时的 NPC 生成权重。 */
        const val NPC_FLEET_WEIGHT_WITH_ASTD_SHIPS = 3f

        /** 剧情空间站市场的权重倍率。 */
        const val STORY_MARKET_WEIGHT_MULT = 20f

        /** 低温休眠舱的权重倍率。 */
        const val CRYOPOD_WEIGHT_MULT = 0.25f

        /** ASTD 剧情空间站市场 id 集（StoryWorldIds.MARKET_* 常量，全部以 astd_story_market 开头）。 */
        val STORY_MARKET_IDS: Set<String> = setOf(
            StoryWorldIds.MARKET_LANTAI,
            StoryWorldIds.MARKET_HONGLU,
            StoryWorldIds.MARKET_CUICHI,
            StoryWorldIds.MARKET_MAIN_STATION,
            StoryWorldIds.MARKET_DUANYUAN,
            StoryWorldIds.MARKET_SHIGUANG,
        )
    }
}
