package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyCoreLootScript
import cn.kasuminova.astd.campaign.bounty.BountyFleetTuneScript
import cn.kasuminova.astd.campaign.bounty.StandardCoreCampaignPlugin
import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onGameLoad
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onNewGameAfterEconomyLoad
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import org.apache.log4j.Logger

object StoryWorldBootstrap {

    private val log: Logger = Global.getLogger(StoryWorldBootstrap::class.java)

    fun onNewGameAfterEconomyLoad() {
        val sector = Global.getSector() ?: return
        StoryWorldGenerator.ensureAll(sector)
        recordWorldgenVersion(sector, currentModVersion())
        StoryWorldState.getOrCreate().worldgenDataVersion = StoryWorldMigrations.CURRENT_DATA_VERSION
    }

    fun onGameLoad(newGame: Boolean) {
        val sector = Global.getSector() ?: return

        sector.addTransientScript(EventHorizonShieldScript())
        sector.addTransientScript(BountyFleetTuneScript())
        sector.addTransientScript(BountyCoreLootScript())
        sector.addTransientScript(StorySystemRevealScript())

        if (!sector.memoryWithoutUpdate.getBoolean(StandardCoreCampaignPlugin.MEMORY_PLUGIN_ADDED)) {
            sector.registerPlugin(StandardCoreCampaignPlugin())
            sector.memoryWithoutUpdate.set(StandardCoreCampaignPlugin.MEMORY_PLUGIN_ADDED, true)
        }

        StandardCores.patchChatterBinding(sector)

        if (newGame) return

        val currentVersion = currentModVersion()
        val savedVersion = savedWorldgenVersion(sector)
        val check = StoryWorldState.compareWorldgenVersion(savedVersion, currentVersion)
        when (check) {
            WorldgenVersionCheck.FIRST_LOAD ->
                log.info("[ASTD] 存档无世界生成版本记录（旧档首次载入），补齐剧情世界并记录版本 $currentVersion")
            WorldgenVersionCheck.VERSION_CHANGED ->
                log.info("[ASTD] 模组版本变更（$savedVersion -> $currentVersion），重新校验剧情世界生成")
            WorldgenVersionCheck.CURRENT -> {}
        }

        try {
            StoryWorldGenerator.ensureAll(sector)
            StoryWorldMigrations.applyPending(sector, StoryWorldState.getOrCreate())
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情世界读档补齐/迁移失败", t)
        }
        if (check != WorldgenVersionCheck.CURRENT) {
            recordWorldgenVersion(sector, currentVersion)
        }
    }

    private fun currentModVersion(): String =
        Global.getSettings().modManager.getModSpec(StoryWorldIds.ASTD_MOD_ID)?.version ?: "unknown"

    private fun savedWorldgenVersion(sector: SectorAPI): String? =
        sector.persistentData[StoryWorldIds.PERSISTENT_WORLDGEN_VERSION_KEY] as? String

    private fun recordWorldgenVersion(sector: SectorAPI, version: String) {
        sector.persistentData[StoryWorldIds.PERSISTENT_WORLDGEN_VERSION_KEY] = version
    }
}
