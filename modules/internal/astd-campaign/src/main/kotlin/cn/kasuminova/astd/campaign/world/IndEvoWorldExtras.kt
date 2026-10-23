package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.WATCHTOWER_COUNT
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.addMainSystemExtrasIfEnabled
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.isEnabled
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.placeArtillery
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.placeWatchtowers
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.repairMainSystemIfEnabled
import cn.kasuminova.astd.campaign.world.IndEvoWorldExtras.repairStarfallIfEnabled
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import indevo.industries.artillery.utils.ArtilleryStationPlacer
import org.apache.log4j.Logger

/**
 * IndEvo 联动内容（软依赖隔离文件）：全部 IndEvo 类引用只在本文件，入口先过 [isEnabled]，缺 IndEvo 时 JVM 不加载本类。
 * 挂炮台必须走 IndEvo 官方入口 `ArtilleryStationPlacer.addArtilleryToPlanet`，禁止直接调
 * `ArtilleryStationEntityPlugin.placeAtMarket`（缺管理脚本时状况会把实体判为已摧毁）；
 * 观锚站必须同时打 IndEvo 原生标签 `IndEvo_watchtower`，addArtilleryToPlanet 以该标签判定星系已有观锚站。
 */
object IndEvoWorldExtras {

    private val log: Logger = Global.getLogger(IndEvoWorldExtras::class.java)

    internal const val ENTITY_WATCHTOWER: String = "IndEvo_Watchtower"

    internal const val TAG_INDEVO_NATIVE_WATCHTOWER: String = "IndEvo_watchtower"

    private const val TAG_ENTITY_HAS_ARTILLERY: String = "IndEvo_Entity_has_artillery"

    private const val FACTION_INDEVO_DERELICT: String = "IndEvo_derelict"

    internal const val WATCHTOWER_ORBIT_RADIUS: Float = 10500f

    internal const val WATCHTOWER_COUNT: Int = 4

    @Volatile
    private var detected: Boolean? = null

    fun isEnabled(): Boolean {
        detected?.let { return it }
        val enabled = try {
            Global.getSettings().modManager.isModEnabled(StoryWorldIds.INDEVO_MOD_ID)
        } catch (t: Throwable) {
            log.error("[ASTD] IndEvo 检测失败", t)
            false
        }
        detected = enabled
        return enabled
    }

    fun addMainSystemExtrasIfEnabled(sector: SectorAPI, system: StarSystemAPI) {
        if (!isEnabled()) return
        addMainSystemExtras(sector)
    }

    fun repairMainSystemIfEnabled(sector: SectorAPI) {
        if (!isEnabled()) return
        addMainSystemExtras(sector)
    }

    fun repairStarfallIfEnabled(sector: SectorAPI) {
        if (!isEnabled()) return
        addStarfallExtras(sector)
    }

    private fun addMainSystemExtras(sector: SectorAPI) {
        // 观锚站必须先于炮台：addArtilleryToPlanet 以 IndEvo 原生标签判定星系已有观锚站，顺序反了它会在跳点旁再补一套
        placeWatchtowers(sector, StoryWorldIds.MAIN_STAR, Factions.NEUTRAL)
        placeArtillery(sector, StoryWorldIds.MAIN_PLANET_HONGLU, neutral = true)
        placeArtillery(sector, StoryWorldIds.MAIN_PLANET_CUICHI, neutral = true)
    }

    private fun addStarfallExtras(sector: SectorAPI) {
        placeWatchtowers(sector, StoryWorldIds.STARFALL_STAR, FACTION_INDEVO_DERELICT)
        placeArtillery(sector, StoryWorldIds.STARFALL_PLANET_DUANYUAN, neutral = false)
    }

    private fun placeArtillery(sector: SectorAPI, planetId: String, neutral: Boolean) {
        val planet = sector.getEntityById(planetId)
        if (planet == null) {
            log.error("[ASTD] IndEvo 轨道炮台放置失败：行星缺失 $planetId")
            return
        }
        val market = planet.market
        if (market == null) {
            log.error("[ASTD] IndEvo 轨道炮台放置失败：行星无市场 $planetId")
            return
        }
        if (planet.hasTag(TAG_ENTITY_HAS_ARTILLERY)) return
        if (!neutral) {
            market.factionId = FACTION_INDEVO_DERELICT
        }
        ArtilleryStationPlacer.addArtilleryToPlanet(planet, false)
        log.info("[ASTD] IndEvo 轨道炮台已挂载：$planetId（${if (neutral) "中立" else "敌对"}）")
    }

    private fun placeWatchtowers(sector: SectorAPI, starId: String, factionId: String) {
        val star = sector.getEntityById(starId) ?: return
        val system = star.containingLocation as? StarSystemAPI ?: return
        val starHash = starId.hashCode()
        for (index in 0 until WATCHTOWER_COUNT) {
            val watchtowerId = "${starId}_${StoryWorldIds.TAG_INDEVO_WATCHTOWER}_$index"
            if (system.getEntityById(watchtowerId) != null) continue

            val angle = ((starHash % 360 + 360) % 360 + index * 90f) % 360f
            val stablePoint = system.addCustomEntity(
                "${watchtowerId}_anchor",
                null,
                "stable_location",
                Factions.NEUTRAL,
            )
            stablePoint.setCircularOrbitPointingDown(star, angle, WATCHTOWER_ORBIT_RADIUS, WATCHTOWER_ORBIT_RADIUS / 45f)

            val watchtower = system.addCustomEntity(
                watchtowerId,
                I18n.t(Categories.MOD, "world.shared.indevo_watchtower"),
                ENTITY_WATCHTOWER,
                factionId,
            )
            val orbitRadius = stablePoint.radius + 250f
            watchtower.setCircularOrbitWithSpin(stablePoint, angle + 45f, orbitRadius, orbitRadius / 10f, 5f, 5f)
            watchtower.addTag(StoryWorldIds.TAG_INDEVO_WATCHTOWER)
            watchtower.addTag(TAG_INDEVO_NATIVE_WATCHTOWER)
        }
        log.info("[ASTD] IndEvo 观锚站已放置：${system.id} ×$WATCHTOWER_COUNT（$factionId）")
    }
}
