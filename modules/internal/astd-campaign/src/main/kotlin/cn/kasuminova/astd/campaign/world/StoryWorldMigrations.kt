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

object StoryWorldMigrations {

    private val log: Logger = Global.getLogger(StoryWorldMigrations::class.java)

    const val CURRENT_DATA_VERSION: Int = 2

    private const val WATCHTOWER_ORBIT_RADIUS_LEGACY = 21000f

    private val WATCHTOWER_ORBIT_RADIUS_LEGACY_MIN =
        (WATCHTOWER_ORBIT_RADIUS_LEGACY + IndEvoWorldExtras.WATCHTOWER_ORBIT_RADIUS) / 2f

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

    private fun renameSystems(sector: SectorAPI) {
        for (starId in listOf(StoryWorldIds.MAIN_STAR, StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            val spec = specOf(sector, starId) ?: continue
            val system = systemOf(sector, starId) ?: continue
            val newName = I18n.t(Categories.MOD, spec.nameKey)
            if (system.baseName == newName) continue
            val oldName = system.baseName
            system.setBaseName(newName)
            system.star?.setName(newName)
            var autoIndex = 0
            for (planetSpec in spec.planets) {
                if (planetSpec.nameKey != null) continue
                system.getEntityById(planetSpec.id)?.setName("$newName ${'b' + autoIndex}")
                autoIndex++
            }
            log.info("[ASTD] 剧情星系重命名：$oldName -> $newName")
        }
    }

    private fun clampSystemsIntoMap(sector: SectorAPI, state: StoryWorldState) {
        for (starId in listOf(StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            val system = systemOf(sector, starId)
            val current = if (system != null) {
                StoryPlacement.Vec(system.location.x, system.location.y)
            } else {
                stateLoc(state, starId)
            }
            if (StoryPlacement.isWithinMapBounds(current)) {
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
