package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.StoryWorldGenerator.ensureAll
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator.ensureChapter2Systems
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI
import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Terrain
import com.fs.starfarer.api.impl.campaign.procgen.StarGenDataSpec
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger

/**
 * 剧情星系生成器（游戏侧，触碰 Global）。
 *
 * - 三个剧情星系均生涯开局（onNewGameAfterEconomyLoad）常生成，位置半随机（种子 = 星区种子串）；
 * - 幂等：以规格实体清单（[StorySystemSpecs.SystemSpec.allEntityIds]）对照当前星系，半途失败
 *   留下的残缺星系会被规范校验拦下并整体重放；补齐成功才写 [StoryWorldState] 状态标志位；
 * - IndEvo 联动统一走 [IndEvoWorldExtras]（软依赖，缺模组时该类不被加载）——主星系创建时注入 +
 *   每次经过 repairMainSystemIfEnabled 补齐；星坠统一由 repairStarfallIfEnabled 幂等补齐
 *   （每次 ensureAll/读档都会执行），已放置实体靠 canonical id / 标签去重。
 */
object StoryWorldGenerator {

    private val log: Logger = Global.getLogger(StoryWorldGenerator::class.java)

    /** 主星系生成种子的盐（避免与第二章落位使用同一随机流起点）。 */
    internal const val SEED_SALT_MAIN: Long = 0xA57E11A1L

    internal const val SEED_SALT_CH2: Long = 0xA57E11A2L

    /**
     * 全部剧情内容的幂等入口（新开档经济加载后 + 读档）。
     *
     * 顺序：主星系 → 双遗址星系。
     */
    fun ensureAll(sector: SectorAPI) {
        val state = StoryWorldState.getOrCreate()
        try {
            ensureMainSystem(sector, state)
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情主星系生成失败", t)
        }
        try {
            ensureChapter2Systems(sector, state)
        } catch (t: Throwable) {
            log.error("[ASTD] 遗址星系生成失败", t)
        }
        ensureMarketIndustries(sector)
        ensureStationEntityTypes(sector)
        ensureColonizedPlanetConditions(sector)
    }

    /**
     * 既有存档热修复：剧情行星被玩家殖民后的状况补齐（只增不删，每次读档执行）。
     *
     * Grand Colonies 等允许殖民 condition-only 行星的 mod 会接管并重写市场状况列表，
     * 矿脉/废墟/剧情遗址等原生状况在殖民后丢失。此处对已被殖民
     * （[com.fs.starfarer.api.campaign.econ.MarketAPI.isPlanetConditionMarketOnly] 为 false）
     * 的剧情行星按规格补挂缺失状况；不做摘除——地貌改造等 mod 后期写入的状况不受干预
     * （未殖民的 condition-only 市场由 [StoryWorldMigrations] 的版本化同步全权负责，含摘除）。
     */
    private fun ensureColonizedPlanetConditions(sector: SectorAPI) {
        val specs = listOf(
            StorySystemSpecs.mainSystemSpec(sectorSeed(sector, SEED_SALT_MAIN)),
            StorySystemSpecs.starfallSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
            StorySystemSpecs.asterSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
        )
        for (spec in specs) {
            val system = sector.getEntityById(spec.starId)?.containingLocation as? StarSystemAPI ?: continue
            for (planetSpec in spec.planets) {
                val marketSpec = planetSpec.market ?: continue
                val market = system.getEntityById(planetSpec.id)?.market ?: continue
                if (market.isPlanetConditionMarketOnly) continue
                var changed = false
                for (conditionId in marketSpec.conditionIds) {
                    if (!market.hasCondition(conditionId)) {
                        market.addCondition(conditionId)
                        changed = true
                        log.info("[ASTD] 殖民地 ${marketSpec.marketId} 补挂原生行星状况 $conditionId（殖民兼容热修复）")
                    }
                }
                if (changed) market.reapplyConditions()
            }
        }
    }

    /** 旧版可打捞遗迹站实体类型（迁移来源）。 */
    private val LEGACY_SALVAGEABLE_STATION_TYPES = setOf(
        Entities.STATION_RESEARCH_REMNANT,
        Entities.STATION_MINING_REMNANT,
    )

    /**
     * 既有存档热修复：旧版本直接复用原版 station_research/mining_remnant 类型（带 salvageable 标记），
     * 玩家打捞后实体会被原版打捞流程转换为碎片区而消失；现改为模组自定义的不可打捞类型，
     * 读档时对类型不匹配的遗迹站按规格原地重建（保留 id/名称/轨道角）。
     */
    private fun ensureStationEntityTypes(sector: SectorAPI) {
        val specs = listOf(
            StorySystemSpecs.mainSystemSpec(sectorSeed(sector, SEED_SALT_MAIN)),
            StorySystemSpecs.starfallSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
            StorySystemSpecs.asterSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
        )
        for (spec in specs) {
            val system = sector.getEntityById(spec.starId)?.containingLocation as? StarSystemAPI ?: continue
            for (entitySpec in spec.entities) {
                val existing = system.getEntityById(entitySpec.id) as? CustomCampaignEntityAPI ?: continue
                if (existing.customEntityType == entitySpec.entityType) continue
                if (existing.customEntityType !in LEGACY_SALVAGEABLE_STATION_TYPES) {
                    log.error(
                        "[ASTD] 剧情站点 ${entitySpec.id} 实体类型异常（${existing.customEntityType}，" +
                                "期望 ${entitySpec.entityType}），非旧版可打捞类型，跳过迁移",
                    )
                    continue
                }
                val focus = system.getEntityById(entitySpec.orbit.focusId)
                if (focus == null) {
                    log.error("[ASTD] 剧情站点 ${entitySpec.id} 轨道焦点 ${entitySpec.orbit.focusId} 缺失，跳过迁移")
                    continue
                }
                val name = existing.name
                val angle = existing.circularOrbitAngle
                system.removeEntity(existing)
                val recreated = system.addCustomEntity(
                    entitySpec.id, name, entitySpec.entityType, entitySpec.factionId,
                )
                recreated.setCircularOrbitPointingDown(
                    focus, angle, entitySpec.orbit.radius, entitySpec.orbit.periodDays,
                )
                for (tag in entitySpec.tags) recreated.addTag(tag)
                log.info("[ASTD] 剧情站点 ${entitySpec.id} 已迁移为不可打捞类型 ${entitySpec.entityType}（旧档热修复）")
            }
        }
    }

    /**
     * 既有存档热修复：FULL 市场产业缺失时按规格补齐（幂等）。
     *
     * 旧版本生成的第七分局空间站/拾光市场没有任何产业，Nexerelin 对其发起入侵时
     * 地面战情报初始化（GroundBattleIntel.init 兜底取首个产业）会因零产业越界崩溃；
     * 星系已完整的存档不会走重建路径，需在读档时单独补齐产业。
     */
    private fun ensureMarketIndustries(sector: SectorAPI) {
        val specs = listOf(
            StorySystemSpecs.mainSystemSpec(sectorSeed(sector, SEED_SALT_MAIN)),
            StorySystemSpecs.starfallSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
            StorySystemSpecs.asterSystemSpec(sectorSeed(sector, SEED_SALT_CH2)),
        )
        for (spec in specs) {
            for (marketSpec in spec.planets.mapNotNull { it.market } + spec.entities.mapNotNull { it.market }) {
                if (marketSpec.conditionOnly || marketSpec.industryIds.isEmpty()) continue
                val market = sector.economy.getMarket(marketSpec.marketId) ?: continue
                for (industryId in marketSpec.industryIds) {
                    if (!market.hasIndustry(industryId)) {
                        market.addIndustry(industryId)
                        log.info("[ASTD] 市场 ${marketSpec.marketId} 补齐缺失产业 $industryId（旧档热修复）")
                    }
                }
            }
        }
    }

    /**
     * 确保目标星系与规格一致：实体缺失即视为生成半途失败，回滚残缺星系并重建（完整重放）。
     *
     * 背景：createSystem 是「先建星系/恒星、再逐实体落地」的多步过程，若中途抛异常，
     * 星系会带着残缺实体留在星区；此后恒星 canonical id 已存在，旧版幂等判定会误认为生成成功、
     * 残缺内容永不补齐。规范校验（而非只看恒星一个 id）把这类星系识别为失败态交给本方法整体重放。
     *
     * 失败必须有日志（调用方 ensureAll 亦各自记录）。
     */
    private fun recreateSystemIfIncomplete(
        sector: SectorAPI,
        state: StoryWorldState,
        spec: StorySystemSpecs.SystemSpec,
        loc: StoryPlacement.Vec,
        reason: String,
    ) {
        val existing = sector.getEntityById(spec.starId)?.containingLocation as? StarSystemAPI
        if (existing != null) {
            log.error("[ASTD] 剧情星系 ${spec.systemId} 生成中断或内容缺失，回滚重建（$reason）")
        } else {
            log.error("[ASTD] 剧情星系 ${spec.systemId} 生成中断，重建（$reason）")
        }
        if (existing != null) {
            sector.removeStarSystem(existing)
            removeMarkets(sector, spec)
        }
        val rebuilt = createSystem(sector, spec, loc)
        log.info("[ASTD] 剧情星系 ${spec.systemId} 已重建 @ (${loc.x.toInt()}, ${loc.y.toInt()})：${rebuilt.nameWithNoType}")
    }

    /** 移除星系中已注册进经济的市场（回滚残缺星系后清理残注册，避免孤儿市场滞留经济表）。 */
    private fun removeMarkets(sector: SectorAPI, spec: StorySystemSpecs.SystemSpec) {
        for (marketId in spec.allMarketIds()) {
            val market = sector.economy.getMarket(marketId)
            if (market != null) {
                sector.economy.removeMarket(market)
            }
        }
    }

    /**
     * 判断目标星系是否已完整生成（以规格实体清单为准，恒星缺失即 false）。
     *
     * 与 [ensureChapter2Systems] 的状态机配合：实体清单残缺的星系视为「半途失败」，回滚重建；
     * 全部实体就位才写 [StoryWorldState] 生成标志位，保证任何一步中断都能在下次进入本类时补齐。
     */
    private fun systemIsComplete(system: StarSystemAPI?, spec: StorySystemSpecs.SystemSpec): Boolean {
        if (system == null) return false
        return spec.isComplete(system.allEntities.map { it.id })
    }

    /** 取星系当前所在坐标（供残缺主星系重建时复用原落位，避免改变玩家已见过的相对布局）。 */
    private fun existingLoc(star: SectorEntityToken, spec: StorySystemSpecs.SystemSpec): StoryPlacement.Vec {
        val system = star.containingLocation as? StarSystemAPI
        return if (system != null) {
            StoryPlacement.Vec(system.location.x, system.location.y)
        } else {
            log.error("[ASTD] 剧情星系 ${spec.systemId} 恒星存在但无法定位所在星系，回退 (0, 0)")
            StoryPlacement.Vec(0f, 0f)
        }
    }

    /**
     * 主星系：恒星已存在且完整则做 IndEvo 联动补齐（读档/模组中途启用路径），并校正缺失的生成标志位；
     * 恒星缺失则新生成（创建后立即做一次 IndEvo 注入）；残缺（半途失败残留）则回滚重建，重建后补一次注入。
     */
    fun ensureMainSystem(sector: SectorAPI, state: StoryWorldState) {
        val spec = StorySystemSpecs.mainSystemSpec(sectorSeed(sector, SEED_SALT_MAIN))
        val star = sector.getEntityById(StoryWorldIds.MAIN_STAR)
        val existingSystem = star?.containingLocation as? StarSystemAPI
        if (systemIsComplete(existingSystem, spec)) {
            IndEvoWorldExtras.repairMainSystemIfEnabled(sector)
            state.mainSystemGenerated = true
            return
        }
        // 生成中断/内容残缺：回滚残缺星系后整体重建（完整重放，下次必达完整态）。
        if (star != null) {
            recreateSystemIfIncomplete(sector, state, spec, existingLoc(star, spec), "主星系规格实体缺失")
        } else {
            val existingLocs = sector.starSystems.map { StoryPlacement.Vec(it.location.x, it.location.y) }
            val loc = StoryPlacement.placeMainSystem(sectorSeed(sector, SEED_SALT_MAIN), existingLocs)
            val system = createSystem(sector, spec, loc)
            IndEvoWorldExtras.addMainSystemExtrasIfEnabled(sector, system)
            state.mainSystemGenerated = true
            log.info("[ASTD] 剧情主星系已生成：${system.nameWithNoType} @ (${loc.x.toInt()}, ${loc.y.toInt()})")
            return
        }
        // 重建路径（上方已 return 的为新建路径）：统一补一次主星系 IndEvo 注入。
        IndEvoWorldExtras.repairMainSystemIfEnabled(sector)
        state.mainSystemGenerated = true
    }

    /**
     * 双遗址星系（星坠/紫菀）：两星系均已完整则做星坠 IndEvo 补齐后幂等返回；
     * 星系缺失或残缺则按存档落位（无存档落位时按种子现算并写入存档）补齐或回滚重建。
     *
     * 状态机语义：只有两个星系全部按规格落地成功才置 [StoryWorldState.chapter2SystemsGenerated]；
     * 任一星系中途失败/残缺，下次进入本方法时以规范实体清单拦下并回滚重建。
     * 星坠 IndEvo extras 一律经 [IndEvoWorldExtras.repairStarfallIfEnabled] 补齐（完整/重建/新建统一入口）。
     */
    fun ensureChapter2Systems(sector: SectorAPI, state: StoryWorldState) {
        val starfallSpec = StorySystemSpecs.starfallSystemSpec(sectorSeed(sector, SEED_SALT_CH2))
        val asterSpec = StorySystemSpecs.asterSystemSpec(sectorSeed(sector, SEED_SALT_CH2))
        val starfallStar = sector.getEntityById(StoryWorldIds.STARFALL_STAR)
        val asterStar = sector.getEntityById(StoryWorldIds.ASTER_STAR)
        val starfallSystem = starfallStar?.containingLocation as? StarSystemAPI
        val asterSystem = asterStar?.containingLocation as? StarSystemAPI
        val starfallComplete = systemIsComplete(starfallSystem, starfallSpec)
        val asterComplete = systemIsComplete(asterSystem, asterSpec)

        if (state.chapter2SystemsGenerated && starfallComplete && asterComplete) {
            // 已生成且完整：仍做一次星坠 IndEvo 补齐——创建当刻若 IndEvo 抛异常，extras 靠此路径幂等补齐。
            IndEvoWorldExtras.repairStarfallIfEnabled(sector)
            return
        }

        val mainStar = sector.getEntityById(StoryWorldIds.MAIN_STAR)
        if (mainStar == null) {
            log.error("[ASTD] 遗址星系生成失败：主星系不存在")
            return
        }
        val mainLoc = StoryPlacement.Vec(
            mainStar.containingLocation.location.x,
            mainStar.containingLocation.location.y,
        )

        // 状态位与实体不一致（曾置位但星系残缺）：复位状态位，回到未置位分支按当前星系状态补齐。
        if (state.chapter2SystemsGenerated && !(starfallComplete && asterComplete)) {
            log.error(
                "[ASTD] 遗址星系状态位与实体不一致（已置位但星系残缺），复位生成状态：" +
                        "starfallComplete=$starfallComplete asterComplete=$asterComplete",
            )
            state.chapter2SystemsGenerated = false
        }

        // 落位：完整时复用存档坐标；未完整（含首次进入）则按种子现算——生成前即写入存档，
        // 否则补全过程半途中断会丢失落位信息、下次现算得不同坐标。
        val (starfallLoc, asterLoc) = if (state.chapter2SystemsGenerated) {
            StoryPlacement.Vec(state.starfallLocX, state.starfallLocY) to
                    StoryPlacement.Vec(state.asterLocX, state.asterLocY)
        } else {
            val pair = StoryPlacement.placeChapter2Systems(mainLoc, sectorSeed(sector, SEED_SALT_CH2))
            state.starfallLocX = pair.first.x
            state.starfallLocY = pair.first.y
            state.asterLocX = pair.second.x
            state.asterLocY = pair.second.y
            pair
        }

        if (!starfallComplete) {
            if (starfallStar != null) {
                recreateSystemIfIncomplete(sector, state, starfallSpec, starfallLoc, "星坠规格实体缺失")
            } else {
                val system = createSystem(sector, starfallSpec, starfallLoc)
                log.info("[ASTD] 星坠遗址星系已生成：${system.nameWithNoType} @ (${starfallLoc.x.toInt()}, ${starfallLoc.y.toInt()})")
            }
        }
        if (!asterComplete) {
            if (asterStar != null) {
                recreateSystemIfIncomplete(sector, state, asterSpec, asterLoc, "紫菀规格实体缺失")
            } else {
                createSystem(sector, asterSpec, asterLoc)
                log.info("[ASTD] 紫菀遗址星系已生成 @ (${asterLoc.x.toInt()}, ${asterLoc.y.toInt()})")
            }
        }

        // 重建完成后统一做一次 IndEvo 补齐（创建/重建均只走 repairStarfallIfEnabled 单入口）。
        IndEvoWorldExtras.repairStarfallIfEnabled(sector)
        if (systemIsComplete(sector.getEntityById(StoryWorldIds.STARFALL_STAR)?.containingLocation as? StarSystemAPI, starfallSpec) &&
            systemIsComplete(sector.getEntityById(StoryWorldIds.ASTER_STAR)?.containingLocation as? StarSystemAPI, asterSpec)
        ) {
            state.chapter2SystemsGenerated = true
        } else {
            log.error("[ASTD] 遗址星系仍有缺失，生成状态未落盘（下次进入本类继续补齐）")
        }
    }

    internal fun sectorSeed(sector: SectorAPI, salt: Long): Long =
        (sector.seedString ?: "asteria_directorate").hashCode().toLong() xor salt

    // ─── 星系落地 ───

    private fun createSystem(sector: SectorAPI, spec: StorySystemSpecs.SystemSpec, loc: StoryPlacement.Vec): StarSystemAPI {
        val systemName = name(spec.nameKey)
        val system = sector.createStarSystem(systemName)
        system.location.set(loc.x, loc.y)
        system.backgroundTextureFilename = "graphics/backgrounds/background4.jpg"

        val star = system.initStar(spec.starId, spec.starType, spec.starRadius, spec.coronaSize)
        if (spec.blackHole) {
            applyEventHorizon(system, star)
        }

        val entitiesById = HashMap<String, SectorEntityToken>()
        entitiesById[spec.starId] = star

        var randomPlanetIndex = 0
        for (planet in spec.planets) {
            val focus = entitiesById.getValue(planet.orbit.focusId)
            val planetName = planet.nameKey?.let { name(it) } ?: run {
                randomPlanetIndex++
                "$systemName ${'b' + randomPlanetIndex - 1}"
            }
            val created = system.addPlanet(
                planet.id, focus, planetName, planet.typeId,
                planet.radius, planet.orbit.angleDeg, planet.orbit.radius, planet.orbit.periodDays,
            )
            entitiesById[planet.id] = created
            planet.market?.let { applyMarket(sector, created, it, planetName) }
            repeat(planet.stellarMirrors) { mirrorIndex ->
                addStellarMirror(system, created, mirrorIndex)
            }
        }

        for (entity in spec.entities) {
            val focus = entitiesById.getValue(entity.orbit.focusId)
            val entityName = entity.nameKey?.let { name(it) }
            val created = system.addCustomEntity(entity.id, entityName, entity.entityType, entity.factionId)
            created.setCircularOrbitPointingDown(focus, entity.orbit.angleDeg, entity.orbit.radius, entity.orbit.periodDays)
            for (tag in entity.tags) created.addTag(tag)
            entity.market?.let { applyMarket(sector, created, it, entityName ?: created.name) }
            entitiesById[entity.id] = created
        }

        for (belt in spec.belts) {
            val focus = entitiesById.getValue(belt.focusId)
            val beltTerrain = system.addAsteroidBelt(focus, belt.asteroidCount, belt.orbitRadius, belt.bandWidth, belt.orbitDays * 0.8f, belt.orbitDays * 1.2f)
            beltTerrain.addTag(belt.tag)
            val ringBand = system.addRingBand(focus, "misc", "rings_asteroids0", 256f, 2, null, belt.bandWidth, belt.orbitRadius, belt.orbitDays)
            ringBand.addTag(belt.tag)
        }

        system.autogenerateHyperspaceJumpPoints(true, true)
        return system
    }

    /** 黑洞事件视界地形（复刻原版 procgen 的黑洞处理：移除默认日冕，按 StarGenDataSpec 铺事件视界）。 */
    private fun applyEventHorizon(system: StarSystemAPI, star: PlanetAPI) {
        Misc.getCoronaFor(star)?.let { corona -> system.removeEntity(corona.entity) }

        val starData = Global.getSettings().getSpec(StarGenDataSpec::class.java, star.spec.planetType, false) as StarGenDataSpec
        var corona = star.radius * starData.coronaMult
        if (corona < starData.coronaMin) corona = starData.coronaMin

        val eventHorizon = system.addTerrain(
            Terrain.EVENT_HORIZON,
            StarCoronaTerrainPlugin.CoronaParams(
                star.radius + corona,
                (star.radius + corona) / 2f,
                star,
                starData.solarWind,
                starData.minFlare,
                starData.crLossMult,
            ),
        )
        eventHorizon.setCircularOrbit(star, 0f, 0f, 100f)
    }

    /** 轨道恒星镜（原版实体类型 stellar_mirror，复刻 MiscellaneousThemeGenerator 布局）。 */
    private fun addStellarMirror(system: StarSystemAPI, planet: SectorEntityToken, index: Int) {
        val mirror = system.addCustomEntity(
            "${planet.id}_mirror_$index",
            name("world.shared.stellar_mirror"),
            com.fs.starfarer.api.impl.campaign.ids.Entities.STELLAR_MIRROR,
            com.fs.starfarer.api.impl.campaign.ids.Factions.NEUTRAL,
        )
        val angle = planet.circularOrbitAngle + (index * 40f - 20f)
        mirror.setCircularOrbitPointingDown(planet, angle, planet.radius + 270f, planet.circularOrbitPeriod)
    }

    private fun applyMarket(sector: SectorAPI, entity: SectorEntityToken, spec: StorySystemSpecs.MarketSpec, marketName: String) {
        val market = Global.getFactory().createMarket(spec.marketId, marketName, spec.size)
        market.isPlanetConditionMarketOnly = spec.conditionOnly
        market.primaryEntity = entity
        market.factionId = spec.factionId
        market.surveyLevel = MarketAPI.SurveyLevel.FULL
        entity.market = market
        entity.setFaction(spec.factionId)
        for (conditionId in spec.conditionIds) {
            market.addCondition(conditionId)
        }
        for (industryId in spec.industryIds) {
            if (!market.hasIndustry(industryId)) {
                market.addIndustry(industryId)
            }
        }
        market.reapplyConditions()
        if (spec.inEconomy && !market.isInEconomy) {
            sector.economy.addMarket(market, true)
        }
    }

    private fun name(key: String): String = I18n.t(Categories.MOD, key)
}
