package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignTerrainAPI
import com.fs.starfarer.api.campaign.RingBandAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import org.apache.log4j.Logger

/**
 * 剧情世界数据迁移（按版本次序按序应用，全部就地修改，不做星系整体重建）。
 *
 * 规则约定（定稿）：
 * - 每次内容更新 bump [CURRENT_DATA_VERSION] 并追加一档迁移；迁移按版本次序顺序执行；
 * - 正常的环境变化（状况增删/环带新增/重命名/搬移）直接修改现有星系实体，不重建星系；
 * - 只有天体被摧毁/无故消失等残缺情形才走 [StoryWorldGenerator] 的规格完整性重建路径；
 * - 每档迁移内部幂等（重跑安全）；单档失败记日志并停在原水位（下轮读档重试，不越过失败档）。
 *
 * 版本线：v1 = 版本化之前的全部内容（旧档 [StoryWorldState.worldgenDataVersion] 缺省 0 视为 1）；
 * v2 = 三星系重命名（Asteria Seven/Arc/Lens）+ 越界星系钳制搬移 + 观锚站稳定点轨道减半 +
 * 主星系外环小行星带 ×2 + 行星状况同步（洪炉/淬池/兰台/锻原提档与气态巨行星随机状况组）。
 */
object StoryWorldMigrations {

    private val log: Logger = Global.getLogger(StoryWorldMigrations::class.java)

    /** 当前剧情世界数据版本（每次内容更新 +1）。 */
    const val CURRENT_DATA_VERSION: Int = 2

    /** 观锚站旧轨道半径（v2 之前为 21000su）。 */
    private const val WATCHTOWER_ORBIT_RADIUS_LEGACY = 21000f

    /** 观锚站旧轨道识别阈值（旧值与新值的中点，随 [IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS] 联动）。 */
    private val WATCHTOWER_ORBIT_RADIUS_LEGACY_MIN =
        (WATCHTOWER_ORBIT_RADIUS_LEGACY + IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS) / 2f

    /** 读档入口：应用全部缺失迁移并推进版本水位；单档失败停在原水位（下轮读档重试）。 */
    fun applyPending(sector: SectorAPI, state: StoryWorldState) {
        var version = if (state.worldgenDataVersion <= 0) 1 else state.worldgenDataVersion
        while (version < CURRENT_DATA_VERSION) {
            val next = version + 1
            val success = try {
                when (next) {
                    2 -> migrateToV2(sector, state)
                    else -> log.error("[ASTD] 未知剧情世界数据版本迁移目标 v$next，跳过")
                }
                true
            } catch (t: Throwable) {
                log.error("[ASTD] 剧情世界数据迁移 v$version -> v$next 失败，版本水位停在 v$version（下轮读档重试）", t)
                false
            }
            if (!success) return
            version = next
            state.worldgenDataVersion = version
        }
    }

    private fun migrateToV2(sector: SectorAPI, state: StoryWorldState) {
        renameSystems(sector)
        clampSystemsIntoMap(sector, state)
        halveWatchtowerOrbits(sector)
        addMainOuterBelts(sector)
        syncPlanetConditions(sector)
        log.info("[ASTD] 剧情世界数据迁移 v1 -> v2 完成（重命名/搬移/观锚站减半/外环带/状况同步）")
    }

    // ─── v2-1 星系重命名 ───

    /** 三星系重命名为现用名（Asteria Seven/Arc/Lens）；恒星本体与自动命名行星（"旧星系名 b/c/..."）同步换名。 */
    private fun renameSystems(sector: SectorAPI) {
        for (starId in listOf(StoryWorldIds.MAIN_STAR, StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            val spec = specOf(sector, starId) ?: continue
            val system = systemOf(sector, starId) ?: continue
            val newName = I18n.t(Categories.MOD, spec.nameKey)
            if (system.baseName == newName) continue
            val oldName = system.baseName
            // setBaseName 内部同步 setName（与新建路径同形）；恒星名在生成时快照自系统名，需一并换名
            system.setBaseName(newName)
            system.star?.setName(newName)
            // 自动命名行星（规格中 nameKey == null）：按规格次序剥离旧前缀换挂新名
            // （序号推进不依赖实体是否存在，与生成侧口径一致，避免缺失时字母错位）
            var autoIndex = 0
            for (planetSpec in spec.planets) {
                if (planetSpec.nameKey != null) continue
                system.getEntityById(planetSpec.id)?.setName("$newName ${'b' + autoIndex}")
                autoIndex++
            }
            log.info("[ASTD] 剧情星系重命名：$oldName -> $newName")
        }
    }

    // ─── v2-2 越界星系钳制搬移 ───

    /**
     * 越界星系就地搬移（不重建）：星系坐标钳回地图矩形内（保持原方向），存档落位同步更新。
     *
     * 超空间侧跳跃点/引力井无需手动平移：其轨道焦点为星系的超空间锚点，
     * 锚点每帧由系统坐标同步（StarSystem.UpdateFromHyperspaceLocation），搬移后自动跟随。
     * 存档落位（[StoryWorldState]）无条件钳制：即使星系实体暂缺（ensureAll 失败等），
     * 也不把越界坐标留给下轮重建。
     */
    private fun clampSystemsIntoMap(sector: SectorAPI, state: StoryWorldState) {
        for (starId in listOf(StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            val system = systemOf(sector, starId)
            val current = if (system != null) {
                StoryPlacement.Vec(system.location.x, system.location.y)
            } else {
                stateLoc(state, starId)
            }
            if (StoryPlacement.isWithinMapBounds(current)) {
                // 界内：仅校正存档落位与实际坐标的偏差（若有）
                if (system != null && (
                        kotlin.math.abs(system.location.x - stateLoc(state, starId).x) > 1f ||
                                kotlin.math.abs(system.location.y - stateLoc(state, starId).y) > 1f
                        )
                ) {
                    saveStateLoc(state, starId, StoryPlacement.Vec(system.location.x, system.location.y))
                }
                continue
            }
            val clamped = StoryPlacement.clampToMapBounds(current)
            system?.location?.set(clamped.x, clamped.y)
            saveStateLoc(state, starId, clamped)
            log.info(
                "[ASTD] 剧情星系 $starId 越界搬移：(${current.x.toInt()}, ${current.y.toInt()}) -> " +
                        "(${clamped.x.toInt()}, ${clamped.y.toInt()})",
            )
        }
    }

    private fun stateLoc(state: StoryWorldState, starId: String): StoryPlacement.Vec = when (starId) {
        StoryWorldIds.STARFALL_STAR -> StoryPlacement.Vec(state.starfallLocX, state.starfallLocY)
        else -> StoryPlacement.Vec(state.asterLocX, state.asterLocY)
    }

    private fun saveStateLoc(state: StoryWorldState, starId: String, loc: StoryPlacement.Vec) {
        when (starId) {
            StoryWorldIds.STARFALL_STAR -> {
                state.starfallLocX = loc.x; state.starfallLocY = loc.y
            }
            else -> {
                state.asterLocX = loc.x; state.asterLocY = loc.y
            }
        }
    }

    // ─── v2-3 观锚站稳定点轨道减半 ───

    /**
     * 观锚站稳定点轨道 21000su → [IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS]su。
     *
     * 就地改轨道（不重建实体）：观锚站功能状态写在实体 memory（IndEvo 侧 $objectiveNonFunctional
     * 等 key），remove/recreate 会丢失玩家已造成的瘫痪状态。仅观锚站实体缺失时补建。
     * IndEvo 未启用时实体不存在，本步骤自然空转。
     */
    private fun halveWatchtowerOrbits(sector: SectorAPI) {
        for (starId in listOf(StoryWorldIds.MAIN_STAR, StoryWorldIds.STARFALL_STAR)) {
            val system = systemOf(sector, starId) ?: continue
            val star = system.getEntityById(starId) ?: continue
            for (index in 0 until IndEvoWorldExtras.WATCHTOWER_COUNT) {
                val watchtowerId = "${starId}_${StoryWorldIds.TAG_INDEVO_WATCHTOWER}_$index"
                val anchor = system.getEntityById("${watchtowerId}_anchor") ?: continue
                if (anchor.circularOrbitRadius < WATCHTOWER_ORBIT_RADIUS_LEGACY_MIN) continue
                val angle = anchor.circularOrbitAngle
                anchor.setCircularOrbitPointingDown(
                    star, angle,
                    IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS, IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS / 45f,
                )
                val orbitRadius = anchor.radius + 250f
                val watchtower = system.getEntityById(watchtowerId)
                if (watchtower != null) {
                    watchtower.setCircularOrbitWithSpin(anchor, angle + 45f, orbitRadius, orbitRadius / 10f, 5f, 5f)
                } else {
                    // 观锚站缺失：补建（原阵营/功能状态随实体灭失无法考证，退回中立）
                    val newTower = system.addCustomEntity(
                        watchtowerId,
                        I18n.t(Categories.MOD, "world.shared.indevo_watchtower"),
                        IndEvoWorldExtras.ENTITY_WATCHTOWER,
                        Factions.NEUTRAL,
                    )
                    newTower.setCircularOrbitWithSpin(anchor, angle + 45f, orbitRadius, orbitRadius / 10f, 5f, 5f)
                    newTower.addTag(StoryWorldIds.TAG_INDEVO_WATCHTOWER)
                    newTower.addTag(IndEvoWorldExtras.TAG_INDEVO_NATIVE_WATCHTOWER)
                }
                log.info("[ASTD] 观锚站稳定点轨道减半：$watchtowerId @ ${system.id}")
            }
        }
    }

    // ─── v2-4 主星系外环小行星带 ───

    /**
     * 主星系小行星带按规格补齐（逐带幂等）：一环为版本化前已存在的无标签环带，
     * 退回轨道半径近似匹配去重（仅比对地形/环带实体，避免被同轨其他实体假命中）；
     * 地形与环圈分别判定补齐，中途失败留下的半档（有带无圈）能在重试时自愈。
     */
    private fun addMainOuterBelts(sector: SectorAPI) {
        val spec = StorySystemSpecs.mainSystemSpec(StoryWorldGenerator.sectorSeed(sector, StoryWorldGenerator.SEED_SALT_MAIN))
        val system = systemOf(sector, StoryWorldIds.MAIN_STAR) ?: return
        val star = system.getEntityById(StoryWorldIds.MAIN_STAR) ?: return
        for (belt in spec.belts) {
            fun legacyMatch(entity: com.fs.starfarer.api.campaign.SectorEntityToken): Boolean =
                entity.orbitFocus === star && kotlin.math.abs(entity.circularOrbitRadius - belt.orbitRadius) < 5f

            val terrainPresent = system.allEntities.any {
                it is CampaignTerrainAPI && (it.hasTag(belt.tag) || legacyMatch(it))
            }
            if (!terrainPresent) {
                val beltTerrain = system.addAsteroidBelt(
                    star, belt.asteroidCount, belt.orbitRadius, belt.bandWidth,
                    belt.orbitDays * 0.8f, belt.orbitDays * 1.2f,
                )
                beltTerrain.addTag(belt.tag)
                log.info("[ASTD] 主星系小行星带补齐（地形）：${belt.tag} @ ${belt.orbitRadius.toInt()}su")
            }
            val ringPresent = system.allEntities.any {
                it is RingBandAPI && (it.hasTag(belt.tag) || legacyMatch(it))
            }
            if (!ringPresent) {
                val ringBand = system.addRingBand(
                    star, "misc", "rings_asteroids0", 256f, 2, null, belt.bandWidth, belt.orbitRadius, belt.orbitDays,
                )
                ringBand.addTag(belt.tag)
                log.info("[ASTD] 主星系小行星带补齐（环圈）：${belt.tag} @ ${belt.orbitRadius.toInt()}su")
            }
        }
    }

    // ─── v2-5 行星状况同步 ───

    /**
     * 行星市场状况按规格同步（受管池内增删）：新增状况补挂、被规格移除的旧状况摘除，
     * 池外状况（其他模组/未来玩家侧写入）不受影响。condition-only 市场经实体 market 访问
     * （不进经济表，economy.getMarket 查不到）。
     * 已被玩家殖民的市场跳过：殖民地的状况由 [StoryWorldGenerator] 的每次读档热修复只增不删，
     * 避免摘除地貌改造等 mod 在殖民后写入的受管池状况。
     */
    private fun syncPlanetConditions(sector: SectorAPI) {
        for (starId in listOf(StoryWorldIds.MAIN_STAR, StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            val spec = specOf(sector, starId) ?: continue
            val system = systemOf(sector, starId) ?: continue
            for (planetSpec in spec.planets) {
                val marketSpec = planetSpec.market ?: continue
                val market = system.getEntityById(planetSpec.id)?.market
                if (market == null) {
                    log.error("[ASTD] 行星状况同步失败：${planetSpec.id} 无市场（${marketSpec.marketId}）")
                    continue
                }
                if (!market.isPlanetConditionMarketOnly) {
                    log.info("[ASTD] ${marketSpec.marketId} 已被殖民，跳过状况同步（交由殖民地热修复处理）")
                    continue
                }
                val desired = marketSpec.conditionIds.toSet()
                var changed = false
                for (existing in market.conditions.map { it.id }) {
                    if (existing in StorySystemSpecs.MANAGED_PLANET_CONDITIONS && existing !in desired) {
                        market.removeCondition(existing)
                        changed = true
                        log.info("[ASTD] ${marketSpec.marketId} 摘除状况 $existing")
                    }
                }
                for (conditionId in marketSpec.conditionIds) {
                    if (!market.hasCondition(conditionId)) {
                        market.addCondition(conditionId)
                        changed = true
                        log.info("[ASTD] ${marketSpec.marketId} 补挂状况 $conditionId")
                    }
                }
                if (changed) market.reapplyConditions()
            }
        }
    }

    // ─── 工具 ───

    private fun systemOf(sector: SectorAPI, starId: String): StarSystemAPI? =
        sector.getEntityById(starId)?.containingLocation as? StarSystemAPI

    private fun specOf(sector: SectorAPI, starId: String): StorySystemSpecs.SystemSpec? = when (starId) {
        StoryWorldIds.MAIN_STAR ->
            StorySystemSpecs.mainSystemSpec(StoryWorldGenerator.sectorSeed(sector, StoryWorldGenerator.SEED_SALT_MAIN))
        StoryWorldIds.STARFALL_STAR ->
            StorySystemSpecs.starfallSystemSpec(StoryWorldGenerator.sectorSeed(sector, StoryWorldGenerator.SEED_SALT_CH2))
        StoryWorldIds.ASTER_STAR ->
            StorySystemSpecs.asterSystemSpec(StoryWorldGenerator.sectorSeed(sector, StoryWorldGenerator.SEED_SALT_CH2))
        else -> null
    }
}
