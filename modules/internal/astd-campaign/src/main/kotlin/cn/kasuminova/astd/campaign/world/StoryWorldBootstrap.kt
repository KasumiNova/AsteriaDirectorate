package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onGameLoad
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onNewGameAfterEconomyLoad
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

/**
 * 剧情世界生成装配入口（ModPlugin 调用点）。
 *
 * - [onNewGameAfterEconomyLoad]：生涯开局生成主星系（半随机落位）；
 * - [onGameLoad]：注册生涯层脚本；非新档时补齐缺失内容（星系缺失补星系）。
 *
 * 全部生成逻辑幂等（canonical id 去重 + [StoryWorldState] 持久化），可重复调用。
 */
object StoryWorldBootstrap {

    private val log: Logger = Global.getLogger(StoryWorldBootstrap::class.java)

    fun onNewGameAfterEconomyLoad() {
        val sector = Global.getSector() ?: return
        StoryWorldGenerator.ensureAll(sector)
    }

    fun onGameLoad(newGame: Boolean) {
        val sector = Global.getSector() ?: return

        // 生涯层脚本为 transient：每次读档重新注册（状态均在 persistentData）。
        sector.addTransientScript(EventHorizonShieldScript())

        if (newGame) return
        try {
            StoryWorldGenerator.ensureAll(sector)
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情世界读档补齐失败", t)
        }
    }
}
