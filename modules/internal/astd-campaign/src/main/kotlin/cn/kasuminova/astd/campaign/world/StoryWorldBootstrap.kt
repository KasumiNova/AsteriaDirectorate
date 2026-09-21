package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.dialog.story.PrologueAgentBarEventCreator
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onGameLoad
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap.onNewGameAfterEconomyLoad
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager
import org.apache.log4j.Logger

/**
 * 剧情世界生成装配入口（ModPlugin 调用点）。
 *
 * - [onNewGameAfterEconomyLoad]：生涯开局生成主星系（半随机落位）；
 * - [onGameLoad]：注册生涯层脚本/战役插件；非新档时补齐缺失内容
 *   （主星系缺失补主星系，第一章结清钩子已触发而双星系缺失补双星系）。
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

        // 战役插件为 transient：每次读档重新注册。
        // 注册语义（已核对 0.98a starfarer_obf：ModAndPluginData.addPlugin 字节码）：
        // addPlugin 对非 null id 先 removePlugin(同 id) 再 add——同 id 注册即替换，
        // 不堆叠、不残留旧实例；0.98a 的 CampaignPlugin 接口没有 getPriorityVersion()
        // 版本机制（该 API 在此版本不存在），同 id 覆盖即原版推荐写法。
        sector.registerPlugin(StoryWorldCampaignPlugin())
        GravityNodes.installScripts(sector)

        // 序章酒馆事件 Creator（等级门槛 + 全局唯一判定在 Creator 内；重复注册按类去重）
        val barManager = sector.memoryWithoutUpdate?.get(BarEventManager.KEY) as? BarEventManager
        if (barManager == null) {
            log.error("[ASTD] 序章酒馆事件注册失败：BarEventManager 不可用")
        } else if (!barManager.hasEventCreator(PrologueAgentBarEventCreator::class.java)) {
            barManager.addEventCreator(PrologueAgentBarEventCreator())
        }

        if (newGame) return
        try {
            StoryWorldGenerator.ensureAll(sector)
        } catch (t: Throwable) {
            log.error("[ASTD] 剧情世界读档补齐失败", t)
        }
    }
}
