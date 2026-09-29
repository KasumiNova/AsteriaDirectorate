package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyFleetTuneScript
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onGameLoad
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onNewGameAfterEconomyLoad
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import org.apache.log4j.Logger

/**
 * 剧情世界生成装配入口（ModPlugin 调用点）。
 *
 * - [onNewGameAfterEconomyLoad]：生涯开局生成三星系（半随机落位）并写入世界生成版本记录；
 * - [onGameLoad]：注册生涯层脚本；非新档时按存档版本记录比对（[StoryWorldState.compareWorldgenVersion]）
 *   记录日志，随后幂等补齐缺失内容（星系缺失补星系），版本记录缺失/变更时回写当前版本。
 *
 * 全部生成逻辑幂等（canonical id 去重 + [StoryWorldState] 持久化），可重复调用。
 */
object StoryWorldBootstrap {

    private val log: Logger = Global.getLogger(StoryWorldBootstrap::class.java)

    fun onNewGameAfterEconomyLoad() {
        val sector = Global.getSector() ?: return
        StoryWorldGenerator.ensureAll(sector)
        recordWorldgenVersion(sector, currentModVersion())
    }

    fun onGameLoad(newGame: Boolean) {
        val sector = Global.getSector() ?: return

        // 生涯层脚本为 transient：每次读档重新注册（状态均在 persistentData）。
        sector.addTransientScript(EventHorizonShieldScript())
        sector.addTransientScript(BountyFleetTuneScript())

        if (newGame) return

        val currentVersion = currentModVersion()
        val savedVersion = savedWorldgenVersion(sector)
        val check = StoryWorldState.compareWorldgenVersion(savedVersion, currentVersion)
        when (check) {
            // 旧档首次载入（进行中存档中途加入模组）：补生成三星系并写入版本记录。
            WorldgenVersionCheck.FIRST_LOAD ->
                log.info("[ASTD] 存档无世界生成版本记录（旧档首次载入），补齐剧情世界并记录版本 $currentVersion")
            // 模组版本变更：当前仅重新跑幂等校验并更新记录；后续版本若需迁移既有存档数据
            // （如调整已生成星系的实体布局），按 savedVersion 在此分支挂迁移逻辑。
            WorldgenVersionCheck.VERSION_CHANGED ->
                log.info("[ASTD] 模组版本变更（$savedVersion -> $currentVersion），重新校验剧情世界生成")
            WorldgenVersionCheck.CURRENT -> {}
        }

        try {
            StoryWorldGenerator.ensureAll(sector)
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情世界读档补齐失败", t)
        }
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
