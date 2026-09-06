package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.StarSystemAPI

/**
 * IndEvo 联动内容的事实采集（口径同
 * [cn.kasuminova.astd.campaign.world.IndEvoWorldExtras]）。
 *
 * 探测全部走实体标签/状况 id/memory 键（`IndEvo_Entity_has_artillery` 标签、
 * `$IndEvo_ArtilleryStationScript` 管理脚本键、`IndEvo_ArtilleryStationCondition` 状况、
 * `IndEvo_derelict` 阵营），不引用 IndEvo 类：
 * 炮台实体由 ArtilleryStationScript 延迟生成（玩家进入星系时），存在性不可作为挂载判定。
 */
object IndEvoCareerProbe {

    /** IndEvo 轨道炮状况 id（IndEvo 4.1.b market_conditions.csv）。 */
    private const val CONDITION_ARTILLERY: String = "IndEvo_ArtilleryStationCondition"

    /** 行星「已挂炮台」标签（ArtilleryStationPlacer.addArtilleryToPlanet 打上）。 */
    private const val TAG_ENTITY_HAS_ARTILLERY: String = "IndEvo_Entity_has_artillery"

    /** 行星上的炮台管理脚本 memory 键（ArtilleryStationPlacer.addArtilleryToPlanet 写入）。 */
    private const val MEM_ARTILLERY_SCRIPT: String = "\$IndEvo_ArtilleryStationScript"

    /** 主星系：洪炉/淬池中立磁轨炮台（行星标签 + 管理脚本 + 市场状况）+ 观锚站 ×4。 */
    fun mainSystemFacts(sector: SectorAPI): Map<String, Any?> = linkedMapOf(
        "hongluArtillery" to artilleryPresent(sector, StoryWorldIds.MAIN_PLANET_HONGLU),
        "hongluArtilleryCondition" to artilleryCondition(sector, StoryWorldIds.MARKET_HONGLU),
        "cuichiArtillery" to artilleryPresent(sector, StoryWorldIds.MAIN_PLANET_CUICHI),
        "cuichiArtilleryCondition" to artilleryCondition(sector, StoryWorldIds.MARKET_CUICHI),
        "watchtowerCount" to watchtowerCount(sector, StoryWorldIds.MAIN_STAR),
    )

    /** 星坠：锻原敌对磁轨炮台（市场阵营 IndEvo_derelict）+ 观锚站 ×4。 */
    fun starfallFacts(sector: SectorAPI): Map<String, Any?> {
        val duanyuan = CareerAutomationScript.findMarket(sector, StoryWorldIds.MARKET_DUANYUAN)
        return linkedMapOf(
            "duanyuanArtillery" to artilleryPresent(sector, StoryWorldIds.STARFALL_PLANET_DUANYUAN),
            "duanyuanArtilleryCondition" to artilleryCondition(sector, StoryWorldIds.MARKET_DUANYUAN),
            "duanyuanFaction" to duanyuan?.factionId,
            "watchtowerCount" to watchtowerCount(sector, StoryWorldIds.STARFALL_STAR),
        )
    }

    /**
     * 炮台是否已挂载到行星：IndEvo 官方管线（ArtilleryStationPlacer.addArtilleryToPlanet）的
     * 耐久事实 = 行星 `IndEvo_Entity_has_artillery` 标签 + 管理脚本 memory 键。
     * 炮台实体/舰队由 ArtilleryStationScript 在玩家进入星系时才生成，不能以实体存在性判定。
     */
    private fun artilleryPresent(sector: SectorAPI, planetId: String): Boolean {
        val planet = sector.getEntityById(planetId) ?: return false
        return planet.hasTag(TAG_ENTITY_HAS_ARTILLERY) &&
            planet.memoryWithoutUpdate.contains(MEM_ARTILLERY_SCRIPT)
    }

    private fun artilleryCondition(sector: SectorAPI, marketId: String): Boolean =
        CareerAutomationScript.findMarket(sector, marketId)?.hasCondition(CONDITION_ARTILLERY) == true

    private fun watchtowerCount(sector: SectorAPI, starId: String): Int {
        val system = sector.getEntityById(starId)?.containingLocation as? StarSystemAPI ?: return 0
        return system.allEntities.count { it.hasTag(StoryWorldIds.TAG_INDEVO_WATCHTOWER) }
    }
}
