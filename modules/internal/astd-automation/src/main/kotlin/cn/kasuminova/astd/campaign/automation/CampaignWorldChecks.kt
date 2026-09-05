package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.world.StoryConditionAdminRuins
import cn.kasuminova.astd.campaign.world.StoryConditionAsterResearchRuins
import cn.kasuminova.astd.campaign.world.StoryConditionEventHorizonPower
import cn.kasuminova.astd.campaign.world.StoryConditionStarfallEngRuins
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap
import cn.kasuminova.astd.campaign.world.StoryWorldGenState
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.campaign.world.StoryWorldLocations
import cn.kasuminova.astd.campaign.world.StoryWorldSpecs
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.LocationAPI
import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Industries
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.campaign.ids.Terrain
import com.fs.starfarer.api.util.Misc
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 生涯世界真实集成验收（场景 `campaign_world_indevo` 的世界部分）。
 *
 * 驱动契约：由外部 CampaignRun 按帧调用 [advance]；返回 `false` 表示仍在等待
 * （传送重试 / IndEvo 炮台脚本跨帧生成），返回 `true` 表示全部验收完成。
 * 任何断言失败经 [CampaignRun.check] 抛出，由驱动层记录失败终态。
 *
 * 安全前提（父驱动已保证，本类不再重复验证）：运行在专用复制存档上且 devMode 开启，
 * 允许传送玩家舰队与触发生成。
 *
 * 验收流程：
 * 1. 基准新档核对：主星系已生成、第二章未解锁、遗址双星系缺席；
 * 2. 场景 fixture：标记第一章结清（真实剧情门槛状态），随后调用真实入口
 *    [StoryWorldBootstrap.notifyChapterTwoUnlocked] 触发遗址星系生成（不伪造门槛）；
 * 3. 规格核对：以与生产一致的种子公式重建 [StoryWorldSpecs] 三个星系规格
 *    （含全部随机星球条目），逐实体核对存在性/类型/轨道/标签/角色/阵营/市场绑定；
 * 4. 真实传送：玩家舰队逐个实体移入三个星系（非仅查存在），核对当前位置与实体绑定，
 *    并在场等待 IndEvo 炮台脚本真实生成炮台（炮台脚本仅在玩家位于同位置时 advance）；
 * 5. IndEvo 联动核对：真实标签/插件/阵营/railgun 类型全部对照游戏目录实际安装的
 *    IndEvo jar 核实（见 [IndEvoRuntimeProbe] 文档）；
 * 6. 难度缩放核对：对剧情市场 reapplyConditions 后读取实际 stat 修正值，
 *    与 [DifficultyTuningImpl] 按当前 k_s 计算的期望值逐项比对；
 * 7. 幂等核对：再次调用真实重入入口，前后实体/市场/炮台/观锚站数量一致、ID 无重复。
 *
 * 星系解析约定：剧情星系一律经主星实体 ID（[storySystem]）解析；vanilla
 * `CampaignEngine.getStarSystem(String)` 按 `optionalUniqueId` 或本地化显示名小写匹配，
 * 生产生成器不设 `optionalUniqueId`，稳定 ID 查询必然落空。
 */
class CampaignWorldChecks(private val run: CampaignRun) : CampaignCheck {

    companion object {
        /** 本验收必须全部标真的证据键（驱动层可据此校验完整性）。 */
        val REQUIRED_EVIDENCE: Set<String> = linkedSetOf(
            "mainSystem", "starfallSystem", "asterSystem", "markets", "conditions",
            "indEvoLoaded", "mainArtillery", "starfallArtillery", "watchtowers",
            "idempotent", "transfers",
        )

        /** 第一章（章节编号 1）结清标记，与 StoryWorldBootstrap 的章节门槛一致。 */
        private const val CHAPTER_ONE_INDEX = 1

        /** 等待 IndEvo 炮台脚本生成炮台实体的帧预算（约 15s @60fps）。 */
        private const val ARTILLERY_WAIT_FRAMES = 900

        /** 传送重试帧预算（玩家舰队尚未就绪时逐帧重试）。 */
        private const val TELEPORT_RETRY_FRAMES = 120

        /** stat 数值比对容差。 */
        private const val STAT_EPSILON = 1e-3f

        /** 轨道半径/星球半径比对容差（su）。 */
        private const val ORBIT_EPSILON = 1f

        /**
         * 复刻 [StoryWorldGenerator] 私有种子公式（`seedString:salt` 的 hashCode），
         * 用于重建与生产完全一致的随机规格。生产公式若变更，本验收会立即失败——此为预期行为。
         */
        internal fun replicateSeededRandom(seedString: String?, salt: String): Random =
            Random(((seedString ?: "asteria_directorate") + ":" + salt).hashCode().toLong())

        /** 浮点比对助手（stat/轨道数值容差判定）。 */
        internal fun approxEquals(actual: Float, expected: Float, epsilon: Float): Boolean =
            kotlin.math.abs(actual - expected) <= epsilon
    }

    private enum class Phase {
        BASELINE,
        UNLOCK_CHAPTER_TWO,
        VERIFY_SPECS,
        TRANSFER_MAIN,
        TRANSFER_STARFALL,
        TRANSFER_ASTER,
        VERIFY_SCALING,
        VERIFY_IDEMPOTENT,
        DONE,
    }

    private var phase = Phase.BASELINE

    /** 当前阶段已消耗的帧数（进入阶段时清零）。 */
    private var phaseFrames = 0

    /** 传送是否已在当前阶段成功（成功后转入等待/核对子状态）。 */
    private var transferDone = false

    /** 已成功完成真实传送并核对位置的星系 ID。 */
    private val transferredSystems = mutableSetOf<String>()

    override fun advance(amount: Float): Boolean {
        if (phase == Phase.DONE) return true
        val sector = Global.getSector()
            ?: throw IllegalStateException("[CampaignWorldChecks] sector unavailable")
        when (phase) {
            Phase.BASELINE -> phaseBaseline(sector)
            Phase.UNLOCK_CHAPTER_TWO -> phaseUnlockChapterTwo(sector)
            Phase.VERIFY_SPECS -> phaseVerifySpecs(sector)
            Phase.TRANSFER_MAIN -> phaseTransferArtillerySystem(
                sector,
                systemId = StoryWorldIds.SYSTEM_MAIN,
                artilleryPlanets = listOf(StoryWorldIds.MAIN_PLANET_HONGLU, StoryWorldIds.MAIN_PLANET_CUICHI),
                artilleryEvidenceKey = "mainArtillery",
                watchtowerIds = listOf(
                    StoryWorldIds.MAIN_INDEVO_STABLE_1, StoryWorldIds.MAIN_INDEVO_STABLE_2,
                    StoryWorldIds.MAIN_INDEVO_STABLE_3, StoryWorldIds.MAIN_INDEVO_STABLE_4,
                ),
                expectedSettledFactionId = Factions.NEUTRAL,
                teleportAngleDeg = 300f,
                teleportRadiusSu = 3500f,
                nextPhase = Phase.TRANSFER_STARFALL,
            )
            Phase.TRANSFER_STARFALL -> phaseTransferArtillerySystem(
                sector,
                systemId = StoryWorldIds.SYSTEM_STARFALL,
                artilleryPlanets = listOf(StoryWorldIds.STARFALL_PLANET_DUANYUAN),
                artilleryEvidenceKey = "starfallArtillery",
                watchtowerIds = listOf(
                    StoryWorldIds.STARFALL_INDEVO_STABLE_1, StoryWorldIds.STARFALL_INDEVO_STABLE_2,
                    StoryWorldIds.STARFALL_INDEVO_STABLE_3, StoryWorldIds.STARFALL_INDEVO_STABLE_4,
                ),
                // IndEvo 4.1.b ArtilleryStationScript.updateFaction 会从星系经济市场重推导
                // 炮台/观锚站阵营：星坠的经济市场均为原版 derelict 遗址站，锻原市场为
                // IndEvo_derelict（condition-only 不进经济），故落地值为两者之一。
                expectedSettledFactionId = null,
                teleportAngleDeg = 340f,
                teleportRadiusSu = 8000f,
                nextPhase = Phase.TRANSFER_ASTER,
            )
            Phase.TRANSFER_ASTER -> phaseTransferAster(sector)
            Phase.VERIFY_SCALING -> phaseVerifyScaling(sector)
            Phase.VERIFY_IDEMPOTENT -> phaseVerifyIdempotent(sector)
            Phase.DONE -> return true
        }
        phaseFrames++
        return phase == Phase.DONE
    }

    // ------------------------------------------------------------------
    // 阶段一：基准新档核对
    // ------------------------------------------------------------------

    private fun phaseBaseline(sector: SectorAPI) {
        run.stage("baseline_new_save")

        // IndEvo 前置门槛：本场景要求 IndEvo 真实安装并启用；未启用则整体失败，
        // 之后的 IndEvo 探针也只会在此门槛通过后才触达（JVM 懒加载隔离）。
        run.check(
            "indEvoLoaded",
            StoryWorldGenerator.isIndEvoEnabled(),
            "scenario requires IndEvo enabled (mod id '${StoryWorldIds.INDEVO_MOD_ID}')",
        )
        run.evidence["indEvoLoaded"] = true

        val state = StoryWorldGenState.getOrCreate()
        if (!state.mainSystemGenerated) {
            // 主星系缺失时走真实新档入口补齐（幂等），不绕过生命周期直接调生成器。
            StoryWorldBootstrap.onNewGameAfterEconomyLoad()
        }
        run.check(
            "baselineMainSystem",
            storySystem(sector, StoryWorldIds.MAIN_STAR) != null &&
                StoryWorldGenState.getOrCreate().mainSystemGenerated,
            "baseline new save must already contain the main story system",
        )
        val bounty = BountyState.getOrCreate()
        run.check(
            "baselineChapterTwoLocked",
            !StoryWorldGenState.getOrCreate().chapterTwoUnlocked &&
                CHAPTER_ONE_INDEX !in bounty.completedChapters,
            "baseline new save must not have chapter one completed / chapter two unlocked",
        )
        run.check(
            "baselineRuinSystemsAbsent",
            storySystem(sector, StoryWorldIds.STARFALL_STAR) == null &&
                storySystem(sector, StoryWorldIds.ASTER_STAR) == null,
            "ruin systems must not exist before chapter two unlock",
        )
        enterPhase(Phase.UNLOCK_CHAPTER_TWO)
    }

    // ------------------------------------------------------------------
    // 阶段二：第一章结清 fixture + 真实解锁入口
    // ------------------------------------------------------------------

    private fun phaseUnlockChapterTwo(sector: SectorAPI) {
        run.stage("fixture_chapter_one_complete")

        // 场景 fixture：写入真实的第一章结清状态（剧情门槛的事实来源），
        // 随后调用章节系统真实通知入口生成遗址星系——不伪造剧情门槛本身。
        BountyState.getOrCreate().completedChapters.add(CHAPTER_ONE_INDEX)
        StoryWorldBootstrap.notifyChapterTwoUnlocked()

        val state = StoryWorldGenState.getOrCreate()
        run.check("chapterTwoUnlocked", state.chapterTwoUnlocked, "notifyChapterTwoUnlocked must set the unlock flag")
        run.check(
            "ruinSystemsGenerated",
            state.starfallSystemGenerated && state.asterSystemGenerated,
            "notifyChapterTwoUnlocked must generate both ruin systems",
        )
        run.check(
            "indEvoExtrasApplied",
            state.indEvoMainExtrasApplied && state.indEvoStarfallExtrasApplied,
            "IndEvo extras must be applied for main and starfall systems",
        )
        enterPhase(Phase.VERIFY_SPECS)
    }

    // ------------------------------------------------------------------
    // 阶段三：全规格核对（固定 + 随机条目）
    // ------------------------------------------------------------------

    private fun phaseVerifySpecs(sector: SectorAPI) {
        run.stage("verify_world_specs")

        val mainSpec = StoryWorldSpecs.mainSystem(replicateSeededRandom(sector.seedString, StoryWorldIds.SYSTEM_MAIN))
        val starfallSpec = StoryWorldSpecs.starfallSystem(replicateSeededRandom(sector.seedString, StoryWorldIds.SYSTEM_STARFALL))
        val asterSpec = StoryWorldSpecs.asterSystem(replicateSeededRandom(sector.seedString, StoryWorldIds.SYSTEM_ASTER))

        verifySystemSpec(sector, mainSpec, expectedFactionOverrides = emptyMap())
        run.evidence["mainSystem"] = true

        // 锻原（星坠）实体/市场阵营在 IndEvo 扩展中被切换为 IndEvo_derelict（生产行为）。
        verifySystemSpec(
            sector, starfallSpec,
            expectedFactionOverrides = mapOf(
                StoryWorldIds.STARFALL_PLANET_DUANYUAN to IndEvoRuntimeProbe.derelictFactionId,
            ),
        )
        run.evidence["starfallSystem"] = true

        verifySystemSpec(sector, asterSpec, expectedFactionOverrides = emptyMap())
        run.evidence["asterSystem"] = true

        // 超空间落位约束：主星系环带 + 遗址星系相对主星系的距离/角距/边缘约束。
        val mainLoc = systemPoint(storySystem(sector, StoryWorldIds.MAIN_STAR)!!)
        val starfallLoc = systemPoint(storySystem(sector, StoryWorldIds.STARFALL_STAR)!!)
        val asterLoc = systemPoint(storySystem(sector, StoryWorldIds.ASTER_STAR)!!)
        run.check(
            "mainSystemLocation",
            mainLoc.length() in StoryWorldLocations.MAIN_MIN_RADIUS..StoryWorldLocations.MAIN_MAX_RADIUS,
            "main system must sit in the ${StoryWorldLocations.MAIN_MIN_RADIUS}~" +
                "${StoryWorldLocations.MAIN_MAX_RADIUS}su ring, actual=${mainLoc.length()}",
        )
        run.check(
            "ruinSystemLocations",
            StoryWorldLocations.ruinPlacementValid(mainLoc, starfallLoc, asterLoc),
            "ruin systems must satisfy distance/angular constraints vs main system",
        )
        run.check(
            "ruinSystemsFringe",
            starfallLoc.length() >= StoryWorldLocations.FRINGE_MIN_RADIUS &&
                asterLoc.length() >= StoryWorldLocations.FRINGE_MIN_RADIUS,
            "ruin systems must sit beyond ${StoryWorldLocations.FRINGE_MIN_RADIUS}su from the core",
        )
        run.detail("mainSystemPos", "${mainLoc.x.toInt()},${mainLoc.y.toInt()}")
        run.detail("starfallSystemPos", "${starfallLoc.x.toInt()},${starfallLoc.y.toInt()}")
        run.detail("asterSystemPos", "${asterLoc.x.toInt()},${asterLoc.y.toInt()}")

        // 三个星系内部实体 ID 不得重复；canonical 查询必须指回本星系实体。
        val systems = listOf(
            storySystem(sector, StoryWorldIds.MAIN_STAR)!!,
            storySystem(sector, StoryWorldIds.STARFALL_STAR)!!,
            storySystem(sector, StoryWorldIds.ASTER_STAR)!!,
        )
        val allIds = systems.flatMap { system -> system.allEntities.map { it.id } }
        run.check(
            "uniqueEntityIds",
            allIds.size == allIds.toSet().size,
            "duplicate entity ids across story systems: ${allIds.groupingBy { it }.eachCount().filter { it.value > 1 }}",
        )
        for (spec in listOf(mainSpec, starfallSpec, asterSpec)) {
            val specSystem = storySystem(sector, spec.star.id)
            for (entityId in spec.allEntityIds()) {
                val canonical = sector.getEntityById(entityId)
                run.check(
                    "canonicalEntity:$entityId",
                    canonical != null && canonical.containingLocation === specSystem,
                    "sector.getEntityById($entityId) must resolve into ${spec.id}",
                )
            }
        }

        run.evidence["markets"] = true
        enterPhase(Phase.TRANSFER_MAIN)
    }

    private fun verifySystemSpec(
        sector: SectorAPI,
        spec: StoryWorldSpecs.SystemSpec,
        expectedFactionOverrides: Map<String, String>,
    ) {
        val system = storySystem(sector, spec.star.id)
        run.check("systemExists:${spec.id}", system != null, "star system ${spec.id} missing")
        system!!
        run.check(
            "systemTags:${spec.id}",
            system.hasTag(Tags.THEME_SPECIAL) && system.hasTag(StoryWorldIds.TAG_STORY_ENTITY),
            "story system must carry theme_special + story entity tags",
        )

        val star = system.getEntityById(spec.star.id) as? PlanetAPI
        run.check("star:${spec.star.id}", star != null && star.isStar, "star ${spec.star.id} missing")
        star!!
        run.check(
            "starType:${spec.star.id}",
            star.typeId == spec.star.typeId,
            "star type ${star.typeId} != ${spec.star.typeId}",
        )
        run.check(
            "starRadius:${spec.star.id}",
            approxEquals(star.radius, spec.star.radius, ORBIT_EPSILON),
            "star radius ${star.radius} != ${spec.star.radius}",
        )
        if (spec.star.blackHole) {
            run.check(
                "blackHoleEventHorizon:${spec.id}",
                system.getEntitiesWithTag(Terrain.EVENT_HORIZON).isNotEmpty() && Misc.getCoronaFor(star) == null,
                "black hole system must have event horizon terrain and no corona",
            )
        }

        spec.planets.forEach { verifyPlanet(sector, system, star, it, expectedFactionOverrides) }
        spec.stations.forEach { verifyStation(sector, system, star, it, expectedFactionOverrides) }
        spec.objectives.forEach { verifyObjective(system, star, it) }
        spec.belts.forEach { belt ->
            run.check(
                "belt:${belt.id}",
                system.getEntityById(belt.id) != null,
                "asteroid belt ${belt.id} missing in ${spec.id}",
            )
        }
    }

    private fun verifyPlanet(
        sector: SectorAPI,
        system: StarSystemAPI,
        star: PlanetAPI,
        spec: StoryWorldSpecs.PlanetSpec,
        factionOverrides: Map<String, String>,
    ) {
        val planet = system.getEntityById(spec.id) as? PlanetAPI
        run.check("planet:${spec.id}", planet != null, "planet ${spec.id} missing")
        planet!!
        run.check(
            "planetType:${spec.id}",
            planet.typeId == spec.typeId,
            "planet type ${planet.typeId} != ${spec.typeId}",
        )
        run.check(
            "planetRadius:${spec.id}",
            approxEquals(planet.radius, spec.planetRadius, ORBIT_EPSILON),
            "planet radius ${planet.radius} != ${spec.planetRadius}",
        )
        verifyOrbit(spec.id, planet, star, spec.orbit)
        run.check(
            "planetTags:${spec.id}",
            planet.hasTag(StoryWorldIds.TAG_STORY_ENTITY) && planet.hasTag(Tags.NOT_RANDOM_MISSION_TARGET),
            "story planet must carry story + not_random_mission_target tags",
        )
        verifyMarketBinding(
            sector, planet, spec.id, spec.marketKind, spec.marketSize,
            factionOverrides[spec.id] ?: spec.factionId,
            spec.conditionIds, spec.customConditionIds, role = null,
        )
    }

    private fun verifyStation(
        sector: SectorAPI,
        system: StarSystemAPI,
        star: PlanetAPI,
        spec: StoryWorldSpecs.StationSpec,
        factionOverrides: Map<String, String>,
    ) {
        val station = system.getEntityById(spec.id)
        run.check("station:${spec.id}", station != null, "station ${spec.id} missing")
        station!!
        run.check(
            "stationType:${spec.id}",
            station.customEntityType == spec.entityTypeId,
            "station entity type ${station.customEntityType} != ${spec.entityTypeId}",
        )
        verifyOrbit(spec.id, station, star, spec.orbit)
        run.check(
            "stationTags:${spec.id}",
            station.hasTag(StoryWorldIds.TAG_STORY_ENTITY) &&
                station.hasTag(Tags.STORY_CRITICAL) &&
                station.hasTag(Tags.NOT_RANDOM_MISSION_TARGET) &&
                spec.extraTags.all { station.hasTag(it) },
            "station must carry story tags plus ${spec.extraTags}",
        )
        run.check(
            "stationRole:${spec.id}",
            station.memoryWithoutUpdate.getString(StoryWorldIds.MEM_STORY_ROLE) == spec.role,
            "station role memory must be ${spec.role}",
        )
        verifyMarketBinding(
            sector, station, spec.id, spec.marketKind, spec.marketSize,
            factionOverrides[spec.id] ?: spec.factionId,
            spec.conditionIds, spec.customConditionIds, spec.role,
        )
    }

    private fun verifyObjective(system: StarSystemAPI, star: PlanetAPI, spec: StoryWorldSpecs.ObjectiveSpec) {
        val objective = system.getEntityById(spec.id)
        run.check("objective:${spec.id}", objective != null, "objective ${spec.id} missing")
        objective!!
        run.check(
            "objectiveType:${spec.id}",
            objective.customEntityType == spec.entityTypeId,
            "objective entity type ${objective.customEntityType} != ${spec.entityTypeId}",
        )
        verifyOrbit(spec.id, objective, star, spec.orbit)
    }

    /** 轨道核对：聚焦主星、轨道半径与公转周期（角度随时间推进，不在验收口径内）。 */
    private fun verifyOrbit(id: String, entity: SectorEntityToken, star: PlanetAPI, orbit: StoryWorldSpecs.OrbitSpec) {
        run.check("orbitFocus:$id", entity.orbitFocus === star, "$id must orbit the system star")
        run.check(
            "orbitRadius:$id",
            approxEquals(entity.circularOrbitRadius, orbit.radiusSu, ORBIT_EPSILON),
            "$id orbit radius ${entity.circularOrbitRadius} != ${orbit.radiusSu}",
        )
        run.check(
            "orbitPeriod:$id",
            approxEquals(entity.circularOrbitPeriod, orbit.days, ORBIT_EPSILON),
            "$id orbit period ${entity.circularOrbitPeriod} != ${orbit.days}",
        )
    }

    /**
     * 市场核对：FULL 市场必须在经济体中且与实体双向绑定；
     * CONDITION_ONLY 市场不得进入经济体，必须经 `entity.market` 触达（验收硬性要求）。
     */
    private fun verifyMarketBinding(
        sector: SectorAPI,
        entity: SectorEntityToken,
        entityId: String,
        kind: StoryWorldSpecs.MarketKind,
        size: Int,
        factionId: String,
        conditionIds: List<String>,
        customConditionIds: List<String>,
        role: String?,
    ) {
        if (kind == StoryWorldSpecs.MarketKind.NONE) return
        val marketId = StoryWorldIds.marketIdFor(entityId)
        when (kind) {
            StoryWorldSpecs.MarketKind.FULL -> {
                val market = sector.economy.getMarket(marketId)
                run.check("marketInEconomy:$marketId", market != null && market.isInEconomy, "FULL market $marketId must be in economy")
                market!!
                run.check(
                    "marketBinding:$marketId",
                    market.primaryEntity === entity && entity.market === market,
                    "FULL market $marketId must be bound to its entity both ways",
                )
                run.check("marketSize:$marketId", market.size == size, "market size ${market.size} != $size")
                run.check("marketFaction:$marketId", market.factionId == factionId, "market faction ${market.factionId} != $factionId")
                run.check(
                    "marketVisible:$marketId",
                    !market.isHidden && !market.isPlanetConditionMarketOnly,
                    "FULL market must be visible and not condition-only",
                )
                run.check(
                    "marketInfrastructure:$marketId",
                    market.hasCondition("population_$size") &&
                        market.hasIndustry(Industries.POPULATION) &&
                        market.hasIndustry(Industries.SPACEPORT) &&
                        market.hasSubmarket(Submarkets.SUBMARKET_OPEN),
                    "FULL market must have population condition/industry, spaceport and open submarket",
                )
                verifyMarketConditions(market, marketId, conditionIds, customConditionIds)
                if (role != null) {
                    run.check(
                        "marketRole:$marketId",
                        market.memoryWithoutUpdate.getString(StoryWorldIds.MEM_STORY_ROLE) == role,
                        "FULL market role memory must be $role",
                    )
                }
            }

            StoryWorldSpecs.MarketKind.CONDITION_ONLY -> {
                run.check(
                    "marketNotInEconomy:$marketId",
                    sector.economy.getMarket(marketId) == null,
                    "CONDITION_ONLY market $marketId must not enter the economy",
                )
                val market = entity.market
                run.check(
                    "marketViaEntity:$marketId",
                    market != null && market.id == marketId && market.primaryEntity === entity,
                    "CONDITION_ONLY market must be reachable via entity.market with binding both ways",
                )
                market!!
                run.check(
                    "marketConditionOnlyFlags:$marketId",
                    market.isPlanetConditionMarketOnly && market.isHidden,
                    "CONDITION_ONLY market must be planetConditionMarketOnly + hidden",
                )
                run.check("marketFaction:$marketId", market.factionId == factionId, "market faction ${market.factionId} != $factionId")
                verifyMarketConditions(market, marketId, conditionIds, customConditionIds)
                if (role != null) {
                    run.check(
                        "marketRole:$marketId",
                        market.memoryWithoutUpdate.getString(StoryWorldIds.MEM_STORY_ROLE) == role,
                        "CONDITION_ONLY market role memory must be $role",
                    )
                }
            }

            else -> Unit
        }
    }

    private fun verifyMarketConditions(
        market: MarketAPI,
        marketId: String,
        conditionIds: List<String>,
        customConditionIds: List<String>,
    ) {
        for (conditionId in conditionIds + customConditionIds) {
            run.check(
                "marketCondition:$marketId:$conditionId",
                market.hasCondition(conditionId),
                "market $marketId must have condition $conditionId",
            )
        }
    }

    // ------------------------------------------------------------------
    // 阶段四：真实传送 + IndEvo 炮台/观锚站核对（主星系 / 星坠）
    // ------------------------------------------------------------------

    private fun phaseTransferArtillerySystem(
        sector: SectorAPI,
        systemId: String,
        artilleryPlanets: List<String>,
        artilleryEvidenceKey: String,
        watchtowerIds: List<String>,
        expectedSettledFactionId: String?,
        teleportAngleDeg: Float,
        teleportRadiusSu: Float,
        nextPhase: Phase,
    ) {
        val system = storySystem(sector, starIdForSystem(systemId))
            ?: throw IllegalStateException("[CampaignWorldChecks] $systemId missing during transfer phase")

        if (!transferDone) {
            run.stage("transfer_$systemId")
            if (phaseFrames >= TELEPORT_RETRY_FRAMES) {
                run.check("transfer:$systemId", false, "player fleet not ready for teleport after $TELEPORT_RETRY_FRAMES frames")
            }
            if (transferFleetToSystem(sector, system, teleportAngleDeg, teleportRadiusSu)) {
                verifyPlayerLocation(sector, system, systemId)
                transferDone = true
                phaseFrames = 0
                transferredSystems += systemId
            }
            return
        }

        run.stage("indevo_artillery_$systemId")
        val statuses = artilleryPlanets.map { planetId ->
            val planet = system.getEntityById(planetId)
                ?: throw IllegalStateException("[CampaignWorldChecks] $planetId missing in $systemId")
            planetId to IndEvoRuntimeProbe.artilleryStatus(planet)
        }
        val allReady = statuses.all { it.second.ready }
        if (!allReady) {
            if (phaseFrames >= ARTILLERY_WAIT_FRAMES) {
                run.check(
                    "artillerySpawn:$systemId",
                    false,
                    "artillery stations not spawned within $ARTILLERY_WAIT_FRAMES frames: " +
                        statuses.joinToString { (id, status) -> "$id=$status" },
                )
            }
            return
        }

        // 炮台真实核对：标签/脚本/类型锁定/condition/实体/插件/railgun 工业/阵营。
        statuses.forEach { (planetId, status) ->
            val planet = system.getEntityById(planetId)!!
            verifyArtilleryPlanet(sector, system, planet, planetId, status, expectedSettledFactionId)
        }
        run.evidence[artilleryEvidenceKey] = true
        run.detail(
            "${artilleryEvidenceKey}Factions",
            statuses.joinToString { (id, status) -> "$id=${status.stationFactionId}(hostile=${status.stationHostileToPlayer})" },
        )

        // 观锚站真实核对：稳定 ID 实体、IndEvo 实体类型/标签/插件、轨道、阵营。
        verifyWatchtowers(system, watchtowerIds, expectedSettledFactionId)
        run.check(
            "watchtowerCount:$systemId",
            IndEvoRuntimeProbe.watchtowerCount(system) == watchtowerIds.size,
            "system must contain exactly ${watchtowerIds.size} IndEvo watchtowers " +
                "(no auto-placed extras), actual=${IndEvoRuntimeProbe.watchtowerCount(system)}",
        )
        if (systemId == StoryWorldIds.SYSTEM_STARFALL) {
            // 两个星系合计恰好 8 座观锚站（生产设计：主 4 + 星坠 4，无自动补放）。
            val total = listOf(StoryWorldIds.SYSTEM_MAIN, StoryWorldIds.SYSTEM_STARFALL, StoryWorldIds.SYSTEM_ASTER)
                .sumOf { IndEvoRuntimeProbe.watchtowerCount(storySystem(sector, starIdForSystem(it))!!) }
            run.check("watchtowerTotal", total == 8, "total IndEvo watchtowers across story systems must be 8, actual=$total")
            run.evidence["watchtowers"] = true
        }

        enterPhase(nextPhase)
    }

    private fun verifyArtilleryPlanet(
        sector: SectorAPI,
        system: StarSystemAPI,
        planet: SectorEntityToken,
        planetId: String,
        status: IndEvoRuntimeProbe.ArtilleryStatus,
        expectedSettledFactionId: String?,
    ) {
        run.check("artilleryTag:$planetId", status.hasArtilleryTag, "planet must carry ${status.hasArtilleryTagName} tag")
        run.check("artilleryScript:$planetId", status.scriptAttached, "planet must have ArtilleryStationScript attached")
        run.check("artilleryScriptType:$planetId", status.scriptTypeRailgun, "script type must be railgun, actual=${status.scriptType}")
        run.check("artilleryTypeLocked:$planetId", status.typeMemoryRailgun, "market type memory must be locked to railgun")
        run.check("artilleryCondition:$planetId", status.conditionPresent, "planet market must have IndEvo artillery condition")
        run.check("artilleryStationSpawned:$planetId", status.stationSpawned, "orbital artillery station entity must spawn while player present")
        run.check("artilleryStationType:$planetId", status.stationCustomType, "station entity must be IndEvo_ArtilleryStation custom entity")
        run.check("artilleryStationPlugin:$planetId", status.stationPluginRailgun, "station plugin must be ArtilleryStationEntityPlugin with railgun type")
        run.check("artilleryStationIndustry:$planetId", status.stationIndustryRailgun, "station market must have the railgun artillery industry")
        run.check(
            "artillerySystemTag:$planetId",
            status.systemTagged,
            "system must carry IndEvo system-has-artillery tag",
        )

        if (expectedSettledFactionId != null) {
            run.check(
                "artilleryStationFaction:$planetId",
                status.stationFactionId == expectedSettledFactionId,
                "station faction ${status.stationFactionId} != $expectedSettledFactionId",
            )
        } else {
            run.check(
                "artilleryStationFaction:$planetId",
                status.stationFactionId in setOf(IndEvoRuntimeProbe.derelictFactionId, Factions.DERELICT),
                "starfall station faction must settle to IndEvo_derelict or vanilla derelict, actual=${status.stationFactionId}",
            )
        }

        // 主星系（中立）炮台不得与玩家敌对；星坠炮台阵营敌对性记录为证据细节
        // （IndEvo 脚本按星系经济市场重推导阵营，锻原行星自身仍为 IndEvo_derelict 兜底）。
        if (system.id == StoryWorldIds.SYSTEM_MAIN) {
            run.check(
                "artilleryStationNonHostile:$planetId",
                !status.stationHostileToPlayer,
                "main system artillery must not be hostile to the player",
            )
        }
        if (planetId == StoryWorldIds.STARFALL_PLANET_DUANYUAN) {
            run.check(
                "duanyuanDerelictFaction",
                planet.faction.id == IndEvoRuntimeProbe.derelictFactionId &&
                    planet.market?.factionId == IndEvoRuntimeProbe.derelictFactionId,
                "duanyuan planet/market must be switched to IndEvo_derelict",
            )
            val derelict = sector.getFaction(IndEvoRuntimeProbe.derelictFactionId)
            run.check(
                "indEvoDerelictHostileToPlayer",
                derelict != null && derelict.isHostileTo(Factions.PLAYER),
                "IndEvo_derelict must be hostile to the player (IndEvo ModPlugin contract)",
            )
        }
    }

    private fun verifyWatchtowers(
        system: StarSystemAPI,
        watchtowerIds: List<String>,
        expectedSettledFactionId: String?,
    ) {
        val star = system.star
        for (id in watchtowerIds) {
            val entity = system.getEntityById(id)
            run.check("watchtower:$id", entity != null, "watchtower $id missing")
            entity!!
            run.check(
                "watchtowerType:$id",
                IndEvoRuntimeProbe.isRealWatchtower(entity),
                "watchtower $id must be IndEvo_Watchtower custom entity with IndEvo watchtower tag and plugin",
            )
            run.check("watchtowerOrbit:$id", entity.orbitFocus === star, "watchtower $id must orbit the system star")
            run.check(
                "watchtowerRole:$id",
                entity.memoryWithoutUpdate.getString(StoryWorldIds.MEM_STORY_ROLE) == "watchtower",
                "watchtower $id must carry the story role marker",
            )
            if (expectedSettledFactionId != null) {
                run.check(
                    "watchtowerFaction:$id",
                    entity.faction.id == expectedSettledFactionId,
                    "watchtower faction ${entity.faction.id} != $expectedSettledFactionId",
                )
            } else {
                run.check(
                    "watchtowerFaction:$id",
                    entity.faction.id in setOf(IndEvoRuntimeProbe.derelictFactionId, Factions.DERELICT),
                    "starfall watchtower faction must be IndEvo_derelict or vanilla derelict, actual=${entity.faction.id}",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段五：紫菀遗址星系传送核对（无炮台，核对绑定与黑洞地形）
    // ------------------------------------------------------------------

    private fun phaseTransferAster(sector: SectorAPI) {
        val systemId = StoryWorldIds.SYSTEM_ASTER
        val system = storySystem(sector, starIdForSystem(systemId))
            ?: throw IllegalStateException("[CampaignWorldChecks] $systemId missing during transfer phase")

        if (!transferDone) {
            run.stage("transfer_$systemId")
            if (phaseFrames >= TELEPORT_RETRY_FRAMES) {
                run.check("transfer:$systemId", false, "player fleet not ready for teleport after $TELEPORT_RETRY_FRAMES frames")
            }
            if (transferFleetToSystem(sector, system, 45f, 6000f)) {
                verifyPlayerLocation(sector, system, systemId)
                transferDone = true
                transferredSystems += systemId
            }
            return
        }

        run.stage("verify_aster_in_situ")
        // 在场核对引力节点绑定：condition-only 市场经 entity.market 触达、标签与角色齐备。
        val nodeIds = listOf(
            StoryWorldIds.ASTER_GRAVITY_NODE_1,
            StoryWorldIds.ASTER_GRAVITY_NODE_2,
            StoryWorldIds.ASTER_GRAVITY_NODE_3,
        )
        for (nodeId in nodeIds) {
            val node = system.getEntityById(nodeId)
            run.check("gravityNode:$nodeId", node != null && node.hasTag(StoryWorldIds.TAG_GRAVITY_NODE), "gravity node $nodeId missing or untagged")
            node!!
            val market = node.market
            run.check(
                "gravityNodeMarket:$nodeId",
                market != null &&
                    market.id == StoryWorldIds.marketIdFor(nodeId) &&
                    market.primaryEntity === node &&
                    market.isPlanetConditionMarketOnly &&
                    sector.economy.getMarket(market.id) == null,
                "gravity node market must be condition-only and reachable via entity.market",
            )
        }
        run.check(
            "asterEventHorizon",
            system.getEntitiesWithTag(Terrain.EVENT_HORIZON).isNotEmpty(),
            "aster black hole must have event horizon terrain",
        )
        run.check(
            "transfers",
            transferredSystems.size == 3,
            "player must be truly transferred into all three story systems, actual=$transferredSystems",
        )
        run.evidence["transfers"] = true
        enterPhase(Phase.VERIFY_SCALING)
    }

    // ------------------------------------------------------------------
    // 阶段六：难度缩放 stat 核对（真实 stat 读取 vs 当前 k_s 期望值）
    // ------------------------------------------------------------------

    private fun phaseVerifyScaling(sector: SectorAPI) {
        run.stage("verify_difficulty_scaling")
        run.detail("k_s", DifficultyTuningImpl.fixedScale)

        val lantai = sector.getEntityById(StoryWorldIds.MAIN_PLANET_LANTAI)?.market
        val duanyuan = sector.getEntityById(StoryWorldIds.STARFALL_PLANET_DUANYUAN)?.market
        val shiguang = sector.getEntityById(StoryWorldIds.ASTER_STATION_SHIGUANG)?.market
        run.check(
            "scalingMarketsPresent",
            lantai != null && duanyuan != null && shiguang != null,
            "lantai/duanyuan/shiguang markets must be reachable via entity binding",
        )
        lantai!!
        duanyuan!!
        shiguang!!

        // 菀星行政部遗址（兰台）：流通性平值、收入乘区、稳定性平值、舰队规模乘区。
        lantai.reapplyConditions()
        expectFlatStat("adminRuinsAccess", lantai, StoryWorldIds.COND_ADMIN_RUINS, StoryConditionAdminRuins.ACCESS) { market, modId ->
            market.accessibilityMod.getFlatBonus(modId)?.value
        }
        expectMultStat("adminRuinsIncome", lantai, StoryWorldIds.COND_ADMIN_RUINS, StoryConditionAdminRuins.INCOME) { market, modId ->
            market.incomeMult.getMultStatMod(modId)?.value
        }
        expectFlatIntStat("adminRuinsStability", lantai, StoryWorldIds.COND_ADMIN_RUINS, StoryConditionAdminRuins.STABILITY) { market, modId ->
            market.stability.getFlatStatMod(modId)?.value
        }
        expectMultStat("adminRuinsFleetSize", lantai, StoryWorldIds.COND_ADMIN_RUINS, StoryConditionAdminRuins.FLEET_SIZE) { market, modId ->
            market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).getMultBonus(modId)?.value
        }

        // 星坠工程部遗址（锻原）：流通性、舰队规模、地面防御、最大工业设施数量。
        duanyuan.reapplyConditions()
        expectFlatStat("starfallEngAccess", duanyuan, StoryWorldIds.COND_STARFALL_ENG_RUINS, StoryConditionStarfallEngRuins.ACCESS) { market, modId ->
            market.accessibilityMod.getFlatBonus(modId)?.value
        }
        expectMultStat("starfallEngFleetSize", duanyuan, StoryWorldIds.COND_STARFALL_ENG_RUINS, StoryConditionStarfallEngRuins.FLEET_SIZE) { market, modId ->
            market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).getMultBonus(modId)?.value
        }
        expectMultStat("starfallEngGroundDefense", duanyuan, StoryWorldIds.COND_STARFALL_ENG_RUINS, StoryConditionStarfallEngRuins.GROUND_DEFENSE) { market, modId ->
            market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).getMultBonus(modId)?.value
        }
        expectFlatIntStat("starfallEngMaxIndustries", duanyuan, StoryWorldIds.COND_STARFALL_ENG_RUINS, StoryConditionStarfallEngRuins.MAX_INDUSTRIES) { market, modId ->
            market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).getFlatBonus(modId)?.value
        }

        // 视界动力（拾光）：最大工业设施数量、维护费减免、危险度减免。
        shiguang.reapplyConditions()
        expectFlatIntStat("eventHorizonMaxIndustries", shiguang, StoryWorldIds.COND_EVENT_HORIZON_POWER, StoryConditionEventHorizonPower.MAX_INDUSTRIES) { market, modId ->
            market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).getFlatBonus(modId)?.value
        }
        expectReductionMultStat("eventHorizonUpkeep", shiguang, StoryWorldIds.COND_EVENT_HORIZON_POWER, StoryConditionEventHorizonPower.UPKEEP_REDUCTION) { market, modId ->
            market.upkeepMult.getMultStatMod(modId)?.value
        }
        expectReductionMultStat("eventHorizonHazard", shiguang, StoryWorldIds.COND_EVENT_HORIZON_POWER, StoryConditionEventHorizonPower.HAZARD_REDUCTION) { market, modId ->
            market.hazard.getMultStatMod(modId)?.value
        }

        // 紫菀科研部遗址（拾光）：流通性、舰队规模。
        expectFlatStat("asterResearchAccess", shiguang, StoryWorldIds.COND_ASTER_RESEARCH_RUINS, StoryConditionAsterResearchRuins.ACCESS) { market, modId ->
            market.accessibilityMod.getFlatBonus(modId)?.value
        }
        expectMultStat("asterResearchFleetSize", shiguang, StoryWorldIds.COND_ASTER_RESEARCH_RUINS, StoryConditionAsterResearchRuins.FLEET_SIZE) { market, modId ->
            market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).getMultBonus(modId)?.value
        }

        // 状况证据在最严格口径落地：存在性（规格阶段）+ 数值（本阶段）双双验证后才标真。
        run.evidence["conditions"] = true
        enterPhase(Phase.VERIFY_IDEMPOTENT)
    }

    /** 平值 stat 核对：实际值 ≈ 当前 k_s 下的缩放值。 */
    private fun expectFlatStat(
        key: String,
        market: MarketAPI,
        conditionId: String,
        entry: ScalingEntry,
        reader: (MarketAPI, String) -> Float?,
    ) {
        val modId = pluginModId(market, conditionId)
        val expected = DifficultyTuningImpl.value(entry)
        val actual = reader(market, modId)
        run.check(
            key,
            actual != null && approxEquals(actual, expected, STAT_EPSILON),
            "$key: actual=$actual expected=$expected (k_s=${DifficultyTuningImpl.fixedScale})",
        )
        run.detail(key, actual ?: Float.NaN)
    }

    /** 整数语义平值核对：实际值 ≈ roundToInt(缩放值)。 */
    private fun expectFlatIntStat(
        key: String,
        market: MarketAPI,
        conditionId: String,
        entry: ScalingEntry,
        reader: (MarketAPI, String) -> Float?,
    ) {
        val modId = pluginModId(market, conditionId)
        val expected = DifficultyTuningImpl.value(entry).roundToInt().toFloat()
        val actual = reader(market, modId)
        run.check(
            key,
            actual != null && approxEquals(actual, expected, STAT_EPSILON),
            "$key: actual=$actual expected=$expected (k_s=${DifficultyTuningImpl.fixedScale})",
        )
        run.detail(key, actual ?: Float.NaN)
    }

    /** 增益乘区核对：实际乘区 ≈ 1 + 缩放值。 */
    private fun expectMultStat(
        key: String,
        market: MarketAPI,
        conditionId: String,
        entry: ScalingEntry,
        reader: (MarketAPI, String) -> Float?,
    ) {
        expectScaled(key, market, conditionId, 1f + DifficultyTuningImpl.value(entry), reader)
    }

    /** 减免乘区核对：实际乘区 ≈ 1 - 缩放值。 */
    private fun expectReductionMultStat(
        key: String,
        market: MarketAPI,
        conditionId: String,
        entry: ScalingEntry,
        reader: (MarketAPI, String) -> Float?,
    ) {
        expectScaled(key, market, conditionId, 1f - DifficultyTuningImpl.value(entry), reader)
    }

    private fun expectScaled(
        key: String,
        market: MarketAPI,
        conditionId: String,
        expected: Float,
        reader: (MarketAPI, String) -> Float?,
    ) {
        val modId = pluginModId(market, conditionId)
        val actual = reader(market, modId)
        run.check(
            key,
            actual != null && approxEquals(actual, expected, STAT_EPSILON),
            "$key: actual=$actual expected=$expected (k_s=${DifficultyTuningImpl.fixedScale})",
        )
        run.detail(key, actual ?: Float.NaN)
    }

    /** 条件插件的 stat 修改 id（vanilla 口径：condition id + "_" + 唯一后缀）。 */
    private fun pluginModId(market: MarketAPI, conditionId: String): String {
        val condition = market.getCondition(conditionId)
        run.check("conditionPluginModId:$conditionId", condition != null, "market ${market.id} missing condition $conditionId")
        return condition!!.idForPluginModifications
    }

    // ------------------------------------------------------------------
    // 阶段七：幂等重入核对
    // ------------------------------------------------------------------

    private fun phaseVerifyIdempotent(sector: SectorAPI) {
        run.stage("verify_idempotent_regeneration")
        val before = snapshotWorld(sector)

        // 真实重入入口（幂等）：已生成内容必须全部 canonical 去重，不得重复创建。
        StoryWorldBootstrap.notifyChapterTwoUnlocked()

        val after = snapshotWorld(sector)
        run.check("idempotentEntityCounts", before.entityCounts == after.entityCounts, "entity counts changed: ${before.entityCounts} -> ${after.entityCounts}")
        run.check("idempotentStoryEntityCount", before.storyEntityCount == after.storyEntityCount, "story entity count changed: ${before.storyEntityCount} -> ${after.storyEntityCount}")
        run.check("idempotentEconomyMarkets", before.storyEconomyMarkets == after.storyEconomyMarkets, "story economy markets changed: ${before.storyEconomyMarkets} -> ${after.storyEconomyMarkets}")
        run.check("idempotentArtilleries", before.artilleryCounts == after.artilleryCounts, "artillery counts changed: ${before.artilleryCounts} -> ${after.artilleryCounts}")
        run.check("idempotentWatchtowers", before.watchtowerCounts == after.watchtowerCounts, "watchtower counts changed: ${before.watchtowerCounts} -> ${after.watchtowerCounts}")
        run.check(
            "idempotentStateFlags",
            after.mainGenerated && after.starfallGenerated && after.asterGenerated &&
                after.indEvoMainApplied && after.indEvoStarfallApplied && after.chapterTwoUnlocked,
            "generation state flags must remain set after re-entry",
        )

        // 重入后 ID 依旧唯一（无重复实体）。
        val allIds = listOf(
            StoryWorldIds.SYSTEM_MAIN, StoryWorldIds.SYSTEM_STARFALL, StoryWorldIds.SYSTEM_ASTER,
        ).flatMap { storySystem(sector, starIdForSystem(it))!!.allEntities.map { entity -> entity.id } }
        run.check(
            "idempotentUniqueIds",
            allIds.size == allIds.toSet().size,
            "duplicate entity ids after re-entry: ${allIds.groupingBy { it }.eachCount().filter { it.value > 1 }}",
        )
        run.detail("storyEntityCount", after.storyEntityCount)

        run.evidence["idempotent"] = true

        // 收口：要求的证据键必须全部真实标真。
        run.check(
            "evidenceComplete",
            run.evidence.keys.containsAll(REQUIRED_EVIDENCE) &&
                REQUIRED_EVIDENCE.all { run.evidence[it] == true },
            "missing or false evidence: ${REQUIRED_EVIDENCE.filter { run.evidence[it] != true }}",
        )
        enterPhase(Phase.DONE)
    }

    private data class WorldSnapshot(
        val entityCounts: Map<String, Int>,
        val storyEntityCount: Int,
        val storyEconomyMarkets: Int,
        val artilleryCounts: Map<String, Int>,
        val watchtowerCounts: Map<String, Int>,
        val mainGenerated: Boolean,
        val starfallGenerated: Boolean,
        val asterGenerated: Boolean,
        val indEvoMainApplied: Boolean,
        val indEvoStarfallApplied: Boolean,
        val chapterTwoUnlocked: Boolean,
    )

    private fun snapshotWorld(sector: SectorAPI): WorldSnapshot {
        val systemIds = listOf(StoryWorldIds.SYSTEM_MAIN, StoryWorldIds.SYSTEM_STARFALL, StoryWorldIds.SYSTEM_ASTER)
        val state = StoryWorldGenState.getOrCreate()
        return WorldSnapshot(
            entityCounts = systemIds.associateWith { storySystem(sector, starIdForSystem(it))!!.allEntities.size },
            storyEntityCount = systemIds.sumOf { id ->
                storySystem(sector, starIdForSystem(id))!!.allEntities.count { it.hasTag(StoryWorldIds.TAG_STORY_ENTITY) }
            },
            storyEconomyMarkets = sector.economy.marketsCopy.count { it.id.startsWith(StoryWorldIds.ID_PREFIX) || it.id.startsWith("astd_market_") },
            artilleryCounts = systemIds.associateWith { IndEvoRuntimeProbe.artilleryCount(storySystem(sector, starIdForSystem(it))!!) },
            watchtowerCounts = systemIds.associateWith { IndEvoRuntimeProbe.watchtowerCount(storySystem(sector, starIdForSystem(it))!!) },
            mainGenerated = state.mainSystemGenerated,
            starfallGenerated = state.starfallSystemGenerated,
            asterGenerated = state.asterSystemGenerated,
            indEvoMainApplied = state.indEvoMainExtrasApplied,
            indEvoStarfallApplied = state.indEvoStarfallExtrasApplied,
            chapterTwoUnlocked = state.chapterTwoUnlocked,
        )
    }

    // ------------------------------------------------------------------
    // 传送与位置核对
    // ------------------------------------------------------------------

    /**
     * 真实传送：玩家舰队实体从原位置移除并加入目标星系（与 devMode 新开档注入的
     * 传送序列同一口径），随后核对舰队所在位置与当前位置。
     */
    private fun transferFleetToSystem(
        sector: SectorAPI,
        system: StarSystemAPI,
        angleDeg: Float,
        radiusSu: Float,
    ): Boolean {
        val fleet = sector.playerFleet ?: return false
        val star = system.star ?: return false
        moveFleetToLocation(fleet, system)

        val rad = angleDeg * (Math.PI.toFloat() / 180f)
        val x = star.location.x + cos(rad) * radiusSu
        val y = star.location.y + sin(rad) * radiusSu

        sector.currentLocation = system
        fleet.clearAssignments()
        fleet.setLocation(x, y)
        fleet.velocity.set(0f, 0f)
        fleet.setVelocity(0f, 0f)
        fleet.setMoveDestination(x, y)
        sector.respawnLocation = system
        sector.respawnCoordinates.set(x, y)

        return fleet.containingLocation === system && !fleet.isInHyperspace
    }

    private fun moveFleetToLocation(fleet: CampaignFleetAPI, location: LocationAPI) {
        val current = fleet.containingLocation
        if (current !== location) {
            current?.removeEntity(fleet)
            location.addEntity(fleet)
        } else if (!location.fleets.contains(fleet)) {
            location.addEntity(fleet)
        }
        fleet.containingLocation = location
    }

    private fun verifyPlayerLocation(sector: SectorAPI, system: StarSystemAPI, systemId: String) {
        val fleet = sector.playerFleet
        run.check(
            "playerLocation:$systemId",
            fleet != null &&
                fleet.containingLocation === system &&
                !fleet.isInHyperspace &&
                sector.currentLocation === system,
            "player fleet must be physically inside $systemId after transfer",
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun enterPhase(next: Phase) {
        phase = next
        phaseFrames = 0
        transferDone = false
    }

    private fun systemPoint(system: StarSystemAPI): StoryWorldLocations.WorldPoint =
        StoryWorldLocations.WorldPoint(system.location.x, system.location.y)

    /** 剧情星系经主星实体 ID 解析（vanilla getStarSystem 按名称匹配，稳定 ID 查不到）。 */
    private fun storySystem(sector: SectorAPI, starId: String): StarSystemAPI? =
        sector.getEntityById(starId)?.starSystem

    private fun starIdForSystem(systemId: String): String = when (systemId) {
        StoryWorldIds.SYSTEM_MAIN -> StoryWorldIds.MAIN_STAR
        StoryWorldIds.SYSTEM_STARFALL -> StoryWorldIds.STARFALL_STAR
        StoryWorldIds.SYSTEM_ASTER -> StoryWorldIds.ASTER_STAR
        else -> throw IllegalArgumentException("[CampaignWorldChecks] unknown story system $systemId")
    }
}

/**
 * IndEvo 运行期探针：所有直接引用 IndEvo 类的代码集中在独立类中（JVM 懒加载隔离），
 * 仅在 [StoryWorldGenerator.isIndEvoEnabled] 为真后才被触达。
 *
 * 以下标签/类型/内存键已对照游戏目录实际安装的 IndEvo 4.1.b（jars/IndEvo.jar，javap +
 * data/config/custom_entities.json）逐一核实，非猜测：
 * - `Ids.TAG_ENTITY_HAS_ARTILLERY_STATION` = "IndEvo_Entity_has_artillery"：行星挂接炮台标签；
 * - `Ids.TAG_WATCHTOWER` = "IndEvo_watchtower"：观锚站实体标签（IndEvo_Watchtower 实体定义自带）；
 * - `Ids.TAG_ARTILLERY_STATION` = "IndEvo_Artillery"：炮台站实体标签；
 * - `Ids.TAG_SYSTEM_HAS_ARTILLERY` = "IndEvo_SystemHasArtillery"：星系级标签（addArtilleryToPlanet 写入）；
 * - `Ids.ARTILLERY_RAILGUN` = "IndEvo_Artillery_railgun"：磁轨炮炮台工业 id；
 * - `Ids.DERELICT_FACTION_ID` = "IndEvo_derelict"：IndEvo 遗弃阵营（ModPlugin 设为对玩家 -1.0 敌对）；
 * - `ArtilleryStationScript.TYPE_KEY` = "\$IndEvo_ArtilleryType"：市场内存中的炮型锁定键；
 * - `ArtilleryStationScript.SCRIPT_KEY` = "\$IndEvo_ArtilleryStationScript"：行星内存中的脚本实例键；
 * - `ArtilleryStationCondition.ID` = "IndEvo_ArtilleryStationCondition"：炮台行星市场状况；
 * - 炮台站实体类型 "IndEvo_ArtilleryStation"（custom_entities.json，插件 ArtilleryStationEntityPlugin）；
 * - 观锚站实体类型 "IndEvo_Watchtower"（custom_entities.json，插件 WatchtowerEntityPlugin）；
 * - 阵营重推导：ArtilleryStationScript.updateFaction 每帧按星系经济市场重写炮台站与
 *   同星系全部观锚站阵营（condition-only 市场不在经济体，故主星系落地为 neutral、
 *   星坠落地为 vanilla derelict 或保留 IndEvo_derelict）。
 */
private object IndEvoRuntimeProbe {

    /** IndEvo 遗弃阵营 id（锻原市场/实体切换目标）。 */
    val derelictFactionId: String get() = indevo.ids.Ids.DERELICT_FACTION_ID

    /** 观锚站实体类型（IndEvo custom_entities.json 定义，自带 IndEvo_watchtower 标签）。 */
    private const val WATCHTOWER_ENTITY_TYPE = "IndEvo_Watchtower"

    /** 炮台站实体类型（IndEvo custom_entities.json 定义）。 */
    private const val ARTILLERY_ENTITY_TYPE = "IndEvo_ArtilleryStation"

    /** 单个行星炮台挂接状态快照（用于等待与逐项核对）。 */
    data class ArtilleryStatus(
        val hasArtilleryTag: Boolean,
        val scriptAttached: Boolean,
        val scriptType: String?,
        val typeMemoryRailgun: Boolean,
        val conditionPresent: Boolean,
        val stationSpawned: Boolean,
        val stationCustomType: Boolean,
        val stationPluginRailgun: Boolean,
        val stationIndustryRailgun: Boolean,
        val stationFactionId: String?,
        val stationHostileToPlayer: Boolean,
        val systemTagged: Boolean,
    ) {
        val hasArtilleryTagName: String get() = indevo.ids.Ids.TAG_ENTITY_HAS_ARTILLERY_STATION

        val scriptTypeRailgun: Boolean
            get() = scriptType == indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.TYPE_RAILGUN

        /** 炮台站实体已生成且 railgun 工业就绪（跨帧等待的完成判据）。 */
        val ready: Boolean get() = scriptAttached && stationSpawned && stationIndustryRailgun
    }

    /** 读取行星当前的炮台挂接状态（全部经真实 IndEvo API/常量）。 */
    fun artilleryStatus(planet: SectorEntityToken): ArtilleryStatus {
        val market = planet.market
        val script = planet.memoryWithoutUpdate
            .get(indevo.industries.artillery.scripts.ArtilleryStationScript.SCRIPT_KEY)
            as? indevo.industries.artillery.scripts.ArtilleryStationScript
        val station = market?.let {
            indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.getOrbitalStationAtMarket(it)
        }
        val plugin = station?.customPlugin as? indevo.industries.artillery.entities.ArtilleryStationEntityPlugin
        return ArtilleryStatus(
            hasArtilleryTag = planet.hasTag(indevo.ids.Ids.TAG_ENTITY_HAS_ARTILLERY_STATION),
            scriptAttached = script != null &&
                planet.hasScriptOfClass(indevo.industries.artillery.scripts.ArtilleryStationScript::class.java),
            scriptType = script?.type,
            typeMemoryRailgun = market?.memoryWithoutUpdate
                ?.get(indevo.industries.artillery.scripts.ArtilleryStationScript.TYPE_KEY) ==
                indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.TYPE_RAILGUN,
            conditionPresent = market?.hasCondition(
                indevo.industries.artillery.conditions.ArtilleryStationCondition.ID,
            ) == true,
            stationSpawned = station != null,
            stationCustomType = station?.customEntityType == ARTILLERY_ENTITY_TYPE &&
                station.hasTag(indevo.ids.Ids.TAG_ARTILLERY_STATION),
            stationPluginRailgun = plugin != null &&
                plugin.type == indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.TYPE_RAILGUN,
            stationIndustryRailgun = station?.market?.hasIndustry(indevo.ids.Ids.ARTILLERY_RAILGUN) == true,
            stationFactionId = station?.faction?.id,
            stationHostileToPlayer = station?.faction?.isHostileTo(Factions.PLAYER) == true,
            systemTagged = planet.starSystem?.hasTag(indevo.ids.Ids.TAG_SYSTEM_HAS_ARTILLERY) == true,
        )
    }

    /** 真实观锚站判定：实体类型 + IndEvo 观锚站标签 + IndEvo 观锚站插件三者齐备。 */
    fun isRealWatchtower(entity: SectorEntityToken): Boolean =
        entity.customEntityType == WATCHTOWER_ENTITY_TYPE &&
            entity.hasTag(indevo.ids.Ids.TAG_WATCHTOWER) &&
            entity.customPlugin is indevo.industries.artillery.entities.WatchtowerEntityPlugin

    /** 星系内 IndEvo 观锚站数量（按真实标签计数）。 */
    fun watchtowerCount(system: StarSystemAPI): Int =
        system.getEntitiesWithTag(indevo.ids.Ids.TAG_WATCHTOWER).size

    /** 星系内 IndEvo 炮台站数量（真实插件静态查询）。 */
    fun artilleryCount(system: StarSystemAPI): Int =
        indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.getArtilleriesInLoc(system).size
}
