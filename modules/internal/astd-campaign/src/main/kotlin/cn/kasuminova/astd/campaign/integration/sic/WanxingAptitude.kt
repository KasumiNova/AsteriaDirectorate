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
        const val ORIGIN_SKILL_ID = "astd_sc_skydome_network"

        const val ASTD_HULL_ID_PREFIX = "astd_"

        const val NPC_FLEET_WEIGHT_WITH_ASTD_SHIPS = 3f

        const val STORY_MARKET_WEIGHT_MULT = 20f

        const val CRYOPOD_WEIGHT_MULT = 0.25f

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
