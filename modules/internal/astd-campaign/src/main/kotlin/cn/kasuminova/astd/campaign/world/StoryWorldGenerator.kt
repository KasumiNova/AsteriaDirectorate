package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainlineProgression
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator.ensureAll
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator.ensureChapter2Systems
import cn.kasuminova.astd.campaign.world.StoryWorldGenerator.onChapterCleared
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Terrain
import com.fs.starfarer.api.impl.campaign.procgen.StarGenDataSpec
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger

/**
 * 剧情星系生成器（游戏侧，触碰 Global）。
 *
 * - 主星系：生涯开局（onNewGameAfterEconomyLoad）生成，位置半随机（种子 = 星区种子串）；
 * - 第二章双星系：第一章结清钩子（[MainlineProgression.HOOK_SEALED_CATEGORIES]）触发生成——
 *   结算时即时生成（[onChapterCleared]）+ 读档补齐（[ensureAll]）双路径；
 * - 幂等：以规格实体清单（[StorySystemSpecs.SystemSpec.allEntityIds]）对照当前星系，半途失败
 *   留下的残缺星系会被规范校验拦下并整体重放；补齐成功才写 [StoryWorldState] 状态标志位；
 * - IndEvo 联动统一走 [IndEvoWorldExtras]（软依赖，缺模组时该类不被加载）——主星系创建时注入 +
 *   每次经过 repairMainSystemIfEnabled 补齐；星坠统一由 repairStarfallIfEnabled 幂等补齐
 *   （每次 ensureAll/读档都会执行），已放置实体靠 canonical id / 标签去重。
 */
object StoryWorldGenerator {

    private val log: Logger = Global.getLogger(StoryWorldGenerator::class.java)

    /** 主星系生成种子的盐（避免与第二章落位使用同一随机流起点）。 */
    private const val SEED_SALT_MAIN: Long = 0xA57E11A1L

    private const val SEED_SALT_CH2: Long = 0xA57E11A2L

    /**
     * 全部剧情内容的幂等入口（新开档经济加载后 + 读档）。
     *
     * 顺序：主星系 → 第二章双星系（钩子门控）。
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
            log.error("[ASTD] 第二章遗址星系生成失败", t)
        }
    }

    /**
     * 确保目标星系与规格一致：实体缺失即视为生成半途失败，回滚残缺星系并重建（完整重放）。
     *
     * 背景：createSystem 是「先建星系/恒星、再逐实体落地」的多步过程，若中途抛异常，
     * 星系会带着残缺实体留在星区；此后恒星 canonical id 已存在，旧版幂等判定会误认为生成成功、
     * 残缺内容永不补齐。规范校验（而非只看恒星一个 id）把这类星系识别为失败态交给本方法整体重放。
     *
     * 失败必须有日志（调用方 ensureAll/onChapterCleared 亦各自记录）。
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

    /** 章节结清钩子回调（MainBountyBridge 结算路径调用）：第一章结清即生成第二章双星系。 */
    fun onChapterCleared(sector: SectorAPI, chapter: Int) {
        if (chapter != 1) return
        try {
            ensureChapter2Systems(sector, StoryWorldState.getOrCreate())
        } catch (t: Throwable) {
            log.error("[ASTD] 第一章结清钩子触发的第二章遗址星系生成失败", t)
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
     * 第二章双星系：钩子未触发则不动；两星系均已完整则做星坠 IndEvo 补齐后幂等返回；
     * 已触发但星系缺失或残缺则按存档落位（无存档落位时按种子现算并写入存档）补齐或回滚重建。
     *
     * 状态机语义：只有两个星系全部按规格落地成功才置 [StoryWorldState.chapter2SystemsGenerated]；
     * 任一星系中途失败/残缺，下次进入本方法时以规范实体清单拦下并回滚重建。
     * 星坠 IndEvo extras 一律经 [IndEvoWorldExtras.repairStarfallIfEnabled] 补齐（完整/重建/新建统一入口）。
     */
    fun ensureChapter2Systems(sector: SectorAPI, state: StoryWorldState) {
        val starfallSpec = StorySystemSpecs.starfallSystemSpec(sectorSeed(sector, SEED_SALT_CH2))
        // 已拔除的引力节点是「合法缺席」：从规格剔除，否则读档补齐会把节点缺失误判为
        // 星系残缺并整体重建，导致已拔除节点复活（完整性判定/回滚重建/落盘校验共用此 spec）
        val asterSpecFull = StorySystemSpecs.asterSystemSpec(sectorSeed(sector, SEED_SALT_CH2))
        val asterSpec = if (state.gravityNodesPulled.isEmpty()) {
            asterSpecFull
        } else {
            asterSpecFull.copy(entities = asterSpecFull.entities.filterNot { it.id in state.gravityNodesPulled })
        }
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

        val bountyState = BountyState.getOrCreate()
        if (MainlineProgression.HOOK_SEALED_CATEGORIES !in bountyState.chapterHooks) {
            return
        }

        val mainStar = sector.getEntityById(StoryWorldIds.MAIN_STAR)
        if (mainStar == null) {
            log.error("[ASTD] 第二章遗址星系生成失败：主星系不存在（钩子已触发但主星系缺失）")
            return
        }
        val mainLoc = StoryPlacement.Vec(
            mainStar.containingLocation.location.x,
            mainStar.containingLocation.location.y,
        )

        // 状态位与实体不一致（曾置位但星系残缺）：复位状态位，回到未置位分支按当前星系状态补齐。
        if (state.chapter2SystemsGenerated && !(starfallComplete && asterComplete)) {
            log.error(
                "[ASTD] 第二章遗址星系状态位与实体不一致（已置位但星系残缺），复位生成状态：" +
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
            log.error("[ASTD] 第二章遗址星系仍有缺失，生成状态未落盘（下次进入本类继续补齐）")
        }
    }

    private fun sectorSeed(sector: SectorAPI, salt: Long): Long =
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
            system.addAsteroidBelt(focus, belt.asteroidCount, belt.orbitRadius, belt.bandWidth, belt.orbitDays * 0.8f, belt.orbitDays * 1.2f)
            system.addRingBand(focus, "misc", "rings_asteroids0", 256f, 2, null, belt.bandWidth, belt.orbitRadius, belt.orbitDays)
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
        market.reapplyConditions()
        if (spec.inEconomy && !market.isInEconomy) {
            sector.economy.addMarket(market, true)
        }
    }

    private fun name(key: String): String = I18n.t(Categories.MOD, key)
}
