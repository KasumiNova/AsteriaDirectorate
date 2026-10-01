package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyCoreLootScript
import cn.kasuminova.astd.campaign.bounty.BountyFleetTuneScript
import cn.kasuminova.astd.campaign.bounty.StandardCoreCampaignPlugin
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onGameLoad
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onNewGameAfterEconomyLoad
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import org.apache.log4j.Logger

/**
 * 剧情世界生成装配入口（ModPlugin 调用点）。
 *
 * - [onNewGameAfterEconomyLoad]：生涯开局生成三星系（半随机落位）并写入世界生成版本记录，
 *   数据版本直接置为最新（新档不走迁移）；
 * - [onGameLoad]：注册生涯层脚本；非新档时按存档版本记录比对（[StoryWorldState.compareWorldgenVersion]）
 *   记录日志，随后幂等补齐缺失内容（星系缺失补星系），再按数据版本水位按序应用
 *   [StoryWorldMigrations]（就地修改，不重建星系），版本记录缺失/变更时回写当前版本。
 *
 * 全部生成逻辑幂等（canonical id 去重 + [StoryWorldState] 持久化），可重复调用。
 */
object StoryWorldBootstrap {

    private val log: Logger = Global.getLogger(StoryWorldBootstrap::class.java)

    fun onNewGameAfterEconomyLoad() {
        val sector = Global.getSector() ?: return
        StoryWorldGenerator.ensureAll(sector)
        recordWorldgenVersion(sector, currentModVersion())
        // 新档按最新规格生成，数据版本直接置最新，不走迁移。
        StoryWorldState.getOrCreate().worldgenDataVersion = StoryWorldMigrations.CURRENT_DATA_VERSION
    }

    fun onGameLoad(newGame: Boolean) {
        val sector = Global.getSector() ?: return

        // 生涯层脚本为 transient：每次读档重新注册（状态均在 persistentData）。
        sector.addTransientScript(EventHorizonShieldScript())
        sector.addTransientScript(BountyFleetTuneScript())
        sector.addTransientScript(BountyCoreLootScript())

        // 制式（量产）核心战役插件：astd_ai_core_g/b/a/o 军官分发 + A 档行政官分发。
        // 非 transient 口径，memory key 去重防止重复注册。
        if (!sector.memoryWithoutUpdate.getBoolean(StandardCoreCampaignPlugin.MEMORY_PLUGIN_ADDED)) {
            sector.registerPlugin(StandardCoreCampaignPlugin())
            sector.memoryWithoutUpdate.set(StandardCoreCampaignPlugin.MEMORY_PLUGIN_ADDED, true)
        }

        if (newGame) return

        val currentVersion = currentModVersion()
        val savedVersion = savedWorldgenVersion(sector)
        val check = StoryWorldState.compareWorldgenVersion(savedVersion, currentVersion)
        when (check) {
            // 旧档首次载入（进行中存档中途加入模组）：补生成三星系并写入版本记录。
            WorldgenVersionCheck.FIRST_LOAD ->
                log.info("[ASTD] 存档无世界生成版本记录（旧档首次载入），补齐剧情世界并记录版本 $currentVersion")
            // 模组版本变更：重新跑幂等校验并更新记录；既有存档数据迁移由
            // [StoryWorldMigrations.applyPending] 按数据版本水位按序执行（就地修改，不重建星系）。
            WorldgenVersionCheck.VERSION_CHANGED ->
                log.info("[ASTD] 模组版本变更（$savedVersion -> $currentVersion），重新校验剧情世界生成")
            WorldgenVersionCheck.CURRENT -> {}
        }

        try {
            StoryWorldGenerator.ensureAll(sector)
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情世界读档补齐失败", t)
        }
        // 迁移内部幂等且逐档 try/catch（失败停水位重试），此处不再捕获。
        StoryWorldMigrations.applyPending(sector, StoryWorldState.getOrCreate())
        if (check != WorldgenVersionCheck.CURRENT) {
            recordWorldgenVersion(sector, currentVersion)
        }
    }

    /** 当前模组版本（运行时经 ModSpec 读取 mod_info.json version）。 */
    private fun currentModVersion(): String =
        Global.getSettings().modManager.getModSpec(StoryWorldIds.ASTD_MOD_ID)?.version ?: "unknown"

    private fun savedWorldgenVersion(sector: SectorAPI): String? =
        sector.persistentData[StoryWorldIds.PERSISTENT_WORLDGEN_VERSION_KEY] as? String

    private fun recordWorldgenVersion(sector: SectorAPI, version: String) {
        sector.persistentData[StoryWorldIds.PERSISTENT_WORLDGEN_VERSION_KEY] = version
    }
}
