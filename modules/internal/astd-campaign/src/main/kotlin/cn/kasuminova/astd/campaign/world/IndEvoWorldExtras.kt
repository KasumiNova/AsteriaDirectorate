package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import indevo.ids.Ids
import indevo.industries.artillery.scripts.ArtilleryStationScript
import indevo.industries.artillery.utils.ArtilleryStationPlacer
import org.apache.log4j.Logger

/**
 * IndEvo（工业革命）联动扩展——唯一允许直接引用 IndEvo 类的隔离文件。
 *
 * 调用纪律：本对象的所有入口都必须先经过 [StoryWorldGenerator.isIndEvoEnabled]
 * （mod manager 检测）与状态标志位确认后才可调用，调用方还需捕获 [Throwable]
 * 并记录警告——JVM 懒加载保证 IndEvo 缺席时本类不会被加载，但防御仍放在调用侧。
 * 不触碰 IndEvo 的全局部署标志（$IndEvo_placedArtilleries 等），只逐实体挂接。
 *
 * 联动内容（docs/story/03、07）：
 * - 剧情主星系：洪炉 / 淬池 各一座磁轨炮轨道炮台（中立、不主动开火）+ 4 个稳定点观锚站；
 * - 星坠遗址星系：锻原一座磁轨炮轨道炮台（敌对、主动开火）+ 4 个稳定点观锚站。
 *
 * 已按 IndEvo 真实 jar（javap）核实的签名：
 * - `ArtilleryStationPlacer.addArtilleryToPlanet(SectorEntityToken, boolean)`：
 *   自带 `IndEvo_Entity_has_artillery` 标签存在性检查（读档重进/重试不会重复挂接，
 *   被玩家摧毁的炮台因标签保留也不会复活）；星系内无 `IndEvo_watchtower` 实体时
 *   会自动调用 placeWatchtowers——本类先按稳定 ID 放置 4 座观锚站，
 *   使该自动布置被跳过，保证恰好 4 座额外观锚站；
 *   同时给行星附加 IndEvo 要求的 `IndEvo_ArtilleryStationCondition` 特性；
 * - `ArtilleryStationScript.TYPE_KEY`（$IndEvo_ArtilleryType）：在脚本惰性取值前写入
 *   "railgun" 锁定磁轨炮类型；
 * - 炮台可摧毁/行星可殖民：不附加任何阻止殖民或交互的标签/记忆
 *   （addArtilleryToPlanet 自行添加的 not_random_mission_target 只影响任务生成器选址）。
 */
object IndEvoWorldExtras {

    private val log: Logger = Global.getLogger(IndEvoWorldExtras::class.java)

    /** IndEvo 观锚站实体类型（data/config/custom_entities.json，自带 IndEvo_watchtower 标签）。 */
    private const val WATCHTOWER_ENTITY_TYPE = "IndEvo_Watchtower"

    private data class WatchtowerSpec(val id: String, val angleDeg: Float, val orbitRadius: Float)

    /** 主星系：4 个额外稳定点的观锚站（轨道避开已有实体；ID 即观锚站实体 ID）。 */
    private val MAIN_WATCHTOWERS = listOf(
        WatchtowerSpec(StoryWorldIds.MAIN_INDEVO_STABLE_1, 60f, 5400f),
        WatchtowerSpec(StoryWorldIds.MAIN_INDEVO_STABLE_2, 165f, 7400f),
        WatchtowerSpec(StoryWorldIds.MAIN_INDEVO_STABLE_3, 285f, 9800f),
        WatchtowerSpec(StoryWorldIds.MAIN_INDEVO_STABLE_4, 345f, 11800f),
    )

    private val STARFALL_WATCHTOWERS = listOf(
        WatchtowerSpec(StoryWorldIds.STARFALL_INDEVO_STABLE_1, 20f, 4500f),
        WatchtowerSpec(StoryWorldIds.STARFALL_INDEVO_STABLE_2, 155f, 7000f),
        WatchtowerSpec(StoryWorldIds.STARFALL_INDEVO_STABLE_3, 265f, 10000f),
        WatchtowerSpec(StoryWorldIds.STARFALL_INDEVO_STABLE_4, 325f, 13500f),
    )

    /** 主星系：洪炉 / 淬池 中立磁轨炮台 + 中立观锚站。 */
    fun applyMainSystemExtras(system: StarSystemAPI) {
        spawnWatchtowers(system, Factions.NEUTRAL, MAIN_WATCHTOWERS)
        addRailgunArtillery(system, StoryWorldIds.MAIN_PLANET_HONGLU)
        addRailgunArtillery(system, StoryWorldIds.MAIN_PLANET_CUICHI)
        log.info("[IndEvoWorldExtras] 主星系 IndEvo 扩展已附加（中立炮台 ×2 + 观锚站 ×4）。")
    }

    /** 星坠遗址：锻原敌对磁轨炮台 + 敌对观锚站。 */
    fun applyStarfallExtras(system: StarSystemAPI) {
        spawnWatchtowers(system, Ids.DERELICT_FACTION_ID, STARFALL_WATCHTOWERS)
        switchDuanyuanToDerelictFaction(system)
        addRailgunArtillery(system, StoryWorldIds.STARFALL_PLANET_DUANYUAN)
        log.info("[IndEvoWorldExtras] 星坠遗址 IndEvo 扩展已附加（敌对炮台 ×1 + 观锚站 ×4）。")
    }

    /**
     * 锻原市场/实体切换到 IndEvo_derelict：
     * 与 IndEvo 原生荒芜星系炮台行为对齐（炮台敌对开火、观锚站传感器锁定联动激活）。
     * IndEvo 自身在 ModPlugin 中已将 IndEvo_derelict 对所有阵营设为敌对。
     * 玩家已殖民锻原时市场被殖民地市场替换（id 不同），此处 market 解析不到即跳过。
     */
    private fun switchDuanyuanToDerelictFaction(system: StarSystemAPI) {
        val planet = system.getEntityById(StoryWorldIds.STARFALL_PLANET_DUANYUAN)
        if (planet == null) {
            log.warn("[IndEvoWorldExtras] 锻原实体缺失，跳过阵营切换。")
            return
        }
        val market = planet.market
        if (market == null || market.id != StoryWorldIds.marketIdFor(StoryWorldIds.STARFALL_PLANET_DUANYUAN)) {
            log.info("[IndEvoWorldExtras] 锻原市场已由外部接管，跳过阵营切换。")
        } else {
            market.factionId = Ids.DERELICT_FACTION_ID
        }
        planet.setFaction(Ids.DERELICT_FACTION_ID)
    }

    /**
     * 观锚站直接以稳定 ID 创建实体并绕主星运行（轨道 focus 为主星，非交互实体）。
     * 不生成 stable_location 锚点，因此不会留下 4 个可再建的空稳定点；
     * 与 IndEvo 原生 placeWatchtowers 中气巨/环带的放置方式一致。
     */
    private fun spawnWatchtowers(
        system: StarSystemAPI,
        factionId: String,
        watchtowers: List<WatchtowerSpec>,
    ) {
        val star = system.star ?: run {
            log.warn("[IndEvoWorldExtras] 星系 ${system.id} 无主星，观锚站布置跳过。")
            return
        }
        val faction = Global.getSector().getFaction(factionId) ?: run {
            log.warn("[IndEvoWorldExtras] 阵营 $factionId 不存在，观锚站布置跳过。")
            return
        }

        for (spec in watchtowers) {
            if (system.getEntityById(spec.id) != null) continue
            val watchtower = system.addCustomEntity(spec.id, null, WATCHTOWER_ENTITY_TYPE, faction.id, null)
            watchtower.setCircularOrbit(star, spec.angleDeg, spec.orbitRadius, spec.orbitRadius / 10f)
            watchtower.addTag(StoryWorldIds.TAG_STORY_ENTITY)
            watchtower.memoryWithoutUpdate[StoryWorldIds.MEM_STORY_ROLE] = "watchtower"
        }
    }

    /**
     * 给行星挂接磁轨炮轨道炮台（强制 railgun 类型）。
     * addArtilleryToPlanet 自带 `IndEvo_Entity_has_artillery` 标签去重：
     * 重入与被摧毁后的读档恢复都不会重复挂接或复活炮台。
     */
    private fun addRailgunArtillery(system: StarSystemAPI, planetId: String) {
        val planet = system.getEntityById(planetId) ?: run {
            log.warn("[IndEvoWorldExtras] 行星 $planetId 缺失，炮台挂接跳过。")
            return
        }
        val market = planet.market ?: run {
            log.warn("[IndEvoWorldExtras] 行星 $planetId 无市场，炮台挂接跳过。")
            return
        }
        if (market.id != StoryWorldIds.marketIdFor(planetId)) {
            // 行星已被玩家殖民/外部接管：不再补挂炮台（殖民地防御由殖民地自身承担）。
            log.info("[IndEvoWorldExtras] 行星 $planetId 市场已易主，炮台挂接跳过。")
            return
        }
        // 在 ArtilleryStationScript 惰性读取类型前锁定为磁轨炮。
        market.memoryWithoutUpdate.set(ArtilleryStationScript.TYPE_KEY, StoryWorldIds.INDEVO_ARTILLERY_TYPE_RAILGUN)
        ArtilleryStationPlacer.addArtilleryToPlanet(planet, false)
    }
}
