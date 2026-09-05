package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Industries
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.campaign.ids.Terrain
import com.fs.starfarer.api.impl.campaign.procgen.StarGenDataSpec
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin
import com.fs.starfarer.api.util.Misc
import java.util.Random
import org.apache.log4j.Logger

/**
 * 剧情星系生成器：把 [StoryWorldSpecs] 的纯数据规格落地到 Sector。
 *
 * 幂等约定：
 * - 每个实体/市场都使用 [StoryWorldIds] 中的稳定 ID；
 * - 落地前以 `sector.getEntityById` / `sector.economy.getMarket` 做 canonical 去重，
 *   已存在即跳过，读档恢复与重入不会重复创建；
 * - 已存在的市场只做不可缺的注册修复（实体↔市场绑定、经济注册），
 *   不覆写规模/阵营/市场形态等可能被玩家改动（殖民、改造）的属性；
 * - 生成完成后由 [StoryWorldGenState] 记录标志位。
 */
object StoryWorldGenerator {

    private val log: Logger = Global.getLogger(StoryWorldGenerator::class.java)

    /** 生成/修复剧情主星系，返回星系实例。 */
    fun ensureMainSystem(sector: SectorAPI, state: StoryWorldGenState): StarSystemAPI {
        val spec = StoryWorldSpecs.mainSystem(seededRandom(sector, StoryWorldIds.SYSTEM_MAIN))
        val system = ensureSystem(sector, spec) { random ->
            val occupied = occupiedLocations(sector)
            StoryWorldLocations.pickMainSystemLocation(random, occupied)
        }
        state.mainSystemGenerated = true
        return system
    }

    /** 生成/修复第二章遗址双星系（星坠 + 紫菀）。 */
    fun ensureChapterTwoSystems(sector: SectorAPI, state: StoryWorldGenState) {
        val main = sector.getEntityById(StoryWorldIds.MAIN_STAR)?.starSystem
            ?: error("剧情主星系尚未生成，无法计算遗址星系落位")
        val mainLoc = StoryWorldLocations.WorldPoint(main.location.x, main.location.y)

        val starfallSpec = StoryWorldSpecs.starfallSystem(seededRandom(sector, StoryWorldIds.SYSTEM_STARFALL))
        val starfall = ensureSystem(sector, starfallSpec) { random ->
            val occupied = occupiedLocations(sector)
            StoryWorldLocations.pickRuinSystemLocation(random, mainLoc, 0, occupied)
        }
        state.starfallSystemGenerated = true

        val asterSpec = StoryWorldSpecs.asterSystem(seededRandom(sector, StoryWorldIds.SYSTEM_ASTER))
        ensureSystem(sector, asterSpec) { random ->
            val occupied = occupiedLocations(sector) + StoryWorldLocations.WorldPoint(
                starfall.location.x, starfall.location.y,
            )
            StoryWorldLocations.pickRuinSystemLocation(random, mainLoc, 1, occupied)
        }
        state.asterSystemGenerated = true
    }

    /** IndEvo 是否启用（mod manager 检测，不触碰 IndEvo 类）。 */
    fun isIndEvoEnabled(): Boolean =
        Global.getSettings().modManager.isModEnabled(StoryWorldIds.INDEVO_MOD_ID)

    // ------------------------------------------------------------------
    // 星系落地
    // ------------------------------------------------------------------

    private fun ensureSystem(
        sector: SectorAPI,
        spec: StoryWorldSpecs.SystemSpec,
        locationPicker: (Random) -> StoryWorldLocations.WorldPoint,
    ): StarSystemAPI {
        val existingStar = sector.getEntityById(spec.star.id)
        val system = existingStar?.starSystem ?: run {
            val created = sector.createStarSystem(I18n[I18n.Categories.MOD, spec.nameKey])
            val loc = locationPicker(seededRandom(sector, spec.id + "_loc"))
            created.location.set(loc.x, loc.y)
            created.setBackgroundTextureFilename(spec.backgroundTexture)
            created.addTag(Tags.THEME_SPECIAL)
            created.addTag(StoryWorldIds.TAG_STORY_ENTITY)
            log.info("[StoryWorldGenerator] 创建剧情星系 ${spec.id} @ (${loc.x.toInt()}, ${loc.y.toInt()})")
            created
        }

        val star = ensureStar(system, spec)
        spec.belts.forEach { ensureBelt(system, star, it) }
        spec.planets.forEach { ensurePlanet(sector, system, star, it) }
        spec.stations.forEach { ensureStation(sector, system, star, it) }
        spec.objectives.forEach { ensureObjective(system, star, it) }

        system.generateAnchorIfNeeded()
        if (system.jumpPoints.isEmpty()) {
            system.autogenerateHyperspaceJumpPoints(true, true)
        }
        return system
    }

    private fun ensureStar(system: StarSystemAPI, spec: StoryWorldSpecs.SystemSpec): PlanetAPI {
        val existing = system.allEntities.firstOrNull { it.id == spec.star.id } as? PlanetAPI
        if (existing != null) return existing

        val star = system.initStar(
            spec.star.id,
            spec.star.typeId,
            spec.star.radius,
            spec.star.coronaRadius,
        )
        star.name = system.name
        star.addTag(StoryWorldIds.TAG_STORY_ENTITY)
        if (spec.star.blackHole) {
            setupBlackHole(system, star)
        }
        return star
    }

    /** 黑洞：以事件视界地形替换日冕（复刻原版 StarSystemGenerator 的公开 API 路径）。 */
    private fun setupBlackHole(system: StarSystemAPI, star: PlanetAPI) {
        Misc.getCoronaFor(star)?.let { system.removeEntity(it.entity) }
        if (system.getEntitiesWithTag(Terrain.EVENT_HORIZON).isNotEmpty()) return

        val starData = Global.getSettings().getSpec(StarGenDataSpec::class.java, StarTypes.BLACK_HOLE, false)
            as StarGenDataSpec
        var corona = star.radius * starData.coronaMult
        if (corona < starData.coronaMin) corona = starData.coronaMin

        val eventHorizon = system.addTerrain(
            Terrain.EVENT_HORIZON,
            StarCoronaTerrainPlugin.CoronaParams(
                star.radius + corona,
                (star.radius + corona) / 2f,
                star,
                starData.solarWind,
                (starData.minFlare + starData.maxFlare) / 2f,
                starData.crLossMult,
            ),
        )
        eventHorizon.setCircularOrbit(star, 0f, 0f, 100f)
    }

    private fun ensureBelt(system: StarSystemAPI, star: PlanetAPI, spec: StoryWorldSpecs.BeltSpec) {
        if (system.getEntityById(spec.id) != null) return
        val belt = system.addAsteroidBelt(
            star,
            spec.asteroidCount,
            spec.orbitRadius,
            spec.width,
            spec.minOrbitDays,
            spec.maxOrbitDays,
        )
        belt.id = spec.id
        belt.name = I18n[I18n.Categories.MOD, spec.nameKey]
        belt.addTag(StoryWorldIds.TAG_STORY_ENTITY)
    }

    private fun ensurePlanet(
        sector: SectorAPI,
        system: StarSystemAPI,
        star: PlanetAPI,
        spec: StoryWorldSpecs.PlanetSpec,
    ) {
        val planet = (system.getEntityById(spec.id) as? PlanetAPI) ?: run {
            val created = system.addPlanet(
                spec.id,
                star,
                I18n[I18n.Categories.MOD, spec.nameKey],
                spec.typeId,
                spec.orbit.angleDeg,
                spec.planetRadius,
                spec.orbit.radiusSu,
                spec.orbit.days,
            )
            created.addTag(StoryWorldIds.TAG_STORY_ENTITY)
            created.addTag(Tags.NOT_RANDOM_MISSION_TARGET)
            created
        }
        ensureMarket(
            sector, planet, spec.id, I18n[I18n.Categories.MOD, spec.nameKey],
            spec.marketSize, spec.factionId, spec.marketKind, spec.conditionIds, spec.customConditionIds, null,
        )
    }

    private fun ensureStation(
        sector: SectorAPI,
        system: StarSystemAPI,
        star: PlanetAPI,
        spec: StoryWorldSpecs.StationSpec,
    ) {
        val name = I18n[I18n.Categories.MOD, spec.nameKey]
        val station = system.getEntityById(spec.id) ?: run {
            val created = system.addCustomEntity(spec.id, name, spec.entityTypeId, spec.factionId)
            created.setCircularOrbit(star, spec.orbit.angleDeg, spec.orbit.radiusSu, spec.orbit.days)
            created.addTag(StoryWorldIds.TAG_STORY_ENTITY)
            created.addTag(Tags.STORY_CRITICAL)
            created.addTag(Tags.NOT_RANDOM_MISSION_TARGET)
            spec.extraTags.forEach { created.addTag(it) }
            created.memoryWithoutUpdate[StoryWorldIds.MEM_STORY_ROLE] = spec.role
            created
        }
        ensureMarket(
            sector, station, spec.id, name,
            spec.marketSize, spec.factionId, spec.marketKind, spec.conditionIds, spec.customConditionIds, spec.role,
        )
    }

    /**
     * 稳定点目标实体：直接占用稳定点绕主星运行（轨道 focus 为主星，非交互实体）。
     * 不再额外生成 stable_location 锚点——目标实体本身即占用该稳定点，
     * 避免“锚点可再建 + 目标已存在”的双倍稳定点问题。
     */
    private fun ensureObjective(system: StarSystemAPI, star: PlanetAPI, spec: StoryWorldSpecs.ObjectiveSpec) {
        if (system.getEntityById(spec.id) != null) return
        val objective = system.addCustomEntity(spec.id, null, spec.entityTypeId, spec.factionId)
        objective.setCircularOrbit(star, spec.orbit.angleDeg, spec.orbit.radiusSu, spec.orbit.days)
        objective.addTag(StoryWorldIds.TAG_STORY_ENTITY)
    }

    // ------------------------------------------------------------------
    // 市场
    // ------------------------------------------------------------------

    /** 市场处理决策（纯逻辑，供单测覆盖读档保护规则）。 */
    enum class MarketAction {
        /** 市场不存在：按规格完整创建。 */
        CREATE,

        /** 我们生成的市场已存在：仅补注册（实体绑定/经济注册），不覆写任何玩家可见属性。 */
        REPAIR_REGISTRATION,

        /** 实体已被其他市场占用（玩家殖民/其他模组接管）：完全跳过，不接管、不覆写。 */
        SKIP_OCCUPIED,

        /** 规格不需要市场。 */
        NONE,
    }

    internal fun decideMarketAction(
        kind: StoryWorldSpecs.MarketKind,
        boundMarketId: String?,
        ourMarketId: String,
        ourMarketInEconomy: Boolean,
    ): MarketAction {
        if (kind == StoryWorldSpecs.MarketKind.NONE) return MarketAction.NONE
        if (boundMarketId != null && boundMarketId != ourMarketId) return MarketAction.SKIP_OCCUPIED
        return if (boundMarketId == ourMarketId || ourMarketInEconomy) {
            MarketAction.REPAIR_REGISTRATION
        } else {
            MarketAction.CREATE
        }
    }

    private fun ensureMarket(
        sector: SectorAPI,
        entity: SectorEntityToken,
        entityId: String,
        name: String,
        size: Int,
        factionId: String,
        kind: StoryWorldSpecs.MarketKind,
        conditionIds: List<String>,
        customConditionIds: List<String>,
        role: String?,
    ) {
        val marketId = StoryWorldIds.marketIdFor(entityId)
        val economyMarket = sector.economy.getMarket(marketId)
        val action = decideMarketAction(kind, entity.market?.id, marketId, economyMarket != null)

        when (action) {
            MarketAction.NONE -> Unit

            MarketAction.SKIP_OCCUPIED -> log.info(
                "[StoryWorldGenerator] 实体 $entityId 已绑定其他市场 ${entity.market?.id}（玩家殖民或外部接管），跳过市场处理。",
            )

            MarketAction.REPAIR_REGISTRATION -> {
                val market = economyMarket ?: entity.market ?: return
                // 仅补不可缺的注册：实体↔市场双向绑定 + FULL 市场的经济注册。
                if (market.primaryEntity !== entity) market.primaryEntity = entity
                if (entity.market !== market) entity.market = market
                if (kind == StoryWorldSpecs.MarketKind.FULL && sector.economy.getMarket(market.id) == null) {
                    sector.economy.addMarket(market, true)
                }
            }

            MarketAction.CREATE -> createMarket(
                sector, entity, marketId, name, size, factionId, kind, conditionIds, customConditionIds, role,
            )
        }
    }

    /** 按规格完整创建市场（仅在市场不存在时调用）。 */
    private fun createMarket(
        sector: SectorAPI,
        entity: SectorEntityToken,
        marketId: String,
        name: String,
        size: Int,
        factionId: String,
        kind: StoryWorldSpecs.MarketKind,
        conditionIds: List<String>,
        customConditionIds: List<String>,
        role: String?,
    ) {
        val market = Global.getFactory().createMarket(marketId, name, size)
        market.factionId = factionId
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL)
        market.primaryEntity = entity
        market.memoryWithoutUpdate["\$core_noDeciv"] = true
        if (role != null) {
            market.memoryWithoutUpdate[StoryWorldIds.MEM_STORY_ROLE] = role
        }
        entity.setFaction(factionId)
        entity.market = market

        when (kind) {
            StoryWorldSpecs.MarketKind.FULL -> {
                market.setPlanetConditionMarketOnly(false)
                market.setHidden(false)
                val populationCondition = "population_$size"
                if (!market.hasCondition(populationCondition)) {
                    market.addCondition(populationCondition)
                }
                if (!market.hasIndustry(Industries.POPULATION)) {
                    market.addIndustry(Industries.POPULATION)
                }
                if (!market.hasIndustry(Industries.SPACEPORT)) {
                    market.addIndustry(Industries.SPACEPORT)
                }
                market.setHasSpaceport(true)
                market.setEconGroup(marketId)
                market.addSubmarket(Submarkets.SUBMARKET_OPEN)
                sector.economy.addMarket(market, true)
            }

            StoryWorldSpecs.MarketKind.CONDITION_ONLY -> {
                market.setPlanetConditionMarketOnly(true)
                market.setHidden(true)
            }

            else -> Unit
        }

        for (conditionId in conditionIds + customConditionIds) {
            if (!market.hasCondition(conditionId)) {
                // 自定义状况必须由 condition 内容任务注册；未注册时此处直接报错（不静默跳过）。
                market.addCondition(conditionId)
            }
        }
        for (condition in market.conditions) {
            condition.setSurveyed(true)
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun seededRandom(sector: SectorAPI, salt: String): Random {
        val seedString = sector.seedString ?: "asteria_directorate"
        return Random((seedString + ":" + salt).hashCode().toLong())
    }

    private fun occupiedLocations(sector: SectorAPI): List<StoryWorldLocations.WorldPoint> =
        sector.starSystems.map { StoryWorldLocations.WorldPoint(it.location.x, it.location.y) }
}
