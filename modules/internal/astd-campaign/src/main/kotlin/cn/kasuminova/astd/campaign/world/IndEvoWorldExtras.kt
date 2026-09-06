package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import indevo.industries.artillery.utils.ArtilleryStationPlacer
import org.apache.log4j.Logger

/**
 * IndEvo（Industrial.Evolution）联动内容（软依赖隔离文件）。
 *
 * **本文件是全部 IndEvo 类引用的唯一落点**：调用入口先过 [isEnabled]
 * （`modManager.isModEnabled("IndEvo")` + 缓存标志位），缺 IndEvo 时 JVM 不会加载本类。
 *
 * API 锁定版本：IndEvo 4.1.b（javap 核实签名）：
 * - `ArtilleryStationPlacer.addArtilleryToPlanet(SectorEntityToken planet, boolean isDestroyed)`
 *   ——IndEvo 官方星球挂炮台入口：加 `IndEvo_ArtilleryStationCondition` 状况、注册
 *   `ArtilleryStationScript`（玩家进入星系时才生成炮台实体/舰队并管理阵营与存亡状态），
 *   幂等（行星 `IndEvo_Entity_has_artillery` 标签去重）。
 *   不要直接调 `ArtilleryStationEntityPlugin.placeAtMarket` 手放实体：缺少管理脚本时
 *   状况的 setup 分支会再注册脚本并把它判为「已摧毁」状态，实体生命周期不可控；
 * - 观锚站实体类型 `IndEvo_Watchtower`（WatchtowerEntityPlugin.spawn 不接受 canonical id，
 *   为幂等改用等价的 addCustomEntity + 轨道复刻：focus 半径 +250，自转 5/5）；
 *   观锚站需同时打 IndEvo 原生标签 `IndEvo_watchtower`：addArtilleryToPlanet 以该标签判定
 *   星系已有观锚站（否则它会在跳点旁再补一套），且 ArtilleryStationScript 经该标签同步激活状态。
 *
 * 紫菀遗址星系：07 文档定稿「模组联动：暂无」，IndEvo 无适配内容，不生成任何 IndEvo 实体。
 *
 * 调用约定：
 * - 主星系：生成当刻 [addMainSystemExtrasIfEnabled] + 每次经过 [repairMainSystemIfEnabled] 双入口；
 * - 星坠：仅 [repairStarfallIfEnabled] 单入口（生成/重建后由 [StoryWorldGenerator] 统一调用）。
 */
object IndEvoWorldExtras {

    private val log: Logger = Global.getLogger(IndEvoWorldExtras::class.java)

    /** IndEvo 观锚站实体类型 id。 */
    private const val ENTITY_WATCHTOWER: String = "IndEvo_Watchtower"

    /** IndEvo 原生观锚站标签（addArtilleryToPlanet 的星系级观锚站判定与脚本状态同步都认它）。 */
    private const val TAG_INDEVO_NATIVE_WATCHTOWER: String = "IndEvo_watchtower"

    /** 行星「已挂炮台」标签（ArtilleryStationPlacer.addArtilleryToPlanet 的幂等去重依据）。 */
    private const val TAG_ENTITY_HAS_ARTILLERY: String = "IndEvo_Entity_has_artillery"

    /** IndEvo 人之领星系防御系统阵营 id（对玩家敌对）。 */
    private const val FACTION_INDEVO_DERELICT: String = "IndEvo_derelict"

    /** 观锚站稳定点轨道半径（各星系通用，位于常规行星轨道之外）。 */
    private const val WATCHTOWER_ORBIT_RADIUS: Float = 21000f

    /** 每星系观锚站稳定点数量（03/07 文档）。 */
    private const val WATCHTOWER_COUNT: Int = 4

    /** 检测标志位缓存（mod 列表在运行期不变，缓存一次即可）。 */
    @Volatile
    private var detected: Boolean? = null

    /** IndEvo 是否启用（入口门控；本方法本身不引用任何 IndEvo 类）。 */
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

    /** 主星系 IndEvo 联动（生成时）：洪炉/淬池中立磁轨炮台 + 观锚站×4。 */
    fun addMainSystemExtrasIfEnabled(sector: SectorAPI, system: StarSystemAPI) {
        if (!isEnabled()) return
        addMainSystemExtras(sector)
    }

    /** 主星系 IndEvo 联动补齐（星系已存在的读档/中途启用路径）。 */
    fun repairMainSystemIfEnabled(sector: SectorAPI) {
        if (!isEnabled()) return
        addMainSystemExtras(sector)
    }

    /**
     * 星坠星系 IndEvo 联动（锻原敌对磁轨炮台 + 观锚站×4）。
     *
     * 补齐幂等入口：每次 [StoryWorldGenerator.ensureChapter2Systems] 经过本类都会执行——
     * 创建当刻若 IndEvo 调用抛异常，星坠 extras 也会在下一次读档补齐，不再依赖创建时点单次成功。
     * 幂等依据：[placeArtillery] 以行星 `IndEvo_Entity_has_artillery` 标签去重（IndEvo 官方入口
     * 内部同样以此去重）、[placeWatchtowers] 以 canonical id `${starId}_astd_indevo_watchtower_$index`
     * 去重，重复调用不会产生重复实体（与 [repairMainSystemIfEnabled] 同模式）。
     */
    fun repairStarfallIfEnabled(sector: SectorAPI) {
        if (!isEnabled()) return
        addStarfallExtras(sector)
    }

    /**
     * 主星系：两颗固定荒芜星球各一座中立磁轨炮台（允许玩家主动摧毁/殖民，不主动开火），
     * 外加 [WATCHTOWER_COUNT] 个观锚站稳定点（中立阵营）。
     */
    private fun addMainSystemExtras(sector: SectorAPI) {
        // 观锚站先于炮台：addArtilleryToPlanet 会以 IndEvo 原生标签判定星系已有观锚站，
        // 顺序反了它会在跳点旁再补一套（且阵营按 condition-only 规则取 IndEvo_derelict）
        placeWatchtowers(sector, StoryWorldIds.MAIN_STAR, Factions.NEUTRAL)
        placeArtillery(sector, StoryWorldIds.MAIN_PLANET_HONGLU, neutral = true)
        placeArtillery(sector, StoryWorldIds.MAIN_PLANET_CUICHI, neutral = true)
    }

    /**
     * 星坠：锻原一座敌对磁轨炮台（市场阵营切 IndEvo_derelict，防御系统仍在执行两百年前的任务），
     * 外加 [WATCHTOWER_COUNT] 个观锚站稳定点（IndEvo_derelict 阵营，敌对）。
     */
    private fun addStarfallExtras(sector: SectorAPI) {
        placeWatchtowers(sector, StoryWorldIds.STARFALL_STAR, FACTION_INDEVO_DERELICT)
        placeArtillery(sector, StoryWorldIds.STARFALL_PLANET_DUANYUAN, neutral = false)
    }

    /**
     * 行星挂磁轨炮台（IndEvo 官方入口：状况 + 管理脚本 + 标签；炮台实体/舰队由
     * ArtilleryStationScript 在玩家进入星系时生成）。敌对炮台先把市场阵营切 IndEvo_derelict，
     * 脚本随后按市场阵营同步炮台阵营。
     */
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
